package net.corda.nodeapi.internal

import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.generateKeyPair
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.cert
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.internal.toX509CertHolder
import net.corda.core.utilities.trace
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509KeyStore
import net.corda.nodeapi.internal.crypto.X509Utilities
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.cert.X509Certificate

object IdentityGenerator {
    private val log = LoggerFactory.getLogger(javaClass)

    const val NODE_IDENTITY_ALIAS_PREFIX = "identity"
    const val DISTRIBUTED_NOTARY_ALIAS_PREFIX = "distributed-notary"

    fun generateNodeIdentity(dir: Path, legalName: CordaX500Name, customRootCert: X509Certificate? = null): Party {
        return generateToDisk(listOf(dir), legalName, NODE_IDENTITY_ALIAS_PREFIX, threshold = 1, customRootCert = customRootCert)
    }

    fun generateDistributedNotaryIdentity(dirs: List<Path>, notaryName: CordaX500Name, threshold: Int = 1, customRootCert: X509Certificate? = null): Party {
        return generateToDisk(dirs, notaryName, DISTRIBUTED_NOTARY_ALIAS_PREFIX, threshold, customRootCert)
    }

    /**
     * Generates signing key pairs and a common distributed service identity for a set of nodes.
     * The key pairs and the group identity get serialized to disk in the corresponding node directories.
     * This method should be called *before* any of the nodes are started.
     *
     * @param dirs List of node directories to place the generated identity and key pairs in.
     * @param name The name of the identity.
     * @param threshold The threshold for the generated group [CompositeKey].
     * @param customRootCert the certificate to use as the Corda root CA. If not specified the one in
     *      X509Utilities.loadDevCaKeyStore is used.
     */
    private fun generateToDisk(dirs: List<Path>,
                               name: CordaX500Name,
                               aliasPrefix: String,
                               threshold: Int,
                               customRootCert: X509Certificate?): Party {
        log.trace { "Generating identity \"$name\" for nodes: ${dirs.joinToString()}" }
        val keyPairs = (1..dirs.size).map { generateKeyPair() }
        val key = CompositeKey.Builder().addKeys(keyPairs.map { it.public }).build(threshold)

        val intermediateCa = X509Utilities.DEV_INTERMEDIATE_CA  // TODO We need to re-generate the intermediate cert if using a custom root
        val rootCert = customRootCert ?: X509Utilities.DEV_ROOT_CA.certificate

        keyPairs.zip(dirs) { keyPair, dir ->
            val (serviceKeyCert, compositeKeyCert) = listOf(keyPair.public, key).map { publicKey ->
                X509Utilities.createCertificate(
                        CertificateType.SERVICE_IDENTITY,
                        intermediateCa.certificate.toX509CertHolder(),
                        intermediateCa.keyPair,
                        name,
                        publicKey
                )
            }
            val distServicesFile = (dir / "certificates").createDirectories() / "distributedService.jks"
            X509KeyStore.fromFile(distServicesFile, "cordacadevpass", createNew = true).update {
                setCertificate("$aliasPrefix-composite-key", compositeKeyCert.cert)
                setPrivateKey("$aliasPrefix-private-key", keyPair.private, listOf(serviceKeyCert.cert, intermediateCa.certificate, rootCert))
            }
        }

        return Party(name, key)
    }
}