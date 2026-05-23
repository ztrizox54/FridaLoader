package com.dns.fridaloader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

class ServerFragment : Fragment() {

    private lateinit var tvServerStatus: MaterialTextView
    private lateinit var tvIp: MaterialTextView
    private lateinit var tvPort: MaterialTextView
    private lateinit var tvCommand: MaterialTextView
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnCopy: MaterialButton

    companion object {
        private const val SERVER_NAME = "frida-server-latest"
        private const val SERVER_PATH = "/data/local/tmp/$SERVER_NAME"
        private const val FRIDA_PORT = 27042
        private const val COLOR_NEUTRAL = 0xFFAAAAAA.toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_server, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvServerStatus = view.findViewById(R.id.tv_server_status)
        tvIp = view.findViewById(R.id.tv_ip)
        tvPort = view.findViewById(R.id.tv_port)
        tvCommand = view.findViewById(R.id.tv_command)
        btnStart = view.findViewById(R.id.btn_start)
        btnStop = view.findViewById(R.id.btn_stop)
        btnCopy = view.findViewById(R.id.btn_copy)

        btnStart.setOnClickListener { startServer() }
        btnStop.setOnClickListener { stopServer() }
        btnCopy.setOnClickListener { copyCommand() }

        refreshAll()
    }

    private fun refreshAll() {
        val ip = getWifiIp()
        tvIp.text = "IP: ${ip ?: "Unknown"}"
        tvPort.text = "Port: $FRIDA_PORT"
        tvCommand.text = "frida -H ${ip ?: "IP"}:$FRIDA_PORT"

        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) { isRunning() }
            setServerStatus(running)
        }
    }

    private fun getWifiIp(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun isRunning(): Boolean =
        RootUtil.shell("${RootUtil.su} /system/bin/ps -A").contains(SERVER_NAME) ||
        RootUtil.shell("${RootUtil.su} /system/bin/ps").contains(SERVER_NAME)

    private fun binaryExists(): Boolean =
        RootUtil.shell("${RootUtil.su} /system/bin/ls $SERVER_PATH").contains(SERVER_NAME)

    private fun startServer() {
        lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) { binaryExists() }
            if (!exists) {
                setServerStatus(false, missingBinary = true)
                return@launch
            }
            setStatus("Starting...", null)
            withContext(Dispatchers.IO) {
                RootUtil.shell("${RootUtil.su} $SERVER_PATH &", readOutput = false)
                kotlinx.coroutines.delay(1500)
            }
            val running = withContext(Dispatchers.IO) { isRunning() }
            setServerStatus(running)
        }
    }

    private fun stopServer() {
        lifecycleScope.launch {
            setStatus("Stopping...", null)
            withContext(Dispatchers.IO) {
                RootUtil.shell("${RootUtil.su} /system/bin/killall $SERVER_NAME", readOutput = false)
                kotlinx.coroutines.delay(800)
            }
            val running = withContext(Dispatchers.IO) { isRunning() }
            setServerStatus(running)
        }
    }

    private fun copyCommand() {
        val ip = getWifiIp() ?: "IP"
        val cmd = "frida -H $ip:$FRIDA_PORT"
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("frida command", cmd))
    }

    private fun setServerStatus(running: Boolean, missingBinary: Boolean = false) {
        when {
            missingBinary -> {
                setStatus("Binary not found", false)
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
            running -> {
                setStatus("Running", true)
                btnStart.isEnabled = false
                btnStop.isEnabled = true
            }
            else -> {
                setStatus("Stopped", false)
                btnStart.isEnabled = true
                btnStop.isEnabled = false
            }
        }
    }

    /**
     * running = null -> neutral gray (#FFAAAAAA)
     * running = true -> green (status_running)
     * running = false -> red (status_stopped)
     */
    private fun setStatus(text: String, running: Boolean?) {
        tvServerStatus.text = text
        val color = when (running) {
            true  -> requireContext().getColor(R.color.status_running)
            false -> requireContext().getColor(R.color.status_stopped)
            null  -> COLOR_NEUTRAL
        }
        tvServerStatus.setTextColor(color)
    }
}
