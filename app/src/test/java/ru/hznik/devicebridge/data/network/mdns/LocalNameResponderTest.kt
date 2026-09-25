package ru.hznik.devicebridge.data.network.mdns

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNameResponderTest {

    private val own = ip(192, 168, 1, 37)
    private val peer = ip(192, 168, 1, 50)
    private val sent = mutableListOf<Pair<MdnsMessage, MdnsDestination>>()
    private var now = 0L
    private var lostCalls = 0
    private var onSend: (MdnsMessage) -> Unit = {}

    private val responder = LocalNameResponder(
        sender = { packet, destination ->
            val message = requireNotNull(MdnsCodec.decode(packet))
            sent += message to destination
            onSend(message)
        },
        address = own,
        subnet = Ipv4Subnet(own, 24),
        nowMs = { now },
        onNameLost = { lostCalls++ },
    )

    @Test
    fun freeNameIsClaimedAfterThreeProbes() = runTest {
        val claim = async { responder.claim("DeviceBridge") }
        advanceTimeBy(749)
        assertFalse(claim.isCompleted)
        advanceTimeBy(2)

        assertEquals(LocalNameClaim.Claimed("devicebridge.local", requestedTaken = false), claim.await())
        assertEquals(3, sent.size)
        sent.forEach { (message, destination) ->
            assertEquals(MdnsDestination.Multicast, destination)
            assertEquals(MdnsQuestion("devicebridge.local", MdnsType.ANY, unicastResponse = true), message.questions.single())
            assertArrayEquals(own, message.authorities.single().data)
        }
        assertEquals("devicebridge.local", responder.currentName)
    }

    @Test
    fun takenNameMovesToTheNextSuffix() = runTest {
        onSend = { message ->
            if (message.questions.firstOrNull()?.name == "devicebridge.local") deliver(answerFrom(peer, "devicebridge.local"))
        }

        val claim = claimNow("devicebridge")

        assertEquals(LocalNameClaim.Claimed("devicebridge-2.local", requestedTaken = true), claim)
    }

    @Test
    fun allSuffixesTakenGivesUp() = runTest {
        onSend = { message ->
            val name = message.questions.firstOrNull()?.name
            if (name != null && !message.isResponse) deliver(answerFrom(peer, name))
        }

        assertEquals(LocalNameClaim.AllTaken, claimNow("devicebridge"))
        assertEquals((listOf("devicebridge") + (2..9).map { "devicebridge-$it" }).map { "$it.local" }, sent.map { it.first.questions.single().name }.distinct())
        assertNull(responder.currentName)
    }

    @Test
    fun simultaneousProbeWithHigherAddressWins() = runTest {
        val higher = ip(192, 168, 1, 200)
        onSend = { message ->
            if (message.questions.firstOrNull()?.name == "devicebridge.local") deliver(probeFrom(higher, "devicebridge.local"))
        }

        assertEquals("devicebridge-2.local", (claimNow("devicebridge") as LocalNameClaim.Claimed).name)
    }

    @Test
    fun simultaneousProbeWithLowerAddressLoses() = runTest {
        val lower = ip(192, 168, 1, 2)
        onSend = { message ->
            if (message.questions.firstOrNull()?.name == "devicebridge.local") deliver(probeFrom(lower, "devicebridge.local"))
        }

        assertEquals("devicebridge.local", (claimNow("devicebridge") as LocalNameClaim.Claimed).name)
    }

    @Test
    fun ownLoopedBackPacketsAreNotConflicts() = runTest {
        onSend = { message ->
            if (!message.isResponse) deliver(answerFrom(own, message.questions.single().name), source = own)
        }

        assertEquals("devicebridge.local", (claimNow("devicebridge") as LocalNameClaim.Claimed).name)
    }

    @Test
    fun announcesTwiceOneSecondApart() = runTest {
        claimNow("devicebridge")
        sent.clear()

        val job = async { responder.announce() }
        runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(1_001)
        job.await()

        assertEquals(2, sent.size)
        sent.forEach { (message, destination) ->
            assertEquals(MdnsDestination.Multicast, destination)
            assertTrue(message.isResponse)
            assertEquals(LocalNameResponder.TTL_SECONDS, message.answers.single().ttlSeconds)
        }
    }

    @Test
    fun multicastQueryGetsAnAnswerWithNoIpv6Record() = runTest {
        claimNow("devicebridge")
        sent.clear()

        deliver(query("devicebridge.local", MdnsType.A))

        val (message, destination) = sent.single()
        assertEquals(MdnsDestination.Multicast, destination)
        val answer = message.answers.single()
        assertEquals(MdnsType.A, answer.type)
        assertArrayEquals(own, answer.data)
        assertTrue(answer.cacheFlush)
        assertEquals(MdnsType.NSEC, message.additionals.single().type)
    }

    @Test
    fun ipv6QuestionGetsOnlyTheNoIpv6Record() = runTest {
        claimNow("devicebridge")
        sent.clear()

        deliver(query("devicebridge.local", MdnsType.AAAA))

        assertEquals(MdnsType.NSEC, sent.single().first.answers.single().type)
    }

    @Test
    fun multicastAnswersAreLimitedToOnePerSecond() = runTest {
        claimNow("devicebridge")
        sent.clear()

        deliver(query("devicebridge.local", MdnsType.A))
        now += 500
        deliver(query("devicebridge.local", MdnsType.A))
        now += 600
        deliver(query("devicebridge.local", MdnsType.A))

        assertEquals(2, sent.size)
    }

    @Test
    fun unicastQuestionAlsoGetsADirectAnswer() = runTest {
        claimNow("devicebridge")
        sent.clear()

        deliver(query("devicebridge.local", MdnsType.A, unicast = true))

        assertEquals(
            listOf(MdnsDestination.Unicast(peer, 5353), MdnsDestination.Multicast),
            sent.map { it.second },
        )
    }

    @Test
    fun legacyUnicastQueryGetsAPlainDnsAnswer() = runTest {
        claimNow("devicebridge")
        sent.clear()

        deliver(query("devicebridge.local", MdnsType.A, id = 0x4242), sourcePort = 53_000)

        val (message, destination) = sent.single()
        assertEquals(MdnsDestination.Unicast(peer, 53_000), destination)
        assertEquals(0x4242, message.id)
        assertEquals("devicebridge.local", message.questions.single().name)
        val answer = message.answers.single()
        assertEquals(LocalNameResponder.TTL_LEGACY_SECONDS, answer.ttlSeconds)
        assertFalse(answer.cacheFlush)
    }

    @Test
    fun knownAnswerSuppressesTheRepeat() = runTest {
        claimNow("devicebridge")
        sent.clear()

        val known = MdnsMessage(
            isResponse = false,
            questions = listOf(MdnsQuestion("devicebridge.local", MdnsType.A)),
            answers = listOf(MdnsCodec.aRecord("devicebridge.local", own, 100, cacheFlush = false)),
        )
        deliver(known)

        assertTrue(sent.isEmpty())
    }

    @Test
    fun questionsFromOutsideTheSubnetOrAboutOtherNamesAreIgnored() = runTest {
        claimNow("devicebridge")
        sent.clear()

        deliver(query("devicebridge.local", MdnsType.A), source = ip(10, 0, 0, 7))
        deliver(query("printer.local", MdnsType.A))
        deliver(query("devicebridge.local", 16))

        assertTrue(sent.isEmpty())
    }

    @Test
    fun conflictWhileRunningGivesUpTheName() = runTest {
        claimNow("devicebridge")
        sent.clear()

        deliver(answerFrom(peer, "devicebridge.local"))
        deliver(query("devicebridge.local", MdnsType.A))

        assertEquals(1, lostCalls)
        assertNull(responder.currentName)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun releaseSaysGoodbyeOnce() = runTest {
        claimNow("devicebridge")
        sent.clear()

        responder.release()
        responder.release()

        val (message, destination) = sent.single()
        assertEquals(MdnsDestination.Multicast, destination)
        assertEquals(0L, message.answers.single().ttlSeconds)
        assertNull(responder.currentName)
    }

    @Test
    fun malformedPacketsAreIgnored() = runTest {
        claimNow("devicebridge")
        sent.clear()

        responder.onPacket(byteArrayOf(1, 2, 3), 3, peer, 5353)

        assertTrue(sent.isEmpty())
    }

    private suspend fun TestScope.claimNow(label: String): LocalNameClaim {
        val claim = async { responder.claim(label) }
        advanceTimeBy(60_000)
        return claim.await()
    }

    private fun deliver(message: MdnsMessage, source: ByteArray = peer, sourcePort: Int = 5353) {
        val bytes = MdnsCodec.encode(message)
        responder.onPacket(bytes, bytes.size, source, sourcePort)
    }

    private fun query(name: String, type: Int, unicast: Boolean = false, id: Int = 0) =
        MdnsMessage(id = id, isResponse = false, questions = listOf(MdnsQuestion(name, type, unicast)))

    private fun answerFrom(address: ByteArray, name: String) =
        MdnsMessage(isResponse = true, answers = listOf(MdnsCodec.aRecord(name, address, 120)))

    private fun probeFrom(address: ByteArray, name: String) = MdnsMessage(
        isResponse = false,
        questions = listOf(MdnsQuestion(name, MdnsType.ANY, unicastResponse = true)),
        authorities = listOf(MdnsCodec.aRecord(name, address, 120, cacheFlush = false)),
    )

    private fun ip(vararg octets: Int): ByteArray = ByteArray(4) { octets[it].toByte() }
}
