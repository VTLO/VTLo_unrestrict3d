package com.vtlo.unrestrict3d

import com.vtlo.unrestrict3d.data.AppSettings
import com.vtlo.unrestrict3d.pipeline.Action
import com.vtlo.unrestrict3d.pipeline.Dispatcher
import com.vtlo.unrestrict3d.pipeline.Executor
import com.vtlo.unrestrict3d.pipeline.Guard
import com.vtlo.unrestrict3d.pipeline.ModelGate
import com.vtlo.unrestrict3d.pipeline.PipelineUi
import com.vtlo.unrestrict3d.pipeline.PlanParser
import com.vtlo.unrestrict3d.pipeline.RunStore
import com.vtlo.unrestrict3d.pipeline.Step
import com.vtlo.unrestrict3d.pipeline.StepOutcome
import com.vtlo.unrestrict3d.pipeline.StepType
import com.vtlo.unrestrict3d.pipeline.Stop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PipelineTest {

    private fun tmp(): File = Files.createTempDirectory("vtlo").toFile()

    private val goodPlan = """
        Sure! ```json
        {"goal":"make hello","understanding":"write and run","steps":[
          {"id":"s1","title":"write","type":"write_file","path":"hello.sh","content":"echo hi > out.txt"},
          {"id":"s2","title":"run","type":"sh","script":"sh hello.sh","expect":{"exit":0,"file":"out.txt"}}]}
        ```
    """.trimIndent()

    // ---- parser ---------------------------------------------------------------------------

    @Test fun parsesPlanInsideFencesAndChatter() {
        val p = PlanParser.parsePlan(goodPlan, "g")
        assertNotNull(p.errors.toString(), p.value)
        assertEquals(2, p.value!!.steps.size)
        assertEquals(StepType.WRITE_FILE, p.value!!.steps[0].type)
    }

    @Test fun rejectsEscapingPathsAndUnknownTypes() {
        val bad = """{"steps":[{"type":"write_file","path":"../x","content":"a"},{"type":"magic"}]}"""
        val p = PlanParser.parsePlan(bad, "g")
        assertNull(p.value)
        assertTrue(p.errors.size >= 2)
    }

    @Test fun askUserShortCircuit() {
        val p = PlanParser.parsePlan("""{"ask_user":"which folder?"}""", "g")
        assertEquals("which folder?", p.value!!.askUser)
    }

    @Test fun parsesActions() {
        assertTrue(PlanParser.parseAction("""{"action":"abort","reason":"x"}""").value is Action.Abort)
        assertTrue(PlanParser.parseAction("""{"action":"answer","value":"yes"}""").value is Action.Answer)
        assertNull(PlanParser.parseAction("""{"action":"answer","value":""}""").value)
        val r = PlanParser.parseAction("""{"action":"replace","step":{"type":"sh","script":"echo ok"}}""")
        assertTrue(r.value is Action.Replace)
    }

    // ---- guard ----------------------------------------------------------------------------

    @Test fun guardBlocksDestructiveCommands() {
        fun sh(s: String) = Step("a", "a", StepType.SH, script = s)
        assertNotNull(Guard.check(sh("rm -rf /")))
        assertNotNull(Guard.check(sh("rm -rf /sdcard")))
        assertNotNull(Guard.check(sh("dd if=x of=/dev/block/sda")))
        assertNotNull(Guard.check(sh("reboot")))
        assertNotNull(Guard.check(sh("echo 1; mkfs.ext4 /dev/x")))
        assertNull(Guard.check(sh("rm -rf build && mkdir build")))
        assertNull(Guard.check(sh("ls -la | grep foo")))
    }

    // ---- executor -------------------------------------------------------------------------

    @Test fun executorRunsWritesAndVerifies() = runBlocking {
        val dir = tmp()
        val ex = Executor(dir, shell = "/bin/sh")
        val w = Step("w", "w", StepType.WRITE_FILE, path = "a/b.sh", content = "echo {{who}} > hi.txt")
        assertTrue(ex.run(w, mapOf("who" to "bob")) is StepOutcome.Ok)
        val r = Step("r", "r", StepType.SH, script = "sh a/b.sh", expect = com.vtlo.unrestrict3d.pipeline.Expect(file = "hi.txt"))
        assertTrue(ex.run(r, emptyMap()) is StepOutcome.Ok)
        assertEquals("bob", File(dir, "hi.txt").readText().trim())
    }

    @Test fun executorReportsFailureNeedsAiAndTimeout() = runBlocking {
        val ex = Executor(tmp(), shell = "/bin/sh")
        val fail = ex.run(Step("f", "f", StepType.SH, script = "echo boom >&2; exit 2"), emptyMap())
        assertTrue((fail as StepOutcome.Halt).stop is Stop.Failed)
        val ai = ex.run(Step("n", "n", StepType.SH, script = """echo '{"needs_ai":"pick one"}'; exit 3"""), emptyMap())
        assertTrue((ai as StepOutcome.Halt).stop is Stop.NeedsAi)
        val to = ex.run(Step("t", "t", StepType.SH, script = "sleep 20", timeoutSec = 5), emptyMap())
        assertTrue(((to as StepOutcome.Halt).stop as Stop.Failed).reason.contains("timeout"))
        val blocked = ex.run(Step("b", "b", StepType.SH, script = "reboot"), emptyMap())
        assertTrue((blocked as StepOutcome.Halt).stop is Stop.Blocked)
    }

    @Test fun aiAnswerIsPassedToTheScriptForRevalidation() = runBlocking {
        val ex = Executor(tmp(), shell = "/bin/sh")
        val s = Step("n", "n", StepType.SH, script = """[ "${'$'}RECOD3R_AI_ANSWER" = "blue" ] || { echo '{"needs_ai":"color?"}'; exit 3; }; echo ok""")
        assertTrue(ex.run(s, emptyMap()) is StepOutcome.Halt)
        assertTrue(ex.run(s, emptyMap(), aiAnswer = "blue") is StepOutcome.Ok)
        assertTrue(ex.run(s, emptyMap(), aiAnswer = "red") is StepOutcome.Halt)
    }

    // ---- dispatcher: the whole point ------------------------------------------------------

    private class FakeGate(val replies: MutableList<String>) : ModelGate {
        var asks = 0; var loads = 0; var releases = 0
        override suspend fun ensureLoaded(): Boolean { loads++; return true }
        override suspend fun release(reason: String) { releases++ }
        override suspend fun ask(system: String, prompt: String, maxOut: Int): String { asks++; return replies.removeAt(0) }
    }

    private fun dispatcher(gate: FakeGate, root: File, ui: MutableList<PipelineUi>, budget: Int = 4) = Dispatcher(
        gate, { false }, { AppSettings(aiBudget = budget) },
        { f -> ui += f(ui.lastOrNull() ?: PipelineUi()) }, root, shell = "/bin/sh",
    )

    @Test fun happyPathUsesTheModelExactlyOnce() = runBlocking {
        val root = tmp(); val ui = mutableListOf<PipelineUi>()
        val gate = FakeGate(mutableListOf(goodPlan))
        val msg = dispatcher(gate, root, ui).start("make hello")
        assertTrue(msg, msg.startsWith("Pipeline done"))
        assertEquals("one planning call, zero recalls", 1, gate.asks)
        assertEquals("model released after planning (memory cut-out)", 1, gate.releases)
        assertEquals(100, ui.last().percent)
        assertTrue(File(root.listFiles()!!.first(), "out.txt").exists())
        assertTrue(File(root.listFiles()!!.first(), "report.md").exists())
    }

    @Test fun badPlanIsRepairedByValidatorFeedback() = runBlocking {
        val gate = FakeGate(mutableListOf("not json at all", goodPlan))
        val msg = dispatcher(gate, tmp(), mutableListOf()).start("x")
        assertTrue(msg, msg.startsWith("Pipeline done"))
        assertEquals(2, gate.asks)
    }

    @Test fun aiIsRecalledOnlyOnAVerifiedStopAndItsFixIsRevalidated() = runBlocking {
        val broken = """{"goal":"g","understanding":"u","steps":[
          {"id":"a","title":"fails","type":"sh","script":"exit 5","retry":0},
          {"id":"b","title":"after","type":"sh","script":"echo done > z.txt","expect":{"file":"z.txt"}}]}"""
        val fix = """{"action":"replace","step":{"id":"a","title":"fixed","type":"sh","script":"echo fine"}}"""
        val gate = FakeGate(mutableListOf(broken, fix))
        val root = tmp()
        val msg = dispatcher(gate, root, mutableListOf()).start("g")
        assertTrue(msg, msg.startsWith("Pipeline done"))
        assertEquals("plan + exactly one recall", 2, gate.asks)
        assertTrue(msg.contains("1 AI recall"))
    }

    @Test fun dangerousAiFixIsRejectedByCode() = runBlocking {
        val broken = """{"steps":[{"id":"a","title":"fails","type":"sh","script":"exit 5","retry":0}]}"""
        val evil = """{"action":"replace","step":{"type":"sh","script":"rm -rf /sdcard"}}"""
        val gate = FakeGate(mutableListOf(broken, evil))
        val msg = dispatcher(gate, tmp(), mutableListOf()).start("g")
        assertTrue(msg, msg.startsWith("Pipeline stopped"))
        assertTrue(msg.contains("blocked"))
    }

    @Test fun budgetStopsRunawayRecallsAndRunIsResumable() = runBlocking {
        val broken = """{"steps":[{"id":"a","title":"fails","type":"sh","script":"exit 5","retry":0}]}"""
        val same = """{"action":"replace","step":{"id":"a","title":"still","type":"sh","script":"exit 6","retry":0}}"""
        val gate = FakeGate(mutableListOf(broken, same, same, same, same, same))
        val root = tmp()
        val msg = dispatcher(gate, root, mutableListOf(), budget = 2).start("g")
        assertTrue(msg, msg.startsWith("Pipeline stopped"))
        assertTrue("never more than plan + budget calls", gate.asks <= 3)
        val st = RunStore.load(root.listFiles()!!.first())
        assertNotNull(st); assertEquals("failed", st!!.status)
    }

    @Test fun decisionStepsAskTheAiAndSaveTheAnswerAsVariable() = runBlocking {
        val plan = """{"steps":[
          {"id":"d","title":"pick","type":"ask_ai","question":"color?","choices":["red","blue"],"save_as":"color"},
          {"id":"w","title":"use","type":"write_file","path":"c.txt","content":"{{color}}","expect":{"file":"c.txt"}}]}"""
        val gate = FakeGate(mutableListOf(plan, """{"action":"answer","value":"blue"}"""))
        val root = tmp()
        val msg = dispatcher(gate, root, mutableListOf()).start("g")
        assertTrue(msg, msg.startsWith("Pipeline done"))
        assertEquals("blue", File(root.listFiles()!!.first(), "c.txt").readText())
    }

    @Test fun checkpointLetsARunResumeAfterAKill() = runBlocking {
        val plan = """{"steps":[
          {"id":"a","title":"one","type":"sh","script":"echo 1 > one.txt","expect":{"file":"one.txt"}},
          {"id":"b","title":"two","type":"sh","script":"echo 2 > two.txt","expect":{"file":"two.txt"}}]}"""
        val gate = FakeGate(mutableListOf(plan))
        val root = tmp()
        dispatcher(gate, root, mutableListOf()).start("g")
        val dir = root.listFiles()!!.first()
        val st = RunStore.load(dir)!!
        st.next = 1; st.status = "stopped"; RunStore.save(st) // simulate a crash after step 1
        File(dir, "two.txt").delete()
        val msg = dispatcher(FakeGate(mutableListOf()), root, mutableListOf()).resume(dir)
        assertTrue(msg, msg.startsWith("Pipeline done"))
        assertTrue(File(dir, "two.txt").exists())
    }
}
