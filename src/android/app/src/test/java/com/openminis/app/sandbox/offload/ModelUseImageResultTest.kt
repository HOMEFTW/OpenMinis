package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.LLMMediaAttachment
import com.openminis.app.data.model.LLMResponse
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelUseImageResultTest {
    @get:Rule val folder = TemporaryFolder()
    private val images = listOf(
        LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, "image/png", byteArrayOf(1, 2, 3)),
        LLMMediaAttachment(LLMMediaAttachment.MediaType.IMAGE, "image/png", byteArrayOf(4, 5, 6)),
    )
    private fun host(path: String) = File(folder.root, path.removePrefix("/"))
    private fun save(path: String?, media: List<LLMMediaAttachment> = images) = saveModelUseImageResult(
        "gpt-image-2.5-sunburst", LLMResponse("revised prompt", "end_turn", null, media),
        path, path?.substringAfterLast('.') ?: "", "images_generations", ::host, { "png" },
    )
    private fun assertImages(body: JSONObject) {
        val files = body.getJSONArray("media_files")
        assertEquals(images.size, files.length())
        images.forEachIndexed { i, image ->
            assertArrayEquals(image.data, host(files.getJSONObject(i).getString("path")).readBytes())
        }
    }

    @Test fun genericAudioOutputPreservesAllAttachmentsAndUsage() {
        val audio = images.map { it.copy(type = LLMMediaAttachment.MediaType.AUDIO, mimeType = "audio/wav") }
        val result = saveModelUseResult(
            modelId = "audio-model", response = LLMResponse("transcript", "stop", com.openminis.app.data.model.LLMUsage(2, 3), audio),
            outputPath = "/var/minis/workspace/result.json", outputExt = "json",
            resolveFile = ::host, mimeToExt = { "wav" },
        )
        assertEquals(0, result.exitCode)
        val body = JSONObject(result.output)
        assertFalse(body.has("image_endpoint"))
        assertEquals(3, body.getJSONObject("usage").getInt("output_tokens"))
        assertImages(body)
        assertEquals("audio", body.getJSONArray("media_files").getJSONObject(0).getString("type"))
    }
    @Test fun genericTextOnlyOutputNeedsNoAttachmentDirectory() {
        val result = saveModelUseResult(
            modelId = "text-model", response = LLMResponse("answer", "stop", null),
            outputPath = null, outputExt = "", resolveFile = { error("No files needed") }, mimeToExt = { "bin" },
        )
        assertEquals(0, result.exitCode)
        assertEquals("answer", JSONObject(result.output).getString("text"))
    }

    @Test fun jsonOutputKeepsAllImagesAndWritesResultManifest() {
        val path = "/var/minis/workspace/portrait_result.json"
        val result = save(path)
        assertEquals(0, result.exitCode)
        assertImages(JSONObject(result.output))
        assertImages(JSONObject(host(path).readText()))
    }

    @Test fun directImageOutputKeepsAdditionalImages() {
        val path = "/var/minis/workspace/portrait.png"
        val result = save(path)
        assertEquals(0, result.exitCode)
        assertArrayEquals(images[0].data, host(path).readBytes())
        assertImages(JSONObject(result.output))
    }

    @Test fun automaticOutputKeepsAllImages() {
        val result = save(null)
        assertEquals(0, result.exitCode)
        assertImages(JSONObject(result.output))
    }

    @Test fun textOutputStillKeepsImages() {
        val path = "/var/minis/workspace/portrait.txt"
        val result = save(path)
        assertEquals("revised prompt", host(path).readText())
        assertImages(JSONObject(result.output))
    }

    @Test fun missingImagesFailWithoutWritingPromptAsAnImage() {
        val path = "/var/minis/workspace/portrait.png"
        assertEquals(1, save(path, emptyList()).exitCode)
        assertFalse(host(path).exists())
    }

    @Test fun fileUriOutputIsRejectedWithoutWritingToTheWrongDirectory() {
        val result = save("file:///var/minis/workspace/image.png")
        assertEquals(1, result.exitCode)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun unresolvedAttachmentDirectoryFails() {
        val result = saveModelUseImageResult(
            "gpt-image-2.5-sunburst", LLMResponse("prompt", null, null, images),
            null, "", "images_generations", { null }, { "png" },
        )
        assertTrue(result.exitCode != 0)
    }

    @Test fun imageApiResponseWithRevisedPromptRetainsDownloadedImageInJsonOutput() = kotlinx.coroutines.runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            val png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==",
            )
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"data":[{"url":"${server.url("/generated.png")}","revised_prompt":"revised prompt"}]}""",
            ))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "image/png")
                .setBody(okio.Buffer().write(png)))
            val provider = com.openminis.app.provider.openai.OpenAIProvider(
                "test", com.openminis.app.data.model.LLMModel.gptImage25Sunburst,
                server.url("/").toString().trimEnd('/'),
            )
            val response = provider.generateImage("a cat")
            val path = "/var/minis/workspace/portrait_result.json"
            val result = saveModelUseImageResult(
                "gpt-image-2.5-sunburst", response, path, "json", "images_generations", ::host, { "png" },
            )
            assertEquals(0, result.exitCode)
            val body = JSONObject(host(path).readText())
            assertEquals("revised prompt", body.getString("text"))
            val imagePath = body.getJSONArray("media_files").getJSONObject(0).getString("path")
            assertArrayEquals(png, host(imagePath).readBytes())
        } finally { server.shutdown() }
    }

    @Test fun expiredImageDownloadCannotBecomeAValidAttachment() = kotlinx.coroutines.runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.start()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody(
                """{"data":[{"url":"${server.url("/expired.png")}"}]}""",
            ))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(403).setBody("expired signed URL"))
            val provider = com.openminis.app.provider.openai.OpenAIProvider(
                "test", com.openminis.app.data.model.LLMModel.gptImage25Sunburst,
                server.url("/").toString().trimEnd('/'),
            )
            try { provider.generateImage("a cat"); fail("Expected failed image download") }
            catch (e: com.openminis.app.data.model.LLMError.ProviderError) { assertTrue(e.message!!.contains("403")) }
        } finally { server.shutdown() }
    }

    @Test fun writeFailureReturnsErrorInsteadOfSuccess() {
        host("/var/minis").apply { parentFile.mkdirs(); writeText("not a directory") }
        assertEquals(1, save(null).exitCode)
    }
}
