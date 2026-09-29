package io.github.olbartek.agentctl.cli

import io.github.olbartek.agentctl.runtime.GradleTasks
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What this guards: how L3 finds a snapshot module's tasks in its task list instead of assuming their names — the
 * variant, the tool, a module with product flavors (the reference's issue #2).
 *
 * What it does not guard: that Gradle then runs them, which needs a module with the plugin applied.
 */
class SnapshotsTest {
    /** Abridged from `./gradlew :feature:items:tasks --all` with Roborazzi applied. */
    private val roborazziOutput = """

        > Task :feature:items:tasks

        ------------------------------------------------------------
        Tasks runnable from project ':feature:items'
        ------------------------------------------------------------

        Verification tasks
        ------------------
        check - Runs all checks.
        clearRoborazziDebug - Clear Roborazzi outputs.
        compareRoborazziDebug - Compare screenshot test results with the reference images.
        recordRoborazziDebug - Record screenshot tests.
        recordRoborazziRelease - Record screenshot tests.
        testDebugUnitTest - Run unit tests for the debug build.
        verifyAndRecordRoborazziDebug - Verify and record screenshot tests.
        verifyRoborazziDebug - Verify screenshot tests against the reference images.
        verifyRoborazziRelease - Verify screenshot tests against the reference images.

        Other tasks
        -----------
        compileDebugKotlin

        Rules
        -----
        Pattern: clean<TaskName>: Cleans the output files of a task.

        BUILD SUCCESSFUL in 1s
        1 actionable task: 1 executed
    """.trimIndent()

    @Test
    fun theTaskNamesAreReadFromTheTaskList() {
        val names = Snapshots.taskNames(roborazziOutput)
        assertEquals(
            listOf(
                "check", "clearRoborazziDebug", "compareRoborazziDebug", "recordRoborazziDebug", "recordRoborazziRelease",
                "testDebugUnitTest", "verifyAndRecordRoborazziDebug", "verifyRoborazziDebug", "verifyRoborazziRelease",
                "compileDebugKotlin",
            ),
            names,
        )
        assertEquals(emptyList(), Snapshots.taskNames("FAILURE: Build failed with an exception.\n\n* What went wrong:"))
    }

    @Test
    fun theDebugVariantIsVerifiedAndRecorded() {
        val names = Snapshots.taskNames(roborazziOutput)
        assertEquals(listOf("verifyRoborazziDebug"), Snapshots.snapshotTasks(names, record = false))
        assertEquals(listOf("recordRoborazziDebug"), Snapshots.snapshotTasks(names, record = true))
    }

    @Test
    fun everyDebugFlavorRunsWhenThereIsNoPlainDebug() {
        val names = listOf("verifyRoborazziDemoDebug", "verifyRoborazziFullDebug", "verifyRoborazziFullRelease")
        assertEquals(listOf("verifyRoborazziDemoDebug", "verifyRoborazziFullDebug"), Snapshots.snapshotTasks(names, record = false))
    }

    @Test
    fun aLoneVariantIsUsedWhateverItsName() {
        assertEquals(listOf("verifyRoborazziDesktop"), Snapshots.snapshotTasks(listOf("verifyRoborazziDesktop"), record = false))
    }

    @Test
    fun paparazziIsFoundWhenThereIsNoRoborazzi() {
        val names = listOf("cleanRecordPaparazziDebug", "recordPaparazziDebug", "verifyPaparazziDebug", "verifyPaparazziRelease")
        assertEquals(listOf("verifyPaparazziDebug"), Snapshots.snapshotTasks(names, record = false))
        assertEquals(listOf("recordPaparazziDebug"), Snapshots.snapshotTasks(names, record = true))
    }

    @Test
    fun nothingWhenNoneFits() {
        assertEquals(emptyList(), Snapshots.snapshotTasks(listOf("verifyRoborazziStaging", "verifyRoborazziRelease"), record = false))
        assertEquals(emptyList(), Snapshots.snapshotTasks(listOf("check", "testDebugUnitTest"), record = false))
        // `verifyAndRecord…` is neither a verify nor a record task.
        assertEquals(emptyList(), Snapshots.snapshotTasks(listOf("verifyAndRecordRoborazziDebug"), record = false))
        assertEquals(emptyList(), Snapshots.snapshotTasks(emptyList(), record = true))
    }

    @Test
    fun modulePaths() {
        assertEquals("feature-items", Snapshots.logName(":feature:items"))
        assertEquals("feature/items", Snapshots.directory(":feature:items"))
        assertEquals("app", Snapshots.directory("app"))
        assertEquals(":feature:items:tasks", Snapshots.taskPath(":feature:items", "tasks"))
        // The root project's tasks are `:tasks`, not `::tasks`.
        assertEquals(":verifyRoborazziDebug", Snapshots.taskPath(":", "verifyRoborazziDebug"))
    }

    @Test
    fun theHelpNamesWhatRuns() {
        assertEquals("nothing: the config's gradle.snapshotModules is empty", AgentCtl.snapshotsSubject(GradleTasks()))
        assertEquals(
            "the Roborazzi or Paparazzi tasks of :app, :feature:items, and :lib:verifyPaparazziDemoDebug",
            AgentCtl.snapshotsSubject(GradleTasks(snapshotModules = listOf(":app", ":feature:items"), snapshotsVerify = listOf(":lib:verifyPaparazziDemoDebug"))),
        )
    }
}
