package ru.hznik.devicebridge.web

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.hznik.devicebridge.core.protocol.text.TEXT_ACCEPTED_TYPE
import ru.hznik.devicebridge.core.protocol.text.TextAcceptedResponse
import ru.hznik.devicebridge.core.protocol.text.TextContentKindDto
import ru.hznik.devicebridge.core.protocol.text.TextProtocolJson
import ru.hznik.devicebridge.core.protocol.text.TextTransferStatusDto
import ru.hznik.devicebridge.domain.text.TextContentValidator

class TextRouteTest {

    @Test
    fun authorizedRequestIsAcceptedOnceAndReturnsStableResponseForRetry() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Яндекс Браузер")
            val headers = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${paired.token}"),
            )
            val body = sendBody(messageId = "browser-message-1", content = "https://example.com")

            val first = server.request("POST", "/api/v1/text", body, headers)
            val repeated = server.request("POST", "/api/v1/text", body, headers)

            assertEquals(200, first.statusCode())
            assertEquals(first.body(), repeated.body())
            assertEquals(
                TextAcceptedResponse(
                    protocolVersion = 1,
                    messageId = "browser-message-1",
                    type = TEXT_ACCEPTED_TYPE,
                    timestamp = 1_000_000,
                    contentKind = TextContentKindDto.LINK,
                    status = TextTransferStatusDto.DELIVERED,
                ),
                TextProtocolJson.decode<TextAcceptedResponse>(first.body()),
            )
            assertEquals(1, server.textCoordinator.state.value.items.size)
        }

    @Test
    fun malformedPayloadIsRejectedBeforeCreatingIncomingItem() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")
            val response = server.request(
                "POST",
                "/api/v1/text",
                """{"protocolVersion":1,"messageId":"message-1"}""",
                server.sameOriginJsonHeaders(
                    mapOf("Authorization" to "Bearer ${paired.token}"),
                ),
            )

            assertEquals(400, response.statusCode())
            assertEquals(0, server.textCoordinator.state.value.items.size)
        }

    @Test
    fun missingBearerIsRejectedBeforeCreatingIncomingItem() =
        withSessionRouteServer { server ->
            val response = server.request(
                "POST",
                "/api/v1/text",
                sendBody(),
                server.sameOriginJsonHeaders(),
            )

            assertEquals(401, response.statusCode())
            assertEquals(0, server.textCoordinator.state.value.items.size)
        }

    @Test
    fun unknownBearerIsRejectedBeforeCreatingIncomingItem() =
        withSessionRouteServer { server ->
            val response = server.request(
                "POST",
                "/api/v1/text",
                sendBody(),
                server.sameOriginJsonHeaders(
                    mapOf("Authorization" to "Bearer unknown-session-token"),
                ),
            )

            assertEquals(401, response.statusCode())
            assertEquals(0, server.textCoordinator.state.value.items.size)
        }

    @Test
    fun repeatedMessageIdWithDifferentContentReturnsConflictWithoutMutation() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Edge")
            val headers = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${paired.token}"),
            )

            val first = server.request("POST", "/api/v1/text", sendBody(content = "first"), headers)
            val conflict = server.request(
                "POST",
                "/api/v1/text",
                sendBody(content = "different"),
                headers,
            )

            assertEquals(200, first.statusCode())
            assertEquals(409, conflict.statusCode())
            assertEquals(listOf("first"), server.textCoordinator.state.value.items.map { it.content })
        }

    @Test
    fun exactContentLimitIsAcceptedWhileOneExtraByteIsRejected() =
        withSessionRouteServer { server ->
            val paired = server.pairBrowser("Chrome")
            val headers = server.sameOriginJsonHeaders(
                mapOf("Authorization" to "Bearer ${paired.token}"),
            )
            val exact = "a".repeat(TextContentValidator.MAX_UTF8_BYTES)
            val oversized = "$exact!"

            val accepted = server.request(
                "POST",
                "/api/v1/text",
                sendBody(messageId = "exact-limit", content = exact),
                headers,
            )
            val rejected = server.request(
                "POST",
                "/api/v1/text",
                sendBody(messageId = "over-limit", content = oversized),
                headers,
            )

            assertEquals(200, accepted.statusCode())
            assertEquals(413, rejected.statusCode())
            assertEquals(listOf("exact-limit"), server.textCoordinator.state.value.items.map {
                it.id.value
            })
        }

    private fun sendBody(
        messageId: String = "message-1",
        content: String = "hello",
    ): String = TextProtocolJson.encode(
        ru.hznik.devicebridge.core.protocol.text.TextSendRequest(
            protocolVersion = 1,
            messageId = messageId,
            type = "text.send",
            timestamp = 123,
            content = content,
        ),
    )
}
