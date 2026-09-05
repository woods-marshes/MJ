package io.github.woods_marshes.mj.view

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 错误报告弹窗：渲染/触发失败时以系统窗口形式弹出，
 * 支持"复制错误"（含设备信息）与"确认"关闭。
 * release 包日志不可见，这是远程用户反馈问题的主要诊断手段。
 */
object ErrorReporter {

    fun show(context: Context, error: String) {
        val deviceInfo =
            "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val fullText = "错误：$error\n\n设备：$deviceInfo"

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        var containerRef: View? = null
        val removeFromWindow = {
            containerRef?.let { runCatching { wm.removeView(it) } }
            containerRef = null
            Unit
        }
        val onCopy = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MJ error", fullText))
            Toast.makeText(context, "已复制错误信息", Toast.LENGTH_SHORT).show()
            Unit
        }

        val container = buildView(context, fullText, onCopy, removeFromWindow)
        containerRef = container

        // 应用内（Activity）走普通应用窗口；无障碍服务悬浮路径走 a11y overlay 窗口
        val windowType = if (context is Activity) {
            WindowManager.LayoutParams.TYPE_APPLICATION
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            if (context is Activity) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            horizontalMargin = 0.08f
        }
        try {
            wm.addView(container, params)
        } catch (e: Exception) {
            // 窗口弹不出来时兜底 Toast（至少让用户看到一段错误）
            Toast.makeText(context, fullText, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildView(
        context: Context,
        fullText: String,
        onCopy: () -> Unit,
        onConfirm: () -> Unit
    ): View {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d + 0.5f).toInt()

        val title = TextView(context).apply {
            text = "请将错误告诉开发者"
            textSize = 17f
            setTextColor(Color.parseColor("#1B1B1B"))
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        val body = TextView(context).apply {
            text = fullText
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            setTextIsSelectable(true)
        }
        val bodyScroll = ScrollView(context).apply { addView(body) }

        val copyBtn = Button(context).apply {
            text = "复制错误"
            textSize = 13f
            setOnClickListener { onCopy() }
        }
        val confirmBtn = TextView(context).apply {
            text = "确认"
            textSize = 15f
            setTextColor(Color.parseColor("#1565C0"))
            setTypeface(Typeface.DEFAULT_BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { onConfirm() }
        }
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(copyBtn)
            addView(confirmBtn)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
            addView(title)
            addView(View(context).apply { minimumHeight = dp(10) })
            addView(bodyScroll)
            addView(View(context).apply { minimumHeight = dp(6) })
            addView(buttonRow)
        }
    }
}
