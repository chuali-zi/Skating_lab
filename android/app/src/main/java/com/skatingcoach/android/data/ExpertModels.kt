package com.skatingcoach.android.data

import kotlinx.serialization.Serializable

const val ISU_SCOPE_LABEL = "2026/27 ISU 成年女子单人自由滑"
@Serializable data class ExpertAnswer(val key: String, val evidenceId: String, val text: String, val conditions: String)
@Serializable data class ExpertResult(val status: String, val reason: String? = null, val method: String, val revision: String,
    val answers: List<ExpertAnswer> = emptyList(), val evidence: List<SearchHit> = emptyList())
@Serializable data class VideoIndex(val submission_enabled: Boolean, val model: String, val jobs: List<VideoJob>)
@Serializable data class VideoJob(val id: String, val status: String, val created_at: String = "", val error: String? = null,
    val result: ClipResult? = null, val reviews: List<VideoReview> = emptyList())
@Serializable data class ClipResult(val model: String, val duration_seconds: Double, val frame_timestamps_seconds: List<Double>,
    val max_sampling_gap_seconds: Double, val model_output: FrameReport, val quality_flags: List<String> = emptyList())
@Serializable data class FrameReport(val observations: List<FrameObservation>, val pending_review: List<String>)
@Serializable data class FrameObservation(val frame_indices: List<Int>, val visible_evidence: String, val confidence: String)
@Serializable data class VideoReview(val id: String, val observation_index: Int, val decision: String, val capability_note: String? = null, val revision: Long)
@Serializable data class ReviewRef(val id: String, val revision: Long)
