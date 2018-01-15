package com.r3.corda.networkmanage.hsm

import com.nhaarman.mockito_kotlin.*
import com.r3.corda.networkmanage.common.persistence.configureDatabase
import com.r3.corda.networkmanage.common.utils.buildCertPath
import com.r3.corda.networkmanage.doorman.DoormanConfig
import com.r3.corda.networkmanage.doorman.NetworkManagementServer
import com.r3.corda.networkmanage.hsm.persistence.ApprovedCertificateRequestData
import com.r3.corda.networkmanage.hsm.persistence.DBSignedCertificateRequestStorage
import com.r3.corda.networkmanage.hsm.persistence.SignedCertificateRequestStorage
import com.r3.corda.networkmanage.hsm.signer.HsmCsrSigner
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.createDirectories
import net.corda.core.internal.div
import net.corda.core.internal.uncheckedCast
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.utilities.registration.HTTPNetworkRegistrationService
import net.corda.node.utilities.registration.NetworkRegistrationHelper
import net.corda.nodeapi.internal.createDevNodeCa
import net.corda.nodeapi.internal.crypto.*
import net.corda.nodeapi.internal.persistence.DatabaseConfig
import net.corda.testing.ALICE_NAME
import net.corda.testing.SerializationEnvironmentRule
import net.corda.testing.internal.createDevIntermediateCaCertPath
import net.corda.testing.internal.rigorousMock
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URL
import java.security.cert.X509Certificate
import java.util.*
import javax.persistence.PersistenceException
import kotlin.concurrent.scheduleAtFixedRate

class SigningServiceIntegrationTest {
    companion object {
        private val HOST = "localhost"
        private val DB_NAME = "test_db"
    }

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule(true)

    private lateinit var timer: Timer
    private lateinit var rootCaCert: X509Certificate
    private lateinit var intermediateCa: CertificateAndKeyPair

    @Before
    fun setUp() {
        timer = Timer()
        val (rootCa, intermediateCa) = createDevIntermediateCaCertPath()
        rootCaCert = rootCa.certificate
        this.intermediateCa = intermediateCa
    }

    @After
    fun tearDown() {
        timer.cancel()
    }

    private fun givenSignerSigningAllRequests(storage: SignedCertificateRequestStorage): HsmCsrSigner {
        // Mock signing logic but keep certificate persistence
        return mock {
            on { sign(any()) }.then {
                val approvedRequests: List<ApprovedCertificateRequestData> = uncheckedCast(it.arguments[0])
                for (approvedRequest in approvedRequests) {
                    JcaPKCS10CertificationRequest(approvedRequest.request).run {
                        val nodeCa = createDevNodeCa(intermediateCa, CordaX500Name.parse(subject.toString()))
                        approvedRequest.certPath = buildCertPath(nodeCa.certificate, intermediateCa.certificate, rootCaCert)
                    }
                }
                storage.store(approvedRequests, listOf("TEST"))
            }
        }
    }

    @Test
    fun `Signing service signs approved CSRs`() {
        //Start doorman server
        val database = configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(runMigration = true))

        NetworkManagementServer().use { server ->
            server.start(NetworkHostAndPort(HOST, 0), database, doormanServiceParameter = DoormanConfig(approveAll = true, approveInterval = 2.seconds.toMillis(), jiraConfig = null), startNetworkMap = null)
            val doormanHostAndPort = server.hostAndPort
            // Start Corda network registration.
            val config = createConfig().also {
                doReturn(ALICE_NAME).whenever(it).myLegalName
                doReturn(URL("http://${doormanHostAndPort.host}:${doormanHostAndPort.port}")).whenever(it).compatibilityZoneURL
            }
            val signingServiceStorage = DBSignedCertificateRequestStorage(configureDatabase(makeTestDataSourceProperties(), DatabaseConfig(runMigration = true)))

            val hsmSigner = givenSignerSigningAllRequests(signingServiceStorage)
            // Poll the database for approved requests
            timer.scheduleAtFixedRate(0, 1.seconds.toMillis()) {
                // The purpose of this tests is to validate the communication between this service and Doorman
                // by the means of data in the shared database.
                // Therefore the HSM interaction logic is mocked here.
                try {
                    val approved = signingServiceStorage.getApprovedRequests()
                    if (approved.isNotEmpty()) {
                        hsmSigner.sign(approved)
                        timer.cancel()
                    }
                } catch (exception: PersistenceException) {
                    // It may happen that Doorman DB is not created at the moment when the signing service polls it.
                    // This is due to the fact that schema is initialized at the time first hibernate session is established.
                    // Since Doorman does this at the time the first CSR arrives, which in turn happens after signing service
                    // startup, the very first iteration of the signing service polling fails with
                    // [org.hibernate.tool.schema.spi.SchemaManagementException] being thrown as the schema is missing.
                }
            }
            config.certificatesDirectory.createDirectories()
            loadOrCreateKeyStore(config.trustStoreFile, config.trustStorePassword).also {
                it.addOrReplaceCertificate(X509Utilities.CORDA_ROOT_CA, rootCaCert)
                it.save(config.trustStoreFile, config.trustStorePassword)
            }
            NetworkRegistrationHelper(config, HTTPNetworkRegistrationService(config.compatibilityZoneURL!!)).buildKeystore()
            verify(hsmSigner).sign(any())
        }
    }

    private fun createConfig(): NodeConfiguration {
        return rigorousMock<NodeConfiguration>().also {
            doReturn(tempFolder.root.toPath()).whenever(it).baseDirectory
            doReturn(it.baseDirectory / "certificates").whenever(it).certificatesDirectory
            doReturn(it.certificatesDirectory / "truststore.jks").whenever(it).trustStoreFile
            doReturn(it.certificatesDirectory / "nodekeystore.jks").whenever(it).nodeKeystore
            doReturn(it.certificatesDirectory / "sslkeystore.jks").whenever(it).sslKeystore
            doReturn("trustpass").whenever(it).trustStorePassword
            doReturn("cordacadevpass").whenever(it).keyStorePassword
            doReturn("iTest@R3.com").whenever(it).emailAddress
        }
    }

    private fun makeTestDataSourceProperties(): Properties {
        val props = Properties()
        props.setProperty("dataSourceClassName", "org.h2.jdbcx.JdbcDataSource")
        props.setProperty("dataSource.url", "jdbc:h2:mem:${SigningServiceIntegrationTest.DB_NAME};DB_CLOSE_DELAY=-1")
        props.setProperty("dataSource.user", "sa")
        props.setProperty("dataSource.password", "")
        return props
    }
}
