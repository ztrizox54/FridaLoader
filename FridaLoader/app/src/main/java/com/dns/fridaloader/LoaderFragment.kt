package com.dns.fridaloader

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.downloader.Error as DownloadError
import com.downloader.OnDownloadListener
import com.downloader.PRDownloader
import com.downloader.Status
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream

class LoaderFragment : Fragment() {

    private lateinit var btnDownload: MaterialButton
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnRecheck: MaterialButton
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var tvProgress: MaterialTextView
    private lateinit var tvStatus: MaterialTextView

    private var downloadId = 0
    private val dirPath by lazy { Util.getRootDirPath(requireContext()) }

    companion object {
        private const val API_URL = "https://api.github.com/repos/frida/frida/releases/latest"
        private const val SERVER_NAME = "frida-server-latest"
        private const val SERVER_PATH = "/data/local/tmp/$SERVER_NAME"
        private const val SERVER_XZ = "frida-server-latest.xz"
        private const val SERVER_DECOMPRESSED = "frida-server-decompressed"
        private const val COLOR_NEUTRAL = 0xFFAAAAAA.toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_loader, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        btnDownload = view.findViewById(R.id.btn_download)
        btnCancel = view.findViewById(R.id.btn_cancel)
        btnRecheck = view.findViewById(R.id.btn_recheck)
        progressBar = view.findViewById(R.id.progress_bar)
        tvProgress = view.findViewById(R.id.tv_progress)
        tvStatus = view.findViewById(R.id.tv_status)

        btnDownload.setOnClickListener { startDownloadFlow() }
        btnCancel.setOnClickListener { PRDownloader.cancel(downloadId) }
        btnRecheck.setOnClickListener { refreshStatus() }

        refreshStatus()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            setStatus("Checking...", null)
            val running = withContext(Dispatchers.IO) { isRunning() }
            if (running) showRunningDialog() else showNotRunningDialog()
        }
    }

    private fun isRunning(): Boolean =
        RootUtil.shell("${RootUtil.su} /system/bin/ps -A").contains(SERVER_NAME) ||
        RootUtil.shell("${RootUtil.su} /system/bin/ps").contains(SERVER_NAME)

    private fun showRunningDialog() {
        setStatus("Running", true)
        MaterialAlertDialogBuilder(requireContext())
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
                    setStatus("Stopped", false)
                }
            }
            .setNeutralButton("Continue") { d, _ -> d.dismiss() }
            .show()
    }

    private fun showNotRunningDialog() {
        setStatus("Not running", false)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Frida is not running")
            .setMessage("$SERVER_NAME is not currently running.")
            .setPositiveButton("Download & run") { d, _ -> d.dismiss(); startDownloadFlow() }
            .setNeutralButton("Force start") { d, _ -> d.dismiss(); forceStart() }
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .show()
    }

    private fun killFrida() {
        RootUtil.shell("${RootUtil.su} /system/bin/killall $SERVER_NAME", readOutput = false)
    }

    private fun forceStart() {
        lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) {
                RootUtil.shell("${RootUtil.su} /system/bin/ls $SERVER_PATH").contains(SERVER_NAME)
            }
            if (!exists) {
                setStatus("Server not found", false)
                return@launch
            }
            withContext(Dispatchers.IO) {
                RootUtil.shell("${RootUtil.su} $SERVER_PATH &", readOutput = false)
                delay(1500)
            }
            refreshStatus()
        }
    }

    private fun startDownloadFlow() {
        lifecycleScope.launch {
            setStatus("Fetching version info...", null)
            val abi = withContext(Dispatchers.IO) { RootUtil.shell("getprop ro.product.cpu.abi").trim() }
            val arch = mapArch(abi)
            val url = withContext(Dispatchers.IO) { FridaApi.getLatestServerUrl(API_URL, arch) }
            if (url == null) {
                setStatus("Failed to fetch download URL", false)
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

        setDownloadActive(true)
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
                                RootUtil.shell("${RootUtil.su} $SERVER_PATH &", readOutput = false)
                                delay(1500)
                            }
                            refreshStatus()
                        } else {
                            setStatus("Install failed", false)
                        }
                    }
                }

                override fun onError(error: DownloadError) {
                    resetDownloadUi()
                    setStatus("Download failed", false)
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
        RootUtil.shell("${RootUtil.su} /system/bin/cp $decompFile $SERVER_PATH", readOutput = false)
        Thread.sleep(500)
        RootUtil.shell("${RootUtil.su} /system/bin/chmod +x $SERVER_PATH", readOutput = false)
        Thread.sleep(500)
        true
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    private fun setDownloadActive(active: Boolean) {
        btnDownload.isEnabled = !active
        btnCancel.isEnabled = active
        btnRecheck.isEnabled = !active
        if (!active) {
            progressBar.progress = 0
            progressBar.isIndeterminate = false
            tvProgress.text = ""
            downloadId = 0
        }
    }

    private fun resetDownloadUi() {
        btnDownload.text = "Download & Run"
        setDownloadActive(false)
    }

    /**
     * Sets the status text with color.
     * running = null -> neutral gray (#FFAAAAAA)
     * running = true -> green (status_running)
     * running = false -> red (status_stopped)
     */
    private fun setStatus(text: String, running: Boolean?) {
        tvStatus.text = text
        val color = when (running) {
            true  -> requireContext().getColor(R.color.status_running)
            false -> requireContext().getColor(R.color.status_stopped)
            null  -> COLOR_NEUTRAL
        }
        tvStatus.setTextColor(color)
    }
}
