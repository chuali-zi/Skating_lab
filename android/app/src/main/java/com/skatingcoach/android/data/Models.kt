package com.skatingcoach.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class DevSession(val token: String, val actorId: String, val athleteId: String, val displayName: String = "选手", val expiresAt: String = "", val devOnly: Boolean = true)
@Serializable
data class ActorIdentity(val actorId: String, val athleteId: String, val displayName: String = "选手")
@Serializable
data class Conversation(val id: String, val revision: Long = 0)
@Serializable
data class JumpOption(val code: String, val label: String, val revolutions: Int)
@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
data class PlanElement(val id: String, @kotlinx.serialization.EncodeDefault val type: String = "SINGLE", val jumps: List<String> = emptyList(), val plannedStartSeconds: Double? = null)
@Serializable
data class PlanBody(val elements: List<PlanElement> = emptyList(), val ruleSet: JsonElement? = null, val notes: String = "", val completedPrefix: List<PlanElement> = emptyList(), val sourcePlanId: String? = null, val failureDescription: String? = null, val selection: Selection? = null, val sourcePlanRevision: Long? = null)
@Serializable
data class PlanView(val id: String, val title: String, val kind: String = "MAIN", val revision: Long, val body: PlanBody, val createdAt: String = "", val updatedAt: String = "", val contextInvalidated: Boolean = false)
@Serializable
data class Selection(val selectedSingles: List<String>, val selectedCombinations: List<String>, val selectedSequences: List<String> = emptyList(), val globallyExcludedJumps: List<String> = emptyList())
@Serializable
data class ElementScore(val elementId: String, val position: Int = 0, val elementKey: String = "", val baseValue: Double? = null, val bonusApplied: Boolean? = null, val bonusMultiplier: Double? = null, val evaluatedValue: Double? = null)
@Serializable
data class RuleCheck(val code: String, val status: String, val elementId: String? = null, val message: String, val ruleReference: String? = null)
@Serializable
data class Evaluation(val ruleSetId: String? = null, val ruleVersion: String? = null, val baseTotal: Double? = null, val knownSubtotal: Double = 0.0, val evaluatedTotal: Double? = null, val scoreComplete: Boolean = false, val checksComplete: Boolean = false, val hasViolations: Boolean = false, val elements: List<ElementScore> = emptyList(), val checks: List<RuleCheck> = emptyList())
@Serializable
data class RecoveryEvaluation(val prefixPreserved: Boolean, val frozenElementCount: Int, val evaluation: Evaluation)
@Serializable
data class SavedRecovery(val plan: PlanView, val evaluation: Evaluation)
@Serializable
data class FailureScenario(val elementId: String, val position: Int, val plannedElementKey: String, val completedPrefix: List<PlanElement>, val unchangedSuffix: List<PlanElement>, val description: String)
@Serializable
data class RecommendationRun(val id: String, val status: String, val result: JsonElement? = null, val errorCode: String? = null)
@Serializable
data class MemoryItem(val id: String, val kind: String = "", val subjectKey: String = "", val statement: String, val scopeType: String = "global", val scopeId: String? = null, val evidenceKind: String = "user_report", val status: String = "active", val revision: Long = 1, val sourceId: String? = null, val observedAt: String? = null, val validUntil: String? = null)
@Serializable
data class MemoryEnvelope(val revision: Long = 0, val privacyEpoch: Long = 0, val items: List<MemoryItem> = emptyList())
@Serializable
data class MemorySource(val id: String, val content: String, val scopeId: String? = null, val status: String = "", val createdAt: String = "")
@Serializable
data class KnowledgeItem(val id: String, val title: String, val sourceKind: String = "", val status: String = "", val revision: Long = 1, val sourceUrl: String? = null, val createdAt: String = "")
@Serializable
data class KnowledgeEnvelope(val items: List<KnowledgeItem> = emptyList())
@Serializable
data class ProfessionalSource(val id: String, val title: String, val publisher: String = "", val url: String, val category: String = "", val season: String? = null, val accessNote: String = "", val summary: String = "", val verifiedOn: String = "", val readyForScoring: Boolean = false)
@Serializable
data class ProfessionalCatalog(val items: List<ProfessionalSource> = emptyList())
@Serializable
data class KnowledgeBlock(val id: String, val text: String, val locator: String = "")
@Serializable
data class KnowledgeDetail(val source: KnowledgeItem, val blocks: List<KnowledgeBlock> = emptyList()) {
    val title get() = source.title
    val status get() = source.status
}
@Serializable
data class SearchHit(val id: String, val sourceId: String, val title: String, val text: String, val locator: String = "", val sourceUrl: String? = null, val sourceVersion: Long = 1, val sourceKind: String = "")
@Serializable
data class SearchResult(val mode: String = "", val items: List<SearchHit> = emptyList(), val warnings: List<String> = emptyList())
@Serializable
data class LocalDraft(val selectedSingles: Set<String> = emptySet(), val excludedDefaults: Set<String> = emptySet(), val selectedCombinations: Set<String> = emptySet(), val excludedCombinationDefaults: Set<String> = emptySet(), val situation: String = "", val competition: String = "", val title: String = "我的编排", val elements: List<PlanElement> = emptyList(), val planId: String? = null, val planRevision: Long? = null, val ruleSet: JsonElement? = null, val contextInvalidated: Boolean = false)
