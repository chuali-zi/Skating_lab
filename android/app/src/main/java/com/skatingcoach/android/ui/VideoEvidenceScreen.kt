@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.skatingcoach.android.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.skatingcoach.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun VideoEvidenceScreen(client: CoachApi, source: KnowledgeItem?, initial: String?, back: () -> Unit, attach: (VideoReview) -> Unit) {
    val resolver=LocalContext.current.contentResolver
    val tasks=rememberCoroutineScope()
    var pendingSource by remember { mutableStateOf(source) }
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf(source?.title.orEmpty()) }
    var consent by remember { mutableStateOf(false) }
    var index by remember { mutableStateOf<VideoIndex?>(null) }
    var selected by remember { mutableStateOf(initial) }
    var refresh by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { chosen ->
        uri=chosen; pendingSource=null; consent=false
        fileName=chosen?.let { resolver.query(it,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c->if(c.moveToFirst()) c.getString(0) else null } } ?: ""
    }
    LaunchedEffect(refresh) {
        try { index=client.get(CoachApi.VIDEO_PATH) } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        TextButton(onClick=back) { Text("返回资料") }
        Text("视频观察与复核",style=MaterialTheme.typography.headlineMedium)
        Text("选择 1–10 秒、10 MiB 以内的 H.264 MP4。长边最多 1920 像素，总像素最多 1920 × 1080。")
        OutlinedButton(onClick={ consent=false; pick.launch(arrayOf("video/mp4")) },enabled=!sending) { Text("选择视频片段") }
        if(pendingSource!=null || uri!=null) {
            Text(fileName.ifBlank { "已选择片段" },style=MaterialTheme.typography.titleMedium)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(consent,onCheckedChange={consent=it},enabled=!sending)
                Text("同意将这段视频的采样帧发送给 OpenAI。仅发送图片，原声不参与分析。",Modifier.weight(1f))
            }
            Button(onClick={
                sending=true; consent=false; error=null
                tasks.launch {
                    try {
                        val job=client.submitVideo(resolver,uri,pendingSource?.id)
                        selected=job.id; uri=null; pendingSource=null; fileName=""; refresh++
                    } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message }
                    finally { sending=false }
                }
            },enabled=index?.submission_enabled==true && consent && !sending) { Text(if(sending) "正在提交" else "提交采样观察") }
        }
        if(index?.submission_enabled==false) Text("服务尚未启用视频观察，历史结果仍可查看。")
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        selected?.let { id -> key(id) { VideoDetail(client,id,onChange={ refresh++ },onDelete={ selected=null; refresh++ },attach=attach) } }
        HorizontalDivider()
        Row(verticalAlignment=Alignment.CenterVertically) { Text("历史片段",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            TextButton(onClick={refresh++}) { Text("刷新") } }
        index?.jobs?.forEach { row ->
            TextButton(onClick={ selected=row.id }) { Text("${row.created_at.take(19).replace('T',' ')} · ${videoStatus(row.status)}") }
        }
    }
}

@Composable
private fun VideoDetail(client: CoachApi, id: String, onChange: () -> Unit, onDelete: () -> Unit, attach: (VideoReview) -> Unit) {
    var job by remember { mutableStateOf<VideoJob?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tasks=rememberCoroutineScope()
    LaunchedEffect(attempt) {
        try {
            error=null
            do {
                val current=client.get<VideoJob>("${CoachApi.VIDEO_PATH}/$id")
                job=current
                if(current.status !in setOf("queued","running")) { onChange(); break }
                delay(1600)
            } while(true)
        } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message; job=null }
    }
    HorizontalDivider()
    Text(job?.let { videoStatus(it.status) } ?: "正在读取片段",style=MaterialTheme.typography.titleLarge)
    job?.error?.let { Text(it) }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    job?.result?.let { clip ->
        Text("按采样帧理解 · ${clip.duration_seconds} 秒 · ${clip.frame_timestamps_seconds.size} 帧 · 最大帧间隔 ${clip.max_sampling_gap_seconds} 秒")
        var frame by remember { mutableStateOf<Int?>(null) }
        FlowRow { clip.frame_timestamps_seconds.forEachIndexed { i,t ->
            TextButton(onClick={frame=i}) { Text("第 ${i+1} 帧 · $t 秒") }
        } }
        frame?.let { n -> key(n) {
            var pic by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(n) {
                try { val bytes=client.videoFrame(id,n); pic=BitmapFactory.decodeByteArray(bytes,0,bytes.size)?.asImageBitmap() }
                catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message }
            }
            pic?.let { Image(it,"第 ${n+1} 帧",Modifier.fillMaxWidth().heightIn(max=280.dp)) }
        } }
        if("SUBJECT_REVIEW_REQUIRED" in clip.quality_flags) Text("请先核对观察对象。模型同时报告了人物动作和“未见滑冰者”，请查看引用帧并确认主体。",color=MaterialTheme.colorScheme.error)
        clip.model_output.pending_review.forEach { Text("待核 · $it") }
        if(clip.model_output.observations.isEmpty()) Text("这段视频没有可供采纳的观察。")
        clip.model_output.observations.forEachIndexed { i,obs ->
            val saved=job?.reviews?.firstOrNull { it.observation_index==i }
            key(i,saved?.revision) { ReviewEditor(client,id,i,obs,saved,onSaved={ job=null; attempt++ },attach=attach) }
        }
    }
    Row {
        TextButton(onClick={ attempt++ }) { Text("重新读取") }
        if(job!=null) TextButton(onClick={confirmDelete=true}) { Text("删除片段") }
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除片段和复核记录？")},
        text={Text("采样帧与观察将移除，引用该备注的预案需要重新核对。")},
        confirmButton={TextButton(onClick={confirmDelete=false; tasks.launch {
            try { client.delete("${CoachApi.VIDEO_PATH}/$id"); onDelete() } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message }
        }}) {Text("删除")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("取消")}})
}

@Composable
private fun ReviewEditor(client: CoachApi,id: String,i: Int,obs: FrameObservation,saved: VideoReview?,onSaved: ()->Unit,attach: (VideoReview)->Unit) {
    var note by remember { mutableStateOf(saved?.capability_note ?: obs.visible_evidence) }
    var writing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val tasks=rememberCoroutineScope()
    OutlinedCard { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("观察 ${i+1} · ${obs.visible_evidence}",style=MaterialTheme.typography.titleMedium)
        val confidence=when(obs.confidence){ "high"->"高"; "medium"->"中"; else->"低" }
        Text("依据第 ${obs.frame_indices.joinToString("、") {(it+1).toString()}} 帧 · 可见观察置信度$confidence")
        OutlinedTextField(note,{note=it.take(400)},Modifier.fillMaxWidth(),enabled=!writing,label={Text("我核对后的片段备注")})
        FlowRow {
            listOf("accept" to "确认采纳","correct" to "确认纠正","ignore" to "确认忽略").forEach { (decision,label)->
                TextButton(enabled=!writing && (decision=="ignore" || note.isNotBlank()),onClick={
                    writing=true
                    tasks.launch {
                        try { client.reviewVideo(id,i,decision,note.trim().takeUnless {decision=="ignore"}); onSaved() }
                        catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message }
                        finally { writing=false }
                    }
                }) { Text(label) }
            }
        }
        saved?.let { previous ->
            Text("已保存第 ${previous.revision} 版 · "+when(previous.decision){"correct"->"已纠正";"accept"->"已采纳";else->"已忽略"})
            if(previous.capability_note!=null) OutlinedButton(onClick={attach(previous)},enabled=!writing && note.trim()==previous.capability_note) { Text("用于主方案的失误预案") }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } }
}
private fun videoStatus(value: String)=when(value){"queued"->"等待处理";"running"->"正在分析";"completed"->"已生成结果";"failed"->"处理失败";else->value}
