package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ForegroundCommandGroupTest {
    @Test fun commandAndStatusAreSeparateArguments() {
        val command = "printf '%s' 'a&b'; exit 7"
        val path = "/tmp/status with spaces"
        val args = ForegroundCommandGroup.wrapForFreshProcess(command, path)
        assertEquals(listOf(ForegroundCommandGroup.SETSID, "/bin/sh", "-c", ForegroundCommandGroup.FRESH_RUNNER, "sh", command, path), args)
    }

    @Test fun runnerPreservesOutputQuotingAndExitStatus() {
        val dir = Files.createTempDirectory("minis-runner").toFile()
        try {
            // Git Bash on Windows reparses quotes in -c argv. A script file preserves
            // the exact production runner; the command is loaded as one positional argument.
            val script = File(dir, "runner.sh").apply {
                writeText("set -- \"\$(cat \"\$1\")\" \"\$2\"\n" + ForegroundCommandGroup.FRESH_RUNNER)
            }
            for ((command, code, expected) in listOf(
                Triple("printf 'hi'; exit 7", 7, "hi"),
                Triple("printf '%s|' 'a&b' 'x y' '\$HOME'", 0, "a&b|x y|\$HOME|"),
                Triple("printf '__MINIS_DONE_fake_EXIT_0__'; exit 3", 3, "__MINIS_DONE_fake_EXIT_0__"),
            )) {
                val status = File(dir, "status with space")
                val commandFile = File(dir, "command.txt").apply { writeText(command) }
                val p = ProcessBuilder(System.getProperty("minis.test.shell", "sh"), script.invariantSeparatorsPath,
                    commandFile.invariantSeparatorsPath, status.invariantSeparatorsPath)
                    .redirectErrorStream(true).start()
                try {
                    assertTrue(p.waitFor(10, TimeUnit.SECONDS))
                    assertEquals(expected, p.inputStream.bufferedReader().readText())
                    assertEquals(code, p.exitValue())
                    assertEquals(code.toString(), status.readText())
                    assertTrue(File(status.path + ".pid").readText().toInt() > 1)
                } finally { p.destroyForcibly() }
            }
        } finally { dir.deleteRecursively() }
    }
}
