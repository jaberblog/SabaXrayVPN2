package com.example.xrayvpn

import android.app.Activity
import android.net.VpnService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.example.xrayvpn.vpn.XrayVpnService

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var messageView: TextView
    private lateinit var downloadView: TextView
    private lateinit var uploadView: TextView
    private lateinit var toggleButton: Button
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val prefs = getSharedPreferences(XrayVpnService.PREFS, MODE_PRIVATE)
            val state = intent.getStringExtra(XrayVpnService.EXTRA_STATE)
                ?: prefs.getString(XrayVpnService.KEY_STATE, XrayVpnService.STATE_DISCONNECTED)
                ?: XrayVpnService.STATE_DISCONNECTED
            val message = intent.getStringExtra(XrayVpnService.EXTRA_MESSAGE)
                ?: prefs.getString(XrayVpnService.KEY_MESSAGE, "")
                .orEmpty()
            val download = intent.getStringExtra(XrayVpnService.EXTRA_DOWNLOAD)
                ?: prefs.getString(XrayVpnService.KEY_DOWNLOAD, "0 B/s")
                ?: "0 B/s"
            val upload = intent.getStringExtra(XrayVpnService.EXTRA_UPLOAD)
                ?: prefs.getString(XrayVpnService.KEY_UPLOAD, "0 B/s")
                ?: "0 B/s"
            render(state, message, download, upload)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.background)
        window.navigationBarColor = getColor(R.color.background)
        window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.tvStatus)
        messageView = findViewById(R.id.tvMessage)
        downloadView = findViewById(R.id.tvDownload)
        uploadView = findViewById(R.id.tvUpload)
        toggleButton = findViewById(R.id.btnToggle)

        toggleButton.setOnClickListener {
            val current = getSharedPreferences(XrayVpnService.PREFS, MODE_PRIVATE)
                .getString(XrayVpnService.KEY_STATE, XrayVpnService.STATE_DISCONNECTED)
            if (current == XrayVpnService.STATE_CONNECTED || current == XrayVpnService.STATE_CONNECTING) {
                stopVpn()
            } else {
                requestVpnPermission()
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
        }
        refreshFromPreferences()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(XrayVpnService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
        refreshFromPreferences()
    }

    override fun onPause() {
        if (receiverRegistered) {
            unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
        super.onPause()
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, VPN_REQUEST)
        } else {
            startVpnService()
        }
    }

    @Deprecated("Uses the VPN permission activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST) {
            if (resultCode == RESULT_OK) {
                startVpnService()
            } else {
                render(
                    XrayVpnService.STATE_DISCONNECTED,
                    "اجازه ایجاد اتصال VPN داده نشد.",
                    "0 B/s",
                    "0 B/s"
                )
            }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopVpn() {
        startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
    }

    private fun refreshFromPreferences() {
        val prefs = getSharedPreferences(XrayVpnService.PREFS, MODE_PRIVATE)
        render(
            prefs.getString(XrayVpnService.KEY_STATE, XrayVpnService.STATE_DISCONNECTED)
                ?: XrayVpnService.STATE_DISCONNECTED,
            prefs.getString(XrayVpnService.KEY_MESSAGE, "برای شروع، دکمه اتصال را بزنید").orEmpty(),
            prefs.getString(XrayVpnService.KEY_DOWNLOAD, "0 B/s") ?: "0 B/s",
            prefs.getString(XrayVpnService.KEY_UPLOAD, "0 B/s") ?: "0 B/s"
        )
    }

    private fun render(state: String, message: String, download: String, upload: String) {
        val connected = state == XrayVpnService.STATE_CONNECTED
        val connecting = state == XrayVpnService.STATE_CONNECTING
        statusView.text = when {
            connected -> getString(R.string.status_connected)
            connecting -> getString(R.string.status_connecting)
            else -> getString(R.string.status_disconnected)
        }
        statusView.setTextColor(getColor(if (connected) R.color.primary else if (state == XrayVpnService.STATE_ERROR) R.color.danger else R.color.text_primary))
        messageView.text = message
        downloadView.text = download
        uploadView.text = upload
        toggleButton.text = if (connected || connecting) getString(R.string.button_disconnect) else getString(R.string.button_connect)
    }

    companion object {
        private const val VPN_REQUEST = 501
        private const val NOTIFICATION_REQUEST = 502
    }
}
