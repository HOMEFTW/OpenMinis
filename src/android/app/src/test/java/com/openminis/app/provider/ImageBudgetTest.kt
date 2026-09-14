package com.openminis.app.provider

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageBudgetTest {
    private fun image(size: Int, path: String? = null) = ImageBudget.BudgetImage(
        data = ByteArray(size),
        linuxPath = path,
        mimeType = "image/jpeg",
    )

    @Test
    fun negativeBudgetIsTreatedAsZeroBytes() {
        val result = ImageBudget.planRequestBudget(listOf(image(1)), maxBytes = -1L)

        assertEquals(0L, result.keptBytes)
        assertEquals(1, result.droppedCount)
        assertEquals(1, result.totalCount)
    }

    @Test
    fun exactBudgetKeepsAllBytes() {
        val result = ImageBudget.planRequestBudget(listOf(image(3), image(7)), maxBytes = 10L)

        assertEquals(10L, result.keptBytes)
        assertEquals(0, result.droppedCount)
    }

    @Test
    fun overBudgetDropsOnlyImagesThatDoNotFit() {
        val result = ImageBudget.planRequestBudget(listOf(image(6), image(5)), maxBytes = 10L)

        assertEquals(5L, result.keptBytes)
        assertEquals(1, result.droppedCount)
        assertEquals(6L, result.elidedBytes)
    }

    @Test
    fun latestImagesHavePriorityWhenBudgetIsExceeded() {
        val result = ImageBudget.planRequestBudget(
            listOf(image(6, "/old"), image(4, "/latest")),
            maxBytes = 4L,
        )

        assertEquals(1, result.droppedCount)
        assertTrue(result.droppedPaths.values.contains("/old"))
    }

    @Test
    fun placeholderCanDescribeCustomByteBudget() {
        val placeholder = ImageBudget.elidedImagePlaceholder("/tmp/image.jpg", maxBytes = 1234L)

        assertTrue(placeholder.contains("1234B request budget"))
        assertTrue(placeholder.contains("/tmp/image.jpg"))
    }

    @Test
    fun messageBudgetReportsOriginalIndexesWhenMiddleImageIsDropped() {
        val result = ImageBudget.applyMessageBudget(
            listOf(ByteArray(4), ByteArray(6), ByteArray(4)),
            maxTotalBytes = 8L,
        )

        assertEquals(listOf(0, 2), result.keptIndices)
        assertEquals(listOf(4, 4), result.keptBytes.map { it.size })
    }

    @Test
    fun undecodableOversizeImageIsDroppedInsteadOfReturningRawBytes() {
        val oversized = ByteArray(ImageBudget.MAX_PER_IMAGE_BYTES.toInt() + 1)

        val result = ImageBudget.applyMessageBudget(listOf(oversized))

        assertTrue(result.keptBytes.isEmpty())
        assertEquals(1, result.droppedCount)
    }

    @Test
    fun repeatedByteArrayReferencesHaveIndependentOccurrenceIds() {
        val shared = ByteArray(1)

        val result = ImageBudget.planRequestBudget(listOf(imageFrom(shared), imageFrom(shared)), maxBytes = 0L)

        assertEquals(2, result.droppedIds.size)
        assertTrue(ImageBudget.ImagePartId.of(0) in result.droppedIds)
        assertTrue(ImageBudget.ImagePartId.of(1) in result.droppedIds)
    }

    @Test
    fun mixedStructuredAndTopLevelImagesShareRequestBudget() {
        val shared = ByteArray(4)
        val messages = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = listOf(
                    AgentContentPart.ImageData(shared, "image/jpeg", linuxPath = "/old"),
                    AgentContentPart.ImageData(ByteArray(4), "image/png", linuxPath = "/middle"),
                ),
            ),
        )

        val budgeted = budgetProviderRequest(
            messages = messages,
            imageParts = listOf(LLMMessage.ImagePart(ByteArray(4), "image/jpeg", linuxPath = "/latest")),
            maxRequestBytes = 8L,
        )

        val parts = budgeted.messages.single().contentParts
        assertTrue(parts[0] is AgentContentPart.Text)
        assertTrue(parts[1] is AgentContentPart.ImageData)
        assertEquals(1, budgeted.imageParts.size)
        assertEquals("/latest", budgeted.imageParts.single().linuxPath)
    }

    @Test
    fun legacyMessageImagesAreBudgetedWhenStructuredPartsAreAbsent() {
        val budgeted = budgetProviderRequest(
            messages = listOf(
                LLMMessage(
                    role = LLMMessage.Role.USER,
                    content = "prompt",
                    imageParts = listOf(LLMMessage.ImagePart(ByteArray(4), "image/jpeg")),
                ),
            ),
            imageParts = emptyList(),
            maxRequestBytes = 0L,
        )

        assertTrue(budgeted.messages.single().imageParts.isEmpty())
        assertTrue(budgeted.messages.single().content.contains("image elided"))
    }

    private fun imageFrom(data: ByteArray) = ImageBudget.BudgetImage(
        data = data,
        linuxPath = null,
        mimeType = "image/jpeg",
    )
}
