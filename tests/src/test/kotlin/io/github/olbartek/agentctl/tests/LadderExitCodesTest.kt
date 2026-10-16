package io.github.olbartek.agentctl.tests

import io.github.olbartek.agentctl.examples.tinyapp.TinyAppConfig
import io.github.olbartek.agentctl.examples.tinyapp.TinyRoot
import io.github.olbartek.agentctl.runtime.AgentStoreDriver
import io.github.olbartek.agentctl.runtime.AppCheck
import io.github.olbartek.agentctl.runtime.AppCtlConfig
import io.github.olbartek.agentctl.runtime.GradleTasks
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What this guards: `snapshots` and `check` exit 1 only for a rung that ran and found the app wrong (CONTRACT.md §5).
 * A usage error — a scenario the config names that does not exist, an ambiguous `--device` — is 2; an environment
 * error — Gradle that cannot be run, nothing configured to run, no scenario files, no device — is 3.
 *
 * The repository here is a temporary one, with a fake `gradlew` and a fake SDK: no build runs and no device is needed.
 */
class LadderExitCodesTest {
    private val root = Files.createTempDirectory("ladder").toFile()
    private val sdk = File(root, "sdk")

    init {
        File(root, "settings.gradle.kts").writeText("")
        File(root, "examples/tinyapp/scenarios").mkdirs()
        File(scenariosDirectory, "browse.appctl").copyTo(File(root, "examples/tinyapp/scenarios/browse.appctl"))
        File(root, "examples/tinyapp/agent-commands.md").writeText(TinyAppConfig.appCtl.docsMarkdown)
        gradlew("exit 0")
        adb("""[ "${'$'}1" = devices ] && echo "List of devices attached"; exit 0""")
    }

    @AfterTest
    fun removeTheRepository() {
        root.deleteRecursively()
    }

    /** The repository's `gradlew`: a shell script with this body. */
    private fun gradlew(body: String, interpreter: String = "/bin/sh") = File(root, "gradlew").apply {
        writeText("#!$interpreter\n$body\n")
        setExecutable(true)
    }

    private fun adb(body: String) = File(sdk, "platform-tools/adb").apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }

    /** TinyApp's config with other Gradle tasks or another L4 check. */
    private fun config(
        gradle: GradleTasks = TinyAppConfig.appCtl.gradle,
        appCheck: AppCheck = TinyAppConfig.appCtl.appCheck,
    ) = TinyAppConfig.appCtl.let { tiny ->
        AppCtlConfig(
            name = tiny.name,
            rootMarker = tiny.rootMarker,
            applicationId = tiny.applicationId,
            launchActivity = tiny.launchActivity,
            gradle = gradle,
            scenariosPath = tiny.scenariosPath,
            docsPath = tiny.docsPath,
            appCheck = appCheck,
            help = tiny.help,
            mockMethods = tiny.mockMethods,
            deviceConfig = tiny.deviceConfig,
            docsText = tiny.docsText,
            screens = tiny.screens,
            makeHeadless = tiny.makeHeadless,
            makeLive = tiny.makeLive,
        )
    }

    private fun run(vararg args: String, config: AppCtlConfig<AgentStoreDriver<TinyRoot.State, TinyRoot.Action>, TinyRoot.Action> = config()) =
        cli(*args, config = config, environment = mapOf("ANDROID_HOME" to sdk.path), workingDirectory = root)

    private val withSnapshots = GradleTasks(build = listOf("assemble"), test = listOf("test"), snapshotModules = listOf(":app"))

    /** `gradlew :app:tasks --all` lists Roborazzi's tasks; every other task exits [status]. */
    private fun gradlewWithRoborazzi(status: Int) = gradlew(
        """
        case "${'$'}*" in
          *:app:tasks*) printf 'verifyRoborazziDebug - Verify\nrecordRoborazziDebug - Record\n' ;;
          *) exit $status ;;
        esac
        """.trimIndent(),
    )

    // check

    @Test
    fun aRungThatFailsIsOne() {
        gradlew("exit 1")
        val result = run("check")
        assertEquals(1, result.status, result.combined)
        assertTrue(result.out.startsWith("L0 build      FAIL  assemble failed"), result.out)
    }

    @Test
    fun gradleThatCannotRunIsAnEnvironmentError() {
        gradlew("exit 0", interpreter = "/nonexistent/sh")
        val result = run("check")
        assertEquals(3, result.status, result.combined)
        assertTrue(result.out.startsWith("L0 build      FAIL  assemble failed"), result.out)
    }

    @Test
    fun noScenarioFilesIsAnEnvironmentError() {
        File(root, "examples/tinyapp/scenarios/browse.appctl").delete()
        val result = run("check")
        assertEquals(3, result.status, result.combined)
        assertTrue("L2 scenarios  FAIL  no scenario files" in result.out, result.out)
    }

    @Test
    fun theWholeLadderPassesOnTheFakes() {
        val result = run("check")
        assertEquals(0, result.status, result.combined)
    }

    @Test
    fun noSnapshotTaskToRunIsAnEnvironmentErrorInL3() {
        gradlew("""case "${'$'}*" in *:app:tasks*) echo "check - Runs all checks" ;; *) exit 0 ;; esac""")
        val result = run("check", "--ui", config = config(gradle = withSnapshots))
        assertEquals(3, result.status, result.combined)
        assertTrue("L3 snapshots  FAIL  no snapshot tasks found" in result.out, result.out)
    }

    @Test
    fun snapshotsThatDifferAreOneInL3() {
        gradlew(
            """
            case "${'$'}*" in
              *:app:tasks*) printf 'verifyRoborazziDebug - Verify\n' ;;
              *verifyRoborazziDebug*) exit 1 ;;
              *) exit 0 ;;
            esac
            """.trimIndent(),
        )
        val result = run("check", "--ui", config = config(gradle = withSnapshots))
        assertEquals(1, result.status, result.combined)
        assertTrue("L3 snapshots  FAIL  :app:verifyRoborazziDebug failed" in result.out, result.out)
    }

    @Test
    fun aScenarioTheConfigNamesThatDoesNotExistIsAUsageErrorInL4() {
        val result = run("check", "--ui", config = config(appCheck = AppCheck(scenario = "nonexistent")))
        assertEquals(2, result.status, result.combined)
        assertTrue("L4 app        FAIL  no scenario to run" in result.out, result.out)
        assertTrue("  cannot read ${File(root, "examples/tinyapp/scenarios/nonexistent.appctl").path}\n" in result.out, result.out)
    }

    @Test
    fun noDeviceIsAnEnvironmentErrorInL4() {
        val result = run("check", "--ui", config = config(appCheck = AppCheck(scenario = "browse")))
        assertEquals(3, result.status, result.combined)
        assertTrue("L4 app        FAIL  error" in result.out, result.out)
        assertTrue("  no device connected (start an emulator, or pass --device <AVD name>)\n" in result.out, result.out)
    }

    @Test
    fun anAmbiguousDeviceIsAUsageErrorInL4() {
        adb(
            """
            case "${'$'}*" in
              devices) printf 'List of devices attached\n1A2B3C\tdevice\n4D5E6F\tdevice\n' ;;
              *"shell getprop ro.build.version.release") echo 16 ;;
              *"shell getprop ro.product.model") echo 'Pixel 7' ;;
              *) exit 1 ;;
            esac
            """.trimIndent(),
        )
        val result = run("check", "--ui", "--device", "Pixel 7", config = config(appCheck = AppCheck(scenario = "browse")))
        assertEquals(2, result.status, result.combined)
        assertTrue("  several devices are named 'Pixel 7'; pass --device <serial>:\n" in result.out, result.out)
    }

    // snapshots

    @Test
    fun recordingWithNothingConfiguredIsAnEnvironmentError() {
        val result = run("snapshots", "--record")
        assertEquals(3, result.status, result.combined)
        assertTrue(result.out.startsWith("FAILED to record snapshots\n"), result.out)
    }

    @Test
    fun aModuleWhoseTasksCannotBeListedIsAnEnvironmentError() {
        gradlew("exit 1")
        val result = run("snapshots", config = config(gradle = withSnapshots))
        assertEquals(3, result.status, result.combined)
        assertTrue(":app: cannot list its tasks; log: " in result.out, result.out)
    }

    @Test
    fun aModuleWithNoScreenshotTaskIsAnEnvironmentError() {
        gradlew("""case "${'$'}*" in *:app:tasks*) echo "check - Runs all checks" ;; *) exit 0 ;; esac""")
        val result = run("snapshots", config = config(gradle = withSnapshots))
        assertEquals(3, result.status, result.combined)
        assertTrue(":app: no screenshot task to verify with" in result.out, result.out)
    }

    @Test
    fun gradleThatCannotRunTheSnapshotsIsAnEnvironmentError() {
        gradlew("exit 0", interpreter = "/nonexistent/sh")
        val result = run("snapshots", config = config(gradle = GradleTasks(snapshotsVerify = listOf(":app:verifyPaparazziDebug"))))
        assertEquals(3, result.status, result.combined)
        assertTrue(result.out.startsWith("FAIL snapshot tests\n"), result.out)
    }

    @Test
    fun snapshotsThatDifferAreOne() {
        gradlewWithRoborazzi(status = 1)
        val result = run("snapshots", config = config(gradle = withSnapshots))
        assertEquals(1, result.status, result.combined)
        assertTrue(result.out.startsWith("FAIL snapshot tests\nfailed; log: "), result.out)
        gradlewWithRoborazzi(status = 0)
        assertEquals(0, run("snapshots", config = config(gradle = withSnapshots)).status)
    }
}
