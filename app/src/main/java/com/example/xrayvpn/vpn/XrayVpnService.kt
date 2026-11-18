package com.example.xrayvpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.example.xrayvpn.MainActivity
import com.example.xrayvpn.R
import com.example.xrayvpn.core.XrayConfigBuilder
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.util.UUID
import java.util.concurrent.Executors
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class XrayVpnService : VpnService() {

    private val worker = Executors.newSingleThreadExecutor()
    private val statsExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var statsFuture: ScheduledFuture<*>? = null

    @Volatile private var coreController: CoreController? = null
    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                worker.execute { stopTunnel() }
            }
            ACTION_START -> {
                stopping = false
                startAsForeground()
                worker.execute { startTunnel() }
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        if (coreController?.isRunning == true) {
            publishState(STATE_CONNECTED, "اتصال برقرار است")
            return
        }

        publishState(STATE_CONNECTING, "در حال آماده‌سازی تونل امن...")

        try {
            // Read the physical network before establishing the VPN, otherwise activeNetwork
            // may already refer to the VPN itself.
            val connectivity = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val physicalNetwork: Network = connectivity.activeNetwork
                ?: error("اتصال اینترنت پیدا نشد؛ Wi-Fi یا دیتای موبایل را بررسی کنید.")
            // Resolve the endpoint before the VPN captures the default route. The REALITY SNI
            // remains play.google.com; only the TCP destination is replaced by its IP address.
            val serverAddress = InetAddress.getAllByName(SERVER_HOST)
                .firstOrNull { it is Inet4Address }?.hostAddress
                ?: error("آدرس سرور $SERVER_HOST از DNS دریافت نشد.")

            val builder = Builder()
                .setSession("Saba Xray VPN")
                .setMtu(1280)
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                // The Xray core runs inside this app UID. Excluding the app prevents its
                // outbound sockets from re-entering the VPN and creating a routing loop.
                .addDisallowedApplication(packageName)

            vpnInterface = builder.establish()
                ?: error("ساخت رابط VPN توسط اندروید ناموفق بود.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setUnderlyingNetworks(arrayOf(physicalNetwork))
            }

            // gomobile needs an Android context before calling generated Libv2ray APIs.
            Seq.setContext(applicationContext)
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            val xudpKey = prefs.getString(KEY_XUDP, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_XUDP, it).apply()
            }
            Libv2ray.initCoreEnv(filesDir.absolutePath, xudpKey)

            val callback = object : CoreCallbackHandler {
                override fun startup(): Long {
                    publishState(STATE_CONNECTING, "هسته Xray راه‌اندازی شد...")
                    return 0L
                }

                override fun shutdown(): Long {
                    return 0L
                }

                override fun onEmitStatus(code: Long, message: String?): Long {
                    if (!message.isNullOrBlank()) {
                        publishState(
                            if (message.contains("Started successfully", ignoreCase = true)) STATE_CONNECTED else STATE_CONNECTING,
                            message
                        )
                    }
                    return 0L
                }
            }

            val controller = Libv2ray.newCoreController(callback)
            coreController = controller
            val config = XrayConfigBuilder.build(serverAddress)
            val fd = vpnInterface?.fd ?: error("فایل‌دسکریپتور VPN در دسترس نیست.")
            controller.startLoop(config, fd)

            if (!controller.isRunning) {
                error("هسته Xray اجرا نشد؛ گزارش اجرای برنامه را بررسی کنید.")
            }

            publishState(STATE_CONNECTED, "اتصال برقرار است")
            startStatsSampling()
            updateNotification("VPN متصل است")
        } catch (e: Exception) {
            runCatching { coreController?.stopLoop() }
            coreController = null
            runCatching { vpnInterface?.close() }
            vpnInterface = null
            publishState(STATE_ERROR, "خطا: ${e.message ?: e.javaClass.simpleName}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startStatsSampling() {
        statsFuture?.cancel(true)
        statsFuture = statsExecutor.scheduleAtFixedRate({
            val controller = coreController
            if (controller?.isRunning != true || stopping) return@scheduleAtFixedRate

            try {
                // The native API returns and resets counters since the previous query.
                // Format: outboundTag,direction,bytes;outboundTag,direction,bytes;
                val raw = controller.queryAllOutboundTrafficStats()
                var uploaded = 0L
                var downloaded = 0L
                raw.split(';').forEach { row ->
                    val parts = row.split(',', limit = 3)
                    if (parts.size != 3) return@forEach
                    val bytes = parts[2].toLongOrNull() ?: return@forEach
                    when (parts[1].lowercase()) {
                        "uplink" -> uploaded += bytes
                        "downlink" -> downloaded += bytes
                    }
                }
                publishSpeeds(formatSpeed(downloaded), formatSpeed(uploaded))
            } catch (_: Exception) {
                // Keep the VPN alive even if one stats poll fails.
            }
        }, 0L, 1L, TimeUnit.SECONDS)
    }

    private fun stopTunnel() {
        statsFuture?.cancel(true)
        statsFuture = null
        runCatching { coreController?.stopLoop() }
        coreController = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        publishState(STATE_DISCONNECTED, "اتصال VPN قطع شد")
        publishSpeeds("0 B/s", "0 B/s")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopping = true
        worker.execute { stopTunnel() }
        super.onRevoke()
    }

    override fun onDestroy() {
        statsFuture?.cancel(true)
        statsFuture = null
        runCatching { coreController?.stopLoop() }
        runCatching { vpnInterface?.close() }
        coreController = null
        vpnInterface = null
        worker.shutdownNow()
        statsExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startAsForeground() {
        createNotificationChannel()
        val notification = buildNotification("در حال اتصال...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this,
            1,
            Intent(this, XrayVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Saba Xray VPN")
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "قطع اتصال", stopService)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "وضعیت VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun publishState(state: String, message: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_STATE, state)
            .putString(KEY_MESSAGE, message)
            .apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    private fun publishSpeeds(download: String, upload: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_DOWNLOAD, download)
            .putString(KEY_UPLOAD, upload)
            .apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_DOWNLOAD, download)
            putExtra(EXTRA_UPLOAD, upload)
        })
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond < 1024) return "$bytesPerSecond B/s"
        val kb = bytesPerSecond / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB/s", kb)
        return String.format(java.util.Locale.US, "%.2f MB/s", kb / 1024.0)
    }

    companion object {
        const val ACTION_START = "com.example.xrayvpn.action.START"
        const val ACTION_STOP = "com.example.xrayvpn.action.STOP"
        const val ACTION_STATUS = "com.example.xrayvpn.action.STATUS"

        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DOWNLOAD = "download"
        const val EXTRA_UPLOAD = "upload"

        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_CONNECTING = "connecting"
        const val STATE_CONNECTED = "connected"
        const val STATE_ERROR = "error"

        const val PREFS = "vpn_state"
        const val KEY_STATE = "state"
        const val KEY_MESSAGE = "message"
        const val KEY_DOWNLOAD = "download"
        const val KEY_UPLOAD = "upload"
        private const val KEY_XUDP = "xudp_key"
        private const val SERVER_HOST = "trx.koomeh.net"

        private const val CHANNEL_ID = "xray_vpn_channel"
        private const val NOTIFICATION_ID = 2701
    }
}
