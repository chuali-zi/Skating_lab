package com.skatingcoach.android.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.skatingcoach.android.BuildConfig
import com.skatingcoach.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.util.UUID
import java.security.MessageDigest

enum class Destination(val label: String) { PREPARE("准备"), PLAN("编排"), KNOWLEDGE("资料"), MEMORY("档案") }
data class Candidate(val title: String, val body: PlanBody, val kind: String = "MAIN", val rationale: String = "")
data class CoachUiState(
    val destination: Destination = Destination.PREPARE,
    val baseUrl: String = "http://127.0.0.1:8080",
    val connected: Boolean = false,
    val displayName: String = "",
    val busy: String? = null,
    val error: String? = null,
    val notice: String? = null,
    val draft: LocalDraft = LocalDraft(),
    val catalog: List<JumpOption> = emptyList(),
    val plans: List<PlanView> = emptyList(),
    val evaluation: Evaluation? = null,
    val evaluating: Boolean = false,
    val memories: List<MemoryItem> = emptyList(),
    val memorySource: MemorySource? = null,
    val knowledge: List<KnowledgeItem> = emptyList(),
    val professionalSources: List<ProfessionalSource> = emptyList(),
    val knowledgeDetail: KnowledgeDetail? = null,
    val searchResult: SearchResult? = null,
    val explanation: String = "",
    val candidates: List<Candidate> = emptyList(),
    val recommendationEvidence: List<SearchHit> = emptyList(),
    val runId: String? = null,
    val recoveryBody: PlanBody? = null,
    val recoveryEvaluation: Evaluation? = null,
    val recoveryFrozenCount: Int = 0,
    val recoveryDescription: String = "",
    val recoverySaved: PlanView? = null,
)

class CoachViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("local_draft", 0)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private var draftStorageKey = prefs.getString("lastDraftKey", "draft.unassigned")!!
    private val restored = runCatching { json.decodeFromString<LocalDraft>(prefs.getString(draftStorageKey, null) ?: "{}") }.getOrDefault(LocalDraft())
    private val mutableState = MutableStateFlow(CoachUiState(baseUrl = prefs.getString("baseUrl", "http://127.0.0.1:8080")!!, draft = restored))
    val state = mutableState.asStateFlow()
    private var api: CoachApi? = null
    private var conversationId: String? = null
    private var localEditVersion = 0L
    private var undoDraft: LocalDraft? = null
    private var privacyEpoch: Long? = null

    fun destination(value: Destination) { mutableState.update { it.copy(destination = value) } }
    fun clearNotice() { mutableState.update { it.copy(notice = null) } }
    fun clearError() { mutableState.update { it.copy(error = null) } }
    fun closeKnowledgeDetail() { mutableState.update { it.copy(knowledgeDetail = null) } }
    fun closeMemorySource() { mutableState.update { it.copy(memorySource = null) } }

    private fun changeDraft(transform: (LocalDraft) -> LocalDraft) {
        val previous = state.value.draft
        undoDraft = previous
        val next = transform(previous)
        val scoreChanged = previous.elements != next.elements || previous.ruleSet != next.ruleSet || previous.selectedSingles != next.selectedSingles || previous.selectedCombinations != next.selectedCombinations
        if (scoreChanged) localEditVersion++
        mutableState.update { it.copy(draft = next, evaluation = if (scoreChanged) null else it.evaluation) }
        prefs.edit().putString(draftStorageKey, json.encodeToString(next)).apply()
    }

    fun updateSituation(value: String) = changeDraft { it.copy(situation = value) }
    fun updateCompetition(value: String) = changeDraft { it.copy(competition = value) }
    fun updateTitle(value: String) = changeDraft { it.copy(title = value) }
    fun toggleSingle(code: String) = changeDraft { SelectionEditor.toggleSingle(it, code, state.value.catalog) }
    fun toggleCombination(code: String) = changeDraft { SelectionEditor.toggleCombination(it, code) }
    fun toggleGroup(revolutions: Int) {
        val group = state.value.catalog.filter { it.revolutions == revolutions }
        val allSelected = group.all { it.code in state.value.draft.selectedSingles }
        changeDraft { draft -> group.fold(draft) { next, jump ->
            if ((jump.code in next.selectedSingles) == allSelected) SelectionEditor.toggleSingle(next, jump.code, state.value.catalog) else next
        } }
    }

    fun undo() { undoDraft?.let { previous -> changeDraft { previous }; mutableState.update { it.copy(notice = "已撤销最近一次修改") } } }
    fun newPlan() { changeDraft { it.copy(title = "新的编排", elements = emptyList(), planId = null, planRevision = null, ruleSet = null, contextInvalidated = false) }; mutableState.update { it.copy(recoveryBody = null, explanation = "", candidates = emptyList(), destination = Destination.PLAN) } }
    fun addElement() = changeDraft { it.copy(elements = it.elements + PlanElement(UUID.randomUUID().toString())) }
    fun removeElement(id: String) = changeDraft { it.copy(elements = it.elements.filterNot { row -> row.id == id }) }
    fun updateElement(id: String, jumps: String, time: String) {
        changeDraft { draft -> draft.copy(elements = draft.elements.map { if (it.id == id) {
            val codes = jumps.split('+').map(String::trim).filter(String::isNotBlank)
            it.copy(jumps = codes, type = if (codes.size > 1) { if (it.type == "SEQUENCE") "SEQUENCE" else "COMBINATION" } else "SINGLE", plannedStartSeconds = time.toDoubleOrNull())
        } else it }) }
    }
    fun moveElement(from: Int, to: Int) {
        if (to !in state.value.draft.elements.indices) return
        changeDraft { draft -> val rows = draft.elements.toMutableList(); rows.add(to, rows.removeAt(from)); draft.copy(elements = rows) }
    }
    fun openPlan(plan: PlanView) {
        if (plan.kind == "RECOVERY") {
            mutableState.update { it.copy(recoveryBody = plan.body, recoverySaved = plan, recoveryFrozenCount = plan.body.completedPrefix.size, recoveryDescription = plan.body.failureDescription.orEmpty(), destination = Destination.PLAN) }
        } else {
            val wasInvalidated = state.value.draft.contextInvalidated
            changeDraft { it.copy(title = plan.title, elements = plan.body.elements, situation = plan.body.notes, planId = plan.id, planRevision = plan.revision, ruleSet = plan.body.ruleSet, contextInvalidated = plan.contextInvalidated, selectedSingles = plan.body.selection?.selectedSingles?.toSet() ?: it.selectedSingles, selectedCombinations = plan.body.selection?.selectedCombinations?.toSet() ?: it.selectedCombinations) }
            if (wasInvalidated || plan.contextInvalidated) undoDraft = null
            mutableState.update { it.copy(destination = Destination.PLAN, explanation = plan.body.notes, recoveryBody = null, recoverySaved = null) }
        }
        evaluate(plan.kind == "RECOVERY")
    }
    fun reloadPlan() = operation("正在读取最新方案") {
        val id = state.value.draft.planId ?: return@operation
        openPlan(requireApi().get("/api/v1/plans/$id"))
    }

    fun connect(baseUrl: String, token: String) = operation("正在连接服务") {
        val parsed = Uri.parse(baseUrl.trim())
        require(parsed.scheme == "https" || (BuildConfig.DEBUG && parsed.scheme == "http" && parsed.host in setOf("127.0.0.1", "localhost", "10.0.2.2"))) { "请输入 HTTPS 服务地址；开发版支持本机转发地址。" }
        val client = CoachApi(baseUrl)
        val identity = if (token.isNotBlank()) { client.token = token.trim(); client.get<ActorIdentity>("/api/v1/me") } else {
            check(BuildConfig.DEBUG) { "请提供服务访问凭据。" }
            val session = client.post<DevSession>("/api/v1/dev/session")
            client.token = session.token
            ActorIdentity(session.actorId, session.athleteId, session.displayName)
        }
        val catalog = client.get<List<JumpOption>>("/api/v1/catalog/jumps")
        val session = client.post<Conversation>("/api/v1/sessions")
        api = client
        conversationId = session.id
        val scopeHash = MessageDigest.getInstance("SHA-256").digest("${baseUrl.trim().trimEnd('/')}|${identity.athleteId}".toByteArray()).joinToString("") { "%02x".format(it) }
        val nextDraftKey = "draft.$scopeHash"
        val scopedDraft = if (draftStorageKey == "draft.unassigned" || draftStorageKey == nextDraftKey) state.value.draft else runCatching { json.decodeFromString<LocalDraft>(prefs.getString(nextDraftKey, null) ?: "{}") }.getOrDefault(LocalDraft())
        draftStorageKey = nextDraftKey
        prefs.edit().putString("baseUrl", baseUrl.trim()).putString("lastDraftKey", nextDraftKey).putString(nextDraftKey, json.encodeToString(scopedDraft)).remove("draft.unassigned").apply()
        undoDraft = null; privacyEpoch = null; localEditVersion++
        mutableState.value = CoachUiState(baseUrl = baseUrl.trim(), connected = true, displayName = identity.displayName, catalog = catalog, draft = scopedDraft, notice = "服务已连接", busy = "正在同步资料")
        refreshAll(client)
    }

    fun refresh() = operation("正在同步") { refreshAll(requireApi()) }
    private suspend fun refreshAll(client: CoachApi) {
        val plans = client.get<List<PlanView>>("/api/v1/plans")
        val memories = client.get<MemoryEnvelope>("/api/v1/memory")
        val knowledge = client.get<KnowledgeEnvelope>("/api/v1/knowledge")
        val catalog = client.get<ProfessionalCatalog>("/api/v1/knowledge/catalog")
        val revoked = privacyEpoch != null && privacyEpoch != memories.privacyEpoch
        if (revoked) undoDraft = null
        privacyEpoch = memories.privacyEpoch
        mutableState.update {
            val invalidated = plans.firstOrNull { plan -> plan.id == it.draft.planId }?.contextInvalidated ?: it.draft.contextInvalidated
            it.copy(plans = plans, memories = memories.items, knowledge = knowledge.items, professionalSources = catalog.items,
                draft = it.draft.copy(contextInvalidated = invalidated),
                explanation = if (revoked || invalidated) "" else it.explanation,
                candidates = if (revoked || invalidated) emptyList() else it.candidates,
                recommendationEvidence = if (revoked || invalidated) emptyList() else it.recommendationEvidence,
                memorySource = if (revoked) null else it.memorySource,
                knowledgeDetail = if (revoked) null else it.knowledgeDetail,
                searchResult = if (revoked) null else it.searchResult,
            )
        }
    }

    private fun body() = PlanBody(state.value.draft.elements, state.value.draft.ruleSet, state.value.draft.situation, selection = selection())
    private fun selection() = Selection(state.value.draft.selectedSingles.toList(), state.value.draft.selectedCombinations.toList())
    fun evaluate(recovery: Boolean = false) {
        val client = api ?: return
        val version = localEditVersion
        val planBody = if (recovery) state.value.recoveryBody ?: return else body()
        mutableState.update { it.copy(evaluating = true) }
        viewModelScope.launch {
            try {
                val payload = buildJsonObject {
                    put(if (recovery) "proposedElements" else "elements", json.encodeToJsonElement(planBody.elements))
                    if (recovery) put("completedPrefix", json.encodeToJsonElement(planBody.completedPrefix))
                    put("ruleSet", planBody.ruleSet ?: JsonNull)
                    put("selection", json.encodeToJsonElement(selection()))
                }
                val result = if (recovery) client.post<RecoveryEvaluation>("/api/v1/scoring/recovery", payload).evaluation else client.post<Evaluation>("/api/v1/scoring/evaluate", payload)
                if (version == localEditVersion) mutableState.update { if (recovery) it.copy(recoveryEvaluation = result) else it.copy(evaluation = result) }
            } catch (e: Exception) { if (e is CancellationException) throw e; mutableState.update { it.copy(error = e.message ?: "分值更新失败，请重试。") } }
            finally { mutableState.update { it.copy(evaluating = false) } }
        }
    }

    fun savePlan(recovery: Boolean = false) = operation("正在保存方案") {
        val client = requireApi()
        val snapshot = state.value
        val planBody = if (recovery) snapshot.recoveryBody ?: error("先选择失误情景") else body()
        val existingId = if (recovery) snapshot.recoverySaved?.id else snapshot.draft.planId
        val revision = if (recovery) snapshot.recoverySaved?.revision else snapshot.draft.planRevision
        val payload = buildJsonObject {
            put("title", if (recovery) "${snapshot.draft.title} · 失误预案" else snapshot.draft.title)
            put("kind", if (recovery) "RECOVERY" else "MAIN")
            put("body", client.encodePlanBody(planBody))
            revision?.let { put("expectedRevision", it) }
        }
        val saved = if (recovery && existingId == null) {
            val sourceId = snapshot.draft.planId ?: error("请先保存主编排，再保存与它关联的失误预案。")
            client.post<SavedRecovery>("/api/v1/plans/$sourceId/recovery", buildJsonObject {
                put("expectedRevision", snapshot.draft.planRevision ?: 0)
                put("title", "${snapshot.draft.title} · 失误预案")
                put("completedPrefix", json.encodeToJsonElement(planBody.completedPrefix))
                put("proposedElements", json.encodeToJsonElement(planBody.elements))
                put("ruleSet", planBody.ruleSet ?: JsonNull)
                put("selection", json.encodeToJsonElement(selection()))
                put("failureDescription", snapshot.recoveryDescription)
            }).plan
        } else if (existingId == null) client.post<PlanView>("/api/v1/plans", payload) else client.patch<PlanView>("/api/v1/plans/$existingId", payload)
        if (recovery) mutableState.update { it.copy(recoverySaved = saved) } else {
            val savedDraft = state.value.draft.copy(planId = saved.id, planRevision = saved.revision, contextInvalidated = saved.contextInvalidated)
            mutableState.update { it.copy(draft = savedDraft) }
            prefs.edit().putString(draftStorageKey, json.encodeToString(savedDraft)).apply()
        }
        mutableState.update { it.copy(notice = "方案已保存到服务端") }
        refreshAll(client)
    }

    fun recommend(recovery: Boolean = false) = operation("正在结合资料分析") {
        val client = requireApi()
        val session = conversationId ?: error("请重新连接服务")
        val snapshot = state.value
        if (recovery) check(snapshot.draft.planId != null) { "请先保存主编排，再分析与它关联的失误预案。" }
        mutableState.update { it.copy(explanation = "", candidates = emptyList(), recommendationEvidence = emptyList()) }
        val prompt = buildString {
            appendLine("请为我生成可编辑的跳跃编排与解释，考虑能力、偏好和疲劳，不仅追求 BV。")
            appendLine("已选单跳（可用而非必须）：${snapshot.draft.selectedSingles.joinToString()}。")
            appendLine("独立选择的连跳：${snapshot.draft.selectedCombinations.joinToString()}。")
            appendLine("比赛与当前资料语境：${snapshot.draft.competition}")
            appendLine("我的说明：${snapshot.draft.situation}")
            if (recovery) appendLine("失误情景：${snapshot.recoveryDescription}。实际已完成前缀：${json.encodeToString(snapshot.recoveryBody?.elements?.take(snapshot.recoveryFrozenCount))}。保持已发生动作不变，仅调整剩余部分。")
        }
        val run = client.post<RecommendationRun>("/api/v1/recommendations", buildJsonObject {
            put("requestId", UUID.randomUUID().toString()); put("sessionId", session); put("prompt", prompt)
            snapshot.draft.planId?.let { put("planId", it) }
            put("competitionKey", snapshot.draft.competition)
            put("selection", json.encodeToJsonElement(selection()))
            if (recovery) put("completedPrefix", json.encodeToJsonElement(snapshot.recoveryBody?.completedPrefix.orEmpty()))
        })
        readRun(run)
    }

    fun resumeRun() = operation("正在查看分析进度") {
        val id = state.value.runId ?: return@operation
        readRun(requireApi().get("/api/v1/recommendations/$id"))
    }

    private suspend fun readRun(initial: RecommendationRun) {
        var run = initial
        mutableState.update { it.copy(runId = run.id) }
        repeat(30) {
            if (run.status !in setOf("PENDING", "RUNNING", "QUEUED")) return@repeat
            delay(1000)
            run = requireApi().get("/api/v1/recommendations/${run.id}")
        }
        if (run.status == "PROVIDER_UNAVAILABLE") error("服务端尚未配置云端模型，资料和草稿已保留。配置后可以重新生成。")
        if (run.status == "REDACTED") {
            mutableState.update { it.copy(explanation = "", candidates = emptyList(), recommendationEvidence = emptyList(), runId = null) }
            error("分析引用的资料已撤回，旧结果已停止使用。请按当前资料重新生成。")
        }
        if (run.status in setOf("FAILED", "CANCELLED", "STALE")) error("本次分析未完成（${run.errorCode ?: run.status}），请保留当前输入后重试。")
        if (run.status in setOf("PENDING", "RUNNING", "QUEUED")) {
            mutableState.update { it.copy(notice = "分析仍在进行，可稍后继续查看") }; return
        }
        check(run.status == "SUCCEEDED") { "服务返回了尚未完成的分析状态，请稍后重新查看。" }
        val result = run.result?.jsonObject
        val explanation = result?.get("explanation")?.jsonPrimitive?.contentOrNull.orEmpty()
        val candidates = result?.get("candidates")?.jsonArray?.mapNotNull { element ->
            runCatching {
                val candidate = element.jsonObject
                val candidateBody = candidate["body"] ?: buildJsonObject { put("elements", candidate["elements"] ?: JsonArray(emptyList())); put("ruleSet", result["ruleSet"] ?: JsonNull) }
                Candidate(candidate["title"]?.jsonPrimitive?.contentOrNull ?: "建议方案", json.decodeFromJsonElement<PlanBody>(candidateBody), candidate["kind"]?.jsonPrimitive?.contentOrNull ?: "MAIN", candidate["rationale"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }.getOrNull()
        }.orEmpty()
        val evidence = result?.get("evidence")?.let { runCatching { json.decodeFromJsonElement<List<SearchHit>>(it) }.getOrDefault(emptyList()) }.orEmpty()
        mutableState.update { it.copy(explanation = explanation.ifBlank { "分析已完成，服务端没有返回说明。" }, candidates = candidates, recommendationEvidence = evidence, destination = Destination.PLAN) }
    }

    fun useCandidate(candidate: Candidate) {
        if (candidate.kind == "RECOVERY") {
            val prefix = candidate.body.completedPrefix.ifEmpty { state.value.recoveryBody?.completedPrefix.orEmpty() }
            val recovered = candidate.body.copy(completedPrefix = prefix, sourcePlanId = candidate.body.sourcePlanId ?: state.value.draft.planId, failureDescription = candidate.body.failureDescription ?: state.value.recoveryDescription)
            localEditVersion++
            mutableState.update { it.copy(recoveryBody = recovered, recoveryFrozenCount = prefix.size, recoverySaved = null, recoveryEvaluation = null) }
            evaluate(true)
        } else {
            changeDraft { it.copy(title = candidate.title, elements = candidate.body.elements, ruleSet = candidate.body.ruleSet, planId = null, planRevision = null, contextInvalidated = false) }
            evaluate()
        }
    }

    fun createRecovery(index: Int, actual: String, description: String) {
        val draft = state.value.draft
        if (index !in draft.elements.indices) return
        val codes = actual.split('+').map(String::trim).filter(String::isNotBlank)
        val performed = draft.elements[index].copy(jumps = codes, type = if (codes.size > 1) "COMBINATION" else "SINGLE")
        val elements = draft.elements.take(index) + performed + draft.elements.drop(index + 1)
        localEditVersion++
        mutableState.update { it.copy(recoveryBody = PlanBody(elements, draft.ruleSet, description, completedPrefix = elements.take(index + 1), sourcePlanId = draft.planId, failureDescription = description, selection = selection()), recoveryFrozenCount = index + 1, recoveryDescription = description, recoverySaved = null, recoveryEvaluation = null) }
        evaluate(true)
    }
    fun defaultRecovery(onChooseScenario: () -> Unit) = operation("正在确定默认失误情景") {
        val client = requireApi()
        try {
            val draft = state.value.draft
            val scenario = client.post<FailureScenario>("/api/v1/scoring/default-failure", buildJsonObject {
                put("elements", json.encodeToJsonElement(draft.elements)); put("ruleSet", draft.ruleSet ?: JsonNull); put("selection", json.encodeToJsonElement(selection()))
            })
            localEditVersion++
            val recovery = PlanBody(elements = scenario.completedPrefix + scenario.unchangedSuffix, ruleSet = draft.ruleSet, completedPrefix = scenario.completedPrefix, sourcePlanId = draft.planId, failureDescription = scenario.description, selection = selection())
            mutableState.update { it.copy(recoveryBody = recovery, recoveryFrozenCount = scenario.completedPrefix.size, recoveryDescription = scenario.description, recoverySaved = null, recoveryEvaluation = null) }
            evaluate(true)
        } catch (e: ApiException) {
            if (e.status != 404) throw e
            mutableState.update { it.copy(notice = "当前还不能确定最高 BV 连跳，请直接选择关心的失误情景。") }
            onChooseScenario()
        }
    }
    fun updateRecoveryElement(id: String, jumps: String, time: String) {
        val body = state.value.recoveryBody ?: return
        if (body.elements.indexOfFirst { it.id == id } < state.value.recoveryFrozenCount) return
        val codes = jumps.split('+').map(String::trim).filter(String::isNotBlank)
        localEditVersion++
        mutableState.update { it.copy(recoveryBody = body.copy(elements = body.elements.map { row -> if (row.id == id) row.copy(jumps = codes, type = if (codes.size > 1) { if (row.type == "SEQUENCE") "SEQUENCE" else "COMBINATION" } else "SINGLE", plannedStartSeconds = time.toDoubleOrNull()) else row }), recoveryEvaluation = null) }
    }
    fun addRecoveryElement() = editRecovery { it + PlanElement(UUID.randomUUID().toString()) }
    fun removeRecoveryElement(index: Int) {
        if (index < state.value.recoveryFrozenCount) return
        editRecovery { it.filterIndexed { position, _ -> position != index } }
    }
    fun moveRecoveryElement(from: Int, to: Int) {
        val body = state.value.recoveryBody ?: return
        val frozen = state.value.recoveryFrozenCount
        if (from < frozen || to < frozen || from !in body.elements.indices || to !in body.elements.indices) return
        editRecovery { elements -> elements.toMutableList().apply { add(to, removeAt(from)) } }
    }
    private fun editRecovery(transform: (List<PlanElement>) -> List<PlanElement>) {
        val body = state.value.recoveryBody ?: return
        val changed = transform(body.elements)
        if (changed.take(body.completedPrefix.size) != body.completedPrefix) return
        localEditVersion++
        mutableState.update { it.copy(recoveryBody = body.copy(elements = changed), recoveryEvaluation = null) }
    }

    fun addMemory(statement: String) = operation("正在记录") {
        val client = requireApi()
        client.post<JsonElement>("/api/v1/memory", buildJsonObject { put("statement", statement); put("scopeType", "global"); put("requestId", UUID.randomUUID().toString()) })
        refreshAll(client); mutableState.update { it.copy(notice = "已记住你的描述，可随时纠正") }
    }
    fun correctMemory(item: MemoryItem, statement: String) = operation("正在更新记忆") {
        val client = requireApi()
        client.patch<JsonElement>("/api/v1/memory/${item.id}", buildJsonObject { put("statement", statement); put("expectedRevision", item.revision) })
        refreshAll(client); mutableState.update { it.copy(notice = "记忆已更新") }
    }
    fun forgetMemory(item: MemoryItem) = operation("正在停止使用这条记忆") {
        val client = requireApi(); client.delete("/api/v1/memory/${item.id}?expectedRevision=${item.revision}")
        refreshAll(client); mutableState.update { it.copy(notice = "已停止使用这条记忆", memorySource = null) }
    }
    fun readMemorySource(id: String) = operation("正在读取记忆来源") { val source = requireApi().get<MemorySource>("/api/v1/memory/$id/source"); mutableState.update { it.copy(memorySource = source) } }
    fun addKnowledge(title: String, text: String) = operation("正在保存并理解资料") {
        val client = requireApi()
        client.post<JsonElement>("/api/v1/knowledge/text", buildJsonObject { put("title", title.ifBlank { "我的资料" }); put("text", text); put("competitionKey", state.value.draft.competition); put("requestId", UUID.randomUUID().toString()) })
        refreshAll(client); mutableState.update { it.copy(notice = "资料已提交，可刷新查看处理进度") }
    }
    fun uploadKnowledge(uri: Uri) = operation("正在上传资料") {
        val resolver = getApplication<Application>().contentResolver
        val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "资料"
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val body = object : RequestBody() {
            override fun contentType() = mimeType.toMediaTypeOrNull()
            override fun writeTo(sink: BufferedSink) { resolver.openInputStream(uri)?.use { input -> sink.writeAll(input.source()) } ?: error("无法读取所选资料，请重新选择。") }
        }
        val client = requireApi()
        client.upload(fileName, fileName, mimeType, body, state.value.draft.competition, UUID.randomUUID().toString())
        refreshAll(client); mutableState.update { it.copy(notice = "资料已上传，可刷新查看进度") }
    }
    fun readKnowledge(id: String) = operation("正在读取来源") { val detail = requireApi().get<KnowledgeDetail>("/api/v1/knowledge/$id"); mutableState.update { it.copy(knowledgeDetail = detail) } }
    fun deleteKnowledge(id: String) = operation("正在撤回资料") { val client = requireApi(); client.delete("/api/v1/knowledge/$id"); refreshAll(client); mutableState.update { it.copy(notice = "资料已撤回") } }
    fun searchKnowledge(query: String) = operation("正在查找资料") { val result = requireApi().get<SearchResult>("/api/v1/knowledge/search?q=${Uri.encode(query)}&competitionKey=${Uri.encode(state.value.draft.competition)}"); mutableState.update { it.copy(searchResult = result) } }

    private fun requireApi(): CoachApi = api ?: error("请先连接服务；当前输入已保存在本机草稿中。")
    private fun operation(label: String, block: suspend () -> Unit) {
        if (state.value.busy != null) return
        mutableState.update { it.copy(busy = label, error = null) }
        viewModelScope.launch {
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e; mutableState.update { it.copy(error = e.message ?: "操作未完成，请重试。") } }
            finally { mutableState.update { it.copy(busy = null) } }
        }
    }
}
