package com.skatingcoach.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.skatingcoach.android.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ExpertScreen(client: CoachApi, competition: String, back: () -> Unit) {
    val jobs=rememberCoroutineScope()
    val links=LocalUriHandler.current
    var subject by remember { mutableStateOf(competition.ifBlank { ISU_SCOPE_LABEL }) }
    var question by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf<ExpertResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick=back) { Text("返回资料") }; Text("问滑冰教练",style=MaterialTheme.typography.headlineMedium) }
        item { OutlinedTextField(subject,{ subject=it; reply=null },Modifier.fillMaxWidth(),enabled=!busy,label={ Text("规则适用的比赛组别") })
            if(subject!=ISU_SCOPE_LABEL) TextButton(onClick={ subject=ISU_SCOPE_LABEL; reply=null },enabled=!busy) { Text("选用"+ISU_SCOPE_LABEL) } }
        item { OutlinedTextField(question,{ question=it.take(1200); reply=null },Modifier.fillMaxWidth(),enabled=!busy,
            label={ Text("你的问题") },placeholder={ Text("例如，3T< 的基础分是多少？") },minLines=2) }
        item { Button(onClick={
            busy=true; error=null; reply=null
            jobs.launch {
                try { reply=client.get("/api/v1/knowledge/expert?q=${Uri.encode(question)}&competitionKey=${Uri.encode(subject)}") }
                catch(e: CancellationException) { throw e }
                catch(e: Exception) { error=e.message }
                finally { busy=false }
            }
        },enabled=question.isNotBlank() && !busy) { Text(if(busy) "正在查证" else "查证并回答") } }
        error?.let { item { Text(it,color=MaterialTheme.colorScheme.error) } }
        reply?.let { result ->
            item { Text(when(result.status) {
                "matched" -> "教练回答"
                "conflict" -> "同一问题的来源给出了不同结论，请对照下列原文。"
                "non_current" -> "这份规则已超出适用期，需要核对当前版本。"
                else -> if(result.reason=="unsupported_scope") "这个赛季、组别或节目暂未收录。" else "已核资料不足以回答这个问题。"
            },style=MaterialTheme.typography.titleLarge) }
            items(result.answers,key={ "answer-"+it.evidenceId }) { answer -> Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(answer.text); Text(answer.conditions,style=MaterialTheme.typography.bodySmall)
            } }
            items(result.evidence,key={ "citation-"+it.id }) { hit -> TextButton(onClick={
                hit.sourceUrl?.takeIf { it.startsWith("https://") }?.let(links::openUri)
            }) { Text("${hit.title} · ${hit.locator}") } }
            item { Text("资料版本 ${result.revision}", style=MaterialTheme.typography.labelSmall) }
        }
    }
}
