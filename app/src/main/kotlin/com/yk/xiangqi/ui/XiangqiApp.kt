package com.yk.xiangqi.ui

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.yk.xiangqi.core.Move
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XiangqiApp(onStartLink: () -> Unit, viewModel: GameViewModel = viewModel()) {
    val state = viewModel.state
    var selected by remember { mutableIntStateOf(-1) }
    var settingsVisible by remember { mutableStateOf(false) }
    val bookPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importBook) }
    val enginePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importEngine) }
    val nnuePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importNetwork) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("YK 象棋") },
                actions = {
                    TextButton(onStartLink) { Text("连线") }
                    TextButton({ settingsVisible = true }) { Text("设置") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(state.status)
            Board(
                board = state.board,
                flipped = state.flipped,
                pv = state.analysis?.pv.orEmpty(),
                bothArrows = state.bothArrows,
            ) { square ->
                if (selected < 0 && state.board[square] != '.') {
                    selected = square
                } else if (selected >= 0) {
                    viewModel.tap(selected, square)
                    selected = -1
                }
            }
            NavigationControls(viewModel, state.cursor, state.length)
            ModeControls(viewModel, state)
            SearchControls(viewModel)
            EnginePanel(state)
            BookPanel(state, viewModel::optimizeBook, viewModel::removeBook)
            if (settingsVisible) {
                SettingsDialog(
                    viewModel = viewModel,
                    state = state,
                    pickBook = { bookPicker.launch(arrayOf("*/*")) },
                    pickEngine = { enginePicker.launch(arrayOf("*/*")) },
                    pickNetwork = { nnuePicker.launch(arrayOf("*/*")) },
                ) { settingsVisible = false }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    viewModel: GameViewModel,
    state: UiState,
    pickBook: () -> Unit,
    pickEngine: () -> Unit,
    pickNetwork: () -> Unit,
    close: () -> Unit,
) {
    val config = viewModel.linkConfig()
    var settle by remember { mutableStateOf(config.settleMs.toString()) }
    var threshold by remember { mutableStateOf(config.motionThreshold.toString()) }
    var tap by remember { mutableStateOf(config.tapIntervalMs.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("设置") },
        text = {
            Column {
                Text("棋盘", fontWeight = FontWeight.Bold)
                Row {
                    TextButton(viewModel::flip) { Text(if (state.flipped) "取消翻转" else "翻转棋盘") }
                    TextButton(viewModel::arrows) { Text(if (state.bothArrows) "显示双箭头" else "只显示单箭头") }
                }
                Text("引擎与开局库", fontWeight = FontWeight.Bold)
                Row {
                    TextButton({ close(); pickBook() }) { Text("选择 OBK") }
                    TextButton({ close(); pickEngine() }) { Text("选择引擎") }
                    TextButton({ close(); pickNetwork() }) { Text("选择 NNUE") }
                }
                Text("连线", fontWeight = FontWeight.Bold)
                OutlinedTextField(settle, { settle = it; error = null }, label = { Text("静止时间 ms") }, singleLine = true)
                OutlinedTextField(threshold, { threshold = it; error = null }, label = { Text("运动阈值 0–1") }, singleLine = true)
                OutlinedTextField(tap, { tap = it; error = null }, label = { Text("双击间隔 ms") }, singleLine = true)
                error?.let { Text(it, color = Color.Red) }
            }
        },
        confirmButton = {
            TextButton({
                error = viewModel.setLinkConfig(settle, threshold, tap); if (error == null) close()
            }) { Text("确定") }
        },
        dismissButton = { TextButton(close) { Text("取消") } },
    )
}

@Composable
private fun ModeControls(viewModel: GameViewModel, state: UiState) {
    var editingRed by remember { mutableStateOf<Boolean?>(null) }
    Row {
        OutlinedButton({ viewModel.setAi(true, !state.redAi) }) { Text(if (state.redAi) "红 AI 开" else "红 AI 关") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton({ viewModel.setAi(false, !state.blackAi) }) { Text(if (state.blackAi) "黑 AI 开" else "黑 AI 关") }
    }
    Row {
        TextButton({ editingRed = true }) { Text("红：${viewModel.goParamsLabel(true)}") }
        TextButton({ editingRed = false }) { Text("黑：${viewModel.goParamsLabel(false)}") }
    }
    editingRed?.let { red -> LimitDialog(red, viewModel) { editingRed = null } }
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
        Button(viewModel::first) { Text("|<") }
        Button(viewModel::previous) { Text("<") }
        Text(" $cursor/$length ")
        Button(viewModel::next) { Text(">") }
        Button(viewModel::last) { Text(">|") }
    }
}

@Composable
private fun SearchControls(viewModel: GameViewModel) {
    Row {
        Button(viewModel::immediate) { Text("立即出招") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(viewModel::alternative) { Text("变招") }
    }
}

@Composable
private fun EnginePanel(state: UiState) {
    val engine = state.analysis
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("引擎", fontWeight = FontWeight.Bold)
            Text("Q ${engine?.score ?: "—"}  深度 ${engine?.depth ?: "—"}  用时 ${engine?.timeMs ?: "—"}ms")
            Text("NPS ${engine?.nps ?: "—"}  Nodes ${engine?.nodes ?: "—"}  WDL ${engine?.wdl?.let { "${it.win}/${it.draw}/${it.loss}" } ?: "—"}")
            Text(engine?.pv?.joinToString(" ") ?: "等待分析")
        }
    }
}

@Composable
private fun BookPanel(state: UiState, optimize: () -> Unit, remove: () -> Unit) {
    if (state.book.isEmpty() && state.bookName == null) return
    Card(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("开局库", fontWeight = FontWeight.Bold)
            Text(state.bookName ?: "未选择")
            if (state.bookNeedsOptimization) OutlinedButton(optimize) { Text("建立旁路索引") }
            if (state.bookName != null) TextButton(remove) { Text("移除开局库") }
            state.book.take(5).forEach { candidate ->
                Text("${candidate.move}  分 ${candidate.score}  胜率 ${"%.1f".format(candidate.winRate() * 100)}%")
            }
        }
    }
}

@Composable
private fun Board(board: CharArray, flipped: Boolean, pv: List<String>, bothArrows: Boolean, click: (Int) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(.9f)
            .pointerInput(flipped) {
                detectTapGestures { offset ->
                    val cell = min(size.width / 9f, size.height / 10f)
                    val file = ((offset.x - (size.width - cell * 8) / 2) / cell).toInt()
                    val rank = ((offset.y - (size.height - cell * 9) / 2) / cell).toInt()
                    if (file in 0..8 && rank in 0..9) {
                        val square = (if (flipped) rank else 9 - rank) * 9 + if (flipped) 8 - file else file
                        click(square)
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
