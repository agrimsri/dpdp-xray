package com.dpdpxray.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.dpdpxray.core.dns.DnsPacketCodec
import com.dpdpxray.core.model.NetEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A per-app, DNS-only local VPN. Only the audited app (and optionally Google Play services) is routed in, and only
 * traffic to a fake resolver address enters the tunnel, so the app's real traffic is untouched. Every DNS question is
 * timestamped with the same clock as the consent watcher, then relayed to the real resolver. Answers go back with
 * TTL 0 so Android never caches them: every repeat lookup during the audit stays visible.
 */
class XrayVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val pkg = intent.getStringExtra(EXTRA_TARGET) ?: return START_NOT_STICKY
                startForegroundNotice(pkg)
                startCapture(pkg, intent.getBooleanExtra(EXTRA_INCLUDE_GMS, false))
            }
            ACTION_STOP -> {
                stopTunnel()
                CaptureBus.setState(CaptureState.Idle)
                stopForeground(STOP_FOREGROUND_REMOVE)
                // Only stop if no newer START arrived after this STOP was sent.
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(pkg: String, includeGms: Boolean) {
        stopTunnel()
        val builder = Builder()
            .setSession("DPDP X-Ray · $pkg")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setMtu(MTU)
            .setBlocking(true)
        try {
            builder.addAllowedApplication(pkg)
            if (includeGms) runCatching { builder.addAllowedApplication(GMS_PACKAGE) }
        } catch (e: Exception) {
            CaptureBus.setState(CaptureState.Error("App not found: $pkg"))
            stopSelf()
            return
        }
        val fd = runCatching { builder.establish() }.getOrNull()
        if (fd == null) {
            CaptureBus.setState(CaptureState.Error("VPN permission missing or another VPN is active"))
            stopSelf()
            return
        }
        val forwarder = DnsForwarder(this) { protect(it) }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        synchronized(this) {
            tun = fd
            scope = s
        }
        CaptureBus.setState(CaptureState.Running(pkg))
        s.launch { readLoop(fd, forwarder, s) }
    }

    private fun readLoop(fd: ParcelFileDescriptor, forwarder: DnsForwarder, s: CoroutineScope) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buffer = ByteArray(32767)
        while (s.isActive) {
            val n = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) continue
            val query = DnsPacketCodec.parse(buffer, n) ?: continue
            CaptureBus.record(NetEvent(tNanos = SystemClock.elapsedRealtimeNanos(), hostname = query.hostname, queryType = query.queryType))
            s.launch {
                val answer = forwarder.resolve(query.dnsPayload)?.let { DnsPacketCodec.zeroTtls(it) }
                    ?: DnsPacketCodec.servFail(query.dnsPayload)
                val packet = DnsPacketCodec.buildResponse(query, answer)
                synchronized(output) {
                    runCatching { output.write(packet) }
                }
            }
        }
    }

    /** Called from the main thread (commands) and a binder thread (onRevoke), hence synchronized. */
    @Synchronized
    private fun stopTunnel() {
        scope?.cancel()
        scope = null
        runCatching { tun?.close() }
        tun = null
    }

    override fun onRevoke() {
        stopTunnel()
        CaptureBus.setState(CaptureState.Error("VPN was turned off by the system or the user"))
        stopSelf()
    }

    override fun onDestroy() {
        stopTunnel()
        if (CaptureBus.state.value is CaptureState.Running) CaptureBus.setState(CaptureState.Idle)
        super.onDestroy()
    }

    private fun startForegroundNotice(pkg: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Audit capture", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("DPDP X-Ray is auditing")
            .setContentText("Watching DNS lookups of $pkg · nothing is uploaded")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        private const val ACTION_START = "com.dpdpxray.capture.START"
        private const val ACTION_STOP = "com.dpdpxray.capture.STOP"
        private const val EXTRA_TARGET = "target"
        private const val EXTRA_INCLUDE_GMS = "gms"
        private const val VPN_ADDRESS = "10.111.0.2"
        private const val DNS_ADDRESS = "10.111.0.53"
        private const val MTU = 1500
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 7
        const val GMS_PACKAGE = "com.google.android.gms"

        fun start(context: Context, targetPackage: String, includeGms: Boolean) {
            CaptureBus.setState(CaptureState.Starting)
            context.startForegroundService(
                Intent(context, XrayVpnService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_TARGET, targetPackage)
                    .putExtra(EXTRA_INCLUDE_GMS, includeGms),
            )
        }

        fun stop(context: Context) {
            if (CaptureBus.state.value is CaptureState.Idle) return
            context.startService(Intent(context, XrayVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
