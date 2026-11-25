package com.vtlo.unrestrict3d.skills

import com.google.ai.edge.litertlm.OpenApiTool
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class McpServer(val name: String, val url: String, val token: String)

/** Minimal MCP client over Streamable HTTP (JSON or SSE replies). */
class McpClient(private val server: McpServer) {
    private var session: String? = null
    private var nextId = 1

    private fun post(body: JSONObject, expectReply: Boolean): JSONObject? {
        val c = URL(server.url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 90000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json, text/event-stream")
        if (server.token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${server.token}")
        session?.let { c.setRequestProperty("Mcp-Session-Id", it) }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        c.getHeaderField("Mcp-Session-Id")?.let { session = it }
        if (code !in 200..299) error("HTTP $code from ${server.name}")
        if (!expectReply) return null
        val ct = c.contentType.orEmpty()
        val text = c.inputStream.bufferedReader().readText()
        val id = body.optInt("id", -1)
        if (ct.contains("event-stream")) {
            for (line in text.lines()) {
                if (!line.startsWith("data:")) continue
                val o = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: continue
                if (o.optInt("id", -2) == id) return o
            }
            error("no reply in stream")
        }
        return JSONObject(text)
    }

    private fun rpc(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val r = post(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params), true)!!
        r.optJSONObject("error")?.let { error(it.optString("message", "MCP error")) }
        return r.optJSONObject("result") ?: JSONObject()
    }

    fun init() {
        rpc("initialize", JSONObject().put("protocolVersion", "2025-03-26").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "VTLo_unrestrict3d").put("version", "2.1")))
        runCatching { post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), false) }
    }

    fun listTools(): JSONArray = rpc("tools/list", JSONObject()).optJSONArray("tools") ?: JSONArray()

    fun callTool(name: String, args: JSONObject): String {
        val r = rpc("tools/call", JSONObject().put("name", name).put("arguments", args))
        val parts = r.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until parts.length()) parts.getJSONObject(i).let { if (it.optString("type") == "text") sb.append(it.optString("text")) else sb.append("[${it.optString("type")}]") }
        return (if (r.optBoolean("isError")) "ERROR: " else "") + sb.toString().take(6000)
    }
}

/** One MCP tool exposed to the model. */
class McpTool(private val client: McpClient, private val def: JSONObject, private val onUse: (String) -> Unit) : OpenApiTool {
    private val orig = def.getString("name")
    val safeName = orig.replace(Regex("[^A-Za-z0-9_]"), "_")

    override fun getToolDescriptionJsonString(): String {
        val schema = def.optJSONObject("inputSchema") ?: JSONObject().put("type", "object").put("properties", JSONObject())
        if (!schema.has("type")) schema.put("type", "object")
        return JSONObject().put("name", safeName).put("description", def.optString("description").take(300)).put("parameters", schema).toString()
    }

    override fun execute(paramsJsonString: String): String {
        onUse("$safeName ${paramsJsonString.take(120)}")
        return runCatching {
            JSONObject().put("result", client.callTool(orig, JSONObject(paramsJsonString.ifBlank { "{}" }))).toString()
        }.getOrElse { JSONObject().put("error", it.message ?: it.javaClass.simpleName).toString() }
    }
}
