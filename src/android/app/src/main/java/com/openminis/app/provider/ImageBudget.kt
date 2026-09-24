package com.openminis.app.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.WeakHashMap

/**
 * Per-message inline-image byte budget — mirrors iOS AIChatViewModel.swift
 * kPerImageMaxBytes / kMessageImageMaxBytes (commit b830360).
 *
 * Anthropic returns HTTP 413 ("Downloaded image content cannot exceed 30MB")
 * when the request's total inline image payload exceeds the provider cap.
 * Pre-T-imgsize we only resized each attachment to a max-edge of 2000px at
 * JPEG q=85, which is *resolution* budgeting and does nothing for byte size
 * when the source is a 12MP HEIC. We now enforce a real byte cap at two
 * layers:
 *
 *   1. Composer (ChatViewModel.prepareUserAttachments) — runs the user's
 *      brand-new attachments through [compressUnderBudget] so every part is
 *      ≤ MAX_PER_IMAGE_BYTES, then tallies cumulative bytes and drops the
 *      tail with a Snackbar notice once MAX_TOTAL_BYTES is exceeded.
 *   2. Provider boundary (AnthropicProvider / OpenAIProvider) — belt and
 *      braces for history image parts that bypass the composer (e.g. tool
 *      results screenshot bytes, restored sessions, retry-after-edit). Each
 *      every part is validated and normalized via [normalizeImage].
 *
 * URL HEAD pre-check (spec §2.b) intentionally omitted — both Android
 * providers always base64-inline image bytes (no remote URL forwarding),
 * mirroring the iOS finding in commit b830360.
 */
object ImageBudget {
    /** Single image bytes ceiling before re-encode kicks in. */
    const val MAX_PER_IMAGE_BYTES = 5L * 1024 * 1024

    /** Cumulative inline-image bytes per user message. */
    const val MAX_TOTAL_BYTES = 100L * 1024 * 1024

    /**
     * Cumulative inline-image bytes across ALL messages in a single
     * request body. Mirrors the per-message cap but applies at the
     * request boundary so a long history accumulating images from
     * multiple turns (browser screenshots, attachments, read_image
     * results) cannot push the request past the cap that triggered
     * factory.pub / Anthropic gateways to silently return 200 +
     * empty SSE with `finish_reason=stop`. Eldest images are elided
     * to text placeholders first.
     */
    const val MAX_REQUEST_BYTES = 100L * 1024 * 1024

    /** Default re-encode target longest edge in pixels. */
    const val MAX_EDGE_PX = 2000

    /** Default re-encode JPEG quality (0-100). */
    const val JPEG_QUALITY = 80

    private const val TAG = "ImageBudget"
    private const val MAX_VALIDATION_EDGE_PX = 256
    private const val INVALID_MIME = ""
    private val validationCache = Collections.synchronizedMap(WeakHashMap<ByteArray, String>())

    data class NormalizedImage(
        val data: ByteArray,
        val mimeType: String,
    )

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /**
     * (maxEdge, quality) candidates the per-image compressor walks until the
     * output fits under [MAX_PER_IMAGE_BYTES]. Mirrors the iOS ladder from
     * AIChatViewModel.swift compressedImageDataUnderBudget(...).
     */
    private val LADDER: List<Pair<Int, Int>> = listOf(
        2000 to 80,
        1600 to 75,
        1280 to 70,
        1024 to 65,
        896 to 55,
        768 to 50,
        640 to 45,
    )

    /**
     * Validate and normalize an image before it reaches any provider.
     * Supported formats stay in their original encoding when already under
     * the single-image cap; other decodable formats are re-encoded as JPEG.
     */
    fun normalizeImage(data: ByteArray, mimeType: String): NormalizedImage? {
        if (data.isEmpty()) return null

        val magicMime = detectMagicMime(data)
        if (magicMime != null) {
            if (data.size.toLong() <= MAX_PER_IMAGE_BYTES) {
                when (cachedValidationMime(data)) {
                    INVALID_MIME -> return null
                    magicMime -> return NormalizedImage(data, magicMime)
                }
                if (!decodeSample(data)) {
                    cacheValidation(data, INVALID_MIME)
                    return null
                }
                cacheValidation(data, magicMime)
                return NormalizedImage(data, magicMime)
            }

            val jpeg = compressUnderBudgetOrNull(data) ?: run {
                cacheValidation(data, INVALID_MIME)
                return null
            }
            cacheValidation(data, magicMime)
            return NormalizedImage(jpeg, "image/jpeg")
        }

        // Unknown encodings (for example HEIC) are accepted only when Android
        // can decode them, and are always converted to a bounded JPEG.
        val jpeg = compressUnderBudgetOrNull(
            input = data,
            targetMaxBytes = MAX_PER_IMAGE_BYTES,
            forceReencode = true,
        ) ?: return null
        return jpeg.takeIf { it.isNotEmpty() && it.size.toLong() <= MAX_PER_IMAGE_BYTES }
            ?.let { NormalizedImage(it, "image/jpeg") }
    }

    private fun detectMagicMime(data: ByteArray): String? = when {
        data.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { data[it] == PNG_SIGNATURE[it] } -> "image/png"
        data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte() -> "image/jpeg"
        data.size >= 12 && hasAscii(data, 0, "RIFF") && hasAscii(data, 8, "WEBP") -> "image/webp"
        data.size >= 6 && (hasAscii(data, 0, "GIF87a") || hasAscii(data, 0, "GIF89a")) -> "image/gif"
        else -> null
    }

    private fun hasAscii(data: ByteArray, offset: Int, value: String): Boolean =
        offset >= 0 && offset + value.length <= data.size && value.indices.all {
            data[offset + it] == value[it].code.toByte()
        }

    private fun cachedValidationMime(data: ByteArray): String? = synchronized(validationCache) {
        validationCache[data]
    }

    private fun cacheValidation(data: ByteArray, mimeType: String) {
        synchronized(validationCache) {
            validationCache[data] = mimeType
        }
    }

    /** Bounds decode first, then decode only a small sampled bitmap. */
    private fun decodeSample(data: ByteArray): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_VALIDATION_EDGE_PX)
            }
            val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options) ?: return false
            try {
                bitmap.width > 0 && bitmap.height > 0
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "decodeSample failed (${data.size}B): ${t.message}")
            false
        }
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val target = maxEdge.coerceAtLeast(1).toLong()
        while (width.toLong() / sample > target || height.toLong() / sample > target) {
            if (sample > Int.MAX_VALUE / 2) return sample
            sample *= 2
        }
        return sample
    }

    /**
     * Re-encode [input] to a JPEG with max longest edge [maxEdge] at JPEG
     * quality [q]. Returns null on decode/encode failure.
     */
    fun compressBytes(input: ByteArray, maxEdge: Int = MAX_EDGE_PX, q: Int = JPEG_QUALITY): ByteArray? {
        if (input.isEmpty()) return null
        var decoded: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            // Two-pass decode mirroring PhotosOffloadHandler.copyResized — sampled
            // bounds first to keep the in-memory bitmap proportional to maxEdge,
            // then scaled to the exact target after decode.
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, boundsOpts)
            val w0 = boundsOpts.outWidth
            val h0 = boundsOpts.outHeight
            if (w0 <= 0 || h0 <= 0) return null
            val edge = maxEdge.coerceAtLeast(1)
            var sample = 1
            val sampleTarget = edge.toLong() * 2L
            while (w0.toLong() / sample > sampleTarget || h0.toLong() / sample > sampleTarget) {
                if (sample > Int.MAX_VALUE / 2) return null
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            decoded = BitmapFactory.decodeByteArray(input, 0, input.size, decodeOpts) ?: return null
            val scale = minOf(
                edge.toFloat() / decoded.width,
                edge.toFloat() / decoded.height,
                1f,
            )
            val out = if (scale < 1f) {
                scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                scaled ?: return null
            } else decoded!!
            val baos = ByteArrayOutputStream()
            if (!out.compress(Bitmap.CompressFormat.JPEG, q.coerceIn(1, 100), baos)) return null
            baos.toByteArray().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "compressBytes failed (${input.size}B): ${t.message}")
            null
        } finally {
            if (scaled != null && scaled !== decoded) scaled?.recycle()
            decoded?.recycle()
        }
    }

    /**
     * Try increasingly aggressive (maxEdge, quality) candidates until the
     * re-encoded JPEG fits under [targetMaxBytes] bytes. Returns the smallest
     * encoding produced when no candidate fits. Callers that must enforce a
     * hard byte limit should use [compressUnderBudgetOrNull].
     *
     * Re-encodes from the original bytes on each candidate so the JPEG never
     * compounds artifacts.
     */
    fun compressUnderBudget(input: ByteArray, targetMaxBytes: Long = MAX_PER_IMAGE_BYTES): ByteArray {
        return compressUnderBudgetOrNull(input, targetMaxBytes) ?: input
    }

    /**
     * Same ladder as [compressUnderBudget], but returns null when the source
     * cannot be made safe for inline transport. A failed decode/encode must
     * never silently fall back to an over-limit original payload.
     */
    fun compressUnderBudgetOrNull(
        input: ByteArray,
        targetMaxBytes: Long = MAX_PER_IMAGE_BYTES,
    ): ByteArray? = compressUnderBudgetOrNull(input, targetMaxBytes, forceReencode = false)

    private fun compressUnderBudgetOrNull(
        input: ByteArray,
        targetMaxBytes: Long,
        forceReencode: Boolean,
    ): ByteArray? {
        val normalizedTarget = targetMaxBytes.coerceAtLeast(0L)
        if (!forceReencode && input.size.toLong() <= normalizedTarget) return input
        var best: ByteArray? = if (forceReencode) null else input
        var bestSize = best?.size ?: Int.MAX_VALUE
        for ((edge, q) in LADDER) {
            val candidate = compressBytes(input, edge, q) ?: continue
            if (candidate.size < bestSize) {
                best = candidate
                bestSize = candidate.size
            }
            if (candidate.size.toLong() <= normalizedTarget) {
                AppLogger.info(TAG, "compressUnderBudget hit: ${input.size}B → ${candidate.size}B (edge=$edge q=$q)")
                return candidate
            }
        }
        AppLogger.warning(TAG, "compressUnderBudget exhausted ladder: ${input.size}B → ${best?.size ?: 0}B (target=${normalizedTarget}B)")
        return best?.takeIf { it.size.toLong() <= normalizedTarget }
    }

    /** Result of [applyMessageBudget]. */
    data class BudgetResult(
        /** Image bytes ready to send, in original order, dropped occurrences removed. */
        val keptBytes: List<ByteArray>,
        /** Number of parts whose bytes were re-encoded by the ladder. */
        val compressedCount: Int,
        /** Number of tail parts dropped because the cumulative cap was hit. */
        val droppedCount: Int,
        /** Final total payload bytes after compression + drop. */
        val totalBytes: Long,
        /** Original occurrence indexes corresponding to [keptBytes]. */
        val keptIndices: List<Int> = emptyList(),
    ) {
        val mutated: Boolean get() = compressedCount > 0 || droppedCount > 0
    }

    /**
     * Walk [bytesIn] and produce a budgeted output:
     *  - Each oversize part is run through [compressUnderBudgetOrNull] first.
     *  - Then cumulative bytes are summed; once the running total would
     *    exceed [MAX_TOTAL_BYTES] the remaining tail is dropped.
     */
    fun applyMessageBudget(
        bytesIn: List<ByteArray>,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): BudgetResult {
        if (bytesIn.isEmpty()) return BudgetResult(emptyList(), 0, 0, 0L)
        val kept = ArrayList<ByteArray>(bytesIn.size)
        val keptIndices = ArrayList<Int>(bytesIn.size)
        var compressed = 0
        var dropped = 0
        var running = 0L
        for ((index, part) in bytesIn.withIndex()) {
            val sized = if (part.size.toLong() > MAX_PER_IMAGE_BYTES) {
                val c = compressUnderBudgetOrNull(part)
                if (c == null) {
                    dropped += 1
                    continue
                }
                if (c.size != part.size) compressed += 1
                c
            } else part
            if (running + sized.size.toLong() > maxTotalBytes.coerceAtLeast(0L)) {
                dropped += 1
                continue
            }
            kept.add(sized)
            keptIndices.add(index)
            running += sized.size.toLong()
        }
        if (dropped > 0 || compressed > 0) {
            AppLogger.info(
                TAG,
                "applyMessageBudget: in=${bytesIn.size} kept=${kept.size} compressed=$compressed dropped=$dropped total=${running}B",
            )
        }
        return BudgetResult(kept, compressed, dropped, running, keptIndices)
    }

    // ─── Request-level budget ──────────────────────────────────────────────

    /**
     * Identifies a single image part inside an outgoing request payload.
     * Used by providers to look up whether a part has been marked for
     * elision by [planRequestBudget]. The occurrence is the stable structural
     * position assigned by the planner; it deliberately does not depend on
     * ByteArray identity, so repeated references to one array remain distinct.
     */
    @JvmInline
    value class ImagePartId(val occurrence: Int) {
        companion object {
            fun of(occurrence: Int): ImagePartId = ImagePartId(occurrence)
        }
    }

    /**
     * Image part scoped for budget planning. Lets the planner stay generic
     * over `LLMMessage.ImagePart` (current-turn user attachments) and
     * `AgentContentPart.ImageData` / `AgentContentPart.ToolResult.imageData`
     * (history image bytes) without depending on either type directly.
     */
    data class BudgetImage(
        val data: ByteArray,
        val linuxPath: String?,
        val mimeType: String,
    )

    /**
     * Result of [planRequestBudget].
     */
    data class RequestBudgetPlan(
        /** Identity hashes of image bytes that providers must NOT inline. */
        val droppedIds: Set<ImagePartId>,
        /** Map from dropped identity → original linux path (null if none). */
        val droppedPaths: Map<ImagePartId, String?>,
        /** Total bytes kept after planning (sum of per-image cap-clamped). */
        val keptBytes: Long,
        /** Total bytes elided. */
        val elidedBytes: Long,
        /** Number of images elided. */
        val droppedCount: Int,
        /** Total images considered (kept + dropped). */
        val totalCount: Int,
    ) {
        val mutated: Boolean get() = droppedCount > 0
    }

    /**
     * Walk all candidate images in reverse chronological order (latest
     * first) and decide which can fit under [maxBytes]. Anything beyond
     * the cap is recorded in [RequestBudgetPlan.droppedIds] so providers
     * emit a text placeholder instead of base64 bytes.
     *
     * @param images Ordered eldest → latest. The planner reverses
     *   internally so the latest user input + most recent tool results
     *   are protected from elision.
     * @param maxBytes Maximum cumulative inline image payload in bytes;
     *   independent of any context token budget.
     */
    fun planRequestBudget(
        images: List<BudgetImage>,
        maxBytes: Long = MAX_REQUEST_BYTES,
    ): RequestBudgetPlan {
        if (images.isEmpty()) {
            return RequestBudgetPlan(emptySet(), emptyMap(), 0L, 0L, 0, 0)
        }
        val dropped = HashSet<ImagePartId>()
        val droppedPaths = HashMap<ImagePartId, String?>()
        var kept = 0L
        var elided = 0L
        val normalizedMaxBytes = maxBytes.coerceAtLeast(0L)
        // Walk latest → eldest so most-recent images win the budget.
        for (occurrence in images.indices.reversed()) {
            val img = images[occurrence]
            val id = ImagePartId.of(occurrence)
            // Per-image cap-clamped size — same ceiling
            // `compressUnderBudget` would have produced if invoked.
            val effectiveSize = minOf(img.data.size.toLong(), MAX_PER_IMAGE_BYTES)
            if (kept + effectiveSize <= normalizedMaxBytes) {
                kept += effectiveSize
            } else {
                dropped.add(id)
                droppedPaths[id] = img.linuxPath
                elided += effectiveSize
            }
        }
        if (dropped.isNotEmpty()) {
            AppLogger.info(
                TAG,
                "planRequestBudget: in=${images.size} kept=${images.size - dropped.size} dropped=${dropped.size} keptBytes=${kept}B elidedBytes=${elided}B cap=${normalizedMaxBytes}B",
            )
        }
        return RequestBudgetPlan(
            droppedIds = dropped,
            droppedPaths = droppedPaths,
            keptBytes = kept,
            elidedBytes = elided,
            droppedCount = dropped.size,
            totalCount = images.size,
        )
    }

    /**
     * Build the text placeholder a provider emits in place of an elided
     * image. The model gets a clear, actionable hint: this image was
     * dropped to fit the budget, and (if known) the linux path where the
     * bytes are still readable via [read_image]. Without a path the model
     * just sees that an image was elided and can ask the user to re-attach.
     * [maxBytes] is a byte count and is independent of context token limits.
     */
    fun elidedImagePlaceholder(
        linuxPath: String?,
        maxBytes: Long = MAX_REQUEST_BYTES,
    ): String {
        val normalizedBytes = maxBytes.coerceAtLeast(0L)
        val mib = 1024L * 1024L
        val budgetLabel = if (normalizedBytes % mib == 0L) {
            "${normalizedBytes / mib}MiB"
        } else {
            "${normalizedBytes}B"
        }
        return if (linuxPath != null) {
            "[image elided to fit $budgetLabel request budget. Original at $linuxPath — re-fetch with `read_image $linuxPath` if you need to see it.]"
        } else {
            "[image elided to fit $budgetLabel request budget. Original bytes no longer addressable; ask the user to re-attach if needed.]"
        }
    }

    fun invalidImagePlaceholder(linuxPath: String?, mimeType: String?): String {
        val declaredMime = mimeType?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown MIME"
        return if (linuxPath != null) {
            "[image omitted: invalid or empty image ($declaredMime). Original at $linuxPath — re-fetch with `read_image $linuxPath` if needed.]"
        } else {
            "[image omitted: invalid or empty image ($declaredMime). Ask the user to re-attach it if needed.]"
        }
    }

    /**
     * Lazily persist [data] to a session-scoped spillover dir under
     * `attachments/spillover/<sha1>.<ext>` so an elided image without a
     * pre-existing linux path can still be referenced from the text
     * placeholder. The spillover dir is bind-mounted to
     * `/var/minis/attachments/spillover/` inside iSH (same mount as
     * `attachments/uploads/`). Returns the iSH-visible linux path on
     * success, or null if the write failed (in which case the placeholder
     * falls back to the no-path variant).
     *
     * Idempotent: if the same bytes already exist on disk under the
     * sha1 prefix, returns the existing path without re-writing.
     */
    fun ensureSpillover(
        sessionAttachmentsDir: java.io.File,
        data: ByteArray,
        mimeType: String,
    ): String? {
        if (data.isEmpty()) return null
        val ext = when (mimeType.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "bin"
        }
        val sha = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.update(data)
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            // SHA-1 is required by the platform; fallback is just an
            // identity hash — collisions are tolerable here.
            System.identityHashCode(data).toString(16)
        }
        val spilloverDir = java.io.File(sessionAttachmentsDir, "spillover")
        if (!spilloverDir.exists()) {
            try {
                spilloverDir.mkdirs()
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover mkdirs failed: ${e.message}")
                return null
            }
        }
        val file = java.io.File(spilloverDir, "$sha.$ext")
        if (!file.exists()) {
            try {
                file.writeBytes(data)
            } catch (e: Exception) {
                AppLogger.warning(TAG, "ensureSpillover write failed: ${e.message}")
                return null
            }
        }
        // Mirrors uploads mount (see ChatViewModel.prepareUserAttachments).
        return "/var/minis/attachments/spillover/$sha.$ext"
    }
}
