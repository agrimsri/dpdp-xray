package com.dpdpxray.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dpdpxray.app.audit.AuditProgress
import com.dpdpxray.app.ui.XrayViewModel
import com.dpdpxray.app.ui.components.ConsentLine
import com.dpdpxray.app.ui.components.HostText
import com.dpdpxray.app.ui.components.Note
import com.dpdpxray.app.ui.components.PrimaryAction
import com.dpdpxray.app.ui.components.SecondaryAction
import com.dpdpxray.app.ui.components.StatusDot
import com.dpdpxray.app.ui.theme.Film
import com.dpdpxray.app.ui.theme.categoryColor
import com.dpdpxray.core.model.ConsentEvent
import com.dpdpxray.core.model.ConsentKind
import com.dpdpxray.core.model.NetEvent
import java.util.Locale

private sealed interface Row2 {
    data class Net(val e: NetEvent) : Row2
    data class Choice(val e: ConsentEvent) : Row2
}

@Composable
fun LiveAuditScreen(vm: XrayViewModel, onDone: (String) -> Unit, onBack: () -> Unit) {
    val progress by vm.progress.collectAsState()
    val net by vm.liveNet.collectAsState()
    val consent by vm.liveConsent.collectAsState()

    LaunchedEffect(progress) {
        (progress as? AuditProgress.Done)?.let {
            vm.acknowledgeProgress()
            onDone(it.auditId)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        when (val p = progress) {
            is AuditProgress.Failed -> {
                Text("The audit stopped", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 32.dp))
                Text(p.message, style = MaterialTheme.typography.bodyLarge, color = Film.leak, modifier = Modifier.padding(vertical = 12.dp))
                Note("Check the items under \"Before you audit\" on the home screen, then try again.")
                PrimaryAction("Back to home", { vm.acknowledgeProgress(); onBack() }, Modifier.padding(top = 20.dp))
                return@Column
            }
            is AuditProgress.Running -> {
                Text(p.config.targetLabel, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
                // The plans really are a sequence, so they are shown as numbered steps.
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    p.config.plans.forEachIndexed { i, plan ->
                        val color = when {
                            i < p.planIndex -> Film.clear
                            i == p.planIndex -> Film.scan
                            else -> Film.boneDim
                        }
                        Text("${i + 1}. ${plan.label.substringAfter("· ")}", style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
                Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(null)
                    Spacer(Modifier.width(10.dp))
                    Text(p.step, style = MaterialTheme.typography.titleMedium)
                }
                val classified = net.map { e -> vm.graph.catalog().classify(e.hostname).let { e.copy(entity = it.entity, category = it.category) } }
                val choice = consent.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }
                val trackers = classified.filter { it.category.isTracking }
                val before = trackers.count { choice == null || it.tNanos < choice.tNanos }
                Text(
                    "${classified.size} lookups, ${trackers.size} to trackers, $before before any consent choice",
                    style = MaterialTheme.typography.bodyMedium, color = if (before > 0) Film.leak else Film.boneDim,
                )
                Timeline(classified, consent, p.sessionStartNanos, Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryAction("Mark consent now", { vm.markConsentNow() }, Modifier.weight(1f))
                    SecondaryAction("Stop audit", { vm.cancelAudit(); onBack() }, Modifier.weight(1f))
                }
            }
            else -> {
                Text("Preparing…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 32.dp))
            }
        }
    }
}

@Composable
private fun Timeline(events: List<NetEvent>, consent: List<ConsentEvent>, start: Long, modifier: Modifier) {
    val rows: List<Row2> = (events.map { Row2.Net(it) } + consent.filter { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }.map { Row2.Choice(it) })
        .sortedBy { if (it is Row2.Net) it.e.tNanos else (it as Row2.Choice).e.tNanos }
    val choiceEvent = consent.firstOrNull { it.kind == ConsentKind.ACCEPT || it.kind == ConsentKind.REJECT }
    val choiceAt = choiceEvent?.tNanos
    val choiceKind = choiceEvent?.kind
    val state = rememberLazyListState()
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) state.animateScrollToItem(rows.lastIndex) }
    LazyColumn(modifier.fillMaxWidth(), state = state) {
        if (consent.any { it.kind == ConsentKind.SCREEN_APPEARED || it.kind == ConsentKind.MANUAL_MARK }) {
            item { Note("Consent screen detected and sent to on-device review", Film.scan) }
        }
        items(rows) { r ->
            when (r) {
                is Row2.Choice -> ConsentLine(
                    "${if (r.e.kind == ConsentKind.ACCEPT) "Accepted" else "Refused"} at ${secs(r.e.tNanos - start)} s  (${r.e.nodeText ?: ""})",
                    if (r.e.kind == ConsentKind.ACCEPT) Film.scan else Film.warn,
                )
                is Row2.Net -> TimelineRow(r.e, start, beforeConsent = choiceAt == null || r.e.tNanos < choiceAt, afterRefusal = choiceKind == ConsentKind.REJECT)
            }
        }
    }
}

@Composable
fun TimelineRow(e: NetEvent, start: Long, beforeConsent: Boolean, afterRefusal: Boolean = false) {
    val leak = (beforeConsent || afterRefusal) && e.category.isTracking
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(secs(e.tNanos - start).padStart(5), style = MaterialTheme.typography.labelMedium, color = Film.boneDim, modifier = Modifier.width(44.dp))
        StatusDotColor(categoryColor(e.category))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            HostText(e.hostname, if (leak) Film.leak else Film.bone)
            Text(
                listOfNotNull(e.entity, e.category.name.lowercase().replace('_', ' ')).joinToString(", "),
                style = MaterialTheme.typography.labelMedium, color = Film.boneDim,
            )
        }
    }
}

@Composable
private fun StatusDotColor(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(
        Modifier.size(8.dp).clip(CircleShape).background(color),
    )
}

fun secs(nanos: Long) = String.format(Locale.US, "%.1f", nanos.coerceAtLeast(0) / 1e9)
