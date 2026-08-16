package com.piperostool

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalSessionManagerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() {
        TerminalSessionManager.closeAll()
    }

    @Test
    fun linuxSessionTracksDirectoryAndUsesShellStyleCompletion() {
        assumeTrue(TerminalRuntime.inspect(context).installed)
        TerminalSessionManager.closeAll()
        val session = TerminalSessionManager.createSession(
            context,
            TerminalSessionManager.SessionMode.LINUX
        )
        waitUntil { TerminalSessionManager.listSessions().single().running }

        assertTrue(
            TerminalSessionManager.sendCommand(
                session.id,
                "test -t 0 && test -t 1 && test -t 2 && echo PIPER_PTY_OK"
            )
        )
        waitForCommand(session.id)
        assertTrue(TerminalSessionManager.output(session.id).contains("PIPER_PTY_OK"))

        assertTrue(TerminalSessionManager.sendCommand(session.id, "pwd"))
        waitForCommand(session.id)
        val homeOutput = TerminalSessionManager.output(session.id)
        println("PIPER_HOME_OUTPUT=$homeOutput")
        assertTrue(homeOutput.contains("/files/home"))
        assertFalse(homeOutput.contains("[Hoàn tất]"))

        assertTrue(TerminalSessionManager.sendCommand(session.id, "cd projects"))
        waitForCommand(session.id)
        assertEquals(
            "~/projects",
            TerminalSessionManager.listSessions().single().displayDirectory
        )

        assertTrue(TerminalSessionManager.sendCommand(session.id, "cd ~; ls"))
        waitForCommand(session.id)
        val listing = TerminalSessionManager.output(session.id)
        assertTrue(listing.contains("downloads"))
        assertTrue(listing.contains("projects"))
        assertTrue(listing.contains("scripts"))

        assertTrue(TerminalSessionManager.sendCommand(session.id, "false"))
        waitForCommand(session.id)
        assertTrue(TerminalSessionManager.output(session.id).contains("[exit 1]"))
        val visibleOutput = AnsiTerminalText.format(
            TerminalSessionManager.output(session.id)
        ).toString()
        assertTrue(visibleOutput.trimEnd().endsWith("$"))
    }

    @Test
    fun packageCommandUsesTaskProgressState() {
        assumeTrue(TerminalRuntime.inspect(context).installed)
        TerminalSessionManager.closeAll()
        val session = TerminalSessionManager.createSession(
            context,
            TerminalSessionManager.SessionMode.LINUX
        )
        waitUntil { TerminalSessionManager.listSessions().single().running }

        assertTrue(TerminalSessionManager.sendCommand(session.id, "pkg --version"))
        assertTrue(TerminalSessionManager.listSessions().single().showTaskProgress)
        waitForCommand(session.id)
        assertFalse(TerminalSessionManager.listSessions().single().showTaskProgress)
    }

    private fun waitForCommand(sessionId: Long) {
        waitUntil {
            TerminalSessionManager.listSessions()
                .firstOrNull { it.id == sessionId }
                ?.busy == false
        }
    }

    private fun waitUntil(timeoutMs: Long = 15_000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(50)
        }
        error("Timed out waiting for terminal state")
    }
}
