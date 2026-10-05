package com.dpdpxray.app.ai

import android.content.Context
import com.dpdpxray.core.dpdp.DpdpCorpus
import com.dpdpxray.core.dpdp.Passage
import com.dpdpxray.core.dpdp.PassageRetriever
import com.dpdpxray.core.dpdp.ScoredPassage
import com.dpdpxray.core.model.Finding
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.UiNode
import com.dpdpxray.core.screen.DeterministicScreenAuditor
import com.dpdpxray.core.screen.ScreenAuditMerger
import com.dpdpxray.core.screen.UiTreeFormatter
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import kotlin.math.sqrt

/** Consent-screen review: accessibility-tree facts first, then the on-device VLM, merged with conflicts kept visible. */
class AiScreenAuditor(private val llm: LlmEngine) {
    suspend fun audit(tree: UiNode?, screenshot: File?): ScreenAudit? {
        val det = tree?.let { DeterministicScreenAuditor.audit(it) }
        val vlm = if (llm.isReady) llm.auditConsentScreen(screenshot, tree?.let { UiTreeFormatter.format(it) } ?: "(tree unavailable)") else null
        return when {
            det != null -> ScreenAuditMerger.merge(det, vlm)
            else -> vlm
        }
    }
}

/** Plain-language explanation of a finding, grounded only in the cited passages. */
class FindingExplainer(private val llm: LlmEngine) {
    suspend fun explain(finding: Finding, passages: List<Passage>, hindi: Boolean): String? {
        val law = passages.joinToString("\n") { "[${it.id}] ${it.text}" }
        val system = "You explain privacy-compliance findings to Android developers in India. Use ONLY the facts and law " +
            "text given. Never invent section numbers. Be concise and practical."
        val prompt = buildString {
            appendLine("Finding: ${finding.title}")
            appendLine("Details: ${finding.explanation}")
            appendLine("Evidence: ${finding.evidence.take(4).joinToString("; ")}")
            appendLine("Law:\n$law")
            appendLine()
            if (hindi) appendLine("Write 3 short sentences in simple Hindi: what is wrong, why it matters, and what to change.")
            else appendLine("Write 3 short sentences: what is wrong, why it matters under DPDP, and the first thing to change.")
        }
        return llm.generate(system, prompt)
    }
}

/**
 * Semantic retrieval over the DPDP corpus with an on-device embedding model (e.g. EmbeddingGemma), if one is installed.
 * Falls back to the caller's BM25 retriever when no embedding model is present.
 */
class EmbeddingRetriever private constructor(
    private val engine: EmbeddingEngine,
    private val passages: List<Passage>,
    private val vectors: List<FloatArray>,
) : PassageRetriever {

    /** The native embedding engine is not thread-safe: all calls are serialised. */
    @Synchronized
    override fun search(query: String, k: Int): List<ScoredPassage> {
        val q = embed(engine, query)
        return passages.indices.map { ScoredPassage(passages[it], cosine(q, vectors[it]).toDouble()) }
            .sortedByDescending { it.score }.take(k)
    }

    companion object {
        fun create(context: Context, corpus: DpdpCorpus): EmbeddingRetriever? {
            val file = ModelFiles.embeddingModel(context) ?: return null
            return runCatching {
                val engine = EmbeddingEngine(EmbeddingEngineConfig(modelPath = file.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
                engine.initialize()
                val vectors = corpus.passages.map { embed(engine, "${it.title}. ${it.text}") }
                EmbeddingRetriever(engine, corpus.passages, vectors)
            }.getOrNull()
        }

        private fun embed(engine: EmbeddingEngine, text: String): FloatArray =
            engine.computeEmbedding(listOf(InputData.Text(text)), EmbeddingOptions(normalize = true)).embedding

        private fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0f; var na = 0f; var nb = 0f
            for (i in 0 until minOf(a.size, b.size)) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
            return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
        }
    }
}
