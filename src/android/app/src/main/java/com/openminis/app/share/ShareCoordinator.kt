package com.openminis.app.share

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors iOS Shared/ShareCoordinator.swift. Singleton bridge between
 * ShareReceiverActivity (producer, lives in a different Activity instance
 * before re-launching MainActivity) and ChatScreen (consumer, lives inside
 * MainActivity). Keeps the durable [PendingShare] until composer persistence
 * acknowledges delivery; the in-memory notification has a 300s TTL —
 * long enough for the launch flow to land in a chat session, short enough
 * that a stale share doesn't surprise the user weeks later.
 */
object ShareCoordinator {
    private const val TAG = "ShareCoordinator"
    // [T-android-share-buffer-merge] 30s → 300s, aligned with
    // LAUNCH_MAX_AGE_MS below and iOS a51e7255. The old 30s window expired
    // whenever the user lingered on the session list after sharing, so a
    // perfectly good share was discarded before they ever opened a chat.
    private const val BUFFER_TTL_MS = 300_000L
    private const val LAUNCH_MAX_AGE_MS = 5L * 60 * 1000

    private data class Buffered(val share: PendingShare, val bufferedAtMs: Long)

    @Volatile private var buffer: Buffered? = null
    private var inFlight: PendingShare? = null

    private val _bufferVersion = MutableStateFlow(0)
    /**
     * Increments every time a new share is buffered. ChatScreen observes
     * this via collectAsState so a warm-start (user already inside a
     * session) injects too. Cold starts pick up the buffer on first
     * composition since the version is non-zero by then.
     */
    val bufferVersion: StateFlow<Int> = _bufferVersion.asStateFlow()

    /**
     * Called from MainActivity.onCreate / onNewIntent when the share
     * receiver re-launched MainActivity with the `shared_content` extra.
     * Loads from prefs, drops if older than [LAUNCH_MAX_AGE_MS] (5 min,
     * mirrors iOS launch check), stores in the in-memory buffer. Persistence
     * survives process death until acknowledgeConsumed confirms the draft.
     */
    @Synchronized
    fun processPendingShare(context: Context) {
        val pending = SharedShareStore.loadPendingShare(context) ?: run {
            AppLogger.info(TAG, "[Share] processPendingShare: no pending share")
            return
        }
        val ageMs = System.currentTimeMillis() - pending.timestampMs
        if (ageMs > LAUNCH_MAX_AGE_MS) {
            AppLogger.info(TAG, "[Share] processPendingShare: stale (age=${ageMs}ms), discarding")
            SharedShareStore.discardPendingShare(context, pending)
            // [T-android-share-buffer-merge] Preserve files still referenced by a
            // live in-memory buffer. Discarding THIS stale record must not delete
            // an earlier share's attachments that are still waiting to be
            // injected — with a 300s buffer TTL that overlap is entirely
            // reachable.
            SharedShareStore.cleanSharedFiles(context, keep = liveBufferFileNames())
            return
        }
        // The store already merges and caps producer writes. Exclude the
        // claimed snapshot so a warm launch cannot inject it a second time.
        val now = System.currentTimeMillis()
        val claimed = inFlight?.items.orEmpty().toSet()
        val mergedShare = pending.copy(items = pending.items.filterNot { it in claimed })
        if (mergedShare.items.isEmpty()) return
        if (buffer?.share == mergedShare) return
        // bufferedAt renews on merge so the combined buffer gets a full TTL.
        buffer = Buffered(mergedShare, now)
        _bufferVersion.value = _bufferVersion.value + 1
        // [XSessionDiag] Hypothesis 2: this buffer is process-global, is NOT
        // addressed to any session, and MERGES rather than replaces — dedup is by
        // exact (kind, value), so near-duplicate fragments of the same text all
        // survive and concatenate. That is the shape of the reported corrupted
        // input (same sentence restarted, interleaved with its own translation).
        // Log every item's digest so a later report can be matched character-for-
        // character against what the model received. Fires once per share, not in
        // a loop over anything hot.
        logBufferState("buffered", mergedShare, now, bufferedAtMs = now)
    }

    /**
     * [XSessionDiag] Dump the buffer's contents with per-item digests.
     *
     * Truncates each value to [DIAG_VALUE_PREVIEW] chars: a share can carry a
     * whole article, and the diagnostic question is "which fragments merged
     * together", which the head of each item answers. Length is logged in full so
     * truncation never hides the real size.
     */
    private fun logBufferState(
        phase: String,
        share: PendingShare,
        nowMs: Long,
        bufferedAtMs: Long,
    ) {
        val items = share.items.mapIndexed { i, item ->
            val v = item.value
            val preview = v.take(DIAG_VALUE_PREVIEW).replace("\n", "\\n")
            "#$i(${item.kind.name} len=${v.length} \"$preview\"" +
                if (v.length > DIAG_VALUE_PREVIEW) "…\")" else "\")"
        }
        // ttlRemaining is measured from bufferedAtMs, matching consumeBuffer's own
        // expiry test — using share.timestampMs here would report a different
        // number than the one that actually governs the drop.
        AppLogger.info(
            TAG,
            "[XSessionDiag] share/$phase: items=${share.items.size} " +
                "shareTimestampMs=${share.timestampMs} bufferedAtMs=$bufferedAtMs now=$nowMs " +
                "ageMs=${nowMs - bufferedAtMs} ttlRemainingMs=${BUFFER_TTL_MS - (nowMs - bufferedAtMs)} " +
                "-> ${items.joinToString(" | ")}",
        )
    }

    /** Head-of-value length used by [logBufferState]; diagnostic only. */
    private const val DIAG_VALUE_PREVIEW = 100

    /**
     * Attachment file names referenced by the live in-memory buffer, if any.
     * Used to protect them from a directory-wide cleanup triggered by an
     * unrelated stale record. Empty when nothing is buffered.
     */
    private fun liveBufferFileNames(): Set<String> =
        (buffer?.share?.items.orEmpty() + inFlight?.items.orEmpty())
            .filter { it.kind == PendingShare.Item.Kind.ATTACHMENT }.map { it.value }.toSet()

    /**
     * Claim once in this process. Caller must persist its draft and acknowledge,
     * or release on failure; a process restart recovers the durable snapshot.
     *
     * [T-android-share-buffer-merge] An expired buffer used to vanish with
     * only a log line — the user shared something, opened the app, and
     * nothing happened with no explanation. Now the discard surfaces a
     * toast. Mirrors iOS a51e7255's ShareFeedbackToast.
     */
    /**
     * [diagSessionId] is **diagnostic only** — it is logged and nothing else.
     * Deliberately optional with a null default so no call site's behaviour
     * changes: the whole point of hypothesis 2 is that this buffer is NOT
     * addressed to a session, and wiring the id into the decision here would be
     * the fix, not the diagnosis. Recording who drained it is what lets a repro
     * log answer "did the wrong chat eat this share?".
     */
    @Synchronized
    fun consumeBuffer(context: Context, diagSessionId: String? = null): PendingShare? {
        if (inFlight != null) return null
        if (buffer == null) processPendingShare(context)
        val buf = buffer ?: return null
        buffer = null
        val nowMs = System.currentTimeMillis()
        val ageMs = nowMs - buf.bufferedAtMs
        if (ageMs > BUFFER_TTL_MS) {
            SharedShareStore.discardPendingShare(context, buf.share)
            AppLogger.info(TAG, "[Share] consumeBuffer: expired (age=${ageMs}ms)")
            AppLogger.info(
                TAG,
                "[XSessionDiag] share/consume-expired: " +
                    "consumer=${diagSessionId?.take(8) ?: "unknown"} " +
                    "items=${buf.share.items.size} ageMs=$ageMs ttlMs=$BUFFER_TTL_MS",
            )
            SharedShareStore.cleanSharedFiles(context)
            notifyExpired(context)
            return null
        }
        AppLogger.info(TAG, "[Share] consumeBuffer: ${buf.share.items.size} item(s)")
        // [XSessionDiag] Hypothesis 2: whichever ChatScreen composes next drains
        // this — there is no target check (contrast ChatViewModelStore's
        // PendingTransfer, hardened with targetId for this same bug class). The
        // consumer id here is the session that will receive the text.
        logBufferState("consume", buf.share, nowMs, buf.bufferedAtMs)
        AppLogger.info(
            TAG,
            "[XSessionDiag] share/consumed-by: consumer=${diagSessionId?.take(8) ?: "unknown"} " +
                "items=${buf.share.items.size} ageMs=$ageMs",
        )
        inFlight = buf.share
        return buf.share
    }

    @Synchronized
    fun acknowledgeConsumed(context: Context, pending: PendingShare) {
        if (inFlight != pending) return
        SharedShareStore.acknowledgeConsumed(context, pending)
        inFlight = null
        buffer = null
        processPendingShare(context)
    }

    @Synchronized
    fun releaseConsumption(context: Context, pending: PendingShare) {
        if (inFlight != pending) return
        inFlight = null
        // Keep a retryable snapshot without waking the same failing UI effect.
        // A session change, a new share or a later launch can retry it.
        buffer = SharedShareStore.loadPendingShare(context)?.let { Buffered(it, it.timestampMs) }
    }

    /**
     * Surface the expiry to the user. Posted on the main looper because
     * consumeBuffer is reachable from a composition/IO context, and Toast
     * requires a Looper-backed thread.
     */
    private fun notifyExpired(context: Context) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    android.widget.Toast.makeText(
                        context.applicationContext,
                        context.getString(com.openminis.app.R.string.share_expired_toast),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
