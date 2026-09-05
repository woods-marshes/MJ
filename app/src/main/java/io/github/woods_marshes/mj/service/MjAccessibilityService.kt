package io.github.woods_marshes.mj.service

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.woods_marshes.mj.utils.SimpleLog
import io.github.woods_marshes.mj.view.MjAnimationView

/**
 * 监听所有应用的输入与点击事件：输入框内容为 "mj" 且发生"发送"动作时，
 * 通过 WindowManager 挂一个 TYPE_ACCESSIBILITY_OVERLAY 全屏透明悬浮窗
 * 播放蜘蛛侠动画（该窗口类型无需"显示在其他应用上层"权限）。
 *
 * 发送动作的识别分三层，以兼容不同 UI 框架：
 * 1. 点击事件直击：View 体系（微信等）点击"发送"按钮必派发 TYPE_VIEW_CLICKED；
 * 2. 行为特征兜底：Compose 触摸点击不派发点击事件，但"发送后输入框被清空"
 *    这一行为全框架一致，以"mj 被一步清空/露出 hint"作为发送信号；
 * 2b. 窗口变化兜底：B 站评论弹窗、知乎/QQ 搜索等场景发送后输入框直接销毁，
 *    不产生任何文本事件，以"武装包名内持有焦点的 mj 输入框消失"作为发送信号。
 *
 * 所有触发路径都要求当前焦点窗口包名与武装包名一致：切走应用、按 HOME、
 * 下拉通知栏等只保持武装并记录，不触发也不解除（回来还能继续发）；
 * 武装状态 15 秒后自动过期，防止陈旧标记延迟误触发。
 */
class MjAccessibilityService : AccessibilityService() {

    private val TAG = this::class.simpleName

    private val windowManager: WindowManager
        get() = getSystemService(WINDOW_SERVICE) as WindowManager

    private var mjArmed = false
    private var mjArmedAtMs = 0L
    private var armedPackage: String? = null
    private var leftArmedPackage = false
    private var nextAnimationIndex = 0
    private var animationView: MjAnimationView? = null

    private var searchVisited = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingWindowCheck: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        SimpleLog.d(TAG, "Mj accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleViewClicked(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged()
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val text = event.text.joinToString(separator = "").trim()
        val lower = text.lowercase()

        if (lower == TRIGGER_TEXT) {
            mjArmed = true
            mjArmedAtMs = SystemClock.elapsedRealtime()
            leftArmedPackage = false
            // Compose 的文本事件可能不带 packageName，兜底取当前焦点窗口的包名
            armedPackage = event.packageName?.toString()
                ?: rootInActiveWindow?.packageName?.toString()
            SimpleLog.d(TAG, "Armed (package=$armedPackage)")
            return
        }
        if (!mjArmed) return
        if (armExpired()) return
        // 其他应用的输入事件不影响武装标记（武装只属于输入 mj 的那个输入框）
        if (!pkgMatchesArmed()) return

        when {
            // 层2：发送后输入框被一步清空（Compose 等框架清空事件的文本可能携带 hint）
            text.isEmpty() -> inferSendFromText("cleared")
            // 逐字删除中（"m"）：不算发送
            TRIGGER_TEXT.startsWith(lower) -> {
                mjArmed = false
                SimpleLog.d(TAG, "Disarmed by deleting")
            }
            // 在 mj 基础上继续输入（"mjm"）：不算发送
            lower.startsWith(TRIGGER_TEXT) -> {
                mjArmed = false
                SimpleLog.d(TAG, "Disarmed by typing on")
            }
            // 内容一步跳到无关文本：可能是发送后清空露出 hint，也可能是拼音上屏/粘贴
            else -> handleJump(event, text)
        }
    }

    private fun handleJump(event: AccessibilityEvent, newText: String) {
        val source = event.source ?: run {
            inferSendFromText("jump to \"$newText\" (no source)")
            return
        }
        val nodeText = source.text?.toString()?.trim().orEmpty()
        if (nodeText.isEmpty()) {
            inferSendFromText("cleared")
            return
        }
        val hint = if (Build.VERSION.SDK_INT >= 26) {
            source.hintText?.toString()?.trim().orEmpty()
        } else ""
        if (hint.isNotEmpty() && nodeText == hint) {
            inferSendFromText("cleared to hint")
            return
        }
        // 输入框里变成了实际内容（拼音上屏、粘贴等），不算发送
        mjArmed = false
        SimpleLog.d(TAG, "Disarmed by text replace: \"$nodeText\"")
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        if (!mjArmed) return
        if (armExpired()) return
        if (!pkgMatchesArmed()) return // 别的应用的点击，与武装标记无关
        val isSendAction = looksLikeSendAction(event)
        SimpleLog.d(TAG, "Clicked, mjArmed=$mjArmed, isSendAction=$isSendAction")
        if (!isSendAction) return
        consumeArm()
        showAnimation()
    }

    /**
     * 层2b：武装状态下发生窗口级变化（弹窗关闭、搜索页跳转、键盘收起等）。
     * 焦点窗口切到其他包名 = 用户切走了：保持武装但不触发，同时置离开标记——
     * 此后"输入框消失"不再推断为发送（抖音等应用切走时会自行销毁输入框），
     * 重新武装（重输 mj）或触发后才恢复；
     * 未离开时：持有焦点的 "mj" 输入框还在 = 打字噪声，忽略；
     * 该输入框消失或失焦 = 输入场景在应用内结束（发送/跳转），触发。
     */
    private fun handleWindowStateChanged() {
        if (!mjArmed) return
        if (armExpired()) return
        val active = rootInActiveWindow ?: return // 拿不到焦点窗口时保持武装，等下一个事件
        if (active.packageName?.toString() != armedPackage) {
            if (!leftArmedPackage) {
                leftArmedPackage = true
                SimpleLog.d(TAG, "Left armed app, box-gone inference disabled until re-arm")
            }
            return
        }
        if (leftArmedPackage) return // 离开过：输入框消失不再推断为发送
        scheduleWindowCheck()
    }

    /** 合并连续窗口事件，延迟 [WINDOW_CHECK_DELAY_MS] 后做一次最终判决。 */
    private fun scheduleWindowCheck() {
        pendingWindowCheck?.let { mainHandler.removeCallbacks(it) }
        val check = Runnable {
            pendingWindowCheck = null
            if (!mjArmed) return@Runnable
            if (armExpired()) return@Runnable
            val root = rootInActiveWindow ?: return@Runnable // 保持武装，等下一个事件
            if (root.packageName?.toString() != armedPackage) return@Runnable // 已切走，保持武装
            if (armedEditableStillFocused(root)) {
                SimpleLog.d(TAG, "Window settled but focused input still holds \"$TRIGGER_TEXT\", ignore")
                return@Runnable
            }
            consumeArm()
            SimpleLog.d(TAG, "Send inferred (window settled, armed input gone)")
            showAnimation()
        }
        pendingWindowCheck = check
        mainHandler.postDelayed(check, WINDOW_CHECK_DELAY_MS)
    }

    /** 在焦点窗口树里查找持有焦点、内容为 "mj" 的可编辑框是否还存在。 */
    private fun armedEditableStillFocused(root: AccessibilityNodeInfo): Boolean {
        searchVisited = 0
        return findEditableWithTriggerText(root, 0)
    }

    private fun findEditableWithTriggerText(node: AccessibilityNodeInfo, depth: Int): Boolean {
        searchVisited++
        if (searchVisited > MAX_SEARCH_NODES * 15) return false
        if (node.isFocused && node.isEditable &&
            node.text?.toString()?.trim()?.equals(TRIGGER_TEXT, ignoreCase = true) == true
        ) {
            return true
        }
        if (depth >= MAX_SEARCH_DEPTH) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findEditableWithTriggerText(child, depth + 1)) return true
        }
        return false
    }

    private fun inferSendFromText(reason: String) {
        consumeArm()
        SimpleLog.d(TAG, "Send inferred ($reason), sendCandidateVisible=${sendCandidateVisible()}")
        showAnimation()
    }

    private fun consumeArm() {
        mjArmed = false
        armedPackage = null
        leftArmedPackage = false
    }

    private fun armExpired(): Boolean {
        if (SystemClock.elapsedRealtime() - mjArmedAtMs <= ARMED_TIMEOUT_MS) return false
        mjArmed = false
        armedPackage = null
        leftArmedPackage = false
        SimpleLog.d(TAG, "Arm expired")
        return true
    }

    /** 当前焦点窗口包名是否与武装包名一致。 */
    private fun pkgMatchesArmed(): Boolean {
        val armed = armedPackage ?: return false
        val current = rootInActiveWindow?.packageName?.toString() ?: return false
        return current == armed
    }

    /**
     * 判断被点击的节点是否是发送动作。发送按钮形态因应用而异：
     * 文本/描述直接是"发送"/"Send"（微信），或描述在子节点上、节点 id 带 send
     * （Google Messages 的 Compose:Draft:Send），所以对事件文本与节点子树做有界搜索。
     */
    private fun looksLikeSendAction(event: AccessibilityEvent): Boolean {
        if (hasSendToken(event.text.joinToString(" "))) return true
        val source = event.source ?: return false
        var queue = listOf(source)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES) {
            val next = mutableListOf<AccessibilityNodeInfo>()
            for (node in queue) {
                visited++
                if (hasSendToken(describe(node))) return true
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { next.add(it) }
                }
            }
            queue = next
        }
        return false
    }

    /** 降噪参考：当前窗口树里是否存在 send 候选节点（有界搜索）。 */
    private fun sendCandidateVisible(): Boolean {
        val root = rootInActiveWindow ?: return false
        var queue = listOf(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_SEARCH_NODES * 3) {
            val next = mutableListOf<AccessibilityNodeInfo>()
            for (node in queue) {
                visited++
                if (hasSendToken(describe(node))) return true
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { next.add(it) }
                }
            }
            queue = next
        }
        return false
    }

    private fun describe(node: AccessibilityNodeInfo): String = buildString {
        node.text?.let { append(it).append(' ') }
        node.contentDescription?.let { append(it).append(' ') }
        node.viewIdResourceName?.let { append(it) }
    }

    private fun hasSendToken(label: String): Boolean =
        label.contains("发送") || SEND_TOKEN.containsMatchIn(label)

    private fun showAnimation() {
        if (animationView != null) return // 播放中，忽略重复触发
        val asset = if (nextAnimationIndex % 2 == 0) MjAnimationView.ASSET_DROP else MjAnimationView.ASSET_SWING
        nextAnimationIndex++
        SimpleLog.d(TAG, "Trigger animation: $asset")

        val soundEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_SOUND_ENABLED, true)
        val view = MjAnimationView(
            this,
            animationAsset = asset,
            playSound = soundEnabled,
            onFinished = ::hideAnimation
        )
        animationView = view
        try {
            windowManager.addView(view, params())
        } catch (e: Exception) {
            SimpleLog.d(TAG, "Add overlay failed: $e")
            animationView = null
            io.github.woods_marshes.mj.view.ErrorReporter.show(
                this,
                "悬浮窗创建失败: ${e::class.java.simpleName}: ${e.message ?: ""}"
            )
        }
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )

    private fun hideAnimation() {
        animationView?.let { windowManager.removeViewImmediate(it) }
        animationView = null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        hideAnimation()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MjAccessibilityService"
        private const val TRIGGER_TEXT = "mj"
        private const val MAX_SEARCH_NODES = 12
        private const val MAX_SEARCH_DEPTH = 14

        /** 武装状态有效期：超时后视为用户放弃，避免陈旧标记延迟误触发 */
        private const val ARMED_TIMEOUT_MS = 15_000L

        /** 窗口切换瞬态期（焦点/树未就绪）过后再做判决的延迟 */
        private const val WINDOW_CHECK_DELAY_MS = 600L

        const val PREFS_NAME = "settings"
        const val KEY_SOUND_ENABLED = "sound_enabled"
        const val KEY_DISCLAIMER_AGREED = "disclaimer_agreed"

        // "send" 要求词边界（前后不是字母），避免匹配 descend/resend 之类
        private val SEND_TOKEN = Regex(
            "(?<![A-Za-z])send|send(?![A-Za-z])",
            RegexOption.IGNORE_CASE
        )
    }
}
