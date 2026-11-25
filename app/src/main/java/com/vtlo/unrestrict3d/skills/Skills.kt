package com.vtlo.unrestrict3d.skills

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Agent Skill in the AI Edge Gallery format: a kebab-case folder with SKILL.md (+ optional scripts/index.html). */
data class Skill(
    val name: String,
    val description: String,
    val instructions: String,
    val dir: File,
    val requireSecret: Boolean,
    val secretDescription: String,
    val homepage: String,
) {
    val hasScript get() = File(dir, "scripts/index.html").exists()
}

object SkillParser {
    fun parse(text: String, dir: File): Skill? {
        val t = text.replace("\r\n", "\n")
        if (!t.startsWith("---")) return null
        val end = t.indexOf("\n---", 3)
        if (end < 0) return null
        val front = t.substring(3, end).trim('\n').lines()
        val body = t.substring(end + 4).trim()
        var name = ""; var desc = ""; var secret = false; var sdesc = ""; var home = ""
        var inMeta = false
        for (raw in front) {
            if (raw.isBlank()) continue
            val indented = raw.startsWith(" ") || raw.startsWith("\t")
            val line = raw.trim()
            val i = line.indexOf(':'); if (i < 0) continue
            val k = line.substring(0, i).trim(); val v = line.substring(i + 1).trim().trim('"', '\'')
            if (!indented) {
                inMeta = k == "metadata"
                when (k) { "name" -> name = v; "description" -> desc = v }
            } else if (inMeta) {
                when (k) {
                    "require-secret" -> secret = v.equals("true", true)
                    "require-secret-description" -> sdesc = v
                    "homepage" -> home = v
                }
            }
        }
        if (name.isBlank()) return null
        return Skill(name, desc, body, dir, secret, sdesc, home)
    }
}

class SkillStore(private val ctx: Context) {
    private val root = File(ctx.filesDir, "skills").apply { mkdirs() }
    private val sp = ctx.getSharedPreferences("skills", Context.MODE_PRIVATE)

    fun list(): List<Skill> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
        runCatching { SkillParser.parse(File(d, "SKILL.md").readText(), d) }.getOrNull()
    }.sortedBy { it.name }

    fun enabled(name: String) = sp.getBoolean("on_$name", true)
    fun setEnabled(name: String, on: Boolean) = sp.edit().putBoolean("on_$name", on).apply()
    fun secret(name: String) = sp.getString("secret_$name", "") ?: ""
    fun setSecret(name: String, v: String) = sp.edit().putString("secret_$name", v).apply()
    fun find(name: String) = list().firstOrNull { it.name == name }

    fun delete(name: String) { File(root, name).deleteRecursively(); sp.edit().remove("on_$name").remove("secret_$name").apply() }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().readText()
    }.getOrNull()

    /** Installs a skill from the URL of its folder (the one where SKILL.md is reachable). */
    fun installFromUrl(input: String): Skill {
        var base = input.trim().trimEnd('/')
        if (base.endsWith("/SKILL.md")) base = base.removeSuffix("/SKILL.md")
        val md = get("$base/SKILL.md") ?: error("Cannot read $base/SKILL.md")
        val tmp = File(root, ".tmp").apply { deleteRecursively(); mkdirs() }
        val skill = SkillParser.parse(md, tmp) ?: error("SKILL.md has no valid front matter (name is required)")
        require(Regex("[a-z0-9][a-z0-9-]*").matches(skill.name)) { "Skill name must be kebab-case" }
        val dest = File(root, skill.name).apply { deleteRecursively(); mkdirs() }
        File(dest, "SKILL.md").writeText(md)
        get("$base/scripts/index.html")?.let { html ->
            File(dest, "scripts").mkdirs(); File(dest, "scripts/index.html").writeText(html)
            Regex("""src\s*=\s*["']([^"':]+?)["']""").findAll(html).map { it.groupValues[1] }.distinct().forEach { rel ->
                if (!rel.startsWith("/") && !rel.contains("..")) get("$base/scripts/$rel")?.let { js ->
                    File(dest, "scripts/$rel").apply { parentFile?.mkdirs() }.writeText(js)
                }
            }
        }
        tmp.deleteRecursively()
        return SkillParser.parse(md, dest)!!
    }

    /** Installs a skill from a folder picked with the system folder picker. */
    fun installFromTree(tree: Uri): Skill {
        val tmp = File(root, ".tmp").apply { deleteRecursively(); mkdirs() }
        copyTree(tree, DocumentsContract.getTreeDocumentId(tree), tmp)
        val md = File(tmp, "SKILL.md")
        require(md.exists()) { "No SKILL.md in that folder" }
        val skill = SkillParser.parse(md.readText(), tmp) ?: error("SKILL.md has no valid front matter (name is required)")
        val dest = File(root, skill.name).apply { deleteRecursively() }
        check(tmp.renameTo(dest)) { "install failed" }
        return SkillParser.parse(File(dest, "SKILL.md").readText(), dest)!!
    }

    private fun copyTree(tree: Uri, docId: String, dest: File) {
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        ctx.contentResolver.query(
            kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val n = c.getString(1); val mime = c.getString(2)
                if (n.contains("..") || n.contains('/')) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) copyTree(tree, id, File(dest, n).apply { mkdirs() })
                else ctx.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { i ->
                    File(dest, n).outputStream().use { o -> i.copyTo(o) }
                }
            }
        }
    }

    // ---- MCP servers (Streamable HTTP) ----
    fun mcpServers(): List<McpServer> = (sp.getString("mcp", "[]") ?: "[]").let { s ->
        runCatching {
            val a = org.json.JSONArray(s)
            (0 until a.length()).map { val o = a.getJSONObject(it); McpServer(o.getString("name"), o.getString("url"), o.optString("token")) }
        }.getOrDefault(emptyList())
    }
    private fun saveMcp(l: List<McpServer>) {
        val a = org.json.JSONArray(); l.forEach { a.put(org.json.JSONObject().put("name", it.name).put("url", it.url).put("token", it.token)) }
        sp.edit().putString("mcp", a.toString()).apply()
    }
    fun addMcp(s: McpServer) = saveMcp(mcpServers().filter { it.name != s.name } + s)
    fun delMcp(name: String) = saveMcp(mcpServers().filter { it.name != name })

    var skillsOn: Boolean
        get() = sp.getBoolean("skills_on", false)
        set(v) { sp.edit().putBoolean("skills_on", v).apply() }
}
