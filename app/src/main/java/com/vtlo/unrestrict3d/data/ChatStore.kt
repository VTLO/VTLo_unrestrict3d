package com.vtlo.unrestrict3d.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredMessage(val role: String, val text: String)
data class StoredChat(val memory: String, val messages: List<StoredMessage>)

/** Chat transcript on disk: a crash or a kill no longer loses the conversation. */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat.json")

    @Synchronized
    fun save(memory: String, messages: List<StoredMessage>) {
        runCatching {
            val arr = JSONArray()
            messages.takeLast(80).forEach { arr.put(JSONObject().put("r", it.role).put("t", it.text)) }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JSONObject().put("memory", memory).put("messages", arr).toString())
            tmp.renameTo(file)
        }
    }

    fun load(): StoredChat? = runCatching {
        if (!file.exists()) return null
        val o = JSONObject(file.readText())
        val a = o.optJSONArray("messages") ?: JSONArray()
        StoredChat(o.optString("memory"), (0 until a.length()).map {
            val m = a.getJSONObject(it); StoredMessage(m.optString("r"), m.optString("t"))
        })
    }.getOrNull()
}
