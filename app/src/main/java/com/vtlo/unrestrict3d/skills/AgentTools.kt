package com.vtlo.unrestrict3d.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Builds the Gallery-style tool set: load_skill, run_js, run_intent + every MCP tool. */
class AgentTools(private val ctx: Context, private val store: SkillStore, private val onUse: (String) -> Unit) {
    private val js = JsRunner(ctx.applicationContext)

    var mcpNote: String = ""
        private set

    private fun err(m: String) = JSONObject().put("error", m).toString()

    private val loadSkill = object : OpenApiTool {
        override fun getToolDescriptionJsonString() = """{"name":"load_skill","description":"Load the full instructions of a skill by name.","parameters":{"type":"object","properties":{"skill_name":{"type":"string","description":"The skill name."}},"required":["skill_name"]}}"""
        override fun execute(paramsJsonString: String): String = runCatching {
            val name = JSONObject(paramsJsonString).getString("skill_name")
            onUse("load_skill $name")
            val s = store.find(name)?.takeIf { store.enabled(it.name) } ?: return err("unknown skill: $name")
            JSONObject().put("skill_name", s.name).put("skill_instructions", s.instructions.take(6000)).toString()
        }.getOrElse { err(it.message ?: "bad arguments") }
    }

    private val runJs = object : OpenApiTool {
        override fun getToolDescriptionJsonString() = """{"name":"run_js","description":"Run the JavaScript of a skill and return its result.","parameters":{"type":"object","properties":{"skill_name":{"type":"string","description":"The skill name."},"script_name":{"type":"string","description":"Script file, default index.html."},"data":{"type":"string","description":"JSON string with the parameters for the script."}},"required":["skill_name","data"]}}"""
        override fun execute(paramsJsonString: String): String = runCatching {
            val p = JSONObject(paramsJsonString)
            val name = p.getString("skill_name")
            onUse("run_js $name ${p.optString("data").take(100)}")
            val s = store.find(name)?.takeIf { store.enabled(it.name) } ?: return err("unknown skill: $name")
            runBlocking { js.run(s.dir, p.optString("script_name"), p.optString("data", "{}"), if (s.requireSecret) store.secret(s.name) else "") }
        }.getOrElse { err(it.message ?: "bad arguments") }
    }

    private val runIntent = object : OpenApiTool {
        override fun getToolDescriptionJsonString() = """{"name":"run_intent","description":"Run a native phone action. Intents: send_email(extra_email,extra_subject,extra_text), send_sms(extra_phone_number,extra_sms_body), open_url(extra_url), dial(extra_phone_number).","parameters":{"type":"object","properties":{"intent":{"type":"string","description":"send_email, send_sms, open_url or dial."},"parameters":{"type":"string","description":"JSON string with the intent values."}},"required":["intent","parameters"]}}"""
        override fun execute(paramsJsonString: String): String = runCatching {
            val p = JSONObject(paramsJsonString)
            val kind = p.getString("intent")
            val a = JSONObject(p.optString("parameters", "{}"))
            onUse("run_intent $kind")
            val i = when (kind) {
                "send_email" -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).putExtra(Intent.EXTRA_EMAIL, arrayOf(a.optString("extra_email")))
                    .putExtra(Intent.EXTRA_SUBJECT, a.optString("extra_subject")).putExtra(Intent.EXTRA_TEXT, a.optString("extra_text"))
                "send_sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(a.optString("extra_phone_number")))).putExtra("sms_body", a.optString("extra_sms_body"))
                "open_url" -> Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("extra_url")))
                "dial" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(a.optString("extra_phone_number"))))
                else -> return err("unknown intent: $kind")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            JSONObject().put("result", "intent started").toString()
        }.getOrElse { err(it.message ?: "intent failed") }
    }

    /** Connects to the MCP servers (best effort) and returns every tool. */
    fun build(): List<ToolProvider> {
        val list = mutableListOf<ToolProvider>(tool(loadSkill), tool(runJs), tool(runIntent))
        val notes = mutableListOf<String>()
        for (srv in store.mcpServers()) {
            runCatching {
                val c = McpClient(srv); c.init()
                val tools = c.listTools()
                for (i in 0 until tools.length()) list += tool(McpTool(c, tools.getJSONObject(i), onUse))
                notes += "${srv.name}: ${tools.length()} tools"
            }.onFailure { notes += "${srv.name}: failed (${it.message})" }
        }
        mcpNote = notes.joinToString("; ")
        return list
    }

    fun systemPrompt(): String {
        val skills = store.list().filter { store.enabled(it.name) }
        return buildString {
            append("You are a helpful assistant with access to tools and skills.\n")
            if (skills.isNotEmpty()) {
                append("Skills available:\n")
                skills.forEach { append("- ${it.name}: ${it.description}\n") }
                append("When a request matches a skill, first call load_skill with its name, then follow the loaded instructions (they tell you which of run_js or run_intent to call and with what data).\n")
            }
            append("Use other tools when they help. Otherwise answer directly.")
        }
    }
}
