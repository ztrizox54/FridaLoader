package com.dns.fridaloader

object RootUtil {
    var isMagisk = false
    val su get() = if (isMagisk) "su -c" else "su 0"

    fun shell(cmd: String, readOutput: Boolean = true): String {
        return try {
            val p = Runtime.getRuntime().exec(cmd)
            if (!readOutput) { p.waitFor(); return "" }
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        } catch (_: Exception) { "" }
    }
}
