package com.vtlo.unrestrict3d.skills

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File

/** Runs a skill's scripts/index.html in a hidden WebView and calls window.ai_edge_gallery_get_result(data, secret). */
class JsRunner(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())

    private class Bridge(val out: CompletableDeferred<String>) {
        @JavascriptInterface fun done(s: String) { out.complete(s) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun run(dir: File, script: String, dataJson: String, secret: String): String {
        val page = File(dir, "scripts/" + script.ifBlank { "index.html" }.removePrefix("/").replace("..", ""))
        if (!page.exists()) return JSONObject().put("error", "script not found: ${page.name}").toString()
        val out = CompletableDeferred<String>()
        val logs = StringBuilder()
        var web: WebView? = null
        main.post {
            runCatching {
                val w = WebView(ctx); web = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.allowFileAccess = true
                @Suppress("DEPRECATION") run {
                    w.settings.allowUniversalAccessFromFileURLs = true
                    w.settings.allowFileAccessFromFileURLs = true
                }
                w.addJavascriptInterface(Bridge(out), "AndroidBridge")
                w.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage): Boolean { if (logs.length < 2000) logs.append(m.message()).append('\n'); return true }
                }
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, url: String) {
                        val call = "(async function(){try{var n=0;while(typeof window.ai_edge_gallery_get_result!=='function'&&n<50){await new Promise(r=>setTimeout(r,100));n++;}" +
                            "if(typeof window.ai_edge_gallery_get_result!=='function'){AndroidBridge.done(JSON.stringify({error:'ai_edge_gallery_get_result is not defined'}));return;}" +
                            "var r=await window.ai_edge_gallery_get_result(${JSONObject.quote(dataJson)},${JSONObject.quote(secret)});" +
                            "AndroidBridge.done(typeof r==='string'?r:JSON.stringify(r));}catch(e){AndroidBridge.done(JSON.stringify({error:String((e&&e.message)||e)}));}})();"
                        v.evaluateJavascript(call, null)
                    }
                }
                w.loadUrl("file://" + page.absolutePath)
            }.onFailure { out.complete(JSONObject().put("error", it.message ?: "webview failed").toString()) }
        }
        return try {
            val raw = withTimeout(60_000) { out.await() }
            shape(raw)
        } catch (t: Throwable) {
            JSONObject().put("error", "timeout or failure: ${t.message}. console: ${logs.toString().take(300)}").toString()
        } finally {
            main.post { runCatching { web?.destroy() } }
        }
    }

    /** Keeps what the model needs: result / error, plus a note when the skill produced an image or webview. */
    private fun shape(raw: String): String {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return JSONObject().put("result", raw.take(4000)).toString()
        val r = JSONObject()
        if (o.has("error")) r.put("error", o.opt("error").toString().take(1000))
        if (o.has("result")) r.put("result", o.opt("result").toString().take(4000))
        if (o.has("image")) r.put("note", "an image was produced (not displayable here)")
        if (o.has("webview")) r.put("note", "a webview was requested (not displayable here)")
        if (r.length() == 0) r.put("result", raw.take(4000))
        return r.toString()
    }
}
