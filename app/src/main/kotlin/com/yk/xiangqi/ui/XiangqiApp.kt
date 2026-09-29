package com.yk.xiangqi.ui

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yk.xiangqi.Settings
import com.yk.xiangqi.core.Move
import com.yk.xiangqi.core.Side
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun XiangqiApp(onStartLink: () -> Unit, viewModel: GameViewModel = viewModel()) {
    val state = viewModel.state
    var selected by remember { mutableIntStateOf(-1) }
    var settingsVisible by remember { mutableStateOf(false) }
    var engineConfigVisible by remember { mutableStateOf(false) }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importBook) }
    val enginePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importEngine) }
    val nnuePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importNetwork) }

    if (engineConfigVisible) {
        EngineConfigPage(
            viewModel = viewModel,
            pickEngine = { engineConfigVisible = false; enginePicker.launch(arrayOf("*/*")) },
            pickNetwork = { engineConfigVisible = false; nnuePicker.launch(arrayOf("*/*")) },
        ) {
            engineConfigVisible = false
            settingsVisible = true
        }
        return
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopControls(viewModel, state, onStartLink) { settingsVisible = true }
            Board(
                board = state.board,
                flipped = state.flipped,
                selected = selected,
                pv = state.analysis?.pv.orEmpty(),
                bothArrows = state.bothArrows,
            ) { square ->
                val piece = state.board[square]
                val ours = piece != '.' && (piece.isUpperCase() == (state.side == Side.RED))
                if (selected < 0 && ours) {
                    selected = square
                } else if (selected >= 0) {
                    if (ours) selected = if (square == selected) -1 else square
                    else if (viewModel.tap(selected, square)) selected = -1
                }
            }
            NavigationControls(viewModel, state.cursor, state.length)
            InfoPanel(state, viewModel::optimizeBook, viewModel::removeBook, Modifier.weight(1f))
            if (settingsVisible) {
                SettingsDialog(
                    viewModel = viewModel,
                    pickBook = { bookPicker.launch(arrayOf("*/*")) },
                    openEngineConfig = {
                        settingsVisible = false
                        engineConfigVisible = true
                    },
                ) { settingsVisible = false }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    viewModel: GameViewModel,
    pickBook: () -> Unit,
    openEngineConfig: () -> Unit,
    close: () -> Unit,
) {
    val uiState = viewModel.state
    val config = viewModel.linkConfig()
    val engine = uiState.let { viewModel.engineConfig() }
    val activeBook = uiState.let { viewModel.activeBookConfig() }
    var frameSettle by remember { mutableStateOf(config.frameSettleMs.toString()) }
    var tapDuration by remember { mutableStateOf(config.tapDurationMs.toString()) }
    var tapInterval by remember { mutableStateOf(config.tapIntervalMs.toString()) }
    var modelThreads by remember { mutableStateOf(config.modelThreads.toString()) }
    var bookName by remember(activeBook?.id) { mutableStateOf(activeBook?.name.orEmpty()) }
    var editingRed by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("设置") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("引擎", fontWeight = FontWeight.Bold)
                Text("当前：${engine.name}")
                TextButton(openEngineConfig) { Text("配置 UCI 引擎") }
                Row {
                    TextButton({ editingRed = true }) { Text("红方：${viewModel.goParamsLabel(true)}") }
                    TextButton({ editingRed = false }) { Text("黑方：${viewModel.goParamsLabel(false)}") }
                }
                Text("开局库", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                TextButton({ close(); pickBook() }) { Text("选择 OBK 开局库") }
                if (activeBook == null) {
                    Text("当前未选择")
                } else {
                    Text("当前：${activeBook.name}")
                    viewModel.books().forEach { candidate ->
                        TextButton({ viewModel.selectBook(candidate.id) }, enabled = candidate.id != activeBook.id) {
                            Text(if (candidate.id == activeBook.id) "● ${candidate.name}（当前）" else candidate.name)
                        }
                    }
                    OutlinedTextField(
                        value = bookName,
                        onValueChange = { bookName = it; error = null },
                        label = { Text("开局库名称") },
                        singleLine = true,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("启用", Modifier.weight(1f))
                        Switch(activeBook.enabled, onCheckedChange = {
                            error = viewModel.setBookConfig(bookName, it, activeBook.order)
                        })
                    }
                    Text("自动走子策略")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { error = viewModel.setBookConfig(bookName, activeBook.enabled, Settings.Book.Order.MAX_SCORE) },
                            enabled = activeBook.order != Settings.Book.Order.MAX_SCORE,
                        ) { Text("最高分") }
                        OutlinedButton(
                            onClick = { error = viewModel.setBookConfig(bookName, activeBook.enabled, Settings.Book.Order.RANDOM) },
                            enabled = activeBook.order != Settings.Book.Order.RANDOM,
                        ) { Text("完全随机") }
                    }
                    TextButton({ error = viewModel.setBookConfig(bookName, activeBook.enabled, activeBook.order) }) { Text("保存开局库设置") }
                    TextButton(viewModel::optimizeBook) { Text("建立旁路索引") }
                    TextButton(viewModel::deleteBook) { Text("删除当前开局库") }
                }
                Text("连线高级参数", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                OutlinedTextField(frameSettle, { frameSettle = it; error = null }, label = { Text("帧稳定等待 ms") }, singleLine = true)
                OutlinedTextField(tapDuration, { tapDuration = it; error = null }, label = { Text("单击持续 ms（1–100）") }, singleLine = true)
                OutlinedTextField(tapInterval, { tapInterval = it; error = null }, label = { Text("两击等待 ms（0–500）") }, singleLine = true)
                OutlinedTextField(modelThreads, { modelThreads = it; error = null }, label = { Text("模型线程数（下次开始连线生效，1–8）") }, singleLine = true)
                error?.let { Text(it, color = Color.Red) }
            }
        },
        confirmButton = {
            TextButton({
                error = viewModel.setLinkConfig(frameSettle, tapDuration, tapInterval, modelThreads)
                if (error == null) close()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(close) { Text("取消") } },
    )
    editingRed?.let { red -> LimitDialog(red, viewModel) { editingRed = null } }
}

/** UCI 声明的配置很多，独立页面避免挤占应用设置。 */
@Composable
private fun EngineConfigPage(
    viewModel: GameViewModel,
    pickEngine: () -> Unit,
    pickNetwork: () -> Unit,
    back: () -> Unit,
) {
    val uiState = viewModel.state
    val engine = uiState.let { viewModel.engineConfig() }
    val uciOptions = uiState.let { viewModel.engineOptions() }
    var threads by remember(engine.id) { mutableStateOf(engine.threads.toString()) }
    var hashMb by remember(engine.id) { mutableStateOf(engine.hashMb.toString()) }
    var engineName by remember(engine.id) { mutableStateOf(engine.name) }
    var optionValues by remember(engine.id, uciOptions) {
        mutableStateOf(uciOptions.filter { it.type != "button" }.associate { option ->
            option.name to (engine.options[option.name] ?: option.defaultValue ?: if (option.type == "check") "false" else "")
        })
    }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(back) { Text("返回") }
                Text("引擎配置", fontWeight = FontWeight.Bold)
            }
            Text("当前：${engine.name}")
            viewModel.engines().forEach { candidate ->
                TextButton({ viewModel.selectEngine(candidate.id) }, enabled = candidate.id != engine.id) {
                    Text(if (candidate.id == engine.id) "● ${candidate.name}（当前）" else candidate.name)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(pickEngine) { Text("导入 UCI 引擎") }
                TextButton(pickNetwork) { Text("导入 NNUE") }
            }
            OutlinedTextField(threads, { threads = it; error = null }, label = { Text("线程数") }, singleLine = true)
            OutlinedTextField(hashMb, { hashMb = it; error = null }, label = { Text("Hash (MB)") }, singleLine = true)
            OutlinedTextField(engineName, { engineName = it; error = null }, label = { Text("引擎名称") }, singleLine = true)
            TextButton({ error = viewModel.renameEngine(engineName) }) { Text("保存引擎名称") }
            if (uciOptions.isNotEmpty()) Text("引擎选项", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            uciOptions.forEach { option ->
                if (option.type == "button") {
                    TextButton({ viewModel.pressEngineOption(option.name) }) { Text(option.name) }
                    return@forEach
                }
                val value = optionValues[option.name].orEmpty()
                if (option.type == "check") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(option.name, Modifier.weight(1f))
                        Switch(
                            checked = value.equals("true", true),
                            onCheckedChange = { optionValues = optionValues + (option.name to it.toString()) },
                        )
                    }
                } else {
                    OutlinedTextField(
                        value,
                        { optionValues = optionValues + (option.name to it); error = null },
                        label = { Text(option.name) },
                        supportingText = {
                            val hint = when {
                                option.type == "spin" -> "${option.min ?: ""} – ${option.max ?: ""}"
                                option.type == "combo" -> option.vars.joinToString(" / ")
                                else -> null
                            }
                            if (hint != null) Text(hint)
                        },
                        singleLine = true,
                    )
                }
            }
            error?.let { Text(it, color = Color.Red) }
            Button(
                onClick = { error = viewModel.setEngineConfig(threads, hashMb, optionValues) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            ) { Text("保存引擎配置") }
            if (engine.id != Settings.BUNDLED_ENGINE_ID) {
                TextButton(viewModel::deleteEngine, Modifier.fillMaxWidth()) { Text("删除当前引擎") }
            }
        }
    }
}

@Composable
private fun TopControls(viewModel: GameViewModel, state: UiState, startLink: () -> Unit, openSettings: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ActionIcon(viewModel::newGame, Icons.Default.AddBox, "新游戏")
            ActionIcon({ viewModel.setAi(true, !state.redAi) }, Icons.Default.Person, "红方 AI", if (state.redAi) Color(0xffc62828) else Color.Gray)
            ActionIcon({ viewModel.setAi(false, !state.blackAi) }, Icons.Default.Person, "黑方 AI", if (state.blackAi) Color(0xff202020) else Color.Gray)
            ActionIcon(viewModel::query, Icons.Default.Search, "查询模式", if (state.mode == PlayMode.QUERY) Color(0xff1565c0) else Color.Gray)
            ActionIcon(viewModel::flip, Icons.AutoMirrored.Filled.RotateRight, if (state.flipped) "恢复正向" else "翻转棋盘")
            ActionIcon(viewModel::arrows, Icons.Default.SwapHoriz, if (state.bothArrows) "切换为单箭头" else "切换为双箭头")
            ActionIcon(viewModel::immediate, Icons.Default.Bolt, "立即出招")
            ActionIcon(viewModel::alternative, Icons.Default.Block, "变招")
            ActionIcon(startLink, Icons.Default.Link, "连线")
            ActionIcon(openSettings, Icons.Default.Settings, "设置")
        }
    }
}

@Composable
private fun RowScope.ActionIcon(onClick: () -> Unit, image: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Color.Unspecified) {
    IconButton(onClick, Modifier.weight(1f).height(36.dp)) {
        Icon(image, label, Modifier.size(20.dp), tint)
    }
}

@Composable
private fun LimitDialog(red: Boolean, viewModel: GameViewModel, close: () -> Unit) {
    var kind by remember(red) { mutableStateOf(viewModel.goParamsKind(red)) }
    var value by remember(red) { mutableStateOf(viewModel.goParamsValue(red).toString()) }
    var error by remember(red) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (red) "红方搜索限制" else "黑方搜索限制") },
        text = {
            Column {
                Row {
                    SearchConfig.Kind.entries.forEach { option ->
                        TextButton({
                            kind = option
                            value = defaultLimitValue(option).toString()
                            error = null
                        }) { Text(limitKindName(option)) }
                    }
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    singleLine = true,
                    label = { Text(limitValueLabel(kind)) },
                    isError = error != null,
                )
                error?.let { Text(it, color = Color.Red) }
            }
        },
        confirmButton = {
            TextButton({
                error = viewModel.setGoParams(red, kind, value)
                if (error == null) close()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(close) { Text("取消") } },
    )
}

private fun limitKindName(kind: SearchConfig.Kind): String = when (kind) {
    SearchConfig.Kind.MOVETIME -> "时间"
    SearchConfig.Kind.DEPTH -> "深度"
    SearchConfig.Kind.NODES -> "节点"
}

private fun limitValueLabel(kind: SearchConfig.Kind): String = when (kind) {
    SearchConfig.Kind.MOVETIME -> "毫秒"
    SearchConfig.Kind.DEPTH -> "层数"
    SearchConfig.Kind.NODES -> "节点数"
}

private fun defaultLimitValue(kind: SearchConfig.Kind): Long = when (kind) {
    SearchConfig.Kind.MOVETIME -> 1_000L
    SearchConfig.Kind.DEPTH -> 18L
    SearchConfig.Kind.NODES -> 1_000_000L
}

@Composable
private fun NavigationControls(viewModel: GameViewModel, cursor: Int, length: Int) {
    Row {
        TextButton(viewModel::first) { Text("|<") }
        TextButton(viewModel::previous) { Text("<") }
        Text(" $cursor/$length ")
        TextButton(viewModel::next) { Text(">") }
        TextButton(viewModel::last) { Text(">|") }
    }
}

@Composable
private fun InfoPanel(state: UiState, optimize: () -> Unit, remove: () -> Unit, modifier: Modifier) {
    val engine = state.analysis
    Card(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(Modifier.padding(10.dp).verticalScroll(rememberScrollState())) {
            Text("信息", fontWeight = FontWeight.Bold)
            Text(state.status)
            Text("Q ${engine?.score ?: "—"}    深度 ${engine?.depth ?: "—"}    用时 ${engine?.timeMs ?: "—"} ms")
            Text("NPS ${engine?.nps ?: "—"}    节点 ${engine?.nodes ?: "—"}")
            Text("主变", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Text(engine?.pv?.joinToString(" ") ?: "等待分析")
            if (state.book.isEmpty() && state.bookName == null) return@Column
            Text("开局库", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Text(state.bookName ?: "未选择")
            if (state.bookNeedsOptimization) OutlinedButton(optimize) { Text("建立旁路索引") }
            if (state.bookName != null) TextButton(remove) { Text("取消选择书库") }
            state.book.take(5).forEach { candidate ->
                Text("${candidate.move}  分 ${candidate.score}  胜率 ${"%.1f".format(candidate.winRate() * 100)}%")
            }
        }
    }
}

@Composable
private fun Board(board: CharArray, flipped: Boolean, selected: Int, pv: List<String>, bothArrows: Boolean, click: (Int) -> Unit) {
    val latestClick by rememberUpdatedState(click)
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(.9f)
            .pointerInput(flipped) {
                detectTapGestures { offset ->
                    val cell = min(size.width / 9f, size.height / 10f)
                    val file = ((offset.x - (size.width - cell * 8) / 2) / cell).roundToInt()
                    val rank = ((offset.y - (size.height - cell * 9) / 2) / cell).roundToInt()
                    if (file in 0..8 && rank in 0..9) {
                        val square = (if (flipped) rank else 9 - rank) * 9 + if (flipped) 8 - file else file
                        latestClick(square)
                    }
                }
            },
    ) {
        val cell = min(size.width / 9f, size.height / 10f)
        val originX = (size.width - cell * 8) / 2
        val originY = (size.height - cell * 9) / 2
        drawBoard(originX, originY, cell)
        drawPvArrows(pv, bothArrows, flipped, originX, originY, cell)
        drawPieces(board, flipped, originX, originY, cell)
        if (selected >= 0) drawCircle(Color(0xff00a152), cell * .43f, point(selected, flipped, originX, originY, cell), style = Stroke(4f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBoard(originX: Float, originY: Float, cell: Float) {
    drawRect(Color(0xfff4d7a0), Offset(originX - cell / 2, originY - cell / 2), Size(cell * 9, cell * 10))
    for (file in 0..8) drawLine(
        Color(0xff57351f),
        Offset(originX + file * cell, originY),
        Offset(originX + file * cell, originY + cell * 9),
        2f
    )
    for (rank in 0..9) drawLine(
        Color(0xff57351f),
        Offset(originX, originY + rank * cell),
        Offset(originX + cell * 8, originY + rank * cell),
        2f
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPvArrows(
    pv: List<String>,
    bothArrows: Boolean,
    flipped: Boolean,
    originX: Float,
    originY: Float,
    cell: Float,
) {
    pv.take(if (bothArrows) 2 else 1).forEachIndexed { index, uci ->
        if (!Move.isUciCoordinate(uci)) return@forEachIndexed
        val move = Move.parse(uci)
        val color = if (index == 0) Color.Red else Color.Blue
        drawLine(
            color,
            point(move.from.toInt(), flipped, originX, originY, cell),
            point(move.to.toInt(), flipped, originX, originY, cell),
            5f
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPieces(
    board: CharArray,
    flipped: Boolean,
    originX: Float,
    originY: Float,
    cell: Float,
) {
    for (square in 0 until 90) {
        val piece = board[square]
        if (piece == '.') continue
        val point = point(square, flipped, originX, originY, cell)
        val color = if (piece.isUpperCase()) Color.Red else Color.Black
        drawCircle(Color(0xfff8edd1), cell * .39f, point)
        drawCircle(color, cell * .39f, point, style = Stroke(2f))
        drawContext.canvas.nativeCanvas.drawText(
            label(piece),
            point.x - cell * .2f,
            point.y + cell * .14f,
            Paint().apply {
                this.color = if (piece.isUpperCase()) android.graphics.Color.RED else android.graphics.Color.DKGRAY
                textSize = cell * .43f
                isFakeBoldText = true
            },
        )
    }
}

private fun point(square: Int, flipped: Boolean, originX: Float, originY: Float, cell: Float): Offset {
    val file = if (flipped) 8 - square % 9 else square % 9
    val rank = if (flipped) square / 9 else 9 - square / 9
    return Offset(originX + file * cell, originY + rank * cell)
}

private fun label(piece: Char): String = when (piece.lowercaseChar()) {
    'r' -> "车"
    'n' -> "马"
    'b' -> "相"
    'a' -> "仕"
    'k' -> "帅"
    'c' -> "炮"
    else -> "兵"
}
