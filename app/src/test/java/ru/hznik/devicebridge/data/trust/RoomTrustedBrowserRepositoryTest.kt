package ru.hznik.devicebridge.data.trust

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.hznik.devicebridge.data.persistence.room.TrustedBrowserDao
import ru.hznik.devicebridge.data.persistence.room.TrustedBrowserEntity
import ru.hznik.devicebridge.domain.trust.TrustedBrowserAuthenticationResult
import ru.hznik.devicebridge.domain.trust.TrustedBrowserIssueRequest

@OptIn(ExperimentalCoroutinesApi::class)
class RoomTrustedBrowserRepositoryTest {
    @Test
    fun issueStoresOnlyVerifierAndAuthenticationUpdatesLastUsed() = runTest {
        val dao = FakeDao()
        val generator = QueueGenerator("raw-secret", "browser-id")
        val verifier = HmacSha256TrustedCredentialVerifier {
            SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
        }
        val repository = RoomTrustedBrowserRepository(
            dao = dao,
            credentialGenerator = generator,
            credentialVerifier = verifier,
            clock = fixedClock(1_000),
            applicationScope = this,
        )
        runCurrent()

        val issued = repository.issue(
            TrustedBrowserIssueRequest(
                browserLabel = "Chrome on Windows",
                issuedAtEpochMillis = 1_000,
                expiresAtEpochMillis = 2_000,
            ),
        )

        assertEquals("raw-secret", issued.rawCredential)
        val stored = dao.items.value.single()
        assertFalse(stored.credentialVerifier.contentEquals("raw-secret".encodeToByteArray()))
        assertTrue(
            repository.authenticate("wrong", 1_500) is
                TrustedBrowserAuthenticationResult.Invalid,
        )
        val authenticated = repository.authenticate("raw-secret", 1_500)
        assertTrue(authenticated is TrustedBrowserAuthenticationResult.Authenticated)
        assertEquals(1_500L, dao.items.value.single().lastUsedAtEpochMillis)
    }

    @Test
    fun expiredCredentialIsRemovedAndReportedExpired() = runTest {
        val dao = FakeDao()
        val verifier = HmacSha256TrustedCredentialVerifier {
            SecretKeySpec(ByteArray(32) { 9 }, "HmacSHA256")
        }
        dao.insert(
            TrustedBrowserEntity(
                trustedBrowserId = "expired-id",
                browserLabel = "Edge",
                createdAtEpochMillis = 1_000,
                lastUsedAtEpochMillis = null,
                expiresAtEpochMillis = 2_000,
                credentialVerifier = verifier.verifierFor("expired-secret"),
            ),
        )
        val repository = RoomTrustedBrowserRepository(
            dao = dao,
            credentialGenerator = QueueGenerator("unused", "unused-id"),
            credentialVerifier = verifier,
            clock = fixedClock(1_500),
            applicationScope = this,
        )
        runCurrent()

        assertTrue(
            repository.authenticate("expired-secret", 2_000) is
                TrustedBrowserAuthenticationResult.Expired,
        )
        assertTrue(dao.items.value.isEmpty())
    }

    private class QueueGenerator(vararg values: String) : TrustedCredentialGenerator {
        private val iterator = values.iterator()
        override fun generate(): String = iterator.next()
    }

    private class FakeDao : TrustedBrowserDao {
        val items = MutableStateFlow<List<TrustedBrowserEntity>>(emptyList())
        override suspend fun insert(browser: TrustedBrowserEntity) {
            items.value = items.value + browser
        }
        override fun observeAll(): Flow<List<TrustedBrowserEntity>> = items
        override suspend fun findById(trustedBrowserId: String): TrustedBrowserEntity? =
            items.value.firstOrNull { it.trustedBrowserId == trustedBrowserId }
        override suspend fun getAll(): List<TrustedBrowserEntity> = items.value
        override suspend fun updateLastUsed(
            trustedBrowserId: String,
            lastUsedAtEpochMillis: Long,
        ): Int {
            var updated = 0
            items.value = items.value.map {
                if (it.trustedBrowserId == trustedBrowserId) {
                    updated += 1
                    it.copy(lastUsedAtEpochMillis = lastUsedAtEpochMillis)
                } else it
            }
            return updated
        }
        override suspend fun deleteById(trustedBrowserId: String): Int {
            val before = items.value.size
            items.value = items.value.filterNot { it.trustedBrowserId == trustedBrowserId }
            return before - items.value.size
        }
        override suspend fun clear(): Int {
            val count = items.value.size
            items.value = emptyList()
            return count
        }
        override suspend fun deleteExpired(nowEpochMillis: Long): Int {
            val before = items.value.size
            items.value = items.value.filter { it.expiresAtEpochMillis > nowEpochMillis }
            return before - items.value.size
        }
    }

    private fun fixedClock(epochMillis: Long): Clock = Clock.fixed(
        Instant.ofEpochMilli(epochMillis),
        ZoneOffset.UTC,
    )
}
