package com.vtlo.unrestrict3d.pipeline

import com.vtlo.unrestrict3d.core.RootTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** Why code gave up on a step. Only these reasons may recall the AI (REcod3r: "verified stops"). */
sealed class Stop {
    abstract val step: Step
    data class Failed(override val step: Step, val reason: String, val exit: Int, val stdout: String, val stderr: String) : Stop()
    data class NeedsAi(override val step: Step, val question: String, val context: String) : Stop()
    data class Decision(override val step: Step) : Stop()
    data class Blocked(override val step: Step, val reason: String) : Stop()
}

sealed class StepOutcome {
    data class Ok(val note: String = "") : StepOutcome()
    data class Halt(val stop: Stop) : StepOutcome()
}

/**
 * Runs steps deterministically. Contract (same as the REcod3r scripts):
 * exit 0 = ok · other = failure · exit 3 + {"needs_ai": "..."} on stdout = a real decision is needed.
 * When the AI answers a needs_ai stop, the step re-runs with RECOD3R_AI_ANSWER set and revalidates it.
 */
class Executor(
    val runDir: File,
    private val shell: String = "/system/bin/sh",
    private val termuxBin: String = "/data/data/com.termux/files/usr/bin",
) {
    init { runDir.mkdirs() }

    fun subst(s: String, vars: Map<String, String>): String =
        Regex("""\{\{(\w+)\}\}""").replace(s) { vars[it.groupValues[1]] ?: it.value }

    suspend fun run(
        step: Step,
        vars: Map<String, String>,
        aiAnswer: String? = null,
        rootApproved: Boolean = false,
    ): StepOutcome = withContext(Dispatchers.IO) {
        Guard.check(step)?.let { return@withContext StepOutcome.Halt(Stop.Blocked(step, it)) }
        when (step.type) {
            StepType.ASK_AI -> StepOutcome.Halt(Stop.Decision(step))
            StepType.WRITE_FILE -> writeFile(step, vars)
            StepType.SH, StepType.ROOT_SH -> script(step, vars, aiAnswer, step.type == StepType.ROOT_SH && rootApproved)
        }
    }

    private fun writeFile(step: Step, vars: Map<String, String>): StepOutcome {
        val rel = subst(step.path!!, vars)
        if (!Guard.safePath(rel)) return StepOutcome.Halt(Stop.Blocked(step, "unsafe path after substitution"))
        val f = File(runDir, rel)
        f.parentFile?.mkdirs()
        f.writeText(subst(step.content!!, vars))
        return verify(step, ShellResult0(0, "", ""), "wrote $rel (${f.length()} B)")
    }

    private data class ShellResult0(val code: Int, val out: String, val err: String)

    private suspend fun script(step: Step, vars: Map<String, String>, aiAnswer: String?, root: Boolean): StepOutcome {
        val file = File(runDir, ".step_${step.id}.sh")
        file.writeText(subst(step.script!!, vars))
        val env = mutableMapOf("HOME" to runDir.path, "TMPDIR" to File(runDir, ".tmp").apply { mkdirs() }.path)
        aiAnswer?.let { env["RECOD3R_AI_ANSWER"] = it }
        val timeout = step.timeoutSec * 1000L
        val r = if (root) {
            val envs = env.entries.joinToString(" ") { "${it.key}='${it.value.replace("'", "'\\''")}'" }
            RootTools.exec(
                listOf("su", "-c", "cd '${runDir.path}' && PATH=$termuxBin:\$PATH $envs sh '${file.path}'"),
                timeout,
            )
        } else {
            RootTools.exec(listOf(shell, file.path), timeout, env = env, dir = runDir)
        }
        if (r.timedOut) {
            return StepOutcome.Halt(Stop.Failed(step, "timeout after ${step.timeoutSec}s", -1, r.out.takeLast(1500), r.err.takeLast(1500)))
        }
        if (r.code == 3) {
            val q = runCatching { JSONObject(PlanParser.extractJson(r.out) ?: "{}").optString("needs_ai") }.getOrDefault("")
            if (q.isNotBlank()) {
                return StepOutcome.Halt(Stop.NeedsAi(step, q, r.out.takeLast(1500)))
            }
        }
        return verify(step, ShellResult0(r.code, r.out, r.err), "exit ${r.code}")
    }

    private fun verify(step: Step, r: ShellResult0, note: String): StepOutcome {
        val e = step.expect
        fun fail(reason: String) = StepOutcome.Halt(Stop.Failed(step, reason, r.code, r.out.takeLast(1500), r.err.takeLast(1500)))
        if (r.code != e.exit) return fail("exit code ${r.code}, expected ${e.exit}")
        e.file?.let { if (!File(runDir, it).exists()) return fail("expected file missing: $it") }
        e.stdoutRegex?.let {
            val ok = runCatching { Regex(it).containsMatchIn(r.out) }.getOrDefault(true)
            if (!ok) return fail("stdout does not match /$it/")
        }
        return StepOutcome.Ok(note + if (r.out.isNotBlank()) " · " + r.out.trim().lines().last().take(80) else "")
    }
}
