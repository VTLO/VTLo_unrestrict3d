package com.vtlo.unrestrict3d.core

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ShellResult(val code: Int, val out: String, val err: String, val timedOut: Boolean = false)

/** Reads at most [cap] chars and drains the rest so the child never blocks on a full pipe. */
internal fun readCapped(s: InputStream, cap: Int): String {
    val sb = StringBuilder()
    val buf = CharArray(4096)
    s.bufferedReader().use { r ->
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            if (sb.length < cap) sb.append(buf, 0, minOf(n, cap - sb.length))
        }
    }
    return sb.toString()
}

/**
 * Root helpers (Magisk/KernelSU `su`). Everything is opt-in from settings and every action is
 * best-effort: if root is missing the app simply falls back to plain Android behaviour.
 */
object RootTools {

    @Volatile private var cached: Boolean? = null

    /** Runs a command as root (or as the app user when [root] is false) with a hard timeout. */
    suspend fun exec(
        argv: List<String>,
        timeoutMs: Long,
        cap: Int = 16_000,
        env: Map<String, String> = emptyMap(),
        dir: java.io.File? = null,
    ): ShellResult = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(argv)
        pb.environment().putAll(env)
        dir?.let { pb.directory(it) }
        val p = try { pb.start() } catch (t: Throwable) {
            return@withContext ShellResult(127, "", t.message ?: "cannot start")
        }
        var out = ""
        var err = ""
        val t1 = Thread { out = runCatching { readCapped(p.inputStream, cap) }.getOrDefault("") }.apply { start() }
        val t2 = Thread { err = runCatching { readCapped(p.errorStream, cap) }.getOrDefault("") }.apply { start() }
        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        while (true) {
            if (p.waitFor(250, TimeUnit.MILLISECONDS)) break
            if (!coroutineContext.isActive) { p.destroyForcibly(); break }
            if (System.currentTimeMillis() > deadline) { timedOut = true; p.destroyForcibly(); break }
        }
        runCatching { t1.join(1500); t2.join(1500) }
        val code = if (timedOut || !coroutineContext.isActive) -1 else runCatching { p.exitValue() }.getOrDefault(-1)
        ShellResult(code, out, err, timedOut)
    }

    suspend fun su(cmd: String, timeoutMs: Long = 15_000): ShellResult =
        exec(listOf("su", "-c", cmd), timeoutMs)

    suspend fun isAvailable(force: Boolean = false): Boolean {
        if (!force) cached?.let { return it }
        val r = su("id -u", 8_000)
        val ok = r.code == 0 && r.out.trim() == "0"
        cached = ok
        EventLog.log("root available=$ok")
        return ok
    }

    /** Makes the low-memory killer prefer anything else over this process. */
    suspend fun protectSelf(): Boolean {
        if (!isAvailable()) return false
        val pid = Process.myPid()
        val r = su("echo -800 > /proc/$pid/oom_score_adj; renice -n -4 -p $pid >/dev/null 2>&1; cat /proc/$pid/oom_score_adj")
        EventLog.log("protectSelf -> ${r.out.trim()}")
        return r.code == 0
    }

    /** Frees page cache, compacts memory and kills cached background processes. */
    suspend fun freeMemory(killCached: Boolean = true): String {
        if (!isAvailable()) return "root not available"
        val cmd = buildString {
            append("sync; echo 3 > /proc/sys/vm/drop_caches; echo 1 > /proc/sys/vm/compact_memory 2>/dev/null; ")
            if (killCached) append("am kill-all 2>/dev/null; ")
            append("grep -E 'MemAvailable|MemFree|SwapFree' /proc/meminfo")
        }
        val r = su(cmd, 25_000)
        EventLog.log("freeMemory -> ${r.out.replace('\n', ' ').take(160)}")
        return r.out.trim().ifEmpty { r.err.trim() }
    }

    /** Biggest memory consumers right now (for the resources panel). */
    suspend fun topProcesses(): String {
        val cmd = "ps -A -o RSS,NAME --sort=-RSS 2>/dev/null | head -9 || dumpsys meminfo | head -30"
        val r = if (isAvailable()) su(cmd, 12_000) else exec(listOf("/system/bin/sh", "-c", cmd), 8_000)
        return r.out.trim().ifEmpty { "no data" }
    }

    /** Root copy of a file the app cannot read (e.g. /sdcard/Download/model.litertlm) into private storage. */
    suspend fun importFile(src: String, dest: String): Boolean {
        if (!isAvailable()) return false
        val uid = Process.myUid()
        val r = su(
            "cp '$src' '$dest.part' && mv '$dest.part' '$dest' && chown $uid:$uid '$dest' && chmod 600 '$dest' && " +
                "(restorecon '$dest' 2>/dev/null; true)",
            600_000,
        )
        EventLog.log("root import code=${r.code} ${r.err.take(120)}")
        return r.code == 0
    }

    @Suppress("unused")
    suspend fun pause(ms: Long) = delay(ms)
}
