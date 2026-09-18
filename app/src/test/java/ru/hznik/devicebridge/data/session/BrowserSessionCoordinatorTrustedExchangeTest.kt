package ru.hznik.devicebridge.data.session

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.server.MonotonicClock
import ru.hznik.devicebridge.data.session.security.CryptographicRandom
import ru.hznik.devicebridge.data.session.security.SessionSecretGenerator
import ru.hznik.devicebridge.domain.repository.TrustedBrowserRepository
import ru.hznik.devicebridge.domain.session.ServerGenerationId
import ru.hznik.devicebridge.domain.trust.IssuedTrustedBrowser
import ru.hznik.devicebridge.domain.trust.TRUSTED_BROWSER_MAX_LIFETIME_MILLIS
import ru.hznik.devicebridge.domain.trust.TrustedBrowser
import ru.hznik.devicebridge.domain.trust.TrustedBrowserAuthenticationResult
import ru.hznik.devicebridge.domain.trust.TrustedBrowserId
import ru.hznik.devicebridge.domain.trust.TrustedBrowserIssueRequest

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSessionCoordinatorTrustedExchangeTest {
    @Test
    fun allowAndRememberIssuesCredentialButNeverAcceptsItAsBearerToken() = runTest {
        val trusted = FakeTrustedBrowserRepository()
        val coordinator = coordinator(trusted)
        val handle = coordinator.activate(ServerGenerationId(1))
        val challenge = coordinator.createChallenge(
            handle,
            "Chrome",
            "192.168.1.2",
            rememberBrowserRequested = true,
        ) as ChallengeCreationResult.Created
        val confirmation = async {
            coordinator.confirmAndAwait(
                handle,
                challenge.challengeId,
                "123456",
                "Chrome",
                "192.168.1.2",
            )
        }
        runCurrent()

        coordinator.approveAndRemember(coordinator.state.value.pendingRequests.single().id)

        val approved = confirmation.await() as SessionConfirmationResult.Approved
        assertEquals("trusted-raw", approved.trustedCredential)
        assertEquals(
            1_000L + TRUSTED_BROWSER_MAX_LIFETIME_MILLIS,
            approved.trustedCredentialExpiresAtEpochMillis,
        )
        assertEquals(
            TrustedBrowserId("trusted-1"),
            coordinator.state.value.sessions.single().trustedBrowserId,
        )
        assertNull(coordinator.authenticate(handle, "trusted-raw"))
        assertNotNull(coordinator.authenticate(handle, approved.token))
    }

    @Test
    fun trustedExchangeCreatesNewGenerationScopedSessionAndTemporaryBearer() = runTest {
        val trusted = FakeTrustedBrowserRepository().apply { seed() }
        val coordinator = coordinator(trusted)
        val handle = coordinator.activate(ServerGenerationId(7))

        val result = coordinator.exchangeTrusted(
            handle = handle,
            rawCredential = "trusted-raw",
            sourceIpv4 = "192.168.1.3",
            nowEpochMillis = 1_500,
        ) as TrustedSessionExchangeResult.Approved

        val session = coordinator.state.value.sessions.single()
        assertEquals(ServerGenerationId(7), session.generationId)
        assertEquals(TrustedBrowserId("trusted-1"), session.trustedBrowserId)
        assertNotNull(coordinator.authenticate(handle, result.token))
        assertNull(coordinator.authenticate(handle, "trusted-raw"))
    }

    @Test
    fun revokeOneAndRevokeAllCloseOnlyDerivedSessions() = runTest {
        val trusted = FakeTrustedBrowserRepository().apply { seed() }
        val coordinator = coordinator(trusted)
        val handle = coordinator.activate(ServerGenerationId(3))
        val challenge = coordinator.createChallenge(handle, "Edge", "192.168.1.2")
            as ChallengeCreationResult.Created
        val ordinaryConfirmation = async {
            coordinator.confirmAndAwait(
                handle,
                challenge.challengeId,
                "123456",
                "Edge",
                "192.168.1.2",
            )
        }
        runCurrent()
        coordinator.approve(coordinator.state.value.pendingRequests.single().id)
        val ordinary = ordinaryConfirmation.await() as SessionConfirmationResult.Approved
        val firstTrusted = coordinator.exchangeTrusted(
            handle,
            "trusted-raw",
            "192.168.1.3",
            1_500,
        ) as TrustedSessionExchangeResult.Approved
        val ordinaryConnection = RecordingConnection()
        val trustedConnection = RecordingConnection()
        coordinator.attachConnection(ordinary.sessionId, ordinaryConnection)
        coordinator.attachConnection(firstTrusted.sessionId, trustedConnection)

        assertTrue(coordinator.revokeTrustedBrowser(TrustedBrowserId("trusted-1")))

        assertEquals(0, ordinaryConnection.closeCalls)
        assertEquals(1, trustedConnection.closeCalls)
        assertNotNull(coordinator.authenticate(handle, ordinary.token))
        assertNull(coordinator.authenticate(handle, firstTrusted.token))

        trusted.seed(id = "trusted-2", rawCredential = "trusted-raw-2")
        val secondTrusted = coordinator.exchangeTrusted(
            handle,
            "trusted-raw-2",
            "192.168.1.4",
            1_600,
        ) as TrustedSessionExchangeResult.Approved
        val secondConnection = RecordingConnection()
        coordinator.attachConnection(secondTrusted.sessionId, secondConnection)

        assertEquals(1, coordinator.revokeAllTrustedBrowsers())
        assertEquals(1, secondConnection.closeCalls)
        assertNotNull(coordinator.authenticate(handle, ordinary.token))
    }

    @Test
    fun periodicExpiryCleanupClosesDerivedSession() = runTest {
        val trusted = FakeTrustedBrowserRepository().apply {
            seed(expiresAtEpochMillis = 2_000)
        }
        val wallClock = MutableClock(1_500)
        val coordinator = coordinator(trusted, wallClock)
        runCurrent()
        val handle = coordinator.activate(ServerGenerationId(4))
        val exchange = coordinator.exchangeTrusted(
            handle,
            "trusted-raw",
            "192.168.1.3",
            1_500,
        ) as TrustedSessionExchangeResult.Approved
        val connection = RecordingConnection()
        coordinator.attachConnection(exchange.sessionId, connection)

        wallClock.epochMillis = 2_000
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, connection.closeCalls)
        assertTrue(coordinator.state.value.sessions.isEmpty())
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        trusted: TrustedBrowserRepository,
        wallClock: Clock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC),
    ) = BrowserSessionCoordinator(
        clock = MonotonicClock { 1_000 },
        secretGenerator = SessionSecretGenerator(DeterministicRandom()),
        scope = backgroundScope,
        trustedBrowserRepository = trusted,
        wallClock = wallClock,
    )

    private class FakeTrustedBrowserRepository : TrustedBrowserRepository {
        private val mutableBrowsers = MutableStateFlow<List<TrustedBrowser>>(emptyList())
        private val credentials = linkedMapOf<String, TrustedBrowserId>()
        override val trustedBrowsers = mutableBrowsers

        fun seed(
            id: String = "trusted-1",
            rawCredential: String = "trusted-raw",
            expiresAtEpochMillis: Long = 1_000 + TRUSTED_BROWSER_MAX_LIFETIME_MILLIS,
        ) {
            val browser = browser(id = id, expires = expiresAtEpochMillis)
            mutableBrowsers.value = mutableBrowsers.value + browser
            credentials[rawCredential] = browser.id
        }

        override suspend fun issue(request: TrustedBrowserIssueRequest): IssuedTrustedBrowser {
            val browser = browser(
                label = request.browserLabel,
                created = request.issuedAtEpochMillis,
                expires = request.expiresAtEpochMillis,
            )
            mutableBrowsers.value = listOf(browser)
            credentials["trusted-raw"] = browser.id
            return IssuedTrustedBrowser(browser, "trusted-raw")
        }

        override suspend fun authenticate(
            rawCredential: String,
            nowEpochMillis: Long,
        ): TrustedBrowserAuthenticationResult {
            val browserId = credentials[rawCredential]
                ?: return TrustedBrowserAuthenticationResult.Invalid
            val browser = mutableBrowsers.value.firstOrNull { it.id == browserId }
                ?: return TrustedBrowserAuthenticationResult.Invalid
            if (browser.expiresAtEpochMillis <= nowEpochMillis) {
                return TrustedBrowserAuthenticationResult.Expired
            }
            return TrustedBrowserAuthenticationResult.Authenticated(
                browser.copy(lastUsedAtEpochMillis = nowEpochMillis),
            )
        }

        override suspend fun revoke(browserId: TrustedBrowserId): Boolean {
            val removed = mutableBrowsers.value.any { it.id == browserId }
            mutableBrowsers.value = mutableBrowsers.value.filterNot { it.id == browserId }
            credentials.entries.removeAll { it.value == browserId }
            return removed
        }

        override suspend fun revokeAll(): Int {
            val count = mutableBrowsers.value.size
            mutableBrowsers.value = emptyList()
            credentials.clear()
            return count
        }

        override suspend fun deleteExpired(nowEpochMillis: Long): Int {
            val expired = mutableBrowsers.value
                .filter { it.expiresAtEpochMillis <= nowEpochMillis }
                .map { it.id }
                .toSet()
            mutableBrowsers.value = mutableBrowsers.value.filterNot { it.id in expired }
            credentials.entries.removeAll { it.value in expired }
            return expired.size
        }

        private fun browser(
            id: String = "trusted-1",
            label: String = "Chrome",
            created: Long = 1_000,
            expires: Long = 1_000 + TRUSTED_BROWSER_MAX_LIFETIME_MILLIS,
        ) = TrustedBrowser(
            id = TrustedBrowserId(id),
            browserLabel = label,
            createdAtEpochMillis = created,
            lastUsedAtEpochMillis = null,
            expiresAtEpochMillis = expires,
        )
    }

    private class RecordingConnection : SessionConnection {
        var closeCalls = 0
        override suspend fun close() {
            closeCalls += 1
        }
    }

    private class MutableClock(var epochMillis: Long) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(epochMillis)
    }

    private class DeterministicRandom : CryptographicRandom {
        private var nextCode = 123456
        private var seed = 1
        override fun nextInt(bound: Int): Int = nextCode.also { nextCode = 654321 }
        override fun nextBytes(size: Int): ByteArray = ByteArray(size) { (seed++).toByte() }
    }
}
