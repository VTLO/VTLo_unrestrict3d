package com.vtlo.unrestrict3d.data

import android.content.Context
import com.vtlo.unrestrict3d.core.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The built-in model. A 5 GB file cannot live inside an APK (zip/install limits), so it is
 * hardcoded here and fetched once, with resume, into private storage, then loaded like any other.
 */
object DefaultModel {
    const val NAME = "VTLO core"
    const val FILE = "vtlo-core.litertlm"
    const val URL_STR =
        "https://huggingface.co/nqd145/Gemma-4-E2B-it-abliterated-litertlm/resolve/main/Gemma-4-E2B-it-abliterated.litertlm"
    const val SIZE_BYTES = 5_065_244_672L
    const val MIN_RAM_GB = 8
}

class ModelDownloader(private val context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    val target = File(dir, DefaultModel.FILE)
    private val part = File(dir, DefaultModel.FILE + ".part")

    fun isInstalled() = target.exists() && target.length() == DefaultModel.SIZE_BYTES

    /** Downloads with HTTP Range resume and automatic retries. [onProgress] = 0..1. */
    suspend fun download(onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext target
        val need = DefaultModel.SIZE_BYTES - (if (part.exists()) part.length() else 0L) + (300L shl 20)
        require(dir.usableSpace > need) {
            "Not enough free storage: need ${need shr 20} MB, have ${dir.usableSpace shr 20} MB"
        }
        var attempt = 0
        while (true) {
            try {
                fetchOnce(onProgress)
                break
            } catch (t: Throwable) {
                if (!coroutineContext.isActive) throw t
                attempt++
                EventLog.log("download attempt $attempt failed: ${t.message}")
                if (attempt >= 8) throw t
                delay(2_000L * attempt)
            }
        }
        check(part.length() == DefaultModel.SIZE_BYTES) {
            "Download incomplete (${part.length()} of ${DefaultModel.SIZE_BYTES} bytes)"
        }
        if (target.exists()) target.delete()
        check(part.renameTo(target)) { "Could not finalize the model file" }
        onProgress(1f)
        EventLog.log("download complete")
        target
    }

    private suspend fun fetchOnce(onProgress: (Float) -> Unit) {
        val have = if (part.exists()) part.length() else 0L
        if (have >= DefaultModel.SIZE_BYTES) return
        var url = URL(DefaultModel.URL_STR)
        var hops = 0
        var c: HttpURLConnection
        // Resolve redirects by hand so the Range header survives the hop to the CDN.
        while (true) {
            c = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Range", "bytes=$have-")
                setRequestProperty("User-Agent", "VTLo_unrestrict3d/1.0")
            }
            val rc = c.responseCode
            if (rc in 301..308 && hops++ < 6) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
            } else break
        }
        val code = c.responseCode
        check(code == 200 || code == 206) { "HTTP $code" }
        val startAt = if (code == 206) have else 0L
        RandomAccessFile(part, "rw").use { raf ->
            raf.seek(startAt)
            if (startAt == 0L) raf.setLength(0)
            c.inputStream.use { input ->
                val buf = ByteArray(1 shl 18)
                var pos = startAt
                var lastReport = 0L
                while (true) {
                    if (!coroutineContext.isActive) { c.disconnect(); return }
                    val n = input.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    pos += n
                    if (pos - lastReport > (4L shl 20)) {
                        lastReport = pos
                        onProgress(pos.toFloat() / DefaultModel.SIZE_BYTES)
                    }
                }
            }
        }
        c.disconnect()
    }
}
