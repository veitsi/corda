package com.r3corda.node.services.messaging

import com.google.common.net.HostAndPort
import com.r3corda.core.ThreadBox
import com.r3corda.core.messaging.*
import com.r3corda.core.serialization.SerializedBytes
import com.r3corda.core.serialization.opaque
import com.r3corda.core.utilities.loggerFor
import com.r3corda.core.utilities.trace
import com.r3corda.node.services.api.MessagingServiceInternal
import com.r3corda.node.services.config.NodeConfiguration
import com.r3corda.node.utilities.*
import org.apache.activemq.artemis.api.core.ActiveMQObjectClosedException
import org.apache.activemq.artemis.api.core.SimpleString
import org.apache.activemq.artemis.api.core.client.*
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.statements.InsertStatement
import java.nio.file.FileSystems
import java.security.PublicKey
import java.time.Instant
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.annotation.concurrent.ThreadSafe

// TODO: Stop the wallet explorer and other clients from using this class and get rid of persistentInbox

/**
 * This class implements the [MessagingService] API using Apache Artemis, the successor to their ActiveMQ product.
 * Artemis is a message queue broker and here we run a client connecting to the specified broker instance
 * [ArtemisMessagingServer]. It's primarily concerned with peer-to-peer messaging.
 *
 * Message handlers are run on the provided [AffinityExecutor] synchronously, that is, the Artemis callback threads
 * are blocked until the handler is scheduled and completed. This allows backpressure to propagate from the given
 * executor through into Artemis and from there, back through to senders.
 *
 * An implementation of [CordaRPCOps] can be provided. If given, clients using the CordaMQClient RPC library can
 * invoke methods on the provided implementation. There is more documentation on this in the docsite and the
 * CordaRPCClient class.
 *
 * @param serverHostPort The address of the broker instance to connect to (might be running in the same process)
 * @param myIdentity Either the public key to be used as the ArtemisMQ address and queue name for the node globally, or null to indicate
 * that this is a NetworkMapService node which will be bound globally to the name "networkmap"
 * @param executor An executor to run received message tasks upon.
 * @param persistentInbox If true the inbox will be created persistent if not already created.
 * If false the inbox queue will be transient, which is appropriate for UI clients for example.
 * @param persistenceTx A lambda to wrap message processing in any transaction required by the persistence layer (e.g.
 * a database transaction) without introducing a dependency on the actual solution and any parameters it requires
 * in this class.
 */
@ThreadSafe
class NodeMessagingClient(config: NodeConfiguration,
                          val serverHostPort: HostAndPort,
                          val myIdentity: PublicKey?,
                          val executor: AffinityExecutor,
                          val persistentInbox: Boolean = true,
                          val persistenceTx: (() -> Unit) -> Unit = { it() }) : ArtemisMessagingComponent(config), MessagingServiceInternal {
    companion object {
        val log = loggerFor<NodeMessagingClient>()

        // This is a "property" attached to an Artemis MQ message object, which contains our own notion of "topic".
        // We should probably try to unify our notion of "topic" (really, just a string that identifies an endpoint
        // that will handle messages, like a URL) with the terminology used by underlying MQ libraries, to avoid
        // confusion.
        val TOPIC_PROPERTY = "platform-topic"

        val SESSION_ID_PROPERTY = "session-id"

        /**
         * This should be the only way to generate an ArtemisAddress and that only of the remote NetworkMapService node.
         * All other addresses come from the NetworkMapCache, or myAddress below.
         * The node will populate with their own identity based address when they register with the NetworkMapService.
         */
        fun makeNetworkMapAddress(hostAndPort: HostAndPort): SingleMessageRecipient = NetworkMapAddress(hostAndPort)
    }

    private class InnerState {
        var started = false
        var running = false
        val knownQueues = mutableSetOf<SimpleString>()
        var producer: ClientProducer? = null
        var p2pConsumer: ClientConsumer? = null
        var session: ClientSession? = null
        var clientFactory: ClientSessionFactory? = null
        // Consumer for inbound client RPC messages.
        var rpcConsumer: ClientConsumer? = null
        var rpcNotificationConsumer: ClientConsumer? = null

        // TODO: This is not robust and needs to be replaced by more intelligently using the message queue server.
        var undeliveredMessages = listOf<Message>()
    }

    /** A registration to handle messages of different types */
    data class Handler(val topicSession: TopicSession,
                       val callback: (Message, MessageHandlerRegistration) -> Unit) : MessageHandlerRegistration

    /**
     * Apart from the NetworkMapService this is the only other address accessible to the node outside of lookups against the NetworkMapCache.
     */
    override val myAddress: SingleMessageRecipient = if (myIdentity != null) NodeAddress(myIdentity, serverHostPort) else NetworkMapAddress(serverHostPort)

    private val state = ThreadBox(InnerState())
    private val handlers = CopyOnWriteArrayList<Handler>()

    private object Table : JDBCHashedTable("${NODE_DATABASE_PREFIX}message_ids") {
        val uuid = uuidString("message_id")
    }

    private val processedMessages: MutableSet<UUID> = Collections.synchronizedSet(if (persistentInbox) {
        object : AbstractJDBCHashSet<UUID, Table>(Table, loadOnInit = true) {
            override fun elementFromRow(row: ResultRow): UUID = row[table.uuid]

            override fun addElementToInsert(insert: InsertStatement, entry: UUID, finalizables: MutableList<() -> Unit>) {
                insert[table.uuid] = entry
            }
        }
    } else {
        HashSet<UUID>()
    })

    init {
        require(config.basedir.fileSystem == FileSystems.getDefault()) { "Artemis only uses the default file system" }
    }

    fun start(rpcOps: CordaRPCOps? = null) {
        state.locked {
            check(!started) { "start can't be called twice" }
            started = true

            log.info("Connecting to server: $serverHostPort")
            // Connect to our server. TODO: This should use the in-VM transport.
            val tcpTransport = tcpTransport(ConnectionDirection.OUTBOUND, serverHostPort.hostText, serverHostPort.port)
            val locator = ActiveMQClient.createServerLocatorWithoutHA(tcpTransport)
            clientFactory = locator.createSessionFactory()

            // Create a session. Note that the acknowledgement of messages is not flushed to
            // the Artermis journal until the default buffer size of 1MB is acknowledged.
            val session = clientFactory!!.createSession(true, true, ActiveMQClient.DEFAULT_ACK_BATCH_SIZE)
            this.session = session
            session.start()

            // Create a general purpose producer.
            producer = session.createProducer()

            // Create a queue, consumer and producer for handling P2P network messages.
            val queueName = toQueueName(myAddress)
            val query = session.queueQuery(queueName)
            if (!query.isExists) {
                session.createQueue(queueName, queueName, persistentInbox)
            }
            knownQueues.add(queueName)
            p2pConsumer = session.createConsumer(queueName)

            // Create an RPC queue and consumer: this will service locally connected clients only (not via a
            // bridge) and those clients must have authenticated. We could use a single consumer for everything
            // and perhaps we should, but these queues are not worth persisting.
            if (rpcOps != null) {
                session.createTemporaryQueue(RPC_REQUESTS_QUEUE, RPC_REQUESTS_QUEUE)
                session.createTemporaryQueue("activemq.notifications", "rpc.qremovals", "_AMQ_NotifType = 1")
                rpcConsumer = session.createConsumer(RPC_REQUESTS_QUEUE)
                rpcNotificationConsumer = session.createConsumer("rpc.qremovals")
                dispatcher = createRPCDispatcher(state, rpcOps)
            }
        }
    }

    private var shutdownLatch = CountDownLatch(1)

    /** Starts the p2p event loop: this method only returns once [stop] has been called. */
    fun run() {
        val consumer = state.locked {
            check(started)
            check(!running) { "run can't be called twice" }
            running = true
            // Optionally, start RPC dispatch.
            dispatcher?.start(rpcConsumer!!, rpcNotificationConsumer!!, executor)
            p2pConsumer!!
        }

        while (true) {
            // Two possibilities here:
            //
            // 1. We block waiting for a message and the consumer is closed in another thread. In this case
            //    receive returns null and we break out of the loop.
            // 2. We receive a message and process it, and stop() is called during delivery. In this case,
            //    calling receive will throw and we break out of the loop.
            //
            // It's safe to call into receive simultaneous with other threads calling send on a producer.
            val artemisMessage: ClientMessage = try {
                consumer.receive()
            } catch(e: ActiveMQObjectClosedException) {
                null
            } ?: break

            val message: Message? = artemisToCordaMessage(artemisMessage)
            if (message != null)
                deliver(message)

            // Ack the message so it won't be redelivered. We should only really do this when there were no
            // transient failures. If we caught an exception in the handler, we could back off and retry delivery
            // a few times before giving up and redirecting the message to a dead-letter address for admin or
            // developer inspection. Artemis has the features to do this for us, we just need to enable them.
            //
            // TODO: Setup Artemis delayed redelivery and dead letter addresses.
            //
            // ACKing a message calls back into the session which isn't thread safe, so we have to ensure it
            // doesn't collide with a send here. Note that stop() could have been called whilst we were
            // processing a message but if so, it'll be parked waiting for us to count down the latch, so
            // the session itself is still around and we can still ack messages as a result.
            state.locked {
                artemisMessage.acknowledge()
            }
        }
        shutdownLatch.countDown()
    }

    private fun artemisToCordaMessage(message: ClientMessage): Message? {
        try {
            if (!message.containsProperty(TOPIC_PROPERTY)) {
                log.warn("Received message without a $TOPIC_PROPERTY property, ignoring")
                return null
            }
            if (!message.containsProperty(SESSION_ID_PROPERTY)) {
                log.warn("Received message without a $SESSION_ID_PROPERTY property, ignoring")
                return null
            }
            val topic = message.getStringProperty(TOPIC_PROPERTY)
            val sessionID = message.getLongProperty(SESSION_ID_PROPERTY)
            // Use the magic deduplication property built into Artemis as our message identity too
            val uuid = UUID.fromString(message.getStringProperty(org.apache.activemq.artemis.api.core.Message.HDR_DUPLICATE_DETECTION_ID))
            log.info("received message from: ${message.address} topic: $topic sessionID: $sessionID uuid: $uuid")

            val body = ByteArray(message.bodySize).apply { message.bodyBuffer.readBytes(this) }

            val msg = object : Message {
                override val topicSession = TopicSession(topic, sessionID)
                override val data: ByteArray = body
                override val debugTimestamp: Instant = Instant.ofEpochMilli(message.timestamp)
                override val uniqueMessageId: UUID = uuid
                override fun serialise(): ByteArray = body
                override fun toString() = topic + "#" + data.opaque()
            }

            return msg
        } catch (e: Exception) {
            log.error("Internal error whilst reading MQ message", e)
            return null
        }
    }

    private fun deliver(msg: Message): Boolean {
        state.checkNotLocked()
        // Because handlers is a COW list, the loop inside filter will operate on a snapshot. Handlers being added
        // or removed whilst the filter is executing will not affect anything.
        val deliverTo = handlers.filter { it.topicSession.isBlank() || it.topicSession == msg.topicSession }

        if (deliverTo.isEmpty()) {
            // This should probably be downgraded to a trace in future, so the protocol can evolve with new topics
            // without causing log spam.
            log.warn("Received message for ${msg.topicSession} that doesn't have any registered handlers yet")

            // This is a hack; transient messages held in memory isn't crash resistant.
            // TODO: Use Artemis API more effectively so we don't pop messages off a queue that we aren't ready to use.
            state.locked {
                undeliveredMessages += msg
            }
            return false
        }

        try {
            // This will perform a BLOCKING call onto the executor. Thus if the handlers are slow, we will
            // be slow, and Artemis can handle that case intelligently. We don't just invoke the handler
            // directly in order to ensure that we have the features of the AffinityExecutor class throughout
            // the bulk of the codebase and other non-messaging jobs can be scheduled onto the server executor
            // easily.
            //
            // Note that handlers may re-enter this class. We aren't holding any locks and methods like
            // start/run/stop have re-entrancy assertions at the top, so it is OK.
            executor.fetchFrom {
                persistenceTx {
                    callHandlers(msg, deliverTo)
                }
            }
        } catch(e: Exception) {
            log.error("Caught exception whilst executing message handler for ${msg.topicSession}", e)
        }
        return true
    }

    private fun callHandlers(msg: Message, deliverTo: List<Handler>) {
        if (msg.uniqueMessageId in processedMessages) {
            log.trace { "discard duplicate message ${msg.uniqueMessageId} for ${msg.topicSession}" }
            return
        }
        for (handler in deliverTo) {
            handler.callback(msg, handler)
        }
        // TODO We will at some point need to decide a trimming policy for the id's
        processedMessages += msg.uniqueMessageId
    }

    override fun stop() {
        val running = state.locked {
            // We allow stop() to be called without a run() in between, but it must have at least been started.
            check(started)

            val c = p2pConsumer ?: throw IllegalStateException("stop can't be called twice")
            try {
                c.close()
            } catch(e: ActiveMQObjectClosedException) {
                // Ignore it: this can happen if the server has gone away before we do.
            }
            p2pConsumer = null
            val prevRunning = running
            running = false
            prevRunning
        }
        if (running && !executor.isOnThread) {
            // Wait for the main loop to notice the consumer has gone and finish up.
            shutdownLatch.await()
        }
        // Only first caller to gets running true to protect against double stop, which seems to happen in some integration tests.
        if (running) {
            state.locked {
                rpcConsumer?.close()
                rpcConsumer = null
                rpcNotificationConsumer?.close()
                rpcNotificationConsumer = null
                producer?.close()
                producer = null
                // Ensure any trailing messages are committed to the journal
                session!!.commit()
                // Closing the factory closes all the sessions it produced as well.
                clientFactory!!.close()
                clientFactory = null
            }
        }
    }

    override fun send(message: Message, target: MessageRecipients) {
        val queueName = toQueueName(target)
        state.locked {
            val artemisMessage = session!!.createMessage(true).apply {
                val sessionID = message.topicSession.sessionID
                putStringProperty(TOPIC_PROPERTY, message.topicSession.topic)
                putLongProperty(SESSION_ID_PROPERTY, sessionID)
                writeBodyBufferBytes(message.data)
                // Use the magic deduplication property built into Artemis as our message identity too
                putStringProperty(org.apache.activemq.artemis.api.core.Message.HDR_DUPLICATE_DETECTION_ID, SimpleString(UUID.randomUUID().toString()))
            }

            if (knownQueues.add(queueName)) {
                maybeCreateQueue(queueName)
            }
            log.info("send to: $queueName topic: ${message.topicSession.topic} sessionID: ${message.topicSession.sessionID} uuid: ${message.uniqueMessageId}")
            producer!!.send(queueName, artemisMessage)
        }
    }

    private fun maybeCreateQueue(queueName: SimpleString) {
        state.alreadyLocked {
            val queueQuery = session!!.queueQuery(queueName)
            if (!queueQuery.isExists) {
                log.info("Create fresh queue $queueName")
                session!!.createQueue(queueName, queueName, true /* durable */)
            }
        }
    }

    override fun addMessageHandler(topic: String, sessionID: Long, callback: (Message, MessageHandlerRegistration) -> Unit): MessageHandlerRegistration
            = addMessageHandler(TopicSession(topic, sessionID), callback)

    override fun addMessageHandler(topicSession: TopicSession,
                                   callback: (Message, MessageHandlerRegistration) -> Unit): MessageHandlerRegistration {
        require(!topicSession.isBlank()) { "Topic must not be blank, as the empty topic is a special case." }
        val handler = Handler(topicSession, callback)
        handlers.add(handler)
        val messagesToRedeliver = state.locked {
            val messagesToRedeliver = undeliveredMessages
            undeliveredMessages = listOf()
            messagesToRedeliver
        }
        messagesToRedeliver.forEach { deliver(it) }
        return handler
    }

    override fun removeMessageHandler(registration: MessageHandlerRegistration) {
        handlers.remove(registration)
    }

    override fun createMessage(topicSession: TopicSession, data: ByteArray, uuid: UUID): Message {
        // TODO: We could write an object that proxies directly to an underlying MQ message here and avoid copying.
        return object : Message {
            override val topicSession: TopicSession get() = topicSession
            override val data: ByteArray get() = data
            override val debugTimestamp: Instant = Instant.now()
            override fun serialise(): ByteArray = this.serialise()
            override val uniqueMessageId: UUID = uuid
            override fun toString() = topicSession.toString() + "#" + String(data)
        }
    }

    var dispatcher: RPCDispatcher? = null

    private fun createRPCDispatcher(state: ThreadBox<InnerState>, ops: CordaRPCOps) = object : RPCDispatcher(ops) {
        override fun send(bits: SerializedBytes<*>, toAddress: String) {
            state.locked {
                val msg = session!!.createMessage(false).apply {
                    writeBodyBufferBytes(bits.bits)
                    // Use the magic deduplication property built into Artemis as our message identity too
                    putStringProperty(org.apache.activemq.artemis.api.core.Message.HDR_DUPLICATE_DETECTION_ID, SimpleString(UUID.randomUUID().toString()))
                }
                producer!!.send(toAddress, msg)
            }
        }
    }
}
