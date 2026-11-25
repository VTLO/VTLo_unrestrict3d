package com.vtlo.unrestrict3d.pipeline

import com.vtlo.unrestrict3d.core.EventLog
import com.vtlo.unrestrict3d.data.AppSettings
import com.vtlo.unrestrict3d.engine.estimateTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// UI-facing state
// ---------------------------------------------------------------------------------------------

data class StepUi(val title: String, val kind: String, val status: String) // status: pending|running|ok|skipped|failed

/** reply: 0 = deny, 1 = approve once, 2 = approve every root step of this run */
data class ApprovalReq(val title: String, val script: String, val reply: CompletableDeferred<Int>)

data class PipelineUi(
    val goal: String = "",
    val phase: String = "",
    val steps: List<StepUi> = emptyList(),
    val log: List<String> = emptyList(),
    val recalls: Int = 0,
    val budget: Int = 0,
    val aiTokens: Int = 0,
    val percent: Int = 0,
    val running: Boolean = true,
    val runDir: String = "",
    val approval: ApprovalReq? = null,
)

/** The model side the dispatcher needs; implemented by the view-model so RAM policy stays in one place. */
interface ModelGate {
    suspend fun ensureLoaded(): Boolean
    suspend fun release(reason: String)
    /** One stateless model call (planner / incident / repair). */
    suspend fun ask(system: String, prompt: String, maxOut: Int): String
}

// ---------------------------------------------------------------------------------------------
// Persistence (checkpoint after every step => a crash loses at most the running step)
// ---------------------------------------------------------------------------------------------

class RunState(
    val dir: File,
    var goal: String,
    var understanding: String,
    var steps: MutableList<Step>,
    var next: Int = 0,
    val vars: MutableMap<String, String> = mutableMapOf(),
    var status: String = "running", // running | done | failed | stopped
    var recalls: Int = 0,
    var aiTokens: Int = 0,
    var approveAllRoot: Boolean = false,
)

object RunStore {
    fun save(s: RunState) {
        val o = JSONObject().apply {
            put("goal", s.goal); put("understanding", s.understanding)
            put("steps", JSONArray().also { a -> s.steps.forEach { a.put(it.toJson()) } })
            put("next", s.next); put("vars", JSONObject(s.vars as Map<*, *>))
            put("status", s.status); put("recalls", s.recalls); put("aiTokens", s.aiTokens)
        }
        File(s.dir, "run.json").writeText(o.toString())
    }

    fun load(dir: File): RunState? = runCatching {
        val o = JSONObject(File(dir, "run.json").readText())
        val arr = o.getJSONArray("steps")
        val steps = mutableListOf<Step>()
        val errs = mutableListOf<String>()
        for (i in 0 until arr.length()) PlanParser.parseStep(arr.getJSONObject(i), i, errs)?.let { steps += it }
        val vars = mutableMapOf<String, String>()
        o.optJSONObject("vars")?.let { v -> v.keys().forEach { k -> vars[k] = v.optString(k) } }
        RunState(
            dir, o.optString("goal"), o.optString("understanding"), steps, o.optInt("next"), vars,
            o.optString("status", "stopped"), o.optInt("recalls"), o.optInt("aiTokens"),
        )
    }.getOrNull()

    /** Newest run that did not finish (for the "resume" banner). */
    fun resumable(root: File): RunState? =
        root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.firstNotNullOfOrNull { d ->
            load(d)?.takeIf { it.status != "done" && it.next < it.steps.size && it.steps.isNotEmpty() && it.status != "failed" }
        }
}

// ---------------------------------------------------------------------------------------------
// The dispatcher: AI understands + plans ONCE, code runs the pipeline, AI is recalled only on a stop.
// ---------------------------------------------------------------------------------------------

class Dispatcher(
    private val gate: ModelGate,
    private val rootAvailable: suspend () -> Boolean,
    private val settings: () -> AppSettings,
    private val ui: ((PipelineUi) -> PipelineUi) -> Unit,
    private val runsRoot: File,
    private val shell: String = "/system/bin/sh",
) {
    private var tokens = 0
    private var recallsUsed = 0

    private fun log(line: String) {
        EventLog.log("pipeline: $line")
        ui { it.copy(log = (it.log + line).takeLast(60)) }
    }

    private suspend fun ai(system: String, prompt: String, maxOut: Int): String {
        val out = gate.ask(system, prompt, maxOut)
        tokens += estimateTokens(system + prompt + out)
        ui { it.copy(aiTokens = tokens) }
        return out
    }

    // ---- entry points ----------------------------------------------------------------------

    suspend fun start(goal: String): String {
        val dir = File(runsRoot, SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())).apply { mkdirs() }
        ui { PipelineUi(goal = goal, phase = "UNDERSTAND + PLAN · local model", runDir = dir.path, budget = settings().aiBudget) }
        if (!gate.ensureLoaded()) return "Model not available: load a model first."
        val plan = plan(goal) ?: return "Planning failed: the model did not produce a valid pipeline after 3 attempts."
        if (plan.askUser != null) {
            ui { it.copy(running = false, phase = "NEEDS YOUR INPUT") }
            return "Before I can build this: ${plan.askUser}"
        }
        val st = RunState(dir, plan.goal, plan.understanding, plan.steps.toMutableList(), aiTokens = tokens)
        RunStore.save(st)
        ui {
            it.copy(
                steps = st.steps.map { s -> StepUi(s.title, s.type.key, "pending") },
                phase = "PLAN READY · ${st.steps.size} steps",
            )
        }
        if (plan.understanding.isNotBlank()) log("understood: " + plan.understanding.take(160))
        if (settings().releaseAfterPlan) {
            ui { it.copy(phase = "MODEL RELEASED · code is running") }
            gate.release("planning done")
        }
        return execute(st)
    }

    suspend fun resume(dir: File): String {
        val st = RunStore.load(dir) ?: return "Cannot read the saved run."
        tokens = st.aiTokens; recallsUsed = st.recalls
        ui {
            PipelineUi(
                goal = st.goal, phase = "RESUMING at step ${st.next + 1}", runDir = dir.path, budget = settings().aiBudget,
                steps = st.steps.mapIndexed { i, s -> StepUi(s.title, s.type.key, if (i < st.next) "ok" else "pending") },
                recalls = recallsUsed, aiTokens = tokens, percent = st.next * 100 / st.steps.size,
            )
        }
        st.status = "running"
        return execute(st)
    }

    // ---- planning --------------------------------------------------------------------------

    private suspend fun plan(goal: String): Plan? {
        var raw = ai(PLANNER, goal, 1800)
        for (attempt in 1..3) {
            val p = PlanParser.parsePlan(raw, goal)
            p.value?.let { return it }
            log("plan rejected (attempt $attempt): " + p.errors.joinToString("; ").take(140))
            if (attempt == 3) break
            raw = ai(
                PLANNER,
                "The validator rejected your previous reply:\n- " + p.errors.joinToString("\n- ") +
                    "\n\nUser goal: $goal\n\nPrevious reply (truncated):\n" + raw.take(1200) +
                    "\n\nReply again with ONE corrected JSON object.",
                1800,
            )
        }
        return null
    }

    // ---- execution -------------------------------------------------------------------------

    private suspend fun execute(st: RunState): String {
        val exec = Executor(st.dir, shell)
        val notes = arrayOfNulls<String>(st.steps.size)
        try {
            while (st.next < st.steps.size) {
                currentCoroutineContext().ensureActive()
                val i = st.next
                setStatus(i, "running")
                ui { it.copy(phase = "STEP ${i + 1}/${st.steps.size} · ${st.steps[i].title}") }
                var outcome = runWithRetries(st, exec, i, null)
                var tries = 0
                while (outcome is StepOutcome.Halt) {
                    val stop = outcome.stop
                    log("stop @ ${stop.step.id}: " + describe(stop))
                    if (++tries > 3) return finish(st, notes, "failed", "step ${i + 1} still failing after AI fixes")
                    val action = resolve(st, stop)
                    if (settings().releaseAfterPlan) gate.release("incident handled")
                    outcome = when (action) {
                        is Action.Replace -> {
                            st.steps[i] = action.step
                            log("AI replaced step ${i + 1}")
                            runWithRetries(st, exec, i, null)
                        }
                        is Action.Answer -> if (stop is Stop.Decision) {
                            st.vars[stop.step.saveAs ?: stop.step.id] = action.value
                            StepOutcome.Ok("decided: ${action.value.take(60)}")
                        } else runWithRetries(st, exec, i, action.value)
                        is Action.Skip -> StepOutcome.Ok("skipped: ${action.reason.take(60)}")
                        is Action.Abort -> return finish(st, notes, "failed", "AI aborted: ${action.reason.take(120)}")
                    }
                }
                val ok = outcome as StepOutcome.Ok
                notes[i] = ok.note
                setStatus(i, if (ok.note.startsWith("skipped")) "skipped" else "ok")
                st.next = i + 1
                st.aiTokens = tokens; st.recalls = recallsUsed
                RunStore.save(st) // checkpoint
                ui { it.copy(percent = st.next * 100 / st.steps.size) }
            }
        } catch (c: CancellationException) {
            st.status = "stopped"; st.aiTokens = tokens; st.recalls = recallsUsed
            runCatching { RunStore.save(st) }
            throw c
        }
        return finish(st, notes, "done", null)
    }

    private suspend fun runWithRetries(st: RunState, exec: Executor, i: Int, aiAnswer: String?): StepOutcome {
        val step = st.steps[i]
        if (step.type == StepType.ROOT_SH) {
            if (!rootAvailable()) return StepOutcome.Halt(Stop.Blocked(step, "root is not available on this device"))
            if (!st.approveAllRoot) {
                val reply = CompletableDeferred<Int>()
                ui { it.copy(approval = ApprovalReq(step.title, step.script.orEmpty(), reply), phase = "WAITING FOR ROOT APPROVAL") }
                val r = reply.await()
                ui { it.copy(approval = null) }
                if (r == 0) return StepOutcome.Ok("skipped: root step denied by user")
                if (r == 2) st.approveAllRoot = true
            }
        }
        var attempt = 0
        while (true) {
            val r = exec.run(step, st.vars, aiAnswer, rootApproved = true)
            if (r is StepOutcome.Halt && r.stop is Stop.Failed && attempt < step.retry) {
                attempt++
                log("retry $attempt/${step.retry} (code, no AI): " + (r.stop as Stop.Failed).reason)
                delay(1000)
                continue
            }
            return r
        }
    }

    private fun describe(s: Stop) = when (s) {
        is Stop.Failed -> "failed: ${s.reason}"
        is Stop.NeedsAi -> "needs a decision: ${s.question.take(80)}"
        is Stop.Decision -> "decision point: ${s.step.question?.take(80)}"
        is Stop.Blocked -> "blocked: ${s.reason}"
    }

    // ---- AI recall (only reached from a verified stop) ---------------------------------------

    private suspend fun resolve(st: RunState, stop: Stop): Action {
        recallsUsed++
        val budget = settings().aiBudget
        ui { it.copy(recalls = recallsUsed, budget = budget) }
        if (recallsUsed > budget) return Action.Abort("AI budget ($budget recalls) exhausted; run saved, you can resume it")
        ui { it.copy(phase = "STOP · recalling AI ($recallsUsed/$budget)") }
        if (!gate.ensureLoaded()) return Action.Abort("model could not be loaded")

        val prompt = incident(st, stop)
        var parsed = PlanParser.parseAction(ai(INCIDENT, prompt, 900))
        var tries = 0
        while (parsed.value == null && tries++ < 2) {
            parsed = PlanParser.parseAction(
                ai(INCIDENT, "Your reply was rejected: ${parsed.errors.joinToString("; ")}. Reply again with ONE valid JSON.\n\n$prompt", 900)
            )
        }
        val action = parsed.value ?: return Action.Abort("AI reply invalid: " + parsed.errors.joinToString("; "))

        // Revalidate in code (REcod3r: the AI proposes, the code decides).
        return when (action) {
            is Action.Replace -> Guard.check(action.step)?.let { Action.Abort("replacement $it") } ?: action
            is Action.Answer -> when {
                stop is Stop.Decision && stop.step.choices.isNotEmpty() &&
                    stop.step.choices.none { it.equals(action.value.trim(), true) } ->
                    Action.Abort("answer \"${action.value.take(30)}\" is not one of ${stop.step.choices}")
                stop is Stop.Failed || stop is Stop.Blocked -> Action.Abort("an answer cannot fix a failed step")
                else -> action.copy(value = action.value.trim())
            }
            else -> action
        }
    }

    private fun incident(st: RunState, stop: Stop): String {
        val step = stop.step
        val sb = StringBuilder()
        sb.append("GOAL: ").append(st.goal.take(300)).append('\n')
        sb.append("STEP ${st.steps.indexOf(step) + 1}/${st.steps.size}: ").append(step.toJson().toString().take(1800)).append('\n')
        when (stop) {
            is Stop.Failed -> sb.append("FAILED: ${stop.reason}\nSTDERR(tail): ${stop.stderr.takeLast(900)}\nSTDOUT(tail): ${stop.stdout.takeLast(600)}\n")
            is Stop.NeedsAi -> sb.append("THE SCRIPT ASKS: ${stop.question}\nCONTEXT: ${stop.context.takeLast(900)}\n")
            is Stop.Decision -> sb.append("DECISION NEEDED: ${stop.step.question}\n" + if (stop.step.choices.isNotEmpty()) "ALLOWED ANSWERS: ${stop.step.choices}\n" else "")
            is Stop.Blocked -> sb.append("BLOCKED BY SAFETY CHECK: ${stop.reason}\n")
        }
        if (st.vars.isNotEmpty()) sb.append("VARS: ").append(st.vars.entries.joinToString { "${it.key}=${it.value.take(60)}" }).append('\n')
        return sb.toString()
    }

    // ---- finishing -------------------------------------------------------------------------

    private fun setStatus(i: Int, status: String) = ui { u ->
        u.copy(steps = u.steps.mapIndexed { k, s -> if (k == i) s.copy(status = status) else s })
    }

    /** The final report is written by code, not by the AI (zero tokens). */
    private fun finish(st: RunState, notes: Array<String?>, status: String, why: String?): String {
        st.status = status; st.aiTokens = tokens; st.recalls = recallsUsed
        RunStore.save(st)
        val ok = st.steps.indices.count { it < st.next }
        val sb = StringBuilder()
        sb.append("# ").append(st.goal).append("\n\nstatus: ").append(status).append(why?.let { " ($it)" } ?: "")
            .append("\ncode steps done: $ok/${st.steps.size} · AI recalls: $recallsUsed · AI tokens ≈ $tokens\n\n")
        st.steps.forEachIndexed { i, s ->
            val mark = if (i < st.next) "✓" else if (status != "done" && i == st.next) "✗" else "·"
            sb.append("$mark ${s.title}").append(notes.getOrNull(i)?.let { " — $it" } ?: "").append('\n')
        }
        runCatching { File(st.dir, "report.md").writeText(sb.toString()) }
        ui { it.copy(running = false, phase = if (status == "done") "DONE" else "STOPPED", percent = if (status == "done") 100 else it.percent) }
        val head = if (status == "done") "Pipeline done" else "Pipeline stopped"
        return "$head · $ok/${st.steps.size} steps by code, $recallsUsed AI recall(s), ≈$tokens AI tokens." +
            (why?.let { "\n$it" } ?: "") + "\nFolder: ${st.dir.name} (report.md inside)"
    }

    companion object {
        const val PLANNER =
            "You are the PLANNER of an automation pipeline on an Android phone. " +
                "1) Understand the user's goal. 2) Write the COMPLETE pipeline as JSON. A program, not you, will run every step, " +
                "so put all logic in shell scripts and files. You are called again ONLY if a step fails or cannot be decided by code.\n" +
                "Environment: Android toybox sh (ls cat cp mv rm mkdir find grep sed awk tar wc sort uniq head tail tee date). " +
                "No python/git/gradle unless type root_sh (runs as root with Termux binaries in PATH). " +
                "Steps run inside the run folder: use relative paths only, never '..'.\n" +
                "Step types: write_file {path,content} | sh {script} | root_sh {script} (only if root is truly needed) | " +
                "ask_ai {question,choices?,save_as} (only for real judgement, never for mechanical work).\n" +
                "Every sh step should verify its own result and carry \"expect\": {\"exit\":0,\"file\":\"relpath\",\"stdout_regex\":\"...\"}. " +
                "A script that needs a human-like decision prints {\"needs_ai\":\"question\"} and exits with code 3. " +
                "Insert answers saved by ask_ai steps with {{save_as_name}}. Max 25 steps, prefer few bigger scripts.\n" +
                "If the goal is too vague to plan, reply {\"ask_user\":\"one short question\"}.\n" +
                "Reply with ONE JSON object and nothing else:\n" +
                "{\"goal\":\"...\",\"understanding\":\"one sentence\",\"steps\":[" +
                "{\"id\":\"s1\",\"title\":\"Write script\",\"type\":\"write_file\",\"path\":\"build.sh\",\"content\":\"echo hi > out.txt\"}," +
                "{\"id\":\"s2\",\"title\":\"Run it\",\"type\":\"sh\",\"script\":\"sh build.sh\",\"expect\":{\"exit\":0,\"file\":\"out.txt\"},\"retry\":1}]}"

        const val INCIDENT =
            "A step of an automated pipeline stopped and the code cannot decide. Choose exactly ONE action and reply with ONE JSON object only:\n" +
                "{\"action\":\"replace\",\"step\":{same step schema: id,title,type,script|path+content,expect}}  (fix the step)\n" +
                "{\"action\":\"answer\",\"value\":\"...\"}  (only for a decision / needs_ai question)\n" +
                "{\"action\":\"skip\",\"reason\":\"...\"}  (step is optional)\n" +
                "{\"action\":\"abort\",\"reason\":\"...\"}  (cannot be fixed)\n" +
                "Keep the fix minimal. Environment: Android toybox sh, relative paths only."
    }
}
