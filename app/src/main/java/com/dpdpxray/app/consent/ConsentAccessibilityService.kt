package com.dpdpxray.app.consent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dpdpxray.core.consent.ConsentLexicon
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.UiNode
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Watches ONLY the package currently armed by an audit. Detects the consent screen (tree + screenshot), timestamps the
 * user's Accept/Reject tap, and can perform those taps itself for automated audit plans.
 */
class ConsentAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val check = Runnable { checkScreen() }
    private var consentVisible = false

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacks(check)
        io.shutdown()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val armed = ConsentBus.armedPackage.value ?: run { consentVisible = false; return }
        if (event.packageName?.toString() != armed) return
        ConsentBus.markTargetSeen()
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> onClick(event)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Trailing debounce: always re-check once things settle, so a dialog that appears a moment after the
                // activity window is never missed.
                main.removeCallbacks(check)
                main.postDelayed(check, SETTLE_MS)
            }
        }
    }

    /** The armed app's own window (never X-Ray's), falling back to the active window. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        val armed = ConsentBus.armedPackage.value ?: return null
        val fromWindows = runCatching {
            windows.mapNotNull { it.root }.lastOrNull { it.packageName?.toString() == armed }
        }.getOrNull()
        return fromWindows ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == armed }
    }

    private fun onClick(event: AccessibilityEvent) {
        val label = event.text?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: NodeMapper.labelOf(event.source)
        val kind = ConsentLexicon.classifyButton(label) ?: return
        if (!ConsentBus.screenSeen) {
            val tree = NodeMapper.map(targetRoot()) ?: return
            if (!ConsentLexicon.isConsentScreen(tree.flatten().map { it.label })) return
            recordScreen(tree)
        }
        ConsentBus.emit(ConsentEvent(toElapsedNanos(event.eventTime), kind, label))
    }

    private fun checkScreen() {
        val tree = NodeMapper.map(targetRoot()) ?: return
        val isConsent = ConsentLexicon.isConsentScreen(tree.flatten().map { it.label })
        if (isConsent && !ConsentBus.screenSeen) recordScreen(tree)
        if (consentVisible && !isConsent) onConsentGone()
        consentVisible = isConsent
    }

    /** Manual mode on toolkits that don't report taps: the dialog went away, so the user made the choice we asked for. */
    private fun onConsentGone() {
        val expected = ConsentBus.expectedChoice ?: return
        if (ConsentBus.choiceMade) return
        ConsentBus.emit(ConsentEvent(SystemClock.elapsedRealtimeNanos(), expected, "(your tap, inferred when the dialog closed)"))
    }

    private fun recordScreen(tree: UiNode) {
        val t = SystemClock.elapsedRealtimeNanos()
        val dir = ConsentBus.shotDir
        if (dir == null) {
            ConsentBus.emit(ConsentEvent(t, ConsentKind.SCREEN_APPEARED, tree = tree))
            return
        }
        val file = File(dir, "consent_${t}.png")
        capture(file) { ok ->
            ConsentBus.emit(ConsentEvent(t, ConsentKind.SCREEN_APPEARED, screenshotPath = file.absolutePath.takeIf { ok }, tree = tree))
        }
    }

    /** Screenshot on the accessibility framework, encoded off the main thread; the buffer is always released. */
    private fun capture(file: File, done: (Boolean) -> Unit) {
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, io,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val ok = try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            FileOutputStream(file).use { out -> bmp?.compress(Bitmap.CompressFormat.JPEG, 88, out) }
                            bmp != null
                        } catch (_: Exception) {
                            false
                        } finally {
                            result.hardwareBuffer.close()
                        }
                        done(ok)
                    }

                    override fun onFailure(errorCode: Int) = done(false)
                },
            )
        } catch (_: Exception) {
            done(false)
        }
    }

    /** "Mark consent now": snapshot the audited app's window (not X-Ray's own screen). */
    suspend fun markNow(): Boolean {
        val tree = NodeMapper.map(targetRoot()) ?: return false
        val t = SystemClock.elapsedRealtimeNanos()
        val dir = ConsentBus.shotDir ?: return false
        val file = File(dir, "manual_${t}.jpg")
        val ok = suspendCancellableCoroutine { cont -> capture(file) { cont.resume(it) } }
        ConsentBus.emit(ConsentEvent(t, ConsentKind.MANUAL_MARK, screenshotPath = file.absolutePath.takeIf { ok }, tree = tree))
        return true
    }

    /**
     * Taps the on-screen button of [kind] (ACCEPT/REJECT/SETTINGS). Returns the label tapped, or null.
     * The event is recorded here so automated runs are timestamped even if the app sends no click event.
     */
    fun tap(kind: ConsentKind): String? {
        val root = targetRoot() ?: return null
        val target = findButton(root, kind) ?: return null
        val label = NodeMapper.labelOf(target)
        var node: AccessibilityNodeInfo? = target
        while (node != null && !node.isClickable) node = node.parent
        val t = SystemClock.elapsedRealtimeNanos()
        val ok = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (ok && (kind == ConsentKind.ACCEPT || kind == ConsentKind.REJECT)) ConsentBus.emit(ConsentEvent(t, kind, label))
        return label.takeIf { ok }
    }

    private fun findButton(n: AccessibilityNodeInfo, kind: ConsentKind): AccessibilityNodeInfo? {
        val label = listOfNotNull(n.text, n.contentDescription).joinToString(" ")
        if (label.isNotBlank() && ConsentLexicon.classifyButton(label) == kind && (n.isClickable || n.parent?.isClickable == true)) return n
        for (i in 0 until n.childCount) {
            val hit = n.getChild(i)?.let { findButton(it, kind) }
            if (hit != null) return hit
        }
        return null
    }

    /** AccessibilityEvent times are uptime-based; convert to the elapsed-realtime clock the VPN uses. */
    private fun toElapsedNanos(eventUptimeMs: Long): Long {
        val ageMs = (SystemClock.uptimeMillis() - eventUptimeMs).coerceAtLeast(0)
        return SystemClock.elapsedRealtimeNanos() - ageMs * 1_000_000L
    }

    companion object {
        private const val SETTLE_MS = 250L

        @Volatile
        var instance: ConsentAccessibilityService? = null
            private set
    }
}
