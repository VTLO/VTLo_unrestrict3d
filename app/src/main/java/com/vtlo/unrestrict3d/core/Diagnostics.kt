package com.vtlo.unrestrict3d.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Tiny append-only log so a killed process still leaves a trail (phase markers, memory, trims). */
object EventLog {
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "events.log")
        runCatching { if (f.length() > 128_000) f.writeText(f.readText().takeLast(32_000)) }
        file = f
        log("--- process start pid=${Process.myPid()} ---")
    }

    @Synchronized
    fun log(msg: String) {
        runCatching { file?.appendText("${fmt.format(Date())} $msg\n") }
    }

    fun tail(maxChars: Int = 4000): String =
        runCatching { file?.readText()?.takeLast(maxChars) ?: "" }.getOrDefault("")
}

data class MemSnap(val availMb: Long, val totalMb: Long, val rssMb: Long, val low: Boolean)

data class DiagReport(val summary: String, val details: String)

object Diagnostics {

    fun mem(ctx: Context): MemSnap {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return MemSnap(mi.availMem shr 20, mi.totalMem shr 20, rssMb(), mi.lowMemory)
    }

    private fun rssMb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .first { it.startsWith("VmRSS:") }
            .filter { it.isDigit() }.toLong() / 1024
    }.getOrDefault(-1L)

    fun installCrashHandler(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val m = mem(ctx)
                File(ctx.filesDir, "crash.log").writeText(
                    "${Date()} thread=${t.name}\n" +
                        "mem avail=${m.availMb}MB total=${m.totalMb}MB rss=${m.rssMb}MB\n" +
                        Log.getStackTraceString(e) +
                        "\n--- last events ---\n" + EventLog.tail(1500)
                )
            }
            prev?.uncaughtException(t, e)
        }
    }

    private fun ackPrefs(ctx: Context) = ctx.getSharedPreferences("vtlo_diag", Context.MODE_PRIVATE)

    fun acknowledge(ctx: Context) {
        ackPrefs(ctx).edit().putLong("ack_ts", System.currentTimeMillis()).apply()
        File(ctx.filesDir, "crash.log").delete()
    }

    /** What killed the app last time (Java crash, native crash, low-memory kill ...), or null if nothing notable. */
    fun report(ctx: Context): DiagReport? {
        val parts = mutableListOf<String>()
        var headline: String? = null

        File(ctx.filesDir, "crash.log").takeIf { it.exists() }?.let {
            val txt = it.readText()
            headline = "Java crash: " + (txt.lines().getOrNull(3) ?: "see log").take(90)
            parts += "== CRASH LOG ==\n$txt"
        }

        if (Build.VERSION.SDK_INT >= 30) {
            val ack = ackPrefs(ctx).getLong("ack_ts", 0L)
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val notable = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 6) }
                .getOrDefault(emptyList())
                .filter { it.timestamp > ack && it.reason in NOTABLE }
            for (info in notable) {
                val name = reasonName(info.reason)
                if (headline == null) headline = "Last stop: $name"
                parts += "== EXIT ${fmtTs(info.timestamp)} $name ==\n" +
                    "description=${info.description}\nrss=${info.rss / 1024}MB pss=${info.pss / 1024}MB " +
                    "importance=${info.importance}"
            }
        }
        if (parts.isEmpty()) return null
        parts += "== EVENTS ==\n" + EventLog.tail(2500)
        return DiagReport(headline ?: "Previous run ended abnormally", parts.joinToString("\n\n"))
    }

    private fun fmtTs(ts: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    private val NOTABLE = setOf(
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNAL (killed by signal)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW MEMORY (killed by the system: out of RAM)"
        ApplicationExitInfo.REASON_CRASH -> "JAVA CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE CRASH (inside the model runtime)"
        ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT FAILURE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE RESOURCE USAGE"
        else -> "reason $r"
    }
}
