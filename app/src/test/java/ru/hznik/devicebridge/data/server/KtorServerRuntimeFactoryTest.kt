package ru.hznik.devicebridge.data.server

import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.network.LanAddressCandidate
import ru.hznik.devicebridge.data.network.LanEndpointResolver
import ru.hznik.devicebridge.data.network.LanNetworkSnapshot
import ru.hznik.devicebridge.data.network.LanNetworkSnapshotProvider
import ru.hznik.devicebridge.web.AllowlistedWebAssetProvider
import ru.hznik.devicebridge.web.WebAssetDescriptor
import ru.hznik.devicebridge.web.WebAssetSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.hznik.devicebridge.data.session.BrowserSessionCoordinator
import ru.hznik.devicebridge.data.session.security.JavaCryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.data.session.SessionEventDispatcher
import ru.hznik.devicebridge.data.text.TextTransferCoordinator
import ru.hznik.devicebridge.data.file.CompletedFileRegistry
import ru.hznik.devicebridge.data.file.FileDownloadSourceFactory
import ru.hznik.devicebridge.data.file.FileSourceRegistry
import ru.hznik.devicebridge.data.file.FileTransferCoordinator
import ru.hznik.devicebridge.data.file.FileUploadTargetFactory
import ru.hznik.devicebridge.web.FileSessionEventBridge
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import ru.hznik.devicebridge.data.tls.LocalCertificateAuthority
import ru.hznik.devicebridge.data.tls.LocalCertificateSecureTransport
import ru.hznik.devicebridge.data.tls.SecureTransport
import ru.hznik.devicebridge.data.tls.ServerTlsMaterial
import ru.hznik.devicebridge.data.tls.SoftwareTlsKeyStore
import ru.hznik.devicebridge.data.tls.TlsMaterialException
import ru.hznik.devicebridge.domain.model.ServerLifecycleError
import ru.hznik.devicebridge.domain.model.LocalNameStatus
import ru.hznik.devicebridge.domain.settings.DeviceSettings
import ru.hznik.devicebridge.domain.settings.NetworkName
import ru.hznik.devicebridge.data.network.mdns.LocalNameClaim
import ru.hznik.devicebridge.data.network.mdns.LocalNamePublisher
import ru.hznik.devicebridge.data.network.mdns.LocalNameSession
import ru.hznik.devicebridge.data.tls.DEVICEBRIDGE_LOCAL_NAME
import ru.hznik.devicebridge.data.tls.TlsKeyPurpose
import ru.hznik.devicebridge.data.tls.X509Profiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class KtorServerRuntimeFactoryTest {

    @Test
    fun bindsPreferredPortPublishesLanEndpointAndReleasesPort() = runBlocking {
        val runtime = factory().create()

        val endpoint = runtime.start()
        try {
            assertEquals(LAN_HOST, endpoint.host)
            assertTrue(endpoint.port in 1..65_535)
            assertEquals("wlan0|" + LAN_HOST, runtime.networkFingerprint)
            assertTrue(
                rawGet(
                    port = endpoint.port,
                    hostHeader = endpoint.authority,
                    path = "/",
                ).startsWith("HTTP/1.1 200"),
            )
        } finally {
            runtime.stop()
        }

        ServerSocket(endpoint.port).use { rebound ->
            assertTrue(rebound.isBound)
        }
    }

    @Test
    fun reusesPreferredPortAcrossServerGenerationsForSameBrowserOrigin() = runBlocking {
        val preferredPort = findFreePort()
        val factory = factory(preferredPort)

        val first = factory.create()
        val firstEndpoint = first.start()
        first.stop()

        val second = factory.create()
        val secondEndpoint = second.start()
        second.stop()

        assertEquals(preferredPort, firstEndpoint.port)
        assertEquals(firstEndpoint.port, secondEndpoint.port)
    }

    @Test
    fun productionRuntimeKeepsDiagnosticsAbsentAndProtectsSessionStatus() = runBlocking {
        val runtime = factory().create()
        val endpoint = runtime.start()

        try {
            val diagnostics = rawGet(endpoint.port, endpoint.authority, "/diagnostics/health")
            val protectedStatus = rawGet(
                endpoint.port,
                endpoint.authority,
                "/api/v1/status",
                origin = endpoint.url,
            )

            assertTrue(diagnostics.startsWith("HTTP/1.1 404"))
            assertTrue(protectedStatus.startsWith("HTTP/1.1 401"))
            assertFalse(diagnostics.contains("ktorVersion"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun twentyProductionRuntimeCyclesServeManifestAndReleasePort() = runBlocking {
        repeat(20) {
            val runtime = factory().create()
            val endpoint = runtime.start()

            val response = rawGet(
                port = endpoint.port,
                hostHeader = endpoint.authority,
                path = "/web-manifest.json",
            )
            assertTrue(response.startsWith("HTTP/1.1 200"))
            assertTrue(response.contains("\"protocolVersion\":1"))

            runtime.stop()
            ServerSocket(endpoint.port).use { rebound ->
                assertTrue(rebound.isBound)
            }
        }
    }

    @Test
    fun secureModePublishesHttpsAndServesSetupOverPlainHttp() = runBlocking {
        val authority = LocalCertificateAuthority(SoftwareTlsKeyStore(), createTempDir())
        val runtime = factory(
            secureTransport = LocalCertificateSecureTransport({ true }, authority),
        ).create()

        val endpoint = runtime.start()
        try {
            assertTrue(endpoint.secure)
            assertEquals("http://$LAN_HOST:${endpoint.port}", endpoint.url)
            val root = authority.rootOrNull()!!
            val app = tlsGet(endpoint.port, endpoint.authority, "/", root)
            assertTrue(app, app.startsWith("HTTP/1.1 200"))
            assertTrue(app.contains("<title>DeviceBridge</title>"))

            val setup = rawGet(endpoint.port, endpoint.authority, "/")
            assertTrue(setup.startsWith("HTTP/1.1 200"))
            assertTrue(setup.contains("<title>Setup</title>"))
            assertTrue(setup.contains("connect-src 'self' https://${endpoint.authority};"))
            assertTrue(rawGet(endpoint.port, endpoint.authority, "/api/v1/status").startsWith("HTTP/1.1 403"))
        } finally {
            runtime.stop()
        }

        ServerSocket(endpoint.port).use { rebound ->
            assertTrue(rebound.isBound)
        }
    }

    @Test
    fun unusableCertificateFailsTheStartInsteadOfFallingBackToHttp() = runBlocking {
        val broken = object : SecureTransport {
            override fun isEnabled() = true
            override fun permitsName(localName: String) = true
            override fun serverMaterial(address: java.net.Inet4Address, localName: String?): ServerTlsMaterial =
                throw TlsMaterialException("key is gone")
        }
        val port = findFreePort()
        val runtime = factory(port, secureTransport = broken).create()

        val failure = runCatching { runtime.start() }.exceptionOrNull()

        assertTrue(failure is ServerRuntimeStartException)
        assertEquals(
            ServerLifecycleError.SecureCertificateUnavailable,
            (failure as ServerRuntimeStartException).lifecycleError,
        )
        ServerSocket(port).use { assertTrue(it.isBound) }
    }

    @Test
    fun claimedNameIsPublishedAndAcceptedAsHost() = runBlocking {
        val names = FakeNamePublisher(LocalNameClaim.Claimed("devicebridge.local", requestedTaken = false))
        val runtime = factory(localNamePublisher = names).create()

        val endpoint = runtime.start()
        try {
            assertEquals(LAN_HOST to "wlan0", names.opened)
            assertEquals("devicebridge.local", endpoint.localName)
            assertEquals(LocalNameStatus.Claimed("devicebridge", requestedTaken = false), endpoint.nameStatus)
            assertEquals("http://devicebridge.local:${endpoint.port}", endpoint.url)
            val byName = "devicebridge.local:${endpoint.port}"
            assertTrue(rawGet(endpoint.port, byName, "/").startsWith("HTTP/1.1 200"))
            // One address only: the page by IP moves to the name, the API by IP is refused.
            val byIp = rawGet(endpoint.port, endpoint.authority, "/app?x=1")
            assertTrue(byIp, byIp.startsWith("HTTP/1.1 308"))
            assertTrue(byIp, byIp.contains("Location: http://$byName/app?x=1"))
            val apiByIp = rawGet(endpoint.port, endpoint.authority, "/api/v1/status", origin = "http://${endpoint.authority}")
            assertTrue(apiByIp, apiByIp.startsWith("HTTP/1.1 403"))
            val status = rawGet(endpoint.port, byName, "/api/v1/status", origin = "http://$byName")
            assertTrue(status, status.startsWith("HTTP/1.1 401"))
            assertTrue(rawGet(endpoint.port, "printer.local:${endpoint.port}", "/").startsWith("HTTP/1.1 403"))
            assertTrue(rawGet(endpoint.port, "devicebridge.local", "/").startsWith("HTTP/1.1 403"))
            withTimeout(2_000) { while (names.announced == 0) delay(10) }
        } finally {
            runtime.stop()
        }
        assertEquals(1, names.closed)
    }

    @Test
    fun suffixedNameReportsThatTheRequestedOneWasTaken() = runBlocking {
        val names = FakeNamePublisher(LocalNameClaim.Claimed("devicebridge-2.local", requestedTaken = true))
        val runtime = factory(localNamePublisher = names).create()

        val endpoint = runtime.start()
        runtime.stop()

        assertEquals("devicebridge-2.local", endpoint.localName)
        assertEquals(LocalNameStatus.Claimed("devicebridge", requestedTaken = true), endpoint.nameStatus)
    }

    @Test
    fun allNamesTakenFallsBackToTheAddress() = runBlocking {
        val names = FakeNamePublisher(LocalNameClaim.AllTaken)
        val runtime = factory(localNamePublisher = names).create()

        val endpoint = runtime.start()
        runtime.stop()

        assertEquals(null, endpoint.localName)
        assertEquals(LocalNameStatus.Unavailable(LocalNameStatus.Reason.TAKEN, "devicebridge"), endpoint.nameStatus)
        assertEquals("http://$LAN_HOST:${endpoint.port}", endpoint.url)
        assertEquals(1, names.closed)
    }

    @Test
    fun missingMdnsKeepsTheServerWorkingByAddress() = runBlocking {
        val runtime = factory(localNamePublisher = FakeNamePublisher(claim = null)).create()

        val endpoint = runtime.start()
        try {
            assertEquals(LocalNameStatus.Unavailable(LocalNameStatus.Reason.NETWORK, "devicebridge"), endpoint.nameStatus)
            assertTrue(rawGet(endpoint.port, endpoint.authority, "/").startsWith("HTTP/1.1 200"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun nameLostWhileRunningIsNoLongerAccepted() = runBlocking {
        val names = FakeNamePublisher(LocalNameClaim.Claimed("devicebridge.local", requestedTaken = false))
        val runtime = factory(localNamePublisher = names).create()
        val endpoint = runtime.start()
        try {
            names.loseName()

            val changed = withTimeout(2_000) { runtime.endpointChanges.first { it.localName == null } }
            assertEquals(
                LocalNameStatus.Unavailable(LocalNameStatus.Reason.CONFLICT, "devicebridge"),
                changed.nameStatus,
            )
            val byName = "devicebridge.local:${endpoint.port}"
            assertTrue(rawGet(endpoint.port, byName, "/").startsWith("HTTP/1.1 403"))
            assertTrue(rawGet(endpoint.port, endpoint.authority, "/").startsWith("HTTP/1.1 200"))
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun secureModeServesTheNameAndRedirectsToHttpsByName() = runBlocking {
        val directory = createTempDir()
        val authority = LocalCertificateAuthority(SoftwareTlsKeyStore(), directory)
        val names = FakeNamePublisher(LocalNameClaim.Claimed("devicebridge.local", requestedTaken = false))
        val runtime = factory(
            secureTransport = LocalCertificateSecureTransport({ true }, authority),
            localNamePublisher = names,
        ).create()

        val endpoint = runtime.start()
        try {
            assertEquals("devicebridge.local", endpoint.localName)
            val byName = "devicebridge.local:${endpoint.port}"
            val app = tlsGet(endpoint.port, byName, "/", authority.rootOrNull()!!)
            assertTrue(app, app.startsWith("HTTP/1.1 200"))
            val redirect = rawGet(endpoint.port, byName, "/app")
            assertTrue(redirect, redirect.contains("Location: https://$byName/app"))
            val byIp = rawGet(endpoint.port, endpoint.authority, "/")
            assertTrue(byIp, byIp.contains("Location: http://$byName/"))
            val tlsByIp = tlsGet(endpoint.port, endpoint.authority, "/", authority.rootOrNull()!!)
            assertTrue(tlsByIp, tlsByIp.contains("Location: https://$byName/"))
            val certificate = authority.serverMaterial(
                java.net.InetAddress.getByName(LAN_HOST) as java.net.Inet4Address,
                "devicebridge.local",
            ).certificate
            assertTrue(certificate.subjectAlternativeNames.any { it.toList() == listOf<Any>(2, "devicebridge.local") })
        } finally {
            runtime.stop()
        }
    }

    @Test
    fun secureModeSkipsANameTheRootDoesNotCover() = runBlocking {
        val directory = createTempDir()
        val keys = SoftwareTlsKeyStore()
        val publicKey = keys.generate(LocalCertificateAuthority.ROOT_ALIAS, TlsKeyPurpose.CERTIFICATE_AUTHORITY)
        val now = java.time.Instant.now()
        val legacy = X509Profiles.root(
            publicKey = publicKey,
            serial = byteArrayOf(0x41, 0x02),
            commonName = "DeviceBridge Local CA old",
            notBefore = now.minusSeconds(60),
            notAfter = now.plus(java.time.Duration.ofDays(3_650)),
            sign = { keys.signSha256WithEcdsa(LocalCertificateAuthority.ROOT_ALIAS, it) },
            permittedDnsName = DEVICEBRIDGE_LOCAL_NAME,
        )
        java.io.File(directory, "root.der").writeBytes(legacy.encoded)
        val authority = LocalCertificateAuthority(keys, directory)
        val names = FakeNamePublisher(LocalNameClaim.Claimed("nikita.local", requestedTaken = false))
        val runtime = factory(
            secureTransport = LocalCertificateSecureTransport({ true }, authority),
            localNamePublisher = names,
            settings = DeviceSettings.defaults().copy(networkName = NetworkName("nikita")),
        ).create()

        val endpoint = runtime.start()
        runtime.stop()

        assertEquals(null, endpoint.localName)
        assertEquals(LocalNameStatus.Unavailable(LocalNameStatus.Reason.CERTIFICATE, "nikita"), endpoint.nameStatus)
        assertEquals(null, names.opened)
    }

    private class FakeNamePublisher(private val claim: LocalNameClaim?) : LocalNamePublisher {
        var opened: Pair<String, String>? = null
        var closed = 0
        @Volatile var announced = 0
        private var onLost: () -> Unit = {}

        fun loseName() = onLost()

        override fun open(host: String, interfaceName: String, onNameLost: () -> Unit): LocalNameSession? {
            opened = host to interfaceName
            val result = claim ?: return null
            onLost = onNameLost
            return object : LocalNameSession {
                override suspend fun claim(requestedLabel: String): LocalNameClaim = result

                override suspend fun announce() {
                    announced++
                }

                override fun close() {
                    closed++
                }
            }
        }
    }

    private fun factory(
        preferredPort: Int = findFreePort(),
        secureTransport: SecureTransport = SecureTransport.Disabled,
        localNamePublisher: LocalNamePublisher = LocalNamePublisher.Disabled,
        settings: DeviceSettings = DeviceSettings.defaults(),
    ): KtorServerRuntimeFactory {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val browserSessions = BrowserSessionCoordinator(
            clock = MonotonicClock { 1_000 },
            secretGenerator = SessionSecretGenerator(JavaCryptographicRandom()),
            scope = scope,
        )
        val eventDispatcher = SessionEventDispatcher()
        val textTransfers = TextTransferCoordinator(
            nowEpochMillis = { 1_000_000 },
            browserSessionState = { browserSessions.state.value },
            eventGateway = eventDispatcher,
        )
        val fileTransfers = FileTransferCoordinator(
            browserSessionState = { browserSessions.state.value },
        )
        return KtorServerRuntimeFactory(
            networkSnapshotProvider = LanNetworkSnapshotProvider {
                LanNetworkSnapshot(
                    activeWifiAddresses = listOf(
                        LanAddressCandidate(
                            host = LAN_HOST,
                            interfaceName = "wlan0",
                            isUp = true,
                        ),
                    ),
                )
            },
            endpointResolver = LanEndpointResolver(),
            webAssetProvider = AllowlistedWebAssetProvider(
                allowedPaths = FILES.keys,
                source = object : WebAssetSource {
                    override fun describe(path: String): WebAssetDescriptor? {
                        val bytes = FILES[path] ?: return null
                        return WebAssetDescriptor(
                            length = bytes.size.toLong(),
                            openStream = { ByteArrayInputStream(bytes) },
                        )
                    }
                },
            ),
            browserSessionCoordinator = browserSessions,
            textTransferCoordinator = textTransfers,
            sessionEventDispatcher = eventDispatcher,
            fileTransferCoordinator = fileTransfers,
            uploadTargetFactory = FileUploadTargetFactory { _, _ -> error("not used") },
            downloadSourceFactory = FileDownloadSourceFactory { error("not used") },
            fileSourceRegistry = FileSourceRegistry(),
            completedFileRegistry = CompletedFileRegistry(),
            fileSessionEventBridge = FileSessionEventBridge(
                scope = scope,
                coordinator = fileTransfers,
                dispatcher = eventDispatcher,
                wallClockMs = { 1_000_000 },
            ),
            monotonicClock = MonotonicClock { 1_000 },
            preferredPort = preferredPort,
            secureTransport = secureTransport,
            localNamePublisher = localNamePublisher,
            effectiveFileLimitProvider = EffectiveFileLimitProvider.fixed(settings),
        )
    }

    private fun createTempDir(): java.io.File =
        java.nio.file.Files.createTempDirectory("devicebridge-runtime-tls").toFile()

    /** A GET over TLS that trusts only [root]; the certificate names the LAN address, not 127.0.0.1. */
    private fun tlsGet(port: Int, hostHeader: String, path: String, root: X509Certificate): String {
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("root", root)
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, TrustManagerFactory.getInstance("PKIX").apply { init(trust) }.trustManagers, null)
        }
        return (context.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).use { socket ->
            socket.soTimeout = 5_000
            val writer = socket.outputStream.bufferedWriter(StandardCharsets.US_ASCII)
            writer.write("GET $path HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n\r\n")
            writer.flush()
            socket.inputStream.readBytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun rawGet(
        port: Int,
        hostHeader: String,
        path: String,
        origin: String? = null,
    ): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 2_000
        val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.US_ASCII)
        writer.write("GET " + path + " HTTP/1.1\r\n")
        writer.write("Host: " + hostHeader + "\r\n")
        origin?.let { writer.write("Origin: " + it + "\r\n") }
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.flush()
        socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
    }

    private val ru.hznik.devicebridge.domain.model.ServerEndpoint.authority: String
        get() = host + ":" + port

    private companion object {
        const val LAN_HOST = "192.168.1.24"
        val FILES = mapOf(
            "index.html" to "<!doctype html><title>DeviceBridge</title>".encodeToByteArray(),
            "setup.html" to "<!doctype html><title>Setup</title>".encodeToByteArray(),
            "asset-manifest.json" to "{}".encodeToByteArray(),
            "web-manifest.json" to
                "{\"protocolVersion\":1,\"webAssetVersion\":\"sha256-test\"}"
                    .encodeToByteArray(),
        )
    }
}
