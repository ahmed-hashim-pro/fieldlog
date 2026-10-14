package fieldlog.app

import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The README recording: two phones, one relay, both offline while logging,
 * one hole name used twice. Not a regular test; it runs only when given a
 * role (scripts/two-phones.sh passes -e role pixel-a / pixel-b).
 */
@RunWith(AndroidJUnit4::class)
class DemoScript {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val role: String? = InstrumentationRegistry.getArguments().getString("role")

    private fun pause(ms: Long) {
        compose.waitForIdle(); Thread.sleep(ms)
    }

    /** Type the way a person does, so the recording shows it. */
    private fun type(tag: String, text: String) {
        for (c in text) {
            compose.onNodeWithTag(tag).performTextInput(c.toString())
            Thread.sleep(45)
        }
        Thread.sleep(250)
    }

    private fun waitFor(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMs, condition)
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun shows(tag: String, text: String? = null) =
        compose.onAllNodes(if (text == null) hasTestTag(tag) else hasTestTag(tag) and hasText(text, substring = true))
            .fetchSemanticsNodes().isNotEmpty()

    private fun logHole(name: String, x: String, y: String, collar: String, log: List<Pair<String, String>>) {
        compose.onNodeWithTag("addHole").performClick(); pause(500)
        type("holeName", name)
        type("x", x); type("y", y); type("collar", collar)
        for ((i, to) in log.withIndex()) {
            compose.onNodeWithTag("addInterval").performClick(); pause(250)
            type("to:${i}", to.second)
        }
    }

    @Test
    fun play() {
        assumeTrue("only for the recording", role != null)
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        dir.listFiles()?.forEach { it.deleteRecursively() }
        dir.resolve("device.properties").writeText("device=$role\nonline=true\n")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor("the site from the relay", 60_000) { shows("hole:BH03") }
        // scripts/two-phones.sh starts recording on this line.
        android.util.Log.i("fieldlog-demo", "READY $role")
        pause(5000)

        compose.onNodeWithTag("online").performClick()   // into the field: no signal
        pause(1200)
        val units = listOf("topsoil", "clay", "sand", "gravel")
        if (role == "pixel-a") {
            logHole("BH41", "600", "480", "64.62", units.zip(listOf("0.9", "6.2", "10.3", "13.2")))
            compose.onNodeWithTag("flag:water-strike").performClick(); pause(300)
            type("notes", "Water strike 7.4 m")
            compose.onNodeWithTag("back").performClick()
            pause(4000)
            compose.onNodeWithTag("online").performClick()   // back in signal
            waitFor("the other phone's hole") { compose.onAllNodesWithTag("hole:BH41").fetchSemanticsNodes().size == 2 }
            pause(3000)
            waitFor("the clash to be resolved", 60_000) { shows("hole:BH42") }
            pause(2500)
            compose.onNodeWithTag("export").performClick()
            pause(4000)
        } else {
            pause(1500)
            logHole("BH41", "560", "520", "63.80", units.zip(listOf("0.7", "6.1", "10.0", "13.8")))
            pause(1500)
            compose.onNodeWithTag("online").performClick()   // back in signal, still on the hole
            waitFor("the clash") { shows("problem", "also called BH41") }
            pause(2500)
            compose.onNodeWithTag("holeName").performTextReplacement("BH4")
            Thread.sleep(300)
            type("holeName", "2")
            waitFor("the clash to clear") { !shows("problem") }
            pause(1500)
            compose.onNodeWithTag("back").performClick()
            waitFor("the other phone to finish", 60_000) { shows("hole:BH42") }
            pause(4000)
        }
        // scripts/two-phones.sh stops recording at this line, while the app is still on screen.
        android.util.Log.i("fieldlog-demo", "END $role")
        Thread.sleep(20_000)
        scenario.close()
    }
}
