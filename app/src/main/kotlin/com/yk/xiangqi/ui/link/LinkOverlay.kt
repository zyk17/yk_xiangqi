package com.yk.xiangqi.ui.link

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yk.xiangqi.bridge.BoardProjection
import com.yk.xiangqi.core.GameState
import com.yk.xiangqi.core.Move
import com.yk.xiangqi.core.Side
import com.yk.xiangqi.link.BoardGeometry
import kotlin.math.max
import kotlin.math.min

/**
 * 连线功能的系统悬浮 UI：棋盘框选与运行控制。
 *
 * 它只负责绘制和触摸，具体的录屏、识别、同步由服务通过回调处理。
 */
class LinkOverlay(private val context: Context, private val listener: Listener) {
    private enum class Drag {
        NONE, CREATE, MOVE, LEFT, TOP, RIGHT, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    }

    private companion object {
        const val MIN_SIZE = 48f
        const val HANDLE_RADIUS = 40f
        const val CONTROL_HEIGHT = 110f
        const val BUTTON_NONE = 0
        const val BUTTON_CANCEL = 1
        const val BUTTON_CONFIRM = 2
    }

    interface Listener {
        fun onConfigured(geometry: BoardGeometry)
        fun onSelectionCancelled()
        fun onAiToggled(): Boolean
        fun onNewGameSideChanged(sideToMove: Side)
        fun onSynchronize(sideToMove: Side)
        fun onStop()
    }

    private val windows = context.getSystemService(WindowManager::class.java)
    private var selection: View? = null
    private var controls: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var mini: View? = null
    private var miniParams: WindowManager.LayoutParams? = null
    private var miniBoard: MiniBoardView? = null
    private var miniState: GameState? = null
    private var miniArrows: List<Move> = emptyList()
    private var miniEnabled = false
    private var syncSide = Side.RED

    /** 录屏启动后先展示轻量入口；用户切到第三方棋盘后再主动开始框选。 */
    fun showReady() {
        miniEnabled = false
        hideMiniBoard()
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(dragHandle("☰"))
        val select = button("框选棋盘")
        val stop = button("停止")
        select.setOnClickListener { showSelection() }
        stop.setOnClickListener { listener.onStop() }
        row.addView(select)
        row.addView(stop)
        replaceControls(row)
    }

    fun showSelection() {
        if (selection != null) return
        selection = SelectionView(context)
        windows.addView(selection, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ))
    }

    fun showControls(aiEnabled: Boolean) {
        miniEnabled = true
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(dragHandle("☰"))
        val ai = button(if (aiEnabled) "AI开" else "AI")
        val side = button("红先")
        val sync = button("同步")
        val board = button("棋盘")
        val stop = button("停止")
        ai.setOnClickListener { ai.text = if (listener.onAiToggled()) "AI开" else "AI" }
        side.setOnClickListener {
            syncSide = if (syncSide == Side.RED) Side.BLACK else Side.RED
            side.text = if (syncSide == Side.RED) "红先" else "黑先"
            listener.onNewGameSideChanged(syncSide)
        }
        sync.setOnClickListener { listener.onSynchronize(syncSide) }
        board.setOnClickListener { toggleMiniBoard() }
        stop.setOnClickListener { listener.onStop() }
        row.addView(ai)
        row.addView(side)
        row.addView(sync)
        row.addView(board)
        row.addView(stop)
        replaceControls(row)
    }

    private fun replaceControls(row: View) {
        val x = controlParams?.x ?: 16
        val y = controlParams?.y ?: defaultControlY()
        remove(controls)
        controls = row
        controlParams = overlayParams(x, y)
        windows.addView(row, controlParams)
    }

    private fun defaultControlY(): Int {
        val metrics = context.resources.displayMetrics
        return (metrics.heightPixels - 140 * metrics.density).toInt().coerceAtLeast(0)
    }

    /** 识别或本地走子后由服务刷新小棋盘与当前主变箭头。 */
    fun showMiniBoard(state: GameState, arrows: List<Move>) {
        miniState = state
        miniArrows = arrows
        if (!miniEnabled)
            return
        if (mini == null) {
            val density = context.resources.displayMetrics.density
            val size = (230 * density).toInt()
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xdd202020.toInt())
                addView(dragHandle("☰ 小棋盘"))
            }
            miniBoard = MiniBoardView(context)
            panel.addView(miniBoard, LinearLayout.LayoutParams(size, (size * 10f / 9f).toInt()))
            mini = panel
            miniParams = overlayParams(16, 360)
            windows.addView(panel, miniParams)
        }
        miniBoard?.update(state, arrows)
    }

    private fun toggleMiniBoard() {
        miniEnabled = !miniEnabled
        if (!miniEnabled) {
            hideMiniBoard()
        } else {
            miniState?.let { showMiniBoard(it, miniArrows) }
        }
    }

    private fun hideMiniBoard() {
        remove(mini)
        mini = null
        miniParams = null
        miniBoard = null
    }

    private fun overlayParams(x: Int, y: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    /** 拖动手柄才移动悬浮窗，普通按钮仍保持即时点击。 */
    private fun dragHandle(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 18f
        setPadding(18, 12, 18, 12)
        setBackgroundColor(0xdd404040.toInt())
        var rawX = 0f
        var rawY = 0f
        var startX = 0
        var startY = 0
        setOnTouchListener { view, event ->
            val target = if (view.parent === controls) controls to controlParams else mini to miniParams
            val window = target.first ?: return@setOnTouchListener true
            val params = target.second ?: return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX
                    rawY = event.rawY
                    startX = params.x
                    startY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - rawX).toInt()
                    params.y = startY + (event.rawY - rawY).toInt()
                    windows.updateViewLayout(window, params)
                }
            }
            true
        }
    }

    fun close() {
        remove(selection)
        remove(controls)
        remove(mini)
        selection = null
        controls = null
        controlParams = null
        mini = null
        miniBoard = null
        miniParams = null
        miniEnabled = false
    }

    private fun removeSelection() {
        remove(selection)
        selection = null
    }

    private fun remove(view: View?) {
        if (view != null) windows.removeView(view)
    }

    private fun button(text: String): Button = Button(context).apply { this.text = text }

    private inner class SelectionView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
        private var left = 0f
        private var top = 0f
        private var right = 0f
        private var bottom = 0f
        private var ready = false
        private var drag = Drag.NONE
        private var previousX = 0f
        private var previousY = 0f
        private var pressedButton = BUTTON_NONE

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x22000000)
            paint.color = Color.WHITE
            paint.textSize = 34f
            canvas.drawText(
                if (ready) "拖动框内移动；拖动边或圆点微调；底部确认"
                else "拖拽左上、右下交叉点中心",
                30f, 60f, paint,
            )
            if (!ready) return
            paint.style = Paint.Style.STROKE
            paint.color = 0xff00e5ff.toInt()
            canvas.drawRect(left, top, right, bottom, paint)
            for (x in 0 until 9)
                for (y in 0 until 10)
                    canvas.drawCircle(left + x * (right - left) / 8f, top + y * (bottom - top) / 9f, 5f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(left, top, 12f, paint)
            canvas.drawCircle(right, top, 12f, paint)
            canvas.drawCircle(left, bottom, 12f, paint)
            canvas.drawCircle(right, bottom, 12f, paint)
            paint.color = 0xcc222222.toInt()
            canvas.drawRect(0f, height - CONTROL_HEIGHT, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.WHITE
            paint.textSize = 32f
            canvas.drawText("取消", width / 4f - 30f, height - 42f, paint)
            canvas.drawText("确认", width * 3f / 4f - 30f, height - 42f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginDrag(event)
                MotionEvent.ACTION_MOVE -> updateDrag(event)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag(event)
            }
            return true
        }

        private fun beginDrag(event: MotionEvent) {
            previousX = event.x
            previousY = event.y
            if (ready && event.y >= height - CONTROL_HEIGHT) {
                pressedButton = if (event.x < width / 2f) BUTTON_CANCEL else BUTTON_CONFIRM
                return
            }
            if (!ready) {
                drag = Drag.CREATE
                left = event.x
                right = event.x
                top = event.y
                bottom = event.y
            } else {
                drag = hit(event.x, event.y)
            }
        }

        private fun updateDrag(event: MotionEvent) {
            if (pressedButton != BUTTON_NONE) return
            if (drag == Drag.CREATE) {
                right = event.x
                bottom = event.y
            } else {
                resize(event.x - previousX, event.y - previousY)
                previousX = event.x
                previousY = event.y
            }
            invalidate()
        }

        private fun endDrag(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                drag = Drag.NONE
                pressedButton = BUTTON_NONE
                invalidate()
                return
            }
            if (pressedButton != BUTTON_NONE) {
                val button = pressedButton
                pressedButton = BUTTON_NONE
                if (event.y >= height - CONTROL_HEIGHT) {
                    removeSelection()
                    if (button == BUTTON_CANCEL) listener.onSelectionCancelled()
                    else listener.onConfigured(BoardGeometry(left, top, right, bottom))
                }
                return
            }
            if (drag == Drag.CREATE) {
                right = event.x
                bottom = event.y
                val startLeft = left
                val startTop = top
                left = min(startLeft, right).coerceIn(0f, width.toFloat())
                right = max(startLeft, right).coerceIn(0f, width.toFloat())
                top = min(startTop, bottom).coerceIn(0f, height.toFloat())
                bottom = max(startTop, bottom).coerceIn(0f, height.toFloat())
                ready = right - left >= MIN_SIZE && bottom - top >= MIN_SIZE
            }
            drag = Drag.NONE
            invalidate()
        }

        private fun hit(x: Float, y: Float): Drag {
            val nearLeft = x.distance(left) <= HANDLE_RADIUS
            val nearRight = x.distance(right) <= HANDLE_RADIUS
            val nearTop = y.distance(top) <= HANDLE_RADIUS
            val nearBottom = y.distance(bottom) <= HANDLE_RADIUS
            if (nearLeft && nearTop) return Drag.TOP_LEFT
            if (nearRight && nearTop) return Drag.TOP_RIGHT
            if (nearLeft && nearBottom) return Drag.BOTTOM_LEFT
            if (nearRight && nearBottom) return Drag.BOTTOM_RIGHT
            if (nearLeft && y in top..bottom) return Drag.LEFT
            if (nearRight && y in top..bottom) return Drag.RIGHT
            if (nearTop && x in left..right) return Drag.TOP
            if (nearBottom && x in left..right) return Drag.BOTTOM
            if (x in left..right && y in top..bottom) return Drag.MOVE
            return Drag.NONE
        }

        private fun resize(dx: Float, dy: Float) {
            when (drag) {
                Drag.MOVE -> move(dx, dy)
                Drag.LEFT, Drag.TOP_LEFT, Drag.BOTTOM_LEFT -> left = (left + dx).coerceIn(0f, right - MIN_SIZE)
                Drag.RIGHT, Drag.TOP_RIGHT, Drag.BOTTOM_RIGHT -> right = (right + dx).coerceIn(left + MIN_SIZE, width.toFloat())
                else -> Unit
            }
            when (drag) {
                Drag.TOP, Drag.TOP_LEFT, Drag.TOP_RIGHT -> top = (top + dy).coerceIn(0f, bottom - MIN_SIZE)
                Drag.BOTTOM, Drag.BOTTOM_LEFT, Drag.BOTTOM_RIGHT -> bottom = (bottom + dy).coerceIn(top + MIN_SIZE, height.toFloat())
                else -> Unit
            }
        }

        private fun move(dx: Float, dy: Float) {
            val actualX = dx.coerceIn(-left, width - right)
            val actualY = dy.coerceIn(-top, height - bottom)
            left += actualX
            right += actualX
            top += actualY
            bottom += actualY
        }
    }

    private fun Float.distance(other: Float): Float = kotlin.math.abs(this - other)

    /** 连线时始终显示应用当前局面；红方在下，箭头只投影主变前两着。 */
    private class MiniBoardView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var board = CharArray(90) { '.' }
        private var arrows: List<Move> = emptyList()

        fun update(state: GameState, arrows: List<Move>) {
            board = BoardProjection.characters(state.position())
            this.arrows = arrows.take(2)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val cell = min((width - 24f) / 8f, (height - 24f) / 9f)
            val left = (width - cell * 8f) / 2f
            val top = (height - cell * 9f) / 2f
            paint.style = Paint.Style.FILL
            paint.color = 0xffe8bd79.toInt()
            canvas.drawRect(left - cell / 2f, top - cell / 2f, left + cell * 8.5f, top + cell * 9.5f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = 0xff64431f.toInt()
            for (file in 0..8)
                canvas.drawLine(left + file * cell, top, left + file * cell, top + 9 * cell, paint)
            for (rank in 0..9)
                canvas.drawLine(left, top + rank * cell, left + 8 * cell, top + rank * cell, paint)
            arrows.forEachIndexed { index, move ->
                paint.strokeWidth = 4f
                paint.color = if (index == 0) Color.RED else 0xff1976d2.toInt()
                val from = point(move.from.toInt(), left, top, cell)
                val to = point(move.to.toInt(), left, top, cell)
                canvas.drawLine(from.first, from.second, to.first, to.second, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(to.first, to.second, 5f, paint)
                paint.style = Paint.Style.STROKE
            }
            for (square in board.indices) {
                val piece = board[square]
                if (piece == '.') continue
                val point = point(square, left, top, cell)
                paint.style = Paint.Style.FILL
                paint.color = 0xfff8edd1.toInt()
                canvas.drawCircle(point.first, point.second, cell * .36f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = if (piece.isUpperCase()) Color.RED else 0xff202020.toInt()
                canvas.drawCircle(point.first, point.second, cell * .36f, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = cell * .43f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(label(piece), point.first, point.second + cell * .15f, paint)
            }
        }

        private fun point(square: Int, left: Float, top: Float, cell: Float): Pair<Float, Float> {
            val file = square % 9
            val rank = 9 - square / 9
            return left + file * cell to top + rank * cell
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
    }
}
