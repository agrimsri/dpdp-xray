package com.dpdpxray.core.dpdp

import com.dpdpxray.core.model.Finding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ln

@Serializable
data class Passage(
    val id: String,
    val source: String,
    val title: String,
    val text: String,
    /** True when [text] is the exact statutory wording; false for faithful summaries/reconstructions. */
    val verbatim: Boolean = true,
)

class DpdpCorpus(val passages: List<Passage>) {
    private val byId = passages.associateBy { it.id }
    fun byId(id: String): Passage? = byId[id]

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun loadDefault(): DpdpCorpus {
            val text = DpdpCorpus::class.java.getResourceAsStream("/dpdp/passages.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("passages.json missing from resources")
            return DpdpCorpus(json.decodeFromString(text))
        }
    }
}

data class ScoredPassage(val passage: Passage, val score: Double)

/** Anything that can rank legal passages for a query: BM25 here, EmbeddingGemma on the phone. */
fun interface PassageRetriever {
    fun search(query: String, k: Int): List<ScoredPassage>
}

/** Classic Okapi BM25 over passage title + text. Small, deterministic, and good enough for ~20 legal passages. */
class Bm25Retriever(private val passages: List<Passage>, private val k1: Double = 1.5, private val b: Double = 0.75) : PassageRetriever {
    private val docs = passages.map { tokenize("${it.title} ${it.text}") }
    private val avgLen = docs.map { it.size }.average().takeIf { it > 0 } ?: 1.0
    private val df: Map<String, Int> = docs.flatMap { it.toSet() }.groupingBy { it }.eachCount()

    override fun search(query: String, k: Int): List<ScoredPassage> {
        val q = tokenize(query).toSet()
        val n = docs.size
        return docs.mapIndexed { i, doc ->
            val tf = doc.groupingBy { it }.eachCount()
            val score = q.sumOf { term ->
                val f = tf[term] ?: return@sumOf 0.0
                val idf = ln(1 + (n - (df[term] ?: 0) + 0.5) / ((df[term] ?: 0) + 0.5))
                idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * doc.size / avgLen))
            }
            ScoredPassage(passages[i], score)
        }.filter { it.score > 0 }.sortedByDescending { it.score }.take(k)
    }

    companion object {
        private val STOP = setOf(
            "the", "of", "to", "and", "a", "an", "in", "for", "by", "shall", "be", "such", "her", "any", "or", "as",
            "is", "on", "with", "which", "under", "this", "that", "may", "from", "it", "at", "has", "been", "have", "its",
        )
        fun tokenize(s: String): List<String> =
            s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }
    }
}

/** Finds the legal text behind a finding: its own references first, then the best search matches. */
class CitationService(private val corpus: DpdpCorpus, private val retriever: PassageRetriever = Bm25Retriever(corpus.passages)) {
    fun cite(finding: Finding, k: Int = 3): List<Passage> {
        val own = finding.dpdpRefs.mapNotNull { corpus.byId(it) }
        // A finding's own references are authoritative; padding them with search hits produced irrelevant citations.
        if (own.isNotEmpty()) return own.take(k)
        val extra = retriever.search("${finding.title} ${finding.explanation}", k + own.size)
            .map { it.passage }
            .filter { p -> own.none { it.id == p.id } && p.id != "schedule" }
        return (own + extra).take(k)
    }
}
