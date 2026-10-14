package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GitCompatibilityTest {
    @Test fun repairsOnlyLegacyCoreModeAndPreservesOtherSettings() {
        val before = "# keep\r\n[core]\r\n\tcreateObject = copy # legacy\r\n\tfilemode = false\r\n" +
            "[remote \"origin\"]\r\n\turl = https://example.org/project.git\r\n" +
            "[other]\r\n\tcreateObject = copy\r\n"
        val expected = before.replace("createObject = copy # legacy", "createObject = rename # legacy")
        assertEquals(expected, PRootKernel.repairLegacyGitConfig(before))
        assertEquals(expected, PRootKernel.repairLegacyGitConfig(expected))
    }

    @Test fun acceptsQuotedAndCaseInsensitiveLegacyValuesWithoutTouchingValidModes() {
        val before = "[CORE] ; legacy\nCreateObject=\"COPY\" ; note\n" +
            "[core]\ncreateObject=rename\ncreateObject=link\n[core \"other\"]\ncreateObject=copy"
        assertEquals(before.replace("\"COPY\"", "\"rename\""), PRootKernel.repairLegacyGitConfig(before))
        assertEquals("", PRootKernel.repairLegacyGitConfig(""))
    }

    @Test fun migratedConfigAllowsRealGitObjectsCommitAndRepackInUnicodePath() {
        val root = Files.createTempDirectory("minis-git-compat").toFile()
        try {
            val repository = File(root, "项目 with spaces").apply { mkdirs() }
            val config = File(root, "gitconfig").apply {
                writeText("[core]\n\tcreateObject = copy\n[user]\n\tname = Sandbox Test\n\temail = sandbox@example.invalid\n")
            }
            fun git(vararg arguments: String): Pair<Int, String> {
                val builder = ProcessBuilder(listOf(System.getProperty("minis.test.git", "git")) + arguments)
                    .directory(repository).redirectErrorStream(true)
                val environment = builder.environment()
                // Keep this probe independent of the developer's Git configuration.
                environment.keys.filter { it.startsWith("GIT_") }.toList().forEach(environment::remove)
                environment["GIT_CONFIG_SYSTEM"] = config.absolutePath
                environment["GIT_CONFIG_GLOBAL"] = File(root, "absent-global-config").absolutePath
                environment["GIT_CONFIG_COUNT"] = "1"
                environment["GIT_CONFIG_KEY_0"] = "core.createObject"
                environment["GIT_CONFIG_VALUE_0"] = "rename"
                val process = builder.start()
                try {
                    assertTrue("Git did not finish", process.waitFor(15, TimeUnit.SECONDS))
                    return process.exitValue() to process.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } finally { process.destroyForcibly() }
            }
            val broken = git("init", "--quiet")
            assertNotEquals(0, broken.first)
            assertTrue(broken.second.contains("invalid mode for object creation: copy"))

            config.writeText(PRootKernel.repairLegacyGitConfig(config.readText()))
            File(repository, "中文.txt").writeText("中文🙂\n")
            for (arguments in listOf(
                listOf("init", "--quiet"),
                listOf("add", "."),
                listOf("commit", "--quiet", "-m", "Unicode path"),
                listOf("repack", "-ad"),
                listOf("fsck", "--no-dangling"),
            )) {
                val result = git(*arguments.toTypedArray())
                assertEquals("git $arguments: ${result.second}", 0, result.first)
            }
            assertEquals("rename", git("config", "--get", "core.createObject").second.trim())
        } finally { root.deleteRecursively() }
    }
}
