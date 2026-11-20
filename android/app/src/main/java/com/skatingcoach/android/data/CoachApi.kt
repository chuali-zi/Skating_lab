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
import java.net.SocketTimeoutException
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

    suspend fun exchange(method: String, path: String, body: RequestBody? = null, frameConsent: Boolean = false): String = withContext(Dispatchers.IO) {
        require(path.startsWith("/api/v1/")) { "Unsupported API path" }
        val url = base.resolve(path) ?: throw IOException("服务地址无效")
        val request = Request.Builder().url(url).header("Accept", "application/json")
        if (frameConsent) {
            require(path == VIDEO_PATH || path.startsWith("$VIDEO_PATH/from-source/"))
            request.header("X-Skating-Frame-Consent", "1")
        }
        token?.let { request.header("Authorization", "Bearer $it") }
        request.method(method, body)
        try { client.newCall(request.build()).execute().use { response ->
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
        } } catch (e: ApiException) { throw e }
        catch (e: SocketTimeoutException) { throw IOException("服务响应超时，请稍后重试。当前输入已保留。", e) }
        catch (e: IOException) { throw IOException("暂时无法连接服务，请检查网络和服务地址后重试。当前输入已保留。", e) }
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

    suspend fun submitVideo(resolver: android.content.ContentResolver, uri: android.net.Uri?, source: String?): VideoJob {
        val path = if (source == null) VIDEO_PATH else "$VIDEO_PATH/from-source/${java.util.UUID.fromString(source)}"
        val data = if (source != null) ByteArray(0).toRequestBody(null) else object : RequestBody() {
            override fun contentType() = "video/mp4".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) {
                val buffer = ByteArray(16384)
                var total = 0
                val stream = resolver.openInputStream(requireNotNull(uri)) ?: throw IOException("无法读取视频。")
                stream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > 10 * 1024 * 1024) throw IOException("请截取 10 MiB 以内的片段。")
                        sink.write(buffer, 0, n)
                    }
                }
            }
        }
        return json.decodeFromString(exchange("POST",path,data,frameConsent = true))
    }
    suspend fun reviewVideo(job: String, index: Int, decision: String, note: String?): VideoReview {
        val data = buildJsonObject {
            put("confirmed",true)
            put("decision",decision)
            note?.let { put("capability_note",it) }
        }
        return json.decodeFromString(exchange("PUT","$VIDEO_PATH/$job/observations/$index/review",data.toString().toRequestBody(JSON_MEDIA)))
    }
    suspend fun videoFrame(job: String, index: Int): ByteArray = withContext(Dispatchers.IO) {
        val url = base.resolve("$VIDEO_PATH/${java.util.UUID.fromString(job)}/frames/$index") ?: throw IOException("无效的帧地址。")
        val request = Request.Builder().url(url).header("Authorization","Bearer "+requireNotNull(token))
            .header("Accept","image/jpeg").cacheControl(okhttp3.CacheControl.Builder().noCache().noStore().build()).build()
        client.newCall(request).execute().use { reply ->
            if (!reply.isSuccessful || reply.body?.contentType()?.subtype != "jpeg") throw IOException("该帧不可读取，请刷新观察。")
            reply.body!!.bytes()
        }
    }
    companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val VIDEO_PATH = "/api/v1/assets/video-jobs"
    }
}
