package com.dpdpxray.core.trackers

import com.dpdpxray.core.model.Category
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TrackerEntry(
    val entity: String,
    val category: Category,
    val domains: List<String>,
    /** Links the entity to a FixCatalog template. */
    val sdkKey: String? = null,
    /** Per vendor documentation, the SDK reads a device/advertising identifier when it initialises. */
    val collectsAdIdAtInit: Boolean = false,
)

data class TrackerInfo(
    val entity: String?,
    val category: Category,
    val sdkKey: String? = null,
    val collectsAdIdAtInit: Boolean = false,
)

/** Suffix-matching hostname classifier. Longest matching domain wins; user first-party marks win over everything. */
class TrackerCatalog(
    private val entries: List<TrackerEntry>,
    private val firstParty: Set<String> = emptySet(),
) {
    private val byDomain: Map<String, TrackerEntry> =
        entries.flatMap { e -> e.domains.map { it.lowercase().trimEnd('.') to e } }.toMap()

    val size: Int get() = byDomain.size

    fun classify(hostname: String): TrackerInfo {
        val host = hostname.lowercase().trimEnd('.')
        if (suffixes(host).any { it in firstParty }) return TrackerInfo(null, Category.FIRST_PARTY)
        val entry = suffixes(host).firstNotNullOfOrNull { byDomain[it] }
            ?: return TrackerInfo(null, Category.UNKNOWN)
        return TrackerInfo(entry.entity, entry.category, entry.sdkKey, entry.collectsAdIdAtInit)
    }

    fun sdkKeyFor(entity: String): String? = entries.firstOrNull { it.entity == entity }?.sdkKey

    fun withFirstParty(domains: Set<String>): TrackerCatalog =
        TrackerCatalog(entries, firstParty + domains.map { it.lowercase().trimEnd('.') })

    /** "a.b.c.com" → ["a.b.c.com", "b.c.com", "c.com", "com"] (longest first). */
    private fun suffixes(host: String): Sequence<String> = sequence {
        var rest = host
        while (rest.isNotEmpty()) {
            yield(rest)
            val dot = rest.indexOf('.')
            if (dot < 0) break
            rest = rest.substring(dot + 1)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): TrackerCatalog = TrackerCatalog(json.decodeFromString<List<TrackerEntry>>(text))

        fun loadDefault(): TrackerCatalog {
            val text = TrackerCatalog::class.java.getResourceAsStream("/trackers/trackers.json")
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: error("trackers.json missing from resources")
            return fromJson(text)
        }
    }
}
