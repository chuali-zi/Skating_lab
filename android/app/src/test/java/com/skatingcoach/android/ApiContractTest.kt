package com.skatingcoach.android

import com.skatingcoach.android.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ApiContractTest {
    @Test fun disconnectedServiceHasAnActionableMessageWithoutLosingTheCause() = runBlocking {
        val server = MockWebServer()
        server.start()
        val api = CoachApi(server.url("/").toString())
        server.shutdown()
        try {
            api.get<MemoryEnvelope>("/api/v1/memory")
            fail("Expected connection failure")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("暂时无法连接服务"))
            assertTrue(e.message.orEmpty().contains("当前输入已保留"))
            assertNotNull(e.cause)
        }
    }

    @Test fun singleJumpTypeIsSentEvenWhenItIsTheKotlinDefault() {
        val element = PlanElement("single-1", jumps = listOf("3S"))
        val encoded = Json.parseToJsonElement(Json.encodeToString(element)).jsonObject
        assertEquals("SINGLE", encoded.getValue("type").jsonPrimitive.content)
        val api = CoachApi("http://127.0.0.1:8080")
        val body = api.encodePlanBody(PlanBody(elements = listOf(element))).jsonObject
        assertEquals("SINGLE", body.getValue("elements").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun knowledgeDetailReadsNestedSourceAndOriginalLocator() {
        val detail = Json.decodeFromString<KnowledgeDetail>("""{"source":{"id":"source-1","title":"比赛补充通知","status":"partial","revision":2},"blocks":[{"id":"block-1","text":"条款原文","locator":"第2页"}]}""")
        assertEquals("比赛补充通知", detail.title)
        assertEquals("partial", detail.status)
        assertEquals("第2页", detail.blocks.single().locator)
    }

    @Test fun unknownScoreRemainsNullInsteadOfShowingKnownSubtotalAsTotal() {
        val score = Json { ignoreUnknownKeys = true }.decodeFromString<Evaluation>("""{"knownSubtotal":12.3,"evaluatedTotal":null,"scoreComplete":false,"checksComplete":false,"checks":[{"code":"TIME_UNKNOWN","status":"UNKNOWN","message":"时间待核实"}]}""")
        assertNull(score.evaluatedTotal)
        assertFalse(score.scoreComplete)
        assertEquals("UNKNOWN", score.checks.single().status)
    }

    @Test fun recoveryRoundTripKeepsSourceAndFrozenPrefix() {
        val prefix = listOf(PlanElement("performed-1", jumps = listOf("2T"), plannedStartSeconds = 30.0))
        val body = PlanBody(elements = prefix + PlanElement("future-2", jumps = listOf("2A")), completedPrefix = prefix, sourcePlanId = "main-plan", failureDescription = "第一跳降周")
        val restored = Json.decodeFromString<PlanBody>(Json.encodeToString(body))
        assertEquals(prefix, restored.completedPrefix)
        assertEquals("main-plan", restored.sourcePlanId)
    }

    @Test fun authenticatedRequestAndConflictArePreserved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"REVISION_CONFLICT","message":"内容已更新，请刷新"}"""))
            val api = CoachApi(server.url("/").toString())
            api.token = "test-session-only"
            try {
                api.get<MemoryEnvelope>("/api/v1/memory")
                fail("Expected conflict")
            } catch (e: ApiException) {
                assertEquals(409, e.status)
                assertEquals("REVISION_CONFLICT", e.code)
                assertEquals("内容已更新，请刷新", e.message)
            }
            val request = server.takeRequest()
            assertEquals("Bearer test-session-only", request.getHeader("Authorization"))
            assertEquals("/api/v1/memory", request.path)
        }
    }
}
