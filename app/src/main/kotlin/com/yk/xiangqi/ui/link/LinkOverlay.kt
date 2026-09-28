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
import com.yk.xiangqi.core.Side
import com.yk.xiangqi.link.BoardGeometry

/**
 * 连线功能的系统悬浮 UI：棋盘框选与运行控制。
 *
 * 它只负责绘制和触摸，具体的录屏、识别、同步由服务通过回调处理。
 */
class LinkOverlay(private val context: Context, private val listener: Listener) {
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
    private var syncSide = Side.RED

    /** 录屏启动后先展示轻量入口；用户切到第三方棋盘后再主动开始框选。 */
    fun showReady() {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
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
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val ai = button(if (aiEnabled) "AI开" else "AI")
        val side = button("红先")
        val sync = button("同步")
        val stop = button("停止")
        ai.setOnClickListener { ai.text = if (listener.onAiToggled()) "AI开" else "AI" }
        side.setOnClickListener {
            syncSide = if (syncSide == Side.RED) Side.BLACK else Side.RED
            side.text = if (syncSide == Side.RED) "红先" else "黑先"
            listener.onNewGameSideChanged(syncSide)
        }
        sync.setOnClickListener { listener.onSynchronize(syncSide) }
        stop.setOnClickListener { listener.onStop() }
        row.addView(ai)
        row.addView(side)
        row.addView(sync)
        row.addView(stop)
        replaceControls(row)
    }

    private fun replaceControls(row: View) {
        remove(controls)
        controls = row
        windows.addView(row, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 160
        })
    }

    fun close() {
        remove(selection)
        remove(controls)
        selection = null
        controls = null
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

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0x22000000)
            paint.color = Color.WHITE
            paint.textSize = 34f
            canvas.drawText(if (ready) "预览网格：底部左取消，右确认" else "拖拽左上、右下交叉点中心", 30f, 60f, paint)
            if (!ready) return
            paint.style = Paint.Style.STROKE
            paint.color = 0xff00e5ff.toInt()
            canvas.drawRect(left, top, right, bottom, paint)
            for (x in 0 until 9)
                for (y in 0 until 10)
                    canvas.drawCircle(left + x * (right - left) / 8f, top + y * (bottom - top) / 9f, 5f, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0xcc222222.toInt()
            canvas.drawRect(0f, height - 110f, width.toFloat(), height.toFloat(), paint)
            paint.color = Color.WHITE
            paint.textSize = 32f
            canvas.drawText("取消", width / 4f - 30f, height - 42f, paint)
            canvas.drawText("确认", width * 3f / 4f - 30f, height - 42f, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!ready) return select(event)
            if (event.action == MotionEvent.ACTION_UP && event.y > height - 120) {
                if (event.x < width / 2f) {
                    removeSelection()
                    listener.onSelectionCancelled()
                } else {
                    removeSelection()
                    listener.onConfigured(BoardGeometry(left, top, right, bottom))
                }
            }
            return true
        }

        private fun select(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    left = event.x; right = event.x
                    top = event.y; bottom = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    right = event.x; bottom = event.y
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (right < left) {
                        val value = left
                        left = right
                        right = value
                    }
                    if (bottom < top) {
                        val value = top
                        top = bottom
                        bottom = value
                    }
                    ready = right - left > 32f && bottom - top > 32f
                    invalidate()
                }
            }
            return true
        }
    }
}
