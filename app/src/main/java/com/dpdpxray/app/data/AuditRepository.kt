package com.dpdpxray.app.data

import android.content.Context
import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.ScoreBand
import com.dpdpxray.core.report.AuditJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class AuditMeta(
    val id: String,
    val targetPackage: String,
    val targetLabel: String,
    val createdAtMs: Long,
    val score: Int?,
    val band: ScoreBand,
    val findingCount: Int,
    val plans: List<String>,
    val sample: Boolean = false,
)

data class AuditBundle(val meta: AuditMeta, val sessions: List<AuditSession>, val result: AuditResult, val dir: File)

/** Stores each audit as plain JSON + Markdown under filesDir/audits/<id>/ (nothing leaves the device). */
class AuditRepository(private val context: Context) {
    private val root = File(context.filesDir, "audits").apply { mkdirs() }

    private val _audits = MutableStateFlow<List<AuditMeta>>(emptyList())
    val audits: StateFlow<List<AuditMeta>> = _audits.asStateFlow()

    fun newAuditDir(id: String): File = File(root, id).apply { mkdirs() }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _audits.value = root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { dir -> runCatching { AuditJson.json.decodeFromString<AuditMeta>(File(dir, META).readText()) }.getOrNull() }
            .sortedByDescending { it.createdAtMs }
    }

    suspend fun save(meta: AuditMeta, sessions: List<AuditSession>, result: AuditResult, markdown: String) = withContext(Dispatchers.IO) {
        val dir = newAuditDir(meta.id)
        File(dir, SESSIONS).writeText(AuditJson.encodeSessions(sessions))
        File(dir, RESULT).writeText(AuditJson.encodeResult(result))
        File(dir, REPORT_MD).writeText(markdown)
        File(dir, META).writeText(AuditJson.json.encodeToString(AuditMeta.serializer(), meta))
        refresh()
    }

    suspend fun load(id: String): AuditBundle? = withContext(Dispatchers.IO) {
        val dir = File(root, id)
        runCatching {
            AuditBundle(
                meta = AuditJson.json.decodeFromString(AuditMeta.serializer(), File(dir, META).readText()),
                sessions = AuditJson.decodeSessions(File(dir, SESSIONS).readText()),
                result = AuditJson.json.decodeFromString(AuditResult.serializer(), File(dir, RESULT).readText()),
                dir = dir,
            )
        }.getOrNull()
    }

    fun markdownFile(id: String) = File(File(root, id), REPORT_MD)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        refresh()
    }

    companion object {
        private const val META = "meta.json"
        private const val SESSIONS = "sessions.json"
        private const val RESULT = "result.json"
        const val REPORT_MD = "report.md"
    }
}
