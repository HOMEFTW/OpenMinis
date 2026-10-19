package com.openminis.app.browser

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test

class BrowserUseScriptErrorTest {
    @Test fun `WebView null and undefined are explicit errors while quoted strings still decode`() {
        for (raw in listOf(null, "null", "undefined", "")) {
            assertTrue(Json.parseToJsonElement(BrowserUseJS.decodeEvaluationResult(raw)).jsonObject.containsKey("error"))
        }
        assertEquals("null", BrowserUseJS.decodeEvaluationResult("\"null\""))
        assertEquals("ready", BrowserUseJS.decodeEvaluationResult("\"ready\""))
        assertEquals("42", BrowserUseJS.decodeEvaluationResult("42"))
    }

    @Test fun `body dependent actions report a missing body instead of returning null`() {
        val scripts = listOf(
            BrowserUseJS.getText(null), BrowserUseJS.getReadable(), BrowserUseJS.getPageInfo(),
            BrowserUseJS.getBackbone(5), BrowserUseJS.scroll(ScrollDirection.DOWN, 100, null),
        )
        for (script in scripts) {
            val output = evaluate(BrowserUseJS.guardedEvaluation(script))
            val result = Json.parseToJsonElement(output.getValue("value").jsonPrimitive.content).jsonObject
            assertTrue(result.getValue("error").jsonPrimitive.content.contains("document.body"))
        }
    }

    @Test fun `invalid selector errors retain the thrown diagnostic`() {
        val script = BrowserUseJS.guardedEvaluation(BrowserUseJS.click("[broken"))
        val output = evaluate(script, queryThrows = true)
        val result = Json.parseToJsonElement(output.getValue("value").jsonPrimitive.content).jsonObject
        assertTrue(result.getValue("error").jsonPrimitive.content.contains("invalid selector"))
    }

    @Test fun `async runtime errors reject the request and retain its token`() {
        val output = evaluate(BrowserUseJS.executeScript("throw new Error('boom');", "request-1"))
        assertEquals(BrowserUseJS.EXECUTION_STARTED, output.getValue("value").jsonPrimitive.content)
        val event = output.getValue("events").jsonArray.single().jsonObject
        assertEquals("reject", event.getValue("kind").jsonPrimitive.content)
        assertEquals("request-1", event.getValue("token").jsonPrimitive.content)
        assertEquals("boom", event.getValue("text").jsonPrimitive.content)
    }

    @Test fun `throwing null is an error while deliberately returning null is a successful result`() {
        val rejected = evaluate(BrowserUseJS.executeScript("throw null;", "r"))
        assertEquals("reject", rejected.getValue("events").jsonArray.single().jsonObject.getValue("kind").jsonPrimitive.content)
        val resolved = evaluate(BrowserUseJS.executeScript("return null;", "r"))
        val event = resolved.getValue("events").jsonArray.single().jsonObject
        assertEquals("resolve", event.getValue("kind").jsonPrimitive.content)
        assertEquals("null", event.getValue("text").jsonPrimitive.content)
    }

    @Test fun `syntax failure cannot produce the started marker or look like a running promise`() {
        val output = evaluate(BrowserUseJS.executeScript("return (", "r"))
        assertEquals(JsonNull, output["value"])
        assertTrue(output.getValue("parseError").jsonPrimitive.content.isNotBlank())
        assertTrue(output.getValue("events").jsonArray.isEmpty())
    }

    @Test fun `a valid script ending with a comment still resolves normally`() {
        val output = evaluate(BrowserUseJS.executeScript("return 42; // finished", "r"))
        val event = output.getValue("events").jsonArray.single().jsonObject
        assertEquals("resolve", event.getValue("kind").jsonPrimitive.content)
        assertEquals("42", event.getValue("text").jsonPrimitive.content)
    }

    /** Execute the actual generated JavaScript in Node's isolated VM, without a WebView or network. */
    private fun evaluate(script: String, queryThrows: Boolean = false): JsonObject {
        val harness = """
            const vm = require('vm');
            const events = [];
            const context = {
                document: {body: null, querySelector: function() { ${if (queryThrows) "throw new Error('invalid selector');" else "return null;"} }},
                window: {}, location: {href: 'https://example.test/'},
                __minis__: {
                    resolve: (token, text) => events.push({kind: 'resolve', token, text}),
                    reject: (token, text) => events.push({kind: 'reject', token, text})
                }
            };
            const output = {value: null, events};
            try { output.value = vm.runInNewContext(${JsonPrimitive(script)}, context); }
            catch (e) { output.parseError = String(e); }
            setImmediate(() => process.stdout.write(JSON.stringify(output)));
        """.trimIndent()
        val process = try { ProcessBuilder("node", "--input-type=commonjs", "-").redirectErrorStream(true).start() }
        catch (e: IOException) { assumeNoException("Node.js is needed for generated-JavaScript regression tests", e); throw e }
        // Stdin preserves nested JavaScript/JSON quotes on Windows too.
        process.outputStream.bufferedWriter().use { it.write(harness) }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("Generated JavaScript did not settle within 10 seconds")
        }
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.exitValue())
        return Json.parseToJsonElement(output).jsonObject
    }
}
