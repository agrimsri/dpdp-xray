package com.dpdpxray.core.trackers

import com.dpdpxray.core.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerCatalogTest {
    private val catalog = TrackerCatalog(
        listOf(
            TrackerEntry("Meta", Category.SOCIAL, listOf("facebook.com")),
            TrackerEntry("Meta Audience Network", Category.ADVERTISING, listOf("an.facebook.com")),
            TrackerEntry("Google Analytics for Firebase", Category.ANALYTICS, listOf("app-measurement.com"), sdkKey = "firebase-analytics"),
        ),
    )

    @Test
    fun `matches a subdomain by suffix`() {
        val info = catalog.classify("region1.app-measurement.com")
        assertEquals("Google Analytics for Firebase", info.entity)
        assertEquals(Category.ANALYTICS, info.category)
        assertEquals("firebase-analytics", info.sdkKey)
    }

    @Test
    fun `longest matching suffix wins`() {
        assertEquals(Category.ADVERTISING, catalog.classify("an.facebook.com").category)
        assertEquals(Category.SOCIAL, catalog.classify("graph.facebook.com").category)
    }

    @Test
    fun `does not match on a partial label`() {
        val info = catalog.classify("notfacebook.com")
        assertEquals(Category.UNKNOWN, info.category)
        assertNull(info.entity)
    }

    @Test
    fun `ignores case and a trailing dot`() {
        assertEquals("Meta", catalog.classify("GRAPH.Facebook.com.").entity)
    }

    @Test
    fun `first-party override beats the tracker list`() {
        val custom = catalog.withFirstParty(setOf("facebook.com"))
        assertEquals(Category.FIRST_PARTY, custom.classify("graph.facebook.com").category)
    }

    @Test
    fun `default catalog loads and knows the common SDK hosts`() {
        val default = TrackerCatalog.loadDefault()
        assertEquals(Category.ANALYTICS, default.classify("app-measurement.com").category)
        assertTrue(default.classify("graph.facebook.com").category.isTracking)
        assertEquals(Category.ATTRIBUTION, default.classify("launches.appsflyersdk.com").category)
        assertEquals(Category.CRASH_REPORTING, default.classify("firebase-settings.crashlytics.com").category)
        assertEquals(Category.ENGAGEMENT, default.classify("in1.clevertap-prod.com").category)
        assertTrue("catalog should not be tiny", default.size >= 30)
    }
}
