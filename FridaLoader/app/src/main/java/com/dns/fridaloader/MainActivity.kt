package com.dns.fridaloader

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.downloader.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.*
import org.tukaani.xz.XZInputStream
import java.io.*

class MainActivity : AppCompatActivity() {

    private lateinit var btnDownload: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnRecheck: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvProgress: MaterialTextView
    private lateinit var tvStatus: MaterialTextView

    private var downloadId = 0
    private val dirPath by lazy { Util.getRootDirPath(this) }
    private var isMagisk = false
    private val su get() = if (isMagisk) "su -c" else "su 0"

    companion object {
        private const val API_URL = "https://api.github.com/repos/frida/frida/releases/latest"
        private const val SERVER_NAME = "frida-server-latest"
        private const val SERVER_PATH = "/data/local/tmp/$SERVER_NAME"
        private const val SERVER_XZ = "frida-server-latest.xz"
        private const val SERVER_DECOMPRESSED = "frida-server-decompressed"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnDownload = findViewById(R.id.btn_download)
        btnCancel = findViewById(R.id.btn_cancel)
        btnRecheck = findViewById(R.id.btn_recheck)
        progressBar = findViewById(R.id.progress_bar)
        tvProgress = findViewById(R.id.tv_progress)
        tvStatus = findViewById(R.id.tv_status)

        btnDownload.setOnClickListener { startDownloadFlow() }
        btnCancel.setOnClickListener { PRDownloader.cancel(downloadId) }
        btnRecheck.setOnClickListener { refreshStatus() }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                isMagisk = shell("/system/bin/which magisk").contains("magisk")
                shell("$su /system/bin/ls", readOutput = false)
            }
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            tvStatus.text = "Checking..."
            val running = withContext(Dispatchers.IO) { isRunning() }
            if (running) showRunningDialog() else showNotRunningDialog()
        }
    }

    private fun isRunning(): Boolean =
        shell("$su /system/bin/ps -A").contains(SERVER_NAME) ||
        shell("$su /system/bin/ps").contains(SERVER_NAME)

    private fun showRunningDialog() {
        setStatus("Running", running = true)
        MaterialAlertDialogBuilder(this)
            .setTitle("Frida is running")
            .setMessage("$SERVER_NAME is already running.")
            .setPositiveButton("Update") { d, _ ->
                d.dismiss()
                lifecycleScope.launch(Dispatchers.IO) { killFrida() }
                startDownloadFlow()
            }
            .setNegativeButton("Kill") { d, _ ->
                d.dismiss()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { killFrida() }
                    setStatus("Stopped", running = false)
                }
            }
            .setNeutralButton("Continue") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showNotRunningDialog() {
        setStatus("Not running", running = false)
        MaterialAlertDialogBuilder(this)
            .setTitle("Frida is not running")
            .setMessage("$SERVER_NAME is not currently running.")
            .setPositiveButton("Download & run") { d, _ -> d.dismiss(); startDownloadFlow() }
            .setNeutralButton("Force start") { d, _ -> d.dismiss(); forceStart() }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun killFrida() {
        shell("$su /system/bin/killall $SERVER_NAME", readOutput = false)
    }

    private fun forceStart() {
        lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) {
                shell("$su /system/bin/ls $SERVER_PATH").contains(SERVER_NAME)
            }
            if (!exists) {
                setStatus("Server not found", running = false)
                return@launch
            }
            withContext(Dispatchers.IO) {
                shell("$su $SERVER_PATH &", readOutput = false)
                delay(1500)
            }
            refreshStatus()
        }
    }

    private fun startDownloadFlow() {
        lifecycleScope.launch {
            setStatus("Fetching version info...", running = false)
            val abi = withContext(Dispatchers.IO) { shell("getprop ro.product.cpu.abi").trim() }
            val arch = mapArch(abi)
            val url = withContext(Dispatchers.IO) { FridaApi.getLatestServerUrl(API_URL, arch) }
            if (url == null) {
                setStatus("Failed to fetch download URL", running = false)
                return@launch
            }
            startDownload(url)
        }
    }

    private fun mapArch(abi: String): String = when (abi) {
        "arm64-v8a" -> "arm64"
        "x86_64"    -> "x86_64"
        "x86"       -> "x86"
        else        -> "arm"
    }

    private fun startDownload(url: String) {
        if (Status.RUNNING == PRDownloader.getStatus(downloadId)) {
            PRDownloader.pause(downloadId)
            btnDownload.text = "Resume"
            return
        }
        if (Status.PAUSED == PRDownloader.getStatus(downloadId)) {
            PRDownloader.resume(downloadId)
            btnDownload.text = "Pause"
            return
        }

        btnDownload.isEnabled = false
        btnCancel.isEnabled = true
        progressBar.isIndeterminate = true

        downloadId = PRDownloader.download(url, dirPath, SERVER_XZ).build()
            .setOnStartOrResumeListener {
                progressBar.isIndeterminate = false
                btnDownload.text = "Pause"
                btnDownload.isEnabled = true
            }
            .setOnPauseListener {
                btnDownload.text = "Resume"
            }
            .setOnCancelListener {
                resetDownloadUi()
            }
            .setOnProgressListener { p ->
                progressBar.progress = (p.currentBytes * 100 / p.totalBytes).toInt()
                tvProgress.text = Util.getProgressDisplayLine(p.currentBytes, p.totalBytes)
                progressBar.isIndeterminate = false
            }
            .start(object : OnDownloadListener {
                override fun onDownloadComplete() {
                    lifecycleScope.launch {
                        resetDownloadUi()
                        tvProgress.text = "Installing..."
                        val ok = withContext(Dispatchers.IO) { decompressAndInstall() }
                        tvProgress.text = ""
                        if (ok) {
                            withContext(Dispatchers.IO) {
                                shell("$su $SERVER_PATH &", readOutput = false)
                                delay(1500)
                            }
                            refreshStatus()
                        } else {
                            setStatus("Install failed", running = false)
                        }
                    }
                }

                override fun onError(error: Error) {
                    resetDownloadUi()
                    setStatus("Download failed", running = false)
                    tvProgress.text = error.connectionException?.message ?: "Unknown error"
                }
            })
    }

    private fun decompressAndInstall(): Boolean = try {
        val xzFile = "$dirPath/$SERVER_XZ"
        val decompFile = "$dirPath/$SERVER_DECOMPRESSED"
        XZInputStream(BufferedInputStream(FileInputStream(xzFile))).use { xz ->
            FileOutputStream(decompFile).use { out -> xz.copyTo(out) }
        }
        shell("$su /system/bin/cp $decompFile $SERVER_PATH", readOutput = false)
        Thread.sleep(500)
        shell("$su /system/bin/chmod +x $SERVER_PATH", readOutput = false)
        Thread.sleep(500)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    private fun resetDownloadUi() {
        btnDownload.text = "Download & Run"
        btnDownload.isEnabled = true
        btnCancel.isEnabled = false
        progressBar.progress = 0
        progressBar.isIndeterminate = false
        tvProgress.text = ""
        downloadId = 0
    }

    private fun setStatus(text: String, running: Boolean) {
        tvStatus.text = text
        tvStatus.setTextColor(
            if (running) getColor(R.color.status_running)
            else getColor(R.color.status_stopped)
        )
    }

    private fun shell(cmd: String, readOutput: Boolean = true): String = try {
        val p = Runtime.getRuntime().exec(cmd)
        if (!readOutput) { p.waitFor(); return "" }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (_: Exception) { "" }
}
