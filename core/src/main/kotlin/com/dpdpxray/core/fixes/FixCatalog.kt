package com.dpdpxray.core.fixes

import com.dpdpxray.core.model.AuditResult
import com.dpdpxray.core.model.CheckId
import com.dpdpxray.core.trackers.TrackerCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FixTemplate(
    val id: String,
    /** "sdk" (code/manifest change for a vendor SDK) or "ui" (consent-screen change). */
    val kind: String,
    val title: String,
    val appliesTo: List<CheckId>,
    val manifest: String? = null,
    val code: String? = null,
    val steps: List<String> = emptyList(),
    val docUrl: String? = null,
    /** True when checked against the vendor's official docs; false means "verify for your SDK version". */
    val verified: Boolean = false,
)

/** Picks fix templates for a result: SDK fixes for entities in tracking findings, then UI fixes per screen finding. */
class FixCatalog(private val templates: List<FixTemplate>) {
    private val byId = templates.associateBy { it.id }

    fun fixesFor(result: AuditResult, catalog: TrackerCatalog): List<FixTemplate> {
        val sdkFixes = result.findings
            .filter { f -> SDK_CHECKS.contains(f.checkId) }
            .flatMap { f -> f.entities.mapNotNull { catalog.sdkKeyFor(it) }.map { key -> key to f.checkId } }
            .mapNotNull { (key, check) -> byId[key]?.takeIf { check in it.appliesTo } }
            .distinctBy { it.id }
        val uiFixes = result.findings.flatMap { f ->
            templates.filter { it.kind == "ui" && f.checkId in it.appliesTo }
        }.distinctBy { it.id }
        return sdkFixes + uiFixes
    }

    companion object {
        private val SDK_CHECKS = setOf(CheckId.C1, CheckId.C2, CheckId.C9, CheckId.C10)
        private val json = Json { ignoreUnknownKeys = true }

        fun loadDefault(): FixCatalog {
            val text = FixCatalog::class.java.getResourceAsStream("/fixes/fix_templates.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("fix_templates.json missing from resources")
            return FixCatalog(json.decodeFromString(text))
        }
    }
}
