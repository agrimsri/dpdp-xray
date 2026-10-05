package com.dpdpxray.app.data

import com.dpdpxray.core.model.AuditPlan
import com.dpdpxray.core.model.AuditSession
import com.dpdpxray.core.model.AuditSource
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import com.dpdpxray.core.model.RejectOption
import com.dpdpxray.core.model.ScreenAudit
import com.dpdpxray.core.model.UiNode

/**
 * A recorded-style audit of the LeakyShop demo app, built in code. Used as the "Sample audit" (works with no VPN or
 * model, e.g. for a first look or as a stage fallback). Clearly labelled as a sample in the UI and report.
 */
object SampleAudit {
    private const val S = 1_000_000_000L

    private val tree = UiNode(
        className = "android.widget.FrameLayout",
        children = listOf(
            UiNode(className = "android.widget.TextView", text = "We value your privacy"),
            UiNode(className = "android.widget.TextView", text = "We use your data to personalise offers, measure the app and show relevant ads."),
            UiNode(className = "android.widget.CheckBox", text = "Share usage data to improve offers", isCheckable = true, isChecked = true, isClickable = true),
            UiNode(className = "android.widget.Button", text = "Accept & Continue", isClickable = true),
            UiNode(className = "android.widget.TextView", text = "Manage", isClickable = true),
        ),
    )

    private val screen = ScreenAudit(
        isConsentScreen = true, preTicked = true, rejectOption = RejectOption.HIDDEN, purposesCount = 3,
        separateChoices = false, itemisedData = false, purposeStated = true, withdrawalInfo = false,
        languageOptions = listOf("English"), childAudienceSignals = false,
        darkPatterns = listOf("pre-ticked checkbox", "refusal hidden behind 'Manage'", "bundled purposes"),
        summary = "One 'Accept & Continue' covers offers, analytics and ads; data sharing is pre-ticked; refusing needs 'Manage'.",
        confidence = 0.86, source = AuditSource.MERGED, observedFields = setOf("preTicked", "rejectOption"),
        modelId = "sample", latencyMs = 4200,
    )

    private fun net(t: Double, host: String) = NetEvent(tNanos = (t * S).toLong(), hostname = host)

    fun sessions(nowMs: Long): List<AuditSession> {
        val base = AuditSession(
            id = "", targetPackage = "com.leakyshop.demo", targetLabel = "LeakyShop (sample)", plan = AuditPlan.SILENT,
            startedAtEpochMs = nowMs, deviceModel = "Sample device", androidVersion = "16", privateDnsActive = false,
            notes = listOf("Sample audit generated in-app; run a live audit for real evidence."),
        )
        val consentShown = ConsentEvent((2.1 * S).toLong(), ConsentKind.SCREEN_APPEARED, tree = tree)
        return listOf(
            base.copy(
                id = "sample-c", plan = AuditPlan.SILENT, endedAtNanos = 30 * S,
                netEvents = listOf(
                    net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                    net(0.9, "firebase-settings.crashlytics.com"), net(1.3, "launches.appsflyersdk.com"), net(6.0, "app-measurement.com"),
                ),
                consentEvents = listOf(consentShown), screenAudit = screen,
            ),
            base.copy(
                id = "sample-b", plan = AuditPlan.REJECT, endedAtNanos = 35 * S,
                netEvents = listOf(
                    net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.8, "graph.facebook.com"),
                    net(9.4, "graph.facebook.com"), net(11.2, "app-measurement.com"), net(12.0, "launches.appsflyersdk.com"),
                ),
                consentEvents = listOf(consentShown, ConsentEvent((6.0 * S).toLong(), ConsentKind.SETTINGS, "Manage"), ConsentEvent((8.3 * S).toLong(), ConsentKind.REJECT, "Reject all")),
                screenAudit = screen,
            ),
            base.copy(
                id = "sample-a", plan = AuditPlan.ACCEPT, endedAtNanos = 30 * S,
                netEvents = listOf(
                    net(0.4, "api.leakyshop.example"), net(0.6, "app-measurement.com"), net(0.7, "graph.facebook.com"),
                    net(5.2, "cdn.leakyshop.example"), net(5.6, "googleads.g.doubleclick.net"), net(6.1, "in1.clevertap-prod.com"),
                ),
                consentEvents = listOf(consentShown, ConsentEvent((4.9 * S).toLong(), ConsentKind.ACCEPT, "Accept & Continue")),
                screenAudit = screen,
            ),
        )
    }
}
