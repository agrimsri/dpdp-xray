package com.dpdpxray.app.audit

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.dpdpxray.app.AppGraph
import com.dpdpxray.app.ai.BackendKind
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.capture.CaptureBus
import com.dpdpxray.app.capture.CaptureState
import com.dpdpxray.app.capture.XrayVpnService
import com.dpdpxray.app.consent.ConsentAccessibilityService
import com.dpdpxray.app.consent.ConsentBus
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.control.ShizukuBridge
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class AuditConfig(
    val targetPackage: String,
    val targetLabel: String,
    val plans: List<AuditPlan>,
    val childFacing: Boolean = false,
    val includeGms: Boolean = false,
    val autoDrive: Boolean = true,
)

sealed interface AuditProgress {
    data object Idle : AuditProgress
    data class Running(
        val config: AuditConfig,
        val planIndex: Int,
        val plan: AuditPlan,
        val step: String,
        val sessionStartNanos: Long = 0,
    ) : AuditProgress
    data class Done(val auditId: String) : AuditProgress
    data class Failed(val message: String) : AuditProgress
}

/**
 * Runs audit plans end to end: capture → (load AI) → per plan: reset app → launch → consent detection → AI review →
 * agent or user choice → observe → evaluate → report. Runs in the application scope so it survives the UI going to
 * the background; capture stays up for the whole audit (see [startCapture]).
 */
class AuditOrchestrator(private val context: Context, private val graph: AppGraph) {
    private val _progress = MutableStateFlow<AuditProgress>(AuditProgress.Idle)
    val progress: StateFlow<AuditProgress> = _progress.asStateFlow()
    private var job: Job? = null

    private data class Timing(val waitConsentMs: Long, val dwellMs: Long, val observeMs: Long, val silentMs: Long)

    private fun timing() = if (graph.settings.settings.value.fastDemo) Timing(12_000, 1_500, 8_000, 12_000)
    else Timing(20_000, 2_500, 15_000, 25_000)

    @Synchronized
    fun start(config: AuditConfig) {
        if (_progress.value is AuditProgress.Running) return
        // Set before launching so a double tap cannot start two audits.
        _progress.value = AuditProgress.Running(config, 0, config.plans.first(), "Starting")
        job = graph.scope.launch {
            try {
                run(config)
            } catch (e: CancellationException) {
                _progress.value = AuditProgress.Idle
            } catch (e: Exception) {
                _progress.value = AuditProgress.Failed(e.message ?: e.javaClass.simpleName)
                returnToXray()
            }
        }
    }

    fun cancel() {
        val j = job
        if (j != null && j.isActive) j.cancel() else _progress.value = AuditProgress.Idle
        returnToXray()
    }

    fun acknowledge() {
        if (_progress.value !is AuditProgress.Running) _progress.value = AuditProgress.Idle
    }

    private suspend fun run(config: AuditConfig) {
        val auditId = "audit-${System.currentTimeMillis()}"
        val dir = graph.repository.newAuditDir(auditId)
        val sessions = try {
            // Capture starts while X-Ray is still on screen: Android does not let a background app start a
            // foreground service, and the running capture service keeps X-Ray allowed to work between plans.
            _progress.value = AuditProgress.Running(config, 0, config.plans.first(), "Starting per-app DNS capture")
            startCapture(config)
            if (graph.settings.settings.value.autoLoadModel && graph.llm.status.value is LlmStatus.NotLoaded) {
                _progress.value = AuditProgress.Running(config, 0, config.plans.first(), "Loading on-device AI model")
                graph.llm.load(BackendKind.GPU)
            }
            config.plans.mapIndexed { i, plan -> runPlan(config, plan, i, auditId, dir) }
        } finally {
            runCatching { XrayVpnService.stop(context) }
            ConsentBus.disarm()
        }

        _progress.value = AuditProgress.Running(config, config.plans.lastIndex, config.plans.last(), "Evaluating DPDP checks")
        val catalog = graph.catalog()
        val result = RulesEngine(catalog).evaluate(sessions)
        val fixes = graph.fixCatalog.fixesFor(result, catalog)
        val markdown = MarkdownReport.build(result, sessions, fixes, graph.citations())
        val meta = AuditMeta(
            id = auditId, targetPackage = config.targetPackage, targetLabel = config.targetLabel,
            createdAtMs = System.currentTimeMillis(), score = result.score, band = result.band,
            findingCount = result.findings.size, plans = config.plans.map { it.label },
        )
        graph.repository.save(meta, sessions, result, markdown)
        _progress.value = AuditProgress.Done(auditId)
        returnToXray()
    }

    private suspend fun runPlan(config: AuditConfig, plan: AuditPlan, index: Int, auditId: String, dir: File): AuditSession = coroutineScope {
        val t = timing()
        val pkg = config.targetPackage
        val notes = mutableListOf<String>()
        fun step(s: String, start: Long = 0) {
            _progress.value = AuditProgress.Running(config, index, plan, s, start)
        }

        step("Resetting ${config.targetLabel} to a fresh install")
        if (ShizukuBridge.hasPermission()) {
            ShizukuBridge.forceStop(pkg)
            ShizukuBridge.clearAppData(pkg).onFailure { notes += "Could not clear app data: ${it.message}" }
        } else {
            notes += "App data was not cleared (Shizuku not authorised) — results reflect the app's current state."
        }

        check(CaptureBus.state.value is CaptureState.Running) { (CaptureBus.state.value as? CaptureState.Error)?.message ?: "Capture stopped unexpectedly" }
        CaptureBus.reset()
        ConsentBus.arm(pkg, File(dir, "shots"))
        if (!config.autoDrive && plan != AuditPlan.SILENT) {
            ConsentBus.expectedChoice = if (plan == AuditPlan.ACCEPT) ConsentKind.ACCEPT else ConsentKind.REJECT
        }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val epoch = System.currentTimeMillis()

        step("Launching ${config.targetLabel}", t0)
        check(launchTarget(pkg)) { "Cannot launch $pkg" }
        val onScreen = withTimeoutOrNull(LAUNCH_CHECK_MS) { ConsentBus.targetSeen.first { it } } ?: false
        check(onScreen) {
            "${config.targetLabel} did not come to the screen. Android may have blocked opening it from the background — start Shizuku, or keep X-Ray open and retry."
        }

        step("Waiting for a consent screen", t0)
        val appeared = withTimeoutOrNull(t.waitConsentMs) {
            ConsentBus.events.first { list -> list.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK } }
        }?.firstOrNull { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }

        // A child of this plan: cancelling the audit cancels the review too.
        val review = appeared?.let { ev -> async { graph.screenAuditor.audit(ev.tree, ev.screenshotPath?.let(::File)) } }

        when (plan) {
            AuditPlan.ACCEPT, AuditPlan.REJECT -> {
                val kind = if (plan == AuditPlan.ACCEPT) ConsentKind.ACCEPT else ConsentKind.REJECT
                if (appeared != null && config.autoDrive) {
                    step("Agent is choosing: ${kind.name.lowercase()}", t0)
                    delay(t.dwellMs)
                    driveChoice(kind, notes)
                } else if (appeared != null) {
                    step("Your turn: tap ${if (kind == ConsentKind.ACCEPT) "Accept" else "Reject"} in the app", t0)
                    val chosen = withTimeoutOrNull(45_000) { ConsentBus.events.first { it.any { e -> e.kind == ConsentKind.ACCEPT || e.kind == ConsentKind.REJECT } } }
                    if (chosen == null) notes += "No consent choice was recorded within 45 s."
                }
                step("Observing traffic after the choice", t0)
                delay(t.observeMs)
            }
            AuditPlan.SILENT -> {
                step("Observing without touching the app", t0)
                delay(t.silentMs)
            }
        }

        val tEnd = SystemClock.elapsedRealtimeNanos()
        val captured = CaptureBus.events.value
        val consentEvents = ConsentBus.events.value
        ConsentBus.disarm()
        step("Finishing AI review of the consent screen", t0)
        val screen = review?.let { d -> withTimeoutOrNull(REVIEW_TIMEOUT_MS) { d.await() }.also { if (it == null) d.cancel() } }
        if (appeared == null) notes += "No consent screen detected within ${t.waitConsentMs / 1000} s."
        else if (screen == null) notes += "Consent-screen review unavailable; deterministic checks only."
        if (!graph.llm.isReady && appeared != null) notes += "On-device AI model not loaded — screen review used the accessibility tree only."
        if (consentEvents.any { it.nodeText?.contains("inferred") == true }) notes += "The consent choice was inferred from the dialog closing (this app does not report taps)."

        AuditSession(
            id = "$auditId-${plan.name}",
            targetPackage = pkg,
            targetLabel = config.targetLabel,
            plan = plan,
            childFacing = config.childFacing,
            startedAtNanos = t0,
            endedAtNanos = tEnd,
            startedAtEpochMs = epoch,
            netEvents = captured,
            consentEvents = consentEvents,
            screenAudit = screen,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = Build.VERSION.RELEASE,
            privateDnsActive = DeviceChecks.privateDnsActive(context),
            notes = notes,
        )
    }

    private suspend fun startCapture(config: AuditConfig) {
        CaptureBus.reset()
        runCatching { XrayVpnService.start(context, config.targetPackage, config.includeGms) }
            .onFailure { throw IllegalStateException("Could not start capture: ${it.message}") }
        val state = withTimeoutOrNull(6_000) { CaptureBus.state.first { it is CaptureState.Running || it is CaptureState.Error } }
        if (state !is CaptureState.Running) {
            throw IllegalStateException((state as? CaptureState.Error)?.message ?: "Capture did not start — check VPN permission")
        }
    }

    private fun returnToXray() {
        graph.scope.launch {
            val own = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.flattenToShortString()
            val viaShell = own != null && ShizukuBridge.hasPermission() && ShizukuBridge.exec("am", "start", "-n", own).isSuccess
            if (!viaShell) DeviceChecks.bringXrayToFront(context)
        }
    }

    /** Shizuku's shell can start activities at any time; otherwise rely on the bound accessibility service exemption. */
    private suspend fun launchTarget(pkg: String): Boolean {
        if (ShizukuBridge.hasPermission()) {
            val component = context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            if (component != null && ShizukuBridge.exec("am", "start", "-n", component).isSuccess) return true
        }
        return DeviceChecks.launch(context, pkg)
    }

    /** The agent taps the choice; a refusal hidden behind "Manage" is found by opening Manage first. */
    private suspend fun driveChoice(kind: ConsentKind, notes: MutableList<String>) {
        val svc = ConsentAccessibilityService.instance
        if (svc == null) {
            notes += "Accessibility service not connected — the agent could not tap."
            return
        }
        if (svc.tap(kind) != null) return
        if (kind == ConsentKind.REJECT) {
            if (svc.tap(ConsentKind.SETTINGS) != null) {
                ConsentBus.emit(ConsentEvent(SystemClock.elapsedRealtimeNanos(), ConsentKind.SETTINGS, "Manage"))
                delay(1_500)
                if (svc.tap(ConsentKind.REJECT) != null) {
                    notes += "Refusal was only reachable through 'Manage' (extra step)."
                    return
                }
            }
            notes += "No way to refuse consent was found on screen."
        } else {
            notes += "No Accept button found on the consent screen."
        }
    }

    companion object {
        private const val LAUNCH_CHECK_MS = 8_000L
        private const val REVIEW_TIMEOUT_MS = 90_000L
    }
}
