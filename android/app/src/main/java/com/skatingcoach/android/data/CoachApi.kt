package com.skatingcoach.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(val status: Int, val code: String?, override val message: String) : IOException(message)

class CoachApi(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build(),
) {
    private val base = baseUrl.trim().trimEnd('/').toHttpUrl()
    var token: String? = null
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    suspend inline fun <reified T> get(path: String): T = json.decodeFromString(exchange("GET", path))
    suspend inline fun <reified T> post(path: String, body: JsonElement = buildJsonObject {}): T = json.decodeFromString(exchange("POST", path, body.toString().toRequestBody(JSON_MEDIA)))
    suspend inline fun <reified T> patch(path: String, body: JsonElement): T = json.decodeFromString(exchange("PATCH", path, body.toString().toRequestBody(JSON_MEDIA)))
    suspend fun delete(path: String) { exchange("DELETE", path) }

    suspend fun exchange(method: String, path: String, body: RequestBody? = null): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v1/")) { "Unsupported API path" }
        val url = base.resolve(path) ?: throw IOException("服务地址无效")
        val request = Request.Builder().url(url).header("Accept", "application/json")
        token?.let { request.header("Authorization", "Bearer $it") }
        request.method(method, body)
        client.newCall(request.build()).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val problem = runCatching { json.parseToJsonElement(responseText).jsonObject }.getOrNull()
                val code = problem?.get("code")?.jsonPrimitive?.contentOrNull
                val message = problem?.get("detail")?.jsonPrimitive?.contentOrNull
                    ?: problem?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: when (response.code) {
                        401 -> "会话已失效，请重新连接服务。"
                        409 -> "资料已在其他地方更新，请刷新后再修改。"
                        503 -> "服务暂不可用，已保留你的输入，请稍后重试。"
                        else -> "请求未完成（${response.code}），请重试。"
                    }
                throw ApiException(response.code, code, message)
            }
            responseText.ifBlank { "{}" }
        }
    }

    suspend fun upload(title: String, fileName: String, mimeType: String, fileBody: RequestBody, competition: String, requestId: String): KnowledgeItem {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title", title)
            .addFormDataPart("requestId", requestId)
            .addFormDataPart("file", fileName, fileBody)
        if (competition.isNotBlank()) body.addFormDataPart("competitionKey", competition)
        return json.decodeFromString(exchange("POST", "/api/v1/knowledge/upload", body.build()))
    }

    fun encodePlanBody(body: PlanBody): JsonElement = json.parseToJsonElement(json.encodeToString(body))

    companion object { val JSON_MEDIA = "application/json; charset=utf-8".toMediaType() }
}
