package com.vtlo.unrestrict3d.pipeline

import org.json.JSONArray
import org.json.JSONObject

enum class StepType(val key: String) { WRITE_FILE("write_file"), SH("sh"), ROOT_SH("root_sh"), ASK_AI("ask_ai") }

data class Expect(val exit: Int = 0, val file: String? = null, val stdoutRegex: String? = null)

data class Step(
    val id: String,
    val title: String,
    val type: StepType,
    val path: String? = null,
    val content: String? = null,
    val script: String? = null,
    val question: String? = null,
    val choices: List<String> = emptyList(),
    val saveAs: String? = null,
    val expect: Expect = Expect(),
    val retry: Int = 1,
    val timeoutSec: Int = 120,
)

data class Plan(
    val goal: String,
    val understanding: String,
    val steps: List<Step>,
    val askUser: String? = null,
)

/** What the AI may answer when the code gives up on a step (REcod3r: AI decides, code revalidates). */
sealed class Action {
    data class Replace(val step: Step) : Action()
    data class Skip(val reason: String) : Action()
    data class Abort(val reason: String) : Action()
    data class Answer(val value: String) : Action()
}

data class Parsed<T>(val value: T?, val errors: List<String>)

fun Step.toJson(): JSONObject = JSONObject().apply {
    put("id", id); put("title", title); put("type", type.key)
    path?.let { put("path", it) }
    content?.let { put("content", it) }
    script?.let { put("script", it) }
    question?.let { put("question", it) }
    if (choices.isNotEmpty()) put("choices", JSONArray(choices))
    saveAs?.let { put("save_as", it) }
    put("expect", JSONObject().apply {
        put("exit", expect.exit)
        expect.file?.let { put("file", it) }
        expect.stdoutRegex?.let { put("stdout_regex", it) }
    })
    put("retry", retry); put("timeout", timeoutSec)
}

object PlanParser {

    const val MAX_STEPS = 40

    /** Finds the JSON object inside a model reply (handles ``` fences and chatter around it). */
    fun extractJson(raw: String): String? {
        val s = raw.replace(Regex("```[a-zA-Z]*"), "")
        val a = s.indexOf('{')
        val b = s.lastIndexOf('}')
        return if (a >= 0 && b > a) s.substring(a, b + 1) else null
    }

    fun parsePlan(raw: String, fallbackGoal: String): Parsed<Plan> {
        val json = extractJson(raw) ?: return Parsed(null, listOf("no JSON object found in the reply"))
        val o = try { JSONObject(json) } catch (t: Throwable) {
            return Parsed(null, listOf("invalid JSON: ${t.message?.take(100)}"))
        }
        val ask = o.optString("ask_user", "").trim()
        if (ask.isNotEmpty() && !o.has("steps")) {
            return Parsed(Plan(fallbackGoal, "", emptyList(), ask), emptyList())
        }
        val arr = o.optJSONArray("steps") ?: return Parsed(null, listOf("missing \"steps\" array"))
        val errors = mutableListOf<String>()
        if (arr.length() == 0) errors += "\"steps\" is empty"
        if (arr.length() > MAX_STEPS) errors += "too many steps (max $MAX_STEPS)"
        val steps = mutableListOf<Step>()
        val seen = HashSet<String>()
        for (i in 0 until minOf(arr.length(), MAX_STEPS)) {
            val so = arr.optJSONObject(i)
            if (so == null) { errors += "step ${i + 1} is not an object"; continue }
            val st = parseStep(so, i, errors) ?: continue
            val uniq = if (seen.add(st.id)) st else st.copy(id = st.id + "_" + (i + 1)).also { seen.add(it.id) }
            steps += uniq
        }
        if (errors.isNotEmpty()) return Parsed(null, errors)
        return Parsed(
            Plan(o.optString("goal", fallbackGoal).ifBlank { fallbackGoal }, o.optString("understanding", ""), steps),
            emptyList(),
        )
    }

    private fun typeOf(s: String): StepType? = when (s.trim().lowercase()) {
        "write_file", "file", "write" -> StepType.WRITE_FILE
        "sh", "shell", "bash", "script" -> StepType.SH
        "root_sh", "root", "su" -> StepType.ROOT_SH
        "ask_ai", "ask", "ai", "decision" -> StepType.ASK_AI
        else -> null
    }

    fun parseStep(o: JSONObject, index: Int, errors: MutableList<String>): Step? {
        val label = "step ${index + 1}"
        val type = typeOf(o.optString("type", "")) ?: run {
            errors += "$label: unknown type \"${o.optString("type")}\" (use write_file|sh|root_sh|ask_ai)"
            return null
        }
        val id = o.optString("id", "").trim().ifEmpty { "s${index + 1}" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24)
        val title = o.optString("title", "").trim().ifEmpty { id }.take(80)
        val exp = o.optJSONObject("expect")
        val expect = Expect(
            exit = exp?.optInt("exit", 0) ?: 0,
            file = exp?.optString("file", "")?.trim()?.ifEmpty { null },
            stdoutRegex = exp?.optString("stdout_regex", "")?.ifEmpty { null },
        )
        val script = listOf("script", "command", "cmd", "code").map { o.optString(it, "") }.firstOrNull { it.isNotBlank() }
        val choices = o.optJSONArray("choices")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val st = Step(
            id = id, title = title, type = type,
            path = o.optString("path", "").trim().ifEmpty { null },
            content = if (o.has("content")) o.optString("content") else null,
            script = script,
            question = o.optString("question", "").ifBlank { null },
            choices = choices,
            saveAs = o.optString("save_as", "").trim().ifEmpty { null }?.replace(Regex("[^A-Za-z0-9_]"), "_"),
            expect = expect,
            retry = o.optInt("retry", 1).coerceIn(0, 3),
            timeoutSec = o.optInt("timeout", 120).coerceIn(5, 900),
        )
        val problem = Guard.validateShape(st)
        if (problem != null) { errors += "$label: $problem"; return null }
        return st
    }

    /** Parses the AI's answer to an incident. */
    fun parseAction(raw: String): Parsed<Action> {
        val json = extractJson(raw) ?: return Parsed(null, listOf("no JSON object found"))
        val o = try { JSONObject(json) } catch (t: Throwable) { return Parsed(null, listOf("invalid JSON")) }
        return when (o.optString("action", "").lowercase()) {
            "replace" -> {
                val so = o.optJSONObject("step") ?: return Parsed(null, listOf("replace needs a \"step\" object"))
                val errs = mutableListOf<String>()
                val st = parseStep(so, 0, errs)
                if (st == null) Parsed(null, errs) else Parsed(Action.Replace(st), emptyList())
            }
            "skip" -> Parsed(Action.Skip(o.optString("reason", "")), emptyList())
            "abort" -> Parsed(Action.Abort(o.optString("reason", "")), emptyList())
            "answer" -> {
                val v = o.opt("value")?.toString().orEmpty()
                if (v.isBlank()) Parsed(null, listOf("answer needs a non-empty \"value\"")) else Parsed(Action.Answer(v), emptyList())
            }
            else -> Parsed(null, listOf("\"action\" must be replace|skip|abort|answer"))
        }
    }
}

/** Static safety net for model-written code. Not a sandbox: the app's Android sandbox is the sandbox. */
object Guard {

    fun safePath(p: String): Boolean =
        p.isNotBlank() && p.length <= 200 && !p.startsWith("/") && !p.startsWith("~") &&
            p.split('/', '\\').none { it == ".." } && !p.contains('\u0000')

    /** Structural validation (cheap, runs at parse time). */
    fun validateShape(s: Step): String? = when (s.type) {
        StepType.WRITE_FILE -> when {
            s.path == null || !safePath(s.path) -> "write_file needs a relative \"path\" without .."
            s.content == null -> "write_file needs \"content\""
            s.content.length > 200_000 -> "content too large"
            else -> null
        }
        StepType.SH, StepType.ROOT_SH -> when {
            s.script.isNullOrBlank() -> "${s.type.key} needs a \"script\""
            s.script.length > 20_000 -> "script too large"
            else -> null
        }
        StepType.ASK_AI -> if (s.question.isNullOrBlank()) "ask_ai needs a \"question\"" else null
    }.let { p -> p ?: s.expect.file?.let { if (!safePath(it)) "expect.file must be a relative path" else null } }

    /** Structural check only (no content filtering). */
    fun check(s: Step): String? {
        when (s.type) {
            StepType.SH, StepType.ROOT_SH -> return null
            StepType.WRITE_FILE -> return if (s.path != null && safePath(s.path)) null else "unsafe path"
            StepType.ASK_AI -> return null
        }
        return null
    }
}
