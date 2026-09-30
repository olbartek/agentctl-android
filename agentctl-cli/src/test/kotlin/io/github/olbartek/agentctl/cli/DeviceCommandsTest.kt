package io.github.olbartek.agentctl.cli

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Assume.assumeTrue

/**
 * What this guards: the device commands' files and lines, as agentctl-ios writes them (`record.json`, `app info`),
 * the clean status bar's demo-mode commands, and the recording's last-frame hold.
 */
class DeviceCommandsTest {
    private val state = RecordState(
        device = "emulator-5556",
        file = "/tmp/demo/run.mp4",
        pid = 4242,
        platform = "android",
        startedAt = Instant.parse("2026-09-30T10:00:00.5Z"),
    )

    @Test
    fun recordJsonIsWrittenAsBridgeJsonIs() {
        assertEquals(
            """
            {
              "device" : "emulator-5556",
              "file" : "/tmp/demo/run.mp4",
              "pid" : 4242,
              "platform" : "android",
              "startedAt" : "2026-09-30T10:00:00Z"
            }
            """.trimIndent() + "\n",
            state.render(),
        )
    }

    @Test
    fun recordJsonReadsBack() {
        assertEquals(state.copy(startedAt = Instant.parse("2026-09-30T10:00:00Z")), RecordState.parse(state.render()))
        assertNull(RecordState.parse("""{"device":"x"}"""))
    }

    @Test
    fun appInfoIsOneCompactSortedLine() {
        assertEquals(
            """{"appId":"io.github.olbartek.agentctl.examples.agentshop","build":"1","device":"emulator-5556","platform":"android","version":"0.1"}""",
            AppInfo("io.github.olbartek.agentctl.examples.agentshop", "1", "emulator-5556", "android", "0.1").render(),
        )
    }

    @Test
    fun appInfoReadsTheInstalledVersionFromDumpsys() {
        val dump = """
            Packages:
              Package [io.github.olbartek.agentctl.examples.agentshop] (5b1c2e3):
                versionCode=1 minSdk=28 targetSdk=36
                versionName=0.1
        """.trimIndent()
        assertEquals("0.1" to "1", AppInfo.versions(dump))
        assertEquals("" to "", AppInfo.versions(""))
    }

    @Test
    fun theCleanStatusBarIsDemoModeAt0941WithFullSignalAndBattery() {
        val lines = StatusBar.clean.map { it.joinToString(" ") }
        assertEquals("settings put global sysui_demo_allowed 1", lines[0])
        assertTrue("am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941" in lines)
        assertTrue("am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false" in lines)
        assertTrue(lines.any { "-e wifi show -e level 4" in it } && lines.any { "-e mobile show" in it && "-e level 4" in it })
    }

    @Test
    fun resetPutsDemoModesSettingBackAsItWas() {
        val exit = "am broadcast -a com.android.systemui.demo -e command exit"
        assertEquals(listOf(exit), StatusBar.reset(null).map { it.joinToString(" ") })
        assertEquals(listOf(exit, "settings delete global sysui_demo_allowed"), StatusBar.reset("null").map { it.joinToString(" ") })
        assertEquals(listOf(exit, "settings put global sysui_demo_allowed 0"), StatusBar.reset("0").map { it.joinToString(" ") })
    }

    @Test
    fun theTextsAreTheReferences() {
        val device = Device("emulator-5556", "agentctl_pixel7_api36", "16")
        assertEquals("saved /tmp/a.png (agentctl_pixel7_api36 (Android 16) [emulator-5556])", Message.saved(File("/tmp/a.png"), device))
        assertEquals("recorded /tmp/demo/run.mp4 (12.3s)", Message.recorded("/tmp/demo/run.mp4", 12.34))
        assertEquals("the recording of /tmp/demo/run.mp4 is no longer running", Message.recordingGone(state))
        assertEquals("status bar clean on agentctl_pixel7_api36 (Android 16) [emulator-5556]", Message.statusBar(true, device))
        assertEquals("status bar reset on agentctl_pixel7_api36 (Android 16) [emulator-5556]", Message.statusBar(false, device))
    }

    /** `screenrecord` stops at the last change on screen: a still ending is held until the recording was stopped. */
    @Test
    fun aStillEndingIsHeldUntilTheRecordingEnded() {
        assumeTrue("ffmpeg is not installed", Shell.capture(listOf("ffmpeg", "-version")) != null)
        assumeTrue("ffprobe is not installed", Shell.capture(listOf("ffprobe", "-version")) != null)
        assumeTrue("ffmpeg has no libx264", Shell.capture(listOf("ffmpeg", "-hide_banner", "-encoders"))?.contains("libx264") == true)
        val work = Files.createTempDirectory("video").toFile()
        val part = File(work, "part-0.mp4")
        Shell.run(
            listOf("ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=duration=1:size=128x128:rate=10", "-pix_fmt", "yuv420p", part.path),
            work,
            File(work, "make.log"),
        )
        val video = File(work, "out/run.mp4")
        Video.join(listOf(part), video, work, length = 3.seconds)
        val duration = Video.duration(video) ?: error("no duration")
        assertTrue(duration >= 2.8.seconds && duration <= 3.3.seconds, "duration $duration")
    }
}
