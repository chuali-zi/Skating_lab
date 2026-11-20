@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.skatingcoach.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skatingcoach.android.BuildConfig
import com.skatingcoach.android.data.*
import java.util.Locale

@Composable
fun CoachApp(vm: CoachViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showConnection by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = state.destination != Destination.PREPARE) { vm.destination(Destination.PREPARE) }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it); vm.clearNotice() } }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(title = { Text("SKATING LAB", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }, actions = {
                TextButton(onClick = { showConnection = true }) {
                    Icon(if (state.connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (state.connected) "已连接" else "连接服务")
                }
            })
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(selected = state.destination == destination, onClick = { vm.destination(destination) }, label = { Text(destination.label) }, icon = {
                        Icon(when (destination) {
                            Destination.PREPARE -> Icons.Outlined.Tune
                            Destination.PLAN -> Icons.Outlined.FormatListNumbered
                            Destination.KNOWLEDGE -> Icons.Outlined.Description
                            Destination.MEMORY -> Icons.Outlined.PersonOutline
                        }, destination.label)
                    })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy != null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.busy.orEmpty(), Modifier.padding(horizontal = 20.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = vm::clearError) { Icon(Icons.Outlined.Close, "关闭提示") }
                    }
                }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    when (state.destination) {
                        Destination.PREPARE -> PrepareScreen(state, vm, onConnect = { showConnection = true })
                        Destination.PLAN -> PlanScreen(state, vm)
                        Destination.KNOWLEDGE -> KnowledgeScreen(state, vm)
                        Destination.MEMORY -> MemoryScreen(state, vm)
                    }
                }
            }
        }
    }
    if (showConnection) ConnectionDialog(state, { showConnection = false }) { url, token -> vm.connect(url, token); showConnection = false }
    state.memorySource?.let { source ->
        ModalBottomSheet(onDismissRequest = vm::closeMemorySource) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("这条记忆的来源", style = MaterialTheme.typography.headlineSmall)
                Text(source.createdAt.take(19).replace('T', ' '), style = MaterialTheme.typography.labelMedium)
                Text(source.content, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    state.knowledgeDetail?.let { detail ->
        ModalBottomSheet(onDismissRequest = vm::closeKnowledgeDetail) {
            LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text(detail.title, style = MaterialTheme.typography.headlineSmall) }
                item { Text(statusLabel(detail.status), style = MaterialTheme.typography.labelLarge) }
                items(detail.blocks, key = { it.id }) { block ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(block.locator, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(block.text, style = MaterialTheme.typography.bodyLarge) }
                }
                if (detail.blocks.isEmpty()) item { Text(when (detail.status.lowercase()) {
                    "awaiting_visual" -> "文件已保存。视频可在资料页选择「复核视频」并授权采样；其他影像或音频可补充文字记录。"
                    "failed" -> "这份资料暂时未能完成处理，可稍后刷新查看进度。"
                    else -> "资料仍在处理中，稍后刷新可查看原文内容。"
                }) }
            }
        }
    }
}

@Composable
private fun PrepareScreen(state: CoachUiState, vm: CoachViewModel, onConnect: () -> Unit) {
    var comboQuery by rememberSaveable { mutableStateOf("") }
    var expandedGroup by rememberSaveable { mutableIntStateOf(2) }
    val suggestions = listOf("3S+3T", "3S+1Eu+3S", "2A+1Eu+3S", "3S+3Lo", "3S+2T").filter { comboQuery.isNotBlank() && it.contains(comboQuery, ignoreCase = true) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { ScreenHeading("01 / PREPARE", "准备这场比赛", "从你的动作、状态与目标出发。选择表示可以用，不代表必须安排。") }
        if (!state.connected) item {
            OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("连接后读取动作目录和已有资料", style = MaterialTheme.typography.titleMedium); Text("你可以先描述想法，本机草稿会保留。", style = MaterialTheme.typography.bodyMedium); OutlinedButton(onClick = onConnect) { Text("连接服务") } } }
        }
        item {
            SectionTitle("可用单跳", "${state.draft.selectedSingles.size} 项已选")
            state.catalog.groupBy { it.revolutions }.toSortedMap().forEach { (revolutions, jumps) ->
                val allSelected = jumps.all { it.code in state.draft.selectedSingles }
                ListItem(headlineContent = { Text("${revolutions}周跳") }, supportingContent = { Text("${jumps.count { it.code in state.draft.selectedSingles }} / ${jumps.size} 已选") }, leadingContent = { Checkbox(allSelected, onCheckedChange = { vm.toggleGroup(revolutions) }) }, trailingContent = { IconButton(onClick = { expandedGroup = if (expandedGroup == revolutions) -1 else revolutions }) { Icon(if (expandedGroup == revolutions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, "展开或收起${revolutions}周跳") } })
                if (expandedGroup == revolutions) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    jumps.forEach { jump -> FilterChip(selected = jump.code in state.draft.selectedSingles, onClick = { vm.toggleSingle(jump.code) }, label = { Text("${jump.code} · ${jump.label}") }) }
                }
            }
            if (state.draft.selectedSingles.isNotEmpty()) Text(state.draft.selectedSingles.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionTitle("可用连跳", "与单跳独立选择")
            OutlinedTextField(comboQuery, { comboQuery = it }, Modifier.fillMaxWidth(), label = { Text("搜索或直接写一个连跳") }, placeholder = { Text("例如 3S+3T") }, singleLine = true, trailingIcon = { IconButton(onClick = { if (comboQuery.isNotBlank()) { vm.toggleCombination(comboQuery.trim()); comboQuery = "" } }) { Icon(Icons.Outlined.Add, "加入连跳") } })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { suggestions.forEach { combo -> SuggestionChip(onClick = { vm.toggleCombination(combo); comboQuery = "" }, label = { Text(combo) }) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.draft.selectedCombinations.forEach { combo -> InputChip(selected = true, onClick = { vm.toggleCombination(combo) }, label = { Text(combo) }, trailingIcon = { Icon(Icons.Outlined.Close, "移除$combo", Modifier.size(16.dp)) }) } }
        }
        item { OutlinedTextField(state.draft.situation, vm::updateSituation, Modifier.fillMaxWidth(), minLines = 4, label = { Text("说说你的状态与想法") }, placeholder = { Text("例如：3S 比较稳，后半程容易累，这场先求稳。") }, supportingText = { Text("可以说偏好、训练经历、临时状态，不需要填写成功率。") }) }
        item { OutlinedTextField(state.draft.competition, vm::updateCompetition, Modifier.fillMaxWidth(), label = { Text("比赛或训练情境") }, placeholder = { Text("比赛名称、组别，或直接描述这次练习") }, supportingText = { Text("规则文件和截图可在「资料」中上传。") }) }
        item { FilterChip(selected = state.draft.competition == ISU_SCOPE_LABEL, onClick = { vm.updateCompetition(if (state.draft.competition == ISU_SCOPE_LABEL) "" else ISU_SCOPE_LABEL) }, label = { Text(ISU_SCOPE_LABEL) }) }
        item { Button(onClick = { vm.recommend() }, enabled = state.busy == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("结合我的情况生成编排") } }
        if (state.runId != null) item { OutlinedButton(onClick = vm::resumeRun, enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) { Text("查看最近一次分析") } }
        item { TextButton(onClick = vm::newPlan) { Text("从空白方案开始编写") } }
        if (state.plans.isNotEmpty()) {
            item { SectionTitle("已保存方案", "继续上次编排") }
            items(state.plans, key = { it.id }) { plan -> OutlinedCard(onClick = { vm.openPlan(plan) }) { ListItem(headlineContent = { Text(plan.title) }, supportingContent = { Text(if (plan.kind == "RECOVERY") "失误预案 · 版本 ${plan.revision}" else "主编排 · ${plan.body.elements.size} 组动作") }, trailingContent = { Icon(Icons.Outlined.ChevronRight, "打开方案") }) } }
        }
    }
}

@Composable
private fun PlanScreen(state: CoachUiState, vm: CoachViewModel) {
    val recovery = state.recoveryMode
    val focusManager = LocalFocusManager.current
    var showScenario by rememberSaveable { mutableStateOf(false) }
    val rows = if (recovery) state.recoveryBody?.elements.orEmpty() else state.draft.elements
    val evaluation = if (recovery) state.recoveryEvaluation else state.evaluation
    val editVersion = if (recovery) state.recoveryBody else state.draft.elements
    LaunchedEffect(editVersion, state.connected) { if (state.connected && rows.isNotEmpty()) { kotlinx.coroutines.delay(650); vm.evaluate(recovery) } }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("02 / PROGRAM", "主编排与失误预案", "每一行都可以修改。计划时间用于判断后半程，分值由服务端核算。") }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = !recovery, onClick = { vm.showRecovery(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("主编排") }
                SegmentedButton(selected = recovery, onClick = { vm.showRecovery(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("失误预案") }
            }
        }
        if (!recovery) item { OutlinedTextField(state.draft.title, vm::updateTitle, Modifier.fillMaxWidth(), label = { Text("方案名称") }, singleLine = true) }
        val savedRecovery = state.recoverySaved
        val source = state.plans.firstOrNull { it.id == savedRecovery?.body?.sourcePlanId }
        if (recovery && savedRecovery != null && source != null && savedRecovery.body.sourcePlanRevision != source.revision) {
            item { Text("这份预案基于旧版或未记录版本的主编排。请核对失误位置与时间，必要时建立新预案。", color = MaterialTheme.colorScheme.error) }
        }
        if ((!recovery && state.draft.contextInvalidated) || (recovery && state.recoverySaved?.contextInvalidated == true)) item { Column { Text("这份方案引用的部分资料已更新或撤回，请重新核对后使用。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium); if (!recovery) TextButton(onClick = vm::reloadPlan, enabled = state.busy == null) { Text("重新打开已清理的最新方案") } } }
        item { ScoreSummary(evaluation, state.evaluating) }
        if (state.explanation.isNotBlank()) item { OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("建议依据", style = MaterialTheme.typography.titleMedium); Text(state.explanation) } } }
        items(state.candidates) { candidate -> OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(candidate.title, style = MaterialTheme.typography.titleMedium)
            Text(candidate.body.elements.joinToString(" → ") { it.jumps.joinToString("+") })
            if (candidate.rationale.isNotBlank()) Text(candidate.rationale, style = MaterialTheme.typography.bodyMedium)
            state.recommendationEvidence.filter { it.id in candidate.evidenceIds }.forEach { EvidenceButton(it,vm) }
            OutlinedButton(onClick = { vm.useCandidate(candidate) }) { Text(if (candidate.kind == "RECOVERY") "使用这份预案" else "使用这份方案") }
        } } }
        if(state.candidates.all { it.evidenceIds.isEmpty() } && state.recommendationEvidence.isNotEmpty()) {
            item { SectionTitle("本次查阅的资料", "可打开原文核对") }
            items(state.recommendationEvidence,key={it.id}) { EvidenceButton(it,vm) }
        }
        if (state.runId != null) item { TextButton(onClick = vm::resumeRun, enabled = state.busy == null) { Text("查看最近一次分析") } }
        if (recovery && state.recoveryBody == null) item { EmptyPanel("先定义实际发生了什么", "选择失误位置和实际完成动作，已发生的部分会固定保留。") }
        if (recovery && state.recoveryBody != null) item { Text(state.recoveryDescription, style = MaterialTheme.typography.bodyLarge); Text("前 ${state.recoveryFrozenCount} 组为已完成内容，仅通过修改情景变更。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        if (rows.isEmpty() && !recovery) item { EmptyPanel("从第一组跳跃开始", "可以手动写方案，也可以返回「准备」生成建议。") }
        itemsIndexed(rows, key = { _, element -> element.id }) { index, element ->
            val frozen = recovery && index < state.recoveryFrozenCount
            ElementCard(element, index, evaluation?.elements?.firstOrNull { it.elementId == element.id }, frozen,
                onChange = { jumps, time -> if (recovery) vm.updateRecoveryElement(element.id, jumps, time) else vm.updateElement(element.id, jumps, time) },
                onDelete = if (frozen) null else { { if (recovery) vm.removeRecoveryElement(index) else vm.removeElement(element.id) } },
                onMoveUp = if ((!recovery && index > 0) || (recovery && index > state.recoveryFrozenCount)) { { if (recovery) vm.moveRecoveryElement(index, index - 1) else vm.moveElement(index, index - 1) } } else null,
                onMoveDown = if (!frozen && index < rows.lastIndex) { { if (recovery) vm.moveRecoveryElement(index, index + 1) else vm.moveElement(index, index + 1) } } else null,
            )
            evaluation?.checks?.filter { it.elementId == element.id && it.status != "SATISFIED" }?.forEach { check -> CheckLabel(check) }
        }
        if (!recovery) item { OutlinedButton(onClick = vm::addElement, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加跳跃行") } }
        if (recovery && state.recoveryBody != null) item { OutlinedButton(onClick = vm::addRecoveryElement, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("追加后续动作") } }
        evaluation?.checks?.filter { it.elementId == null && it.status != "SATISFIED" }?.let { checks -> items(checks) { CheckLabel(it) } }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!recovery) TextButton(onClick = { focusManager.clearFocus(); vm.undo() }) { Icon(Icons.AutoMirrored.Outlined.Undo, null); Spacer(Modifier.width(4.dp)); Text("撤销修改") }
                OutlinedButton(onClick = { if (recovery || !state.connected) { showScenario = true } else { vm.showRecovery(true); vm.defaultRecovery { showScenario = true } } }, enabled = state.draft.elements.isNotEmpty() && state.busy == null) { Text(if (recovery) "修改失误情景" else "准备失误预案") }
                if (recovery && state.recoveryBody != null) OutlinedButton(onClick = { vm.recommend(true) }, enabled = state.busy == null) { Text("分析后续安排") }
            }
        }
        item { Button(onClick = { vm.savePlan(recovery) }, enabled = state.busy == null && rows.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (recovery) "保存失误预案" else "保存主编排") } }
    }
    if (showScenario || state.pendingVideoReview != null) ScenarioDialog(state.draft.elements, state.pendingVideoReview?.capability_note, { showScenario = false; vm.clearPendingReview() }) { index, actual, description -> vm.createRecovery(index, actual, description); showScenario = false }
}

@Composable
private fun EvidenceButton(evidence: SearchHit,vm: CoachViewModel) {
    val links=LocalUriHandler.current
    TextButton(onClick = {
        when {
            evidence.sourceKind in setOf("official_rule","coaching_research") && evidence.sourceUrl?.startsWith("https://") == true -> links.openUri(evidence.sourceUrl)
            evidence.sourceKind == "reviewed_video" -> vm.openVideo(evidence.sourceId)
            evidence.sourceKind.startsWith("personal_") -> vm.readMemorySource(evidence.id)
            else -> vm.readKnowledge(evidence.sourceId)
        }
    }) { Icon(Icons.Outlined.Description,null,Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("${evidence.title} · ${evidence.locator}") }
}

private fun planTimeText(seconds: Double?): String =
    seconds?.takeIf { it.isFinite() }?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

@Composable
private fun ElementCard(element: PlanElement, index: Int, score: ElementScore?, frozen: Boolean, onChange: (String, String) -> Unit, onDelete: (() -> Unit)?, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?) {
    var jumps by remember(element.id, element.jumps) { mutableStateOf(element.jumps.joinToString("+")) }
    var time by rememberSaveable(element.id) { mutableStateOf(planTimeText(element.plannedStartSeconds)) }
    var timeFocused by remember { mutableStateOf(false) }
    LaunchedEffect(element.plannedStartSeconds, timeFocused) {
        if (!timeFocused && parsePlanTime(time) != element.plannedStartSeconds) {
            time = planTimeText(element.plannedStartSeconds)
        }
    }
    val dragThreshold = with(LocalDensity.current) { 64.dp.toPx() }
    val latestUp by rememberUpdatedState(onMoveUp)
    val latestDown by rememberUpdatedState(onMoveDown)
    OutlinedCard(border = BorderStroke(1.dp, if (frozen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(Locale.ROOT, "%02d", index + 1), Modifier.width(36.dp), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(if (frozen) "已完成 · 固定" else "跳跃元素", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                Text(score?.evaluatedValue?.let(::formatBv) ?: "待核算", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                if (!frozen && (onMoveUp != null || onMoveDown != null)) {
                    Box(Modifier.size(48.dp).semantics { contentDescription = "长按拖动调整第${index + 1}组顺序，也可使用下方上移下移按钮" }.pointerInput(element.id) {
                        var displacement = 0f
                        detectDragGesturesAfterLongPress(onDragStart = { displacement = 0f }, onDragEnd = { displacement = 0f }, onDragCancel = { displacement = 0f }) { change, amount ->
                            change.consume(); displacement += amount.y
                            if (displacement > dragThreshold) { latestDown?.invoke(); displacement = 0f }
                            if (displacement < -dragThreshold) { latestUp?.invoke(); displacement = 0f }
                        }
                    }, contentAlignment = Alignment.Center) { Icon(Icons.Outlined.DragHandle, null) }
                }
            }
            OutlinedTextField(jumps, { jumps = it; onChange(it, time) }, Modifier.fillMaxWidth(), label = { Text("动作或有序连跳") }, placeholder = { Text("例如 3S+2T") }, readOnly = frozen, singleLine = true)
            val invalidTime = time.isNotBlank() && parsePlanTime(time) == null
            OutlinedTextField(time, { time = it; onChange(jumps, it) }, Modifier.fillMaxWidth().onFocusChanged { timeFocused = it.isFocused }, label = { Text("计划执行时间（秒）") }, supportingText = { Text(if (time.isBlank()) "未填时间时，后半程加成保持待核实。" else if (invalidTime) "请输入有限数字，例如 90.5；当前时间按未填写处理。" else "时间按当前比赛规则核算。") }, isError = invalidTime, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), readOnly = frozen, singleLine = true)
            if (score?.bonusApplied == true) AssistChip(onClick = {}, label = { Text("适用后半程加成 ×${score.bonusMultiplier}") }, leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) })
            if (!frozen) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onMoveUp?.let { TextButton(onClick = it) { Icon(Icons.Outlined.ArrowUpward, "上移第${index + 1}组", Modifier.size(18.dp)); Text("上移") } }
                onMoveDown?.let { TextButton(onClick = it) { Icon(Icons.Outlined.ArrowDownward, "下移第${index + 1}组", Modifier.size(18.dp)); Text("下移") } }
                onDelete?.let { TextButton(onClick = it) { Text("删除", color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}

@Composable
private fun KnowledgeScreen(state: CoachUiState, vm: CoachViewModel) {
    val client=vm.connectedApi
    var tool by remember(client) { mutableStateOf("") }
    var source by remember(client) { mutableStateOf<KnowledgeItem?>(null) }
    if (client!=null && (tool.isNotEmpty() || state.videoJobToOpen!=null)) {
        val back = { tool=""; source=null; vm.closeVideo() }
        BackHandler(onBack=back)
        key(client,tool,state.videoJobToOpen,source?.id) {
            if(tool=="expert") ExpertScreen(client,state.draft.competition,back)
            else VideoEvidenceScreen(client,source,state.videoJobToOpen,back,vm::attachVideo)
        }
        return
    }
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<KnowledgeItem?>(null) }
    var showProfessional by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::uploadKnowledge) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("03 / EVIDENCE", "比赛与训练资料", "规则、截图、教练笔记或训练视频，都可以从这里交给系统处理。") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick={ tool="expert" },enabled=client!=null) { Text("问滑冰教练") }
            OutlinedButton(onClick={ source=null; tool="video" },enabled=client!=null) { Text("视频观察与复核") }
        } }
        item { OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("资料标题（可选）") }) }
        item { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("直接粘贴或描述资料") }, minLines = 5, placeholder = { Text("零散条款、特殊要求和你的补充说明都可以。") }) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.addKnowledge(title, text) }, enabled = state.busy == null && text.isNotBlank()) { Text("提交文字资料") }; OutlinedButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = state.busy == null) { Icon(Icons.Outlined.AttachFile, null); Text("选择文件") } } }
        item { HorizontalDivider(); SectionTitle("我的资料", "仅在授权范围内使用"); TextButton(onClick = vm::refresh, enabled = state.busy == null) { Icon(Icons.Outlined.Refresh, null); Text("刷新处理进度") } }
        if (state.knowledge.isEmpty()) item { EmptyPanel("还没有资料", "上传后会显示处理状态。部分可读的内容也可以先使用。") }
        items(state.knowledge, key = { it.id }) { item -> OutlinedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                if(item.status=="awaiting_visual") TextButton(onClick={ source=item; tool="video" },enabled=client!=null) { Text("复核视频") }
                Text("${statusLabel(item.status)} · 版本 ${item.revision}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                FlowRow { TextButton(onClick = { vm.readKnowledge(item.id) }) { Text("查看内容与来源") }; TextButton(onClick = { deleting = item }) { Text("撤回", color = MaterialTheme.colorScheme.error) } }
            }
        } }
        if (state.professionalSources.isNotEmpty()) item { TextButton(onClick = { showProfessional = !showProfessional }) { Text(if (showProfessional) "收起专业知识来源" else "查看专业知识来源（${state.professionalSources.size}）"); Icon(if (showProfessional) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) } }
        if (showProfessional) items(state.professionalSources, key = { it.id }) { source ->
            OutlinedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(source.title, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(source.publisher.takeIf { it.isNotBlank() }, source.season).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(source.summary, style = MaterialTheme.typography.bodyMedium)
                if (source.accessNote.isNotBlank()) Text(source.accessNote, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { if (source.url.startsWith("https://")) uriHandler.openUri(source.url) }) { Text("打开官方原文"); Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)) }
            } }
        }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("从资料里查找") }, trailingIcon = { IconButton(onClick = { if (query.isNotBlank()) vm.searchKnowledge(query) }) { Icon(Icons.Outlined.Search, "搜索资料") } }) }
        state.searchResult?.let { result ->
            items(result.warnings) { warning -> Text(warning, style = MaterialTheme.typography.bodyMedium) }
            if (result.items.isEmpty()) item { Text("没有找到匹配内容，可以换种说法或补充资料。") }
            items(result.items, key = { it.id }) { hit -> OutlinedCard(onClick = { vm.readKnowledge(hit.sourceId) }) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(hit.title, style = MaterialTheme.typography.titleMedium); Text(hit.locator, style = MaterialTheme.typography.labelMedium); Text(hit.text) } } }
        }
    }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("撤回这份资料？") }, text = { Text("后续建议将停止使用「${item.title}」及其派生内容。") }, confirmButton = { TextButton(onClick = { vm.deleteKnowledge(item.id); deleting = null }) { Text("撤回") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun MemoryScreen(state: CoachUiState, vm: CoachViewModel) {
    var newStatement by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<MemoryItem?>(null) }
    var forgetting by remember { mutableStateOf<MemoryItem?>(null) }
    var corrected by rememberSaveable { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("04 / YOUR PROFILE", "系统记住了什么", "能力、偏好和近期状态都有来源。你可以随时纠正，或让系统停止使用。") }
        item { OutlinedTextField(newStatement, { newStatement = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("补充一条希望长期记住的信息") }, placeholder = { Text("例如：我更喜欢前半程安排 3S+3T。") }) }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.addMemory(newStatement) }, enabled = state.busy == null && newStatement.isNotBlank()) { Text("记住这条描述") }; TextButton(onClick = vm::refresh, enabled = state.busy == null) { Text("刷新档案") } } }
        if (state.memories.isEmpty()) item { EmptyPanel("档案会随着你的反馈逐渐形成", "没有记录不代表没有能力。当前状态也可以直接在「准备」中告诉系统。") }
        items(state.memories, key = { it.id }) { item -> OutlinedCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(item.statement, style = MaterialTheme.typography.bodyLarge)
                Text("${sourceLabel(item.evidenceKind)} · ${scopeLabel(item.scopeType)} · ${statusLabel(item.status)}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                item.observedAt?.let { Text("记录时间：${it.take(19).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall) }
                item.validUntil?.let { Text("适用至：${it.take(19).replace('T', ' ')}", style = MaterialTheme.typography.bodySmall) }
                FlowRow { TextButton(onClick = { vm.readMemorySource(item.id) }) { Text("查看来源") }; TextButton(onClick = { editing = item; corrected = item.statement }) { Text("纠正") }; TextButton(onClick = { forgetting = item }) { Text("忘记", color = MaterialTheme.colorScheme.error) } }
            }
        } }
    }
    editing?.let { item -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("纠正这条记忆") }, text = { OutlinedTextField(corrected, { corrected = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("应该如何理解") }) }, confirmButton = { TextButton(onClick = { vm.correctMemory(item, corrected); editing = null }, enabled = corrected.isNotBlank()) { Text("保存更正") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }) }
    forgetting?.let { item -> AlertDialog(onDismissRequest = { forgetting = null }, title = { Text("停止记住这条信息？") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.statement, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Text("后续建议将停止使用这条记忆。原始聊天或文件是否删除，由对应资料操作决定。")
        }
    }, confirmButton = { TextButton(onClick = { vm.forgetMemory(item); forgetting = null }) { Text("忘记这条") } }, dismissButton = { TextButton(onClick = { forgetting = null }) { Text("取消") } }) }
}

@Composable
private fun ConnectionDialog(state: CoachUiState, onDismiss: () -> Unit, onConnect: (String, String) -> Unit) {
    var url by rememberSaveable { mutableStateOf(state.baseUrl) }
    var token by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("连接 Skating Lab 服务") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接后同步方案、个人记忆与资料。", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(url, { url = it }, label = { Text("服务地址") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, label = { Text("访问凭据") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (BuildConfig.DEBUG) Text("本机调试可留空凭据，通过 adb reverse 连接本机开发会话。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onConnect(url, token) }, enabled = url.isNotBlank() && state.busy == null) { Text("连接") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ScenarioDialog(elements: List<PlanElement>, videoNote: String?, onDismiss: () -> Unit, onCreate: (Int, String, String) -> Unit) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    var actual by rememberSaveable { mutableStateOf(elements.firstOrNull()?.jumps?.firstOrNull().orEmpty()) }
    var description by rememberSaveable { mutableStateOf("第一跳完成，后续连跳未接上") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("这次实际发生了什么") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            videoNote?.let { Text("本次预案选用的人工复核备注\n$it") }
            Text("选择失误位置")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { elements.forEachIndexed { row, element -> FilterChip(selected = row == index, onClick = { index = row; actual = element.jumps.firstOrNull().orEmpty() }, label = { Text("${row + 1}. ${element.jumps.joinToString("+")}") }) } }
            OutlinedTextField(actual, { actual = it }, label = { Text("这一组实际完成的动作") }, supportingText = { Text("例如 3T，或降周后的 2T。") })
            OutlinedTextField(description, { description = it }, label = { Text("情景与当前状态") }, minLines = 3)
        }
    }, confirmButton = { TextButton(onClick = { onCreate(index, actual, description) }) { Text("建立预案") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ScoreSummary(evaluation: Evaluation?, evaluating: Boolean) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("跳跃基础分 · BV", style = MaterialTheme.typography.labelLarge)
                Text(when { evaluating -> "服务端正在核算"; evaluation == null -> "编辑后从服务端更新"; !evaluation.scoreComplete -> "规则或时间仍有待核实内容"; !evaluation.checksComplete -> "分值已计算，部分条件仍待核对"; evaluation.hasViolations -> "存在需调整的规则条件"; else -> "按当前规则版本计算" }, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (evaluation?.scoreComplete == true) evaluation.evaluatedTotal?.let(::formatBv) ?: "待核算" else "待核算", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun CheckLabel(check: RuleCheck) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (check.status == "VIOLATED") Icons.Outlined.ErrorOutline else Icons.Outlined.Info, null, Modifier.size(18.dp), tint = if (check.status == "VIOLATED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(check.message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScreenHeading(kicker: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(kicker, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable
private fun EmptyPanel(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
private fun formatBv(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
private fun scopeLabel(scope: String): String = when (scope.lowercase()) { "session" -> "本次情境"; "competition" -> "本场比赛"; else -> "长期记录" }
private fun sourceLabel(kind: String): String = when (kind.lowercase()) { "user_report" -> "本人描述"; "coach_report" -> "教练记录"; "video_observation" -> "视频观察"; "inference" -> "待核实推测"; else -> "有来源的记录" }
private fun statusLabel(status: String): String = when (status.lowercase()) { "ready", "available" -> "可使用"; "active" -> "当前有效"; "candidate" -> "待澄清"; "partial", "partial_ready" -> "部分可用"; "processing", "queued", "pending", "received" -> "处理中"; "awaiting_visual" -> "已保存 · 待画面或音频分析"; "failed" -> "处理待重试"; "disputed" -> "存在不同描述"; "expired" -> "已过适用期"; "superseded" -> "已被更正"; "retracted", "forgotten" -> "已停止使用"; else -> status.ifBlank { "已接收" } }
