package com.yk.xiangqi.ui

import android.app.Application
import android.util.Log
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yk.xiangqi.GameRuntime
import com.yk.xiangqi.Settings
import com.yk.xiangqi.XiangqiApplication
import com.yk.xiangqi.book.MoveInfo
import com.yk.xiangqi.book.Obk
import com.yk.xiangqi.book.ObkException
import com.yk.xiangqi.book.Zobrist
import com.yk.xiangqi.bridge.BoardProjection
import com.yk.xiangqi.core.GameAction
import com.yk.xiangqi.core.Move
import com.yk.xiangqi.core.Position
import com.yk.xiangqi.core.Side
import com.yk.xiangqi.engine.EngineFiles
import com.yk.xiangqi.engine.GoParams
import com.yk.xiangqi.engine.ThinkingInfo
import com.yk.xiangqi.engine.UciEngine
import com.yk.xiangqi.engine.UciOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class PlayMode {
    MANUAL, QUERY, RED_AI, BLACK_AI, BOTH_AI,
}

data class UiState(
    val board: CharArray,
    val side: Side,
    val legal: Set<String>,
    val cursor: Int,
    val length: Int,
    val mode: PlayMode,
    val redAi: Boolean,
    val blackAi: Boolean,
    val flipped: Boolean,
    val bothArrows: Boolean,
    /** 当前搜索最新的一条完整信息；不是搜索历史。 */
    val analysis: ThinkingInfo? = null,
    val book: List<MoveInfo> = emptyList(),
    val bookName: String? = null,
    val bookNeedsOptimization: Boolean = false,
    val status: String = "准备中",
)

class GameViewModel(app: Application) : AndroidViewModel(app), UciEngine.Listener {
    private companion object {
        const val ENGINE_LOG_TAG = "XiangqiEngine"
    }
    private data class BookRead(
        val candidates: List<MoveInfo>,
        val needsOptimization: Boolean,
        val error: String? = null,
    )

    private val application = app as XiangqiApplication
    private val settings = application.settings()
    private val gameRuntime = application.gameRuntime()
    private val gameListener = GameRuntime.Listener { _, _, origin ->
        if (origin == GameRuntime.Origin.EXTERNAL) {
            viewModelScope.launch {
                state = snapshot("外部棋盘已走子")
                refresh()
            }
        }
    }
    private val aiListener = GameRuntime.AiListener { _, _ ->
        viewModelScope.launch { cancelSearch(); state = snapshot(); refresh() }
    }
    private val bookZobrist = app.assets.open("book/zobrist.txt").use(Zobrist::read)
    private val engine = UciEngine(this)

    /** ViewModel 是当前开局库资源的唯一所有者；Obk 本身不决定生命周期。 */
    private var obk: Obk? = null
    private var flipped = false
    private var bothArrows = true
    private var queryEnabled = false
    private var redSearch = SearchConfig(SearchConfig.Kind.MOVETIME, 1_000L)
    private var blackSearch = SearchConfig(SearchConfig.Kind.MOVETIME, 1_000L)
    private var pendingAlternative = false
    private var pendingImmediate = false
    private var activeSearchId = -1L
    private var engineReadyText = "引擎已就绪"
    private var selectedNetwork: File? = null
    private var bookCandidates: List<MoveInfo> = emptyList()
    private var bookNeedsOptimization = false
    private var refreshId = 0L
    private var analysis: ThinkingInfo? = null
    private var uciOptions: List<UciOption> = emptyList()
    private var engineDirectoryToDelete: File? = null

    var state by mutableStateOf(snapshot("正在安装 Pikafish…"))
        private set

    init {
        gameRuntime.addListener(gameListener)
        gameRuntime.addAiListener(aiListener)
        reloadBook()
        viewModelScope.launch {
            try {
                startSelectedEngine()
                state = snapshot("正在验证 UCI 握手…")
            } catch (error: Exception) {
                state = snapshot("引擎不可用: ${error.message}")
            }
        }
    }

    fun tap(from: Int, to: Int): Boolean {
        val move = Move(from, to)
        if (move.uci() !in state.legal || !reduce(GameAction.Play(move))) {
            state = snapshot("不是合法着")
            return false
        }
        cancelSearch()
        state = snapshot()
        refresh()
        return true
    }

    /** 丢弃当前线性历史，回到标准初始局面；AI 与查询模式保持用户当前选择。 */
    fun newGame() {
        cancelSearch()
        reduce(GameAction.Reset(Position.start()))
        state = snapshot("新对局")
        refresh()
    }

    fun previous() = navigate(GameAction.Navigate.PREVIOUS)
    fun next() = navigate(GameAction.Navigate.NEXT)
    fun first() = navigate(GameAction.Navigate.FIRST)
    fun last() = navigate(GameAction.Navigate.LAST)

    fun flip() {
        flipped = !flipped
        state = snapshot()
    }

    fun arrows() {
        bothArrows = !bothArrows
        state = snapshot()
    }

    fun setAi(red: Boolean, enabled: Boolean) {
        if (enabled) queryEnabled = false
        gameRuntime.setAi(if (red) Side.RED else Side.BLACK, enabled)
    }

    /** 查询与 AI 互斥；再次点击关闭查询，回到纯手动模式。 */
    fun query() {
        queryEnabled = !queryEnabled
        if (queryEnabled) {
            val redEnabled = gameRuntime.aiEnabled(Side.RED)
            val blackEnabled = gameRuntime.aiEnabled(Side.BLACK)
            if (redEnabled) gameRuntime.setAi(Side.RED, false)
            if (blackEnabled) gameRuntime.setAi(Side.BLACK, false)
            if (redEnabled || blackEnabled) return
        }
        cancelSearch()
        state = snapshot()
        refresh()
    }

    fun engineConfig(): Settings.Engine = settings.activeEngine()

    fun engines(): List<Settings.Engine> = settings.engines()

    fun books(): List<Settings.Book> = settings.books()

    fun activeBookConfig(): Settings.Book? = settings.activeBook()

    /** 内置两项和 NNUE 文件有固定入口，其余由引擎声明决定。 */
    fun engineOptions(): List<UciOption> = uciOptions.filter {
        !it.name.equals("Threads", true) && !it.name.equals("Hash", true) &&
            !it.name.equals("MultiPV", true) && !it.name.equals("EvalFile", true)
    }

    fun setEngineConfig(
        threads: String,
        hashMb: String,
        options: Map<String, String>,
    ): String? {
        val threadValue = threads.toIntOrNull()
        val hashValue = hashMb.toIntOrNull()
        if (threadValue == null || hashValue == null || threadValue <= 0 || hashValue <= 0) return "线程和 Hash 都必须是正整数"
        val savedOptions = options.filterKeys { !it.equals("MultiPV", true) }
        val prior = settings.activeEngine()
        try {
            for ((name, optionValue) in savedOptions)
                uciOptions.firstOrNull { it.name.equals(name, true) }?.setOption(optionValue)
        } catch (error: IllegalArgumentException) {
            return error.message ?: "引擎选项无效"
        }
        val value = Settings.Engine(
            prior.id,
            prior.name,
            prior.directory,
            threadValue,
            hashValue,
            savedOptions
        )
        settings.saveEngine(value, true)
        engine.configure(
            linkedMapOf(
                "Threads" to threadValue.toString(),
                "Hash" to hashValue.toString(),
            ).apply { putAll(savedOptions) })
        state = snapshot("已保存引擎配置")
        return null
    }

    /** 名称仅是本地显示信息，不应受 UCI 选项校验或引擎重启影响。 */
    fun renameEngine(name: String): String? {
        val engineName = name.trim()
        if (engineName.isEmpty()) return "请输入引擎名称"
        val prior = settings.activeEngine()
        settings.saveEngine(
            Settings.Engine(
                prior.id,
                engineName,
                prior.directory,
                prior.threads,
                prior.hashMb,
                prior.options,
            ),
            true,
        )
        state = snapshot("已保存引擎名称")
        return null
    }

    fun pressEngineOption(name: String) {
        try {
            uciOptions.firstOrNull { it.name.equals(name, true) }?.press()
                ?: throw IllegalArgumentException("引擎未声明该选项")
            engine.pressOption(name)
        } catch (error: IllegalArgumentException) {
            state = snapshot(error.message ?: "引擎选项无效")
        }
    }

    fun selectEngine(id: String) {
        if (id == settings.activeEngine().id) return
        settings.selectEngine(id)
        cancelSearch()
        uciOptions = emptyList()
        state = snapshot("正在切换 UCI 引擎…")
        viewModelScope.launch {
            try {
                startSelectedEngine()
            } catch (error: Exception) {
                state = snapshot("切换引擎失败：${error.message}")
            }
        }
    }

    fun deleteEngine() {
        val deleted = settings.activeEngine()
        if (deleted.id == Settings.BUNDLED_ENGINE_ID) {
            state = snapshot("不能删除内置引擎")
            return
        }
        cancelSearch()
        settings.removeEngine(deleted.id)
        uciOptions = emptyList()
        engineDirectoryToDelete = deleted.directory?.let(::File)
        state = snapshot("已删除 ${deleted.name}，正在切换至 Pikafish…")
        viewModelScope.launch {
            try {
                startSelectedEngine()
            } catch (error: Exception) {
                state = snapshot("切换内置引擎失败：${error.message}")
            }
        }
    }

    fun selectBook(id: String) {
        if (id == settings.activeBook()?.id) return
        settings.selectBook(id)
        state = snapshot("正在切换开局库…")
        reloadBook()
    }

    fun setBookConfig(name: String, enabled: Boolean, order: Settings.Book.Order): String? {
        val book = settings.activeBook() ?: return "未选择开局库"
        val bookName = name.trim()
        if (bookName.isEmpty()) return "请输入开局库名称"
        settings.saveBook(Settings.Book(book.id, bookName, book.path, enabled, order), true)
        state = snapshot("已保存开局库设置")
        reloadBook()
        return null
    }

    fun deleteBook() {
        val deleted = settings.activeBook() ?: return
        cancelSearch()
        closeBook()
        settings.removeBook(deleted.id)
        bookCandidates = emptyList()
        bookNeedsOptimization = false
        viewModelScope.launch {
            withContext(Dispatchers.IO) { deleteImportedBook(File(deleted.path)) }
            state = snapshot("已删除 ${deleted.name}")
            refresh()
        }
    }

    fun setGoParams(red: Boolean, kind: SearchConfig.Kind, text: String): String? {
        val value = text.trim().toLongOrNull() ?: return "请输入正整数"
        if (value <= 0L) return "限制必须大于 0"
        if (kind == SearchConfig.Kind.DEPTH && value > Int.MAX_VALUE) return "深度不能超过 ${Int.MAX_VALUE}"
        if (red) redSearch = SearchConfig(kind, value)
        else blackSearch = SearchConfig(kind, value)
        state = snapshot("${if (red) "红" else "黑"}方参数：${goParamsLabel(kind, value)}")
        return null
    }

    fun immediate() {
        if (activeSearchId < 0L || state.analysis?.pv?.firstOrNull() !in state.legal) {
            state = snapshot("当前没有可立即执行的最佳着")
            return
        }
        pendingImmediate = true
        engine.stop()
    }

    fun alternative() {
        if (mode() == PlayMode.MANUAL) {
            state = snapshot("请先开启查询或 AI 模式")
            return
        }
        val bestMove = state.analysis?.pv?.firstOrNull() ?: return
        val choices = state.legal.filter { it != bestMove }
        if (choices.isEmpty()) {
            state = snapshot("没有可用变招")
            return
        }
        pendingAlternative = true
        analysis = null
        state = snapshot("正在搜索变招")
        val game = gameRuntime.state()
        activeSearchId = if (mode() == PlayMode.QUERY) engine.go(game, GoParams.infinite(choices))
        else engine.go(game, goParams(state.side, choices))
    }

    override fun onSearchFailed(searchId: Long, message: String) {
        viewModelScope.launch {
            if (searchId != activeSearchId) return@launch
            activeSearchId = -1L
            state = snapshot("搜索失败：$message")
        }
    }

    override fun onInfo(searchId: Long, value: ThinkingInfo) {
        viewModelScope.launch {
            if (searchId != activeSearchId) return@launch
            analysis = value
            publishArrows()
            state = snapshot("分析中")
        }
    }

    override fun onUciOptions(options: List<com.yk.xiangqi.engine.UciOption>) {
        viewModelScope.launch {
            uciOptions = options
            val config = settings.activeEngine()
            val values = linkedMapOf(
                "Threads" to config.threads.toString(),
                "Hash" to config.hashMb.toString(),
            )
            values.putAll(config.options.filterKeys { !it.equals("MultiPV", true) })
            options.firstOrNull { it.name.equals("MultiPV", true) }?.let { values[it.name] = "1" }
            val evalFile = options.firstOrNull { it.name.equals("EvalFile", ignoreCase = true) }
            if (evalFile != null) selectedNetwork?.let { values[evalFile.name] = it.absolutePath }
            engine.configure(values)
            state = snapshot("正在配置 UCI 引擎…")
        }
    }

    override fun onReady() {
        viewModelScope.launch {
            engineDirectoryToDelete?.let { directory ->
                engineDirectoryToDelete = null
                try {
                    withContext(Dispatchers.IO) { EngineFiles.deleteBundle(getApplication(), directory) }
                } catch (_: Exception) {
                    // 新引擎已经可用；旧资源清理失败不影响本次切换。
                }
            }
            state = snapshot(engineReadyText)
            Log.i(ENGINE_LOG_TAG, engineReadyText)
            refresh()
        }
    }

    override fun onEngineFailed(message: String) {
        viewModelScope.launch {
            activeSearchId = -1L
            state = snapshot(message)
            Log.e(ENGINE_LOG_TAG, message)
        }
    }

    override fun onBestMove(searchId: Long, info: com.yk.xiangqi.engine.BestMoveInfo) {
        viewModelScope.launch {
            if (searchId != activeSearchId) return@launch
            if (info.bestMove !in state.legal) {
                Log.w(ENGINE_LOG_TAG, "忽略非法 bestmove：${info.bestMove}")
                return@launch
            }
            val active = mode()
            val aiTurn =
                active == PlayMode.BOTH_AI || (active == PlayMode.RED_AI && state.side == Side.RED) || (active == PlayMode.BLACK_AI && state.side == Side.BLACK)
            val shouldPlay =
                pendingImmediate || aiTurn || (pendingAlternative && active != PlayMode.QUERY)
            activeSearchId = -1L
            if (shouldPlay) {
                Log.i(ENGINE_LOG_TAG, "自动走子：${info.bestMove}")
                pendingImmediate = false
                pendingAlternative = false
                if (reduce(GameAction.Play(Move.parse(info.bestMove)))) {
                    state = snapshot("引擎走子")
                    refresh()
                }
            } else {
                state = snapshot("已得到最佳着")
            }
        }
    }

    override fun onCleared() {
        gameRuntime.removeListener(gameListener)
        gameRuntime.removeAiListener(aiListener)
        engine.close()
        closeBook()
    }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            val bookId = UUID.randomUUID().toString()
            val result = withContext(Dispatchers.IO) {
                val target = File(
                    File(getApplication<Application>().filesDir, "books").also { it.mkdirs() },
                    "book-$bookId.obk"
                )
                getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { input -> target.outputStream().use(input::copyTo) }
                    ?: return@withContext Result.failure(IllegalStateException("无法读取所选文件"))
                try {
                    Result.success(Obk.open(target, bookZobrist))
                } catch (error: ObkException) {
                    target.delete()
                    Result.failure(error)
                }
            }
            result.fold(
                onSuccess = { opened ->
                    closeBook()
                    replaceBook(opened)
                    val config = Settings.Book(
                        bookId, "开局库", opened.path().path, true, Settings.Book.Order.MAX_SCORE
                    )
                    settings.saveBook(config, true)
                    state = snapshot("已导入开局库")
                    refresh()
                },
                onFailure = { error -> state = snapshot("导入开局库失败：${error.message}") },
            )
        }
    }

    fun importEngine(uri: Uri) {
        viewModelScope.launch {
            try {
                val directory = withContext(Dispatchers.IO) {
                    EngineFiles.importBundle(
                        getApplication(), uri, null
                    )
                }
                cancelSearch()
                saveEngine(directory.path)
                engineReadyText = "自定义引擎已就绪"
                startEngine(File(directory, "engine"), directory, null)
                state = snapshot("正在验证自定义 UCI 引擎…")
            } catch (error: Exception) {
                state = snapshot("引擎导入失败：${error.message}")
            }
        }
    }

    fun importNetwork(uri: Uri) {
        viewModelScope.launch {
            try {
                val directory = settings.activeEngine().directory?.let(::File)
                    ?: throw IllegalStateException("请先导入引擎")
                val network = withContext(Dispatchers.IO) {
                    EngineFiles.importNetwork(
                        getApplication(), uri, directory
                    )
                }
                cancelSearch()
                engineReadyText = "自定义引擎与 NNUE 已就绪"
                startEngine(File(directory, "engine"), directory, network)
                state = snapshot("正在验证自定义引擎与 NNUE…")
            } catch (error: Exception) {
                state = snapshot("NNUE 导入失败：${error.message}")
            }
        }
    }

    fun optimizeBook() {
        viewModelScope.launch {
            val file = selectedBookFile()
            if (file == null) {
                state = snapshot("未选择开局库")
                return@launch
            }
            state = snapshot("正在建立旁路索引…")
            closeBook()
            try {
                val result = withContext(Dispatchers.IO) {
                    Obk.open(file, bookZobrist).use { it.optimize() }
                }
                val opened = withContext(Dispatchers.IO) { Obk.open(file, bookZobrist) }
                replaceBook(opened)
                state =
                    snapshot("旁路索引完成：${result.indexedRows} 行，跳过 ${result.skippedRows} 行")
                refresh()
            } catch (error: Exception) {
                state = snapshot("旁路索引失败：${error.message}")
            }
        }
    }

    fun removeBook() {
        settings.clearActiveBook()
        reloadBook()
    }

    private fun snapshot(status: String = "准备就绪"): UiState {
        val game = gameRuntime.state()
        val position = game.position()
        return UiState(
            board = BoardProjection.characters(position),
            side = position.sideToMove(),
            legal = position.legalMoves().map { it.uci() }.toSet(),
            cursor = game.cursor(),
            length = game.allMoves().size,
            mode = mode(),
            redAi = gameRuntime.aiEnabled(Side.RED),
            blackAi = gameRuntime.aiEnabled(Side.BLACK),
            flipped = flipped,
            bothArrows = bothArrows,
            analysis = analysis,
            book = bookCandidates,
            bookName = settings.activeBook()?.name,
            bookNeedsOptimization = bookNeedsOptimization,
            status = status,
        )
    }

    private fun navigate(action: GameAction) {
        cancelSearch()
        if (!reduce(action)) return
        state = snapshot()
        refresh()
    }

    private fun reduce(action: GameAction): Boolean {
        return gameRuntime.reduce(action, GameRuntime.Origin.LOCAL)
    }

    private fun mode(): PlayMode {
        val red = gameRuntime.aiEnabled(Side.RED)
        val black = gameRuntime.aiEnabled(Side.BLACK)
        return when {
            queryEnabled -> PlayMode.QUERY
            red && black -> PlayMode.BOTH_AI
            red -> PlayMode.RED_AI
            black -> PlayMode.BLACK_AI
            else -> PlayMode.MANUAL
        }
    }

    private fun goParams(side: Side, searchMoves: List<String>? = null): GoParams {
        return searchConfig(side).toGoParams(searchMoves)
    }

    private fun searchConfig(side: Side): SearchConfig {
        return if (side == Side.RED) redSearch else blackSearch
    }

    fun goParamsKind(red: Boolean): SearchConfig.Kind =
        searchConfig(if (red) Side.RED else Side.BLACK).kind

    fun goParamsValue(red: Boolean): Long = searchConfig(if (red) Side.RED else Side.BLACK).value

    fun goParamsLabel(red: Boolean): String = goParamsLabel(goParamsKind(red), goParamsValue(red))

    fun linkConfig(): Settings.Link = settings.link()

    fun setLinkConfig(frameSettleMs: String, tapMs: String): String? = try {
        settings.setLink(Settings.Link(frameSettleMs.toLong(), tapMs.toLong()))
        null
    } catch (error: Exception) {
        "连线参数无效"
    }

    private fun goParamsLabel(kind: SearchConfig.Kind, value: Long): String = when (kind) {
        SearchConfig.Kind.MOVETIME -> "${value / 1000.0} 秒"
        SearchConfig.Kind.DEPTH -> "$value 层"
        SearchConfig.Kind.NODES -> "$value 节点"
    }

    private fun refresh() {
        val game = gameRuntime.state()
        val requestedRefreshId = ++refreshId
        if (activeSearchId >= 0L) cancelSearch()
        if (game.isOver()) {
            state = snapshot("对局结束")
            return
        }
        val active = mode()
        val aiTurn =
            active == PlayMode.BOTH_AI || (active == PlayMode.RED_AI && state.side == Side.RED) || (active == PlayMode.BLACK_AI && state.side == Side.BLACK)
        val position = game.position()
        val book = obk
        if (book == null) {
            bookCandidates = emptyList()
            bookNeedsOptimization = false
            beginAnalysis(active, aiTurn)
            return
        }
        state = snapshot("正在查询开局库…")
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) { readBook(book, position) }
            if (requestedRefreshId != refreshId || position !== gameRuntime.state()
                    .position()
            ) return@launch
            if (read.error != null && obk === book) closeBook()
            bookCandidates = read.candidates
            bookNeedsOptimization = read.needsOptimization
            state =
                snapshot(read.error ?: if (bookCandidates.isEmpty()) "引擎分析" else "开局库命中")
            beginAnalysis(active, aiTurn)
        }
    }

    private fun beginAnalysis(active: PlayMode, aiTurn: Boolean) {
        val bookMove = when (settings.activeBook()?.order ?: Settings.Book.Order.MAX_SCORE) {
            Settings.Book.Order.MAX_SCORE -> bookCandidates.firstOrNull()
            Settings.Book.Order.RANDOM -> bookCandidates.randomOrNull()
        }
        if (aiTurn && bookMove != null && reduce(GameAction.Play(Move.parse(bookMove.move)))) {
            state = snapshot("开局库走子")
            refresh()
        } else if (active == PlayMode.QUERY) {
            activeSearchId = engine.go(gameRuntime.state(), GoParams.infinite(null))
        } else if (aiTurn) {
            activeSearchId = engine.go(gameRuntime.state(), goParams(state.side))
            Log.i(ENGINE_LOG_TAG, "请求自动走子：${state.side}，搜索=$activeSearchId")
        }
    }

    private fun selectedBookFile(): File? {
        val book = settings.activeBook() ?: return null
        return File(book.path).takeIf { book.enabled && it.isFile }
    }

    private fun saveEngine(directory: String) {
        val prior = settings.activeEngine()
        settings.saveEngine(
            Settings.Engine(
                UUID.randomUUID().toString(),
                "自定义引擎",
                directory,
                prior.threads,
                prior.hashMb,
                prior.options
            ), true
        )
    }

    private fun startEngine(executable: File, directory: File, network: File?) {
        selectedNetwork = network?.takeIf { it.isFile }
        engine.start(executable, directory)
    }

    private fun deleteImportedBook(file: File) {
        val directory = File(getApplication<Application>().filesDir, "books").canonicalFile
        val target = file.canonicalFile
        if (target.parentFile != directory || !target.name.startsWith("book-"))
            return
        File(target.path + ".idx").delete()
        target.delete()
    }

    private suspend fun startSelectedEngine() {
        val config = settings.activeEngine()
        val custom = config.directory?.let(::File)?.takeIf { File(it, "engine").isFile }
        engineReadyText = if (custom == null) "Pikafish 已就绪" else "${config.name} 已就绪"
        if (custom != null) {
            startEngine(File(custom, "engine"), custom, File(custom, "network.nnue"))
        } else {
            val directory = withContext(Dispatchers.IO) { EngineFiles.installBundled(getApplication()) }
            startEngine(File(directory, "pikafish"), directory, File(directory, "pikafish.nnue"))
        }
    }

    private fun reloadBook() {
        cancelSearch()
        closeBook()
        bookCandidates = emptyList()
        bookNeedsOptimization = false
        val book = settings.activeBook()
        val file = selectedBookFile()
        if (book == null || file == null) {
            state = snapshot(if (book == null) "未选择开局库" else "开局库已关闭")
            refresh()
            return
        }
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { openBook(file) }
            if (file != selectedBookFile()) {
                opened.getOrNull()?.close()
                return@launch
            }
            opened.onSuccess { replaceBook(it) }
                .onFailure { state = snapshot("开局库不可用：${it.message}") }
            refresh()
        }
    }

    private fun openBook(file: File): Result<Obk> = try {
        Result.success(Obk.open(file, bookZobrist))
    } catch (error: ObkException) {
        Result.failure(error)
    }

    private fun readBook(book: Obk, position: Position): BookRead = try {
        BookRead(book.query(position), book.needsOptimization())
    } catch (error: ObkException) {
        BookRead(emptyList(), false, "开局库不可用：${error.message}")
    }

    private fun replaceBook(value: Obk) {
        check(obk == null)
        obk = value
    }

    private fun closeBook() {
        obk?.close()
        obk = null
    }

    private fun cancelSearch() {
        activeSearchId = -1L
        pendingImmediate = false
        pendingAlternative = false
        analysis = null
        gameRuntime.setArrows(emptyList())
        engine.cancelSearch()
    }

    private fun publishArrows() {
        val moves = analysis?.pv.orEmpty().take(2)
            .filter(Move::isUciCoordinate)
            .map(Move::parse)
        gameRuntime.setArrows(moves)
    }
}
