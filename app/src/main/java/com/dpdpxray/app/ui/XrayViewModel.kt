package com.dpdpxray.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dpdpxray.app.ai.BackendKind
import com.dpdpxray.app.ai.BenchResult
import com.dpdpxray.app.ai.LlmStatus
import com.dpdpxray.app.audit.AuditConfig
import com.dpdpxray.app.capture.CaptureBus
import com.dpdpxray.app.consent.ConsentAccessibilityService
import com.dpdpxray.app.consent.ConsentBus
import com.dpdpxray.app.control.AuditableApp
import com.dpdpxray.app.control.DeviceChecks
import com.dpdpxray.app.control.Preflight
import com.dpdpxray.app.control.ShizukuBridge
import com.dpdpxray.app.data.AuditBundle
import com.dpdpxray.app.data.AuditMeta
import com.dpdpxray.app.data.SampleAudit
import com.dpdpxray.app.graph
import com.dpdpxray.app.report.ExportManager
import com.dpdpxray.app.report.PdfExporter
import com.dpdpxray.core.audit.PhaseAssigner
import com.dpdpxray.core.dpdp.Passage
import com.dpdpxray.core.fixes.FixTemplate
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.report.MarkdownReport
import com.dpdpxray.core.rules.RulesEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class XrayViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    val graph = app.graph

    val audits: StateFlow<List<AuditMeta>> = graph.repository.audits
    val progress = graph.orchestrator.progress
    val llmStatus: StateFlow<LlmStatus> = graph.llm.status
    val settings = graph.settings.settings
    val liveNet = CaptureBus.events
    val liveConsent = ConsentBus.events

    private val _preflight = MutableStateFlow(Preflight(false, false, false, false, false, false, false))
    val preflight = _preflight.asStateFlow()

    private val _apps = MutableStateFlow<List<AuditableApp>>(emptyList())
    val apps = _apps.asStateFlow()

    private val _bundle = MutableStateFlow<AuditBundle?>(null)
    val bundle = _bundle.asStateFlow()

    private val _explanations = MutableStateFlow<Map<String, String>>(emptyMap())
    val explanations = _explanations.asStateFlow()
    private val _explaining = MutableStateFlow<String?>(null)
    val explaining = _explaining.asStateFlow()

    private val _bench = MutableStateFlow<List<BenchResult>>(emptyList())
    val bench = _bench.asStateFlow()
    private val _benchRunning = MutableStateFlow<BackendKind?>(null)
    val benchRunning = _benchRunning.asStateFlow()

    private val _cameraResult = MutableStateFlow<CameraCheckState>(CameraCheckState.Idle)
    val cameraResult = _cameraResult.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast = _toast.asStateFlow()
    fun toastShown() { _toast.value = null }

    fun refreshPreflight() {
        graph.llm.refreshAvailability()
        _preflight.value = Preflight(
            vpnReady = DeviceChecks.vpnReady(ctx),
            accessibilityOn = DeviceChecks.accessibilityOn(ctx),
            shizukuRunning = ShizukuBridge.isRunning(),
            shizukuGranted = ShizukuBridge.hasPermission(),
            privateDnsActive = DeviceChecks.privateDnsActive(ctx),
            modelFound = llmStatus.value !is LlmStatus.NoModel,
            modelLoaded = graph.llm.isReady,
        )
    }

    fun requestShizuku() = ShizukuBridge.requestPermission()

    fun loadApps() = viewModelScope.launch {
        _apps.value = withContext(Dispatchers.IO) { DeviceChecks.launchableApps(ctx) }
    }

    fun startAudit(config: AuditConfig) = graph.orchestrator.start(config)
    fun cancelAudit() = graph.orchestrator.cancel()
    fun acknowledgeProgress() = graph.orchestrator.acknowledge()

    fun markConsentNow() = viewModelScope.launch {
        val ok = ConsentAccessibilityService.instance?.markNow() == true
        _toast.value = if (ok) "Consent moment marked" else "Turn on the X-Ray accessibility service first"
    }

    /** Builds the sample audit through the same rules/report pipeline as a live one. */
    fun openSample(onReady: (String) -> Unit) = viewModelScope.launch(Dispatchers.Default) {
        val id = "sample-${System.currentTimeMillis()}"
        val sessions = SampleAudit.sessions(System.currentTimeMillis())
        val catalog = graph.catalog()
        val result = RulesEngine(catalog).evaluate(sessions)
        val fixes = graph.fixCatalog.fixesFor(result, catalog)
        val md = MarkdownReport.build(result, sessions, fixes, graph.citations())
        graph.repository.save(
            AuditMeta(id, sessions.first().targetPackage, sessions.first().targetLabel, System.currentTimeMillis(), result.score, result.band,
                result.findings.size, sessions.map { it.plan.label }, sample = true),
            sessions, result, md,
        )
        withContext(Dispatchers.Main) { onReady(id) }
    }

    fun open(id: String) = viewModelScope.launch {
        if (_bundle.value?.meta?.id != id) _bundle.value = graph.repository.load(id)
    }

    fun delete(id: String) = viewModelScope.launch { graph.repository.delete(id) }

    fun fixesFor(bundle: AuditBundle): List<FixTemplate> = graph.fixCatalog.fixesFor(bundle.result, graph.catalog())

    private val citationCache = java.util.concurrent.ConcurrentHashMap<String, List<Passage>>()

    /** Legal citations may run the on-device embedding model, so never call this on the main thread. */
    suspend fun citationsFor(f: Finding): List<Passage> = citationCache[f.checkId.name + f.explanation.hashCode()]
        ?: withContext(Dispatchers.Default) { graph.citations().cite(f, 3) }.also { citationCache[f.checkId.name + f.explanation.hashCode()] = it }

    /** Network events of a session, classified and placed before/after the consent choice, for the timeline. */
    fun timeline(session: AuditSession): List<NetEvent> {
        val catalog = graph.catalog()
        return PhaseAssigner.assign(session.netEvents, session.consentEvents).map { e ->
            if (e.entity != null) e else catalog.classify(e.hostname).let { e.copy(entity = it.entity, category = it.category) }
        }
    }

    fun explain(finding: Finding, hindi: Boolean) = viewModelScope.launch {
        val key = "${finding.checkId}-${if (hindi) "hi" else "en"}"
        if (!graph.llm.isReady) {
            _explaining.value = key
            if (!graph.llm.load(BackendKind.GPU)) {
                _explaining.value = null
                _toast.value = "On-device model not available — see AI lab"
                return@launch
            }
        }
        _explaining.value = key
        val text = graph.explainer.explain(finding, citationsFor(finding), hindi)
        _explanations.update { it + (key to (text ?: "The model returned no text. Try again.")) }
        _explaining.value = null
    }

    fun markFirstParty(host: String) {
        val domain = host.split('.').takeLast(2).joinToString(".")
        graph.settings.update { it.copy(firstPartyDomains = it.firstPartyDomains + domain) }
        _toast.value = "$domain marked as your own domain"
    }

    fun loadModel(kind: BackendKind) = viewModelScope.launch {
        graph.llm.load(kind)
        refreshPreflight()
    }

    fun unloadModel() = viewModelScope.launch { graph.llm.unload(); refreshPreflight() }

    fun runBench(kind: BackendKind) = viewModelScope.launch {
        _benchRunning.value = kind
        val r = graph.llm.benchmark(kind)
        _bench.update { list -> list.filterNot { it.backend == kind } + r }
        _benchRunning.value = null
        refreshPreflight()
    }

    fun thermalHeadroom() = graph.llm.thermalHeadroom()

    fun cameraCheck(bitmap: Bitmap) = viewModelScope.launch {
        _cameraResult.value = CameraCheckState.Working
        if (!graph.llm.isReady && !graph.llm.load(BackendKind.GPU)) {
            _cameraResult.value = CameraCheckState.Error("The on-device model is not loaded. Push a Gemma model and open AI lab.")
            return@launch
        }
        val file = File(ctx.cacheDir, "camera_check.jpg")
        withContext(Dispatchers.IO) { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } }
        val audit = graph.llm.auditConsentScreen(file, "(photo of a screen; no accessibility tree)")
        _cameraResult.value = audit?.let { CameraCheckState.Done(it, file.absolutePath) }
            ?: CameraCheckState.Error("Could not read a consent screen in that photo. Hold the phone closer and keep the whole dialog in frame.")
    }

    fun resetCamera() { _cameraResult.value = CameraCheckState.Idle }

    fun exportPdf(bundle: AuditBundle, share: Boolean) = viewModelScope.launch {
        val md = withContext(Dispatchers.IO) { graph.repository.markdownFile(bundle.meta.id).readText() }
        val pdf = withContext(Dispatchers.IO) { PdfExporter.render(md, File(bundle.dir, "DPDP-XRay-${bundle.meta.targetLabel.filter { it.isLetterOrDigit() }}.pdf")) }
        if (share) ExportManager.share(ctx, pdf, "application/pdf")
        else {
            val ok = withContext(Dispatchers.IO) { ExportManager.saveToDownloads(ctx, pdf, pdf.name, "application/pdf") }
            _toast.value = if (ok) "Saved to Downloads/DPDP-XRay" else "Could not save the PDF"
        }
    }

    fun saveMarkdown(bundle: AuditBundle) = viewModelScope.launch {
        val f = graph.repository.markdownFile(bundle.meta.id)
        val ok = withContext(Dispatchers.IO) { ExportManager.saveToDownloads(ctx, f, "DPDP-XRay-${bundle.meta.id}.md", "text/markdown") }
        _toast.value = if (ok) "Markdown saved to Downloads/DPDP-XRay" else "Could not save the report"
    }

    fun markdown(bundle: AuditBundle): String = graph.repository.markdownFile(bundle.meta.id).takeIf { it.exists() }?.readText().orEmpty()

    fun copy(label: String, text: String) {
        ExportManager.copy(ctx, label, text)
        _toast.value = "Copied to the clipboard"
    }

    fun updateSettings(transform: (com.dpdpxray.app.data.XraySettings) -> com.dpdpxray.app.data.XraySettings) = graph.settings.update(transform)
}

sealed interface CameraCheckState {
    data object Idle : CameraCheckState
    data object Working : CameraCheckState
    data class Done(val audit: ScreenAudit, val imagePath: String) : CameraCheckState
    data class Error(val message: String) : CameraCheckState
}
