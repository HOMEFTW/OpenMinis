package com.openminis.app.ui.sessions

import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger

/**
 * [T-sessions-cli-update] The session list's Regenerate Title, without the
 * ViewModel, so `minis-sessions-cli update --title-auto` runs the same code.
 */
internal class SessionTitleRegenerator(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val context: android.content.Context,
) {
    private companion object {
        /** The session list's tag, so the log reads as before. */
        const val TAG = "SessionListVM"
        /**
         * [GH#210] The placeholder ChatViewModel writes for a session whose
         * title has not been generated yet. The fallback path keys off this
         * exact string (plus null/blank), which is what keeps a user-typed
         * title from ever being overwritten.
         */
        const val NEW_CHAT_TITLE = "New Chat"
    }

    /**
     * [GH#210] Generate a title for [id] purely from what is in the DB.
     *
     * Takes no ViewModel state, so the same code serves any USER-INITIATED
     * entry point. It is deliberately never called automatically: an implicit
     * LLM request the user did not ask for — e.g. sweeping untitled sessions
     * at list load — would mean a burst of hidden network calls at startup,
     * which is not an acceptable trade for a cosmetic title.
     *
     * @param origin tags the log line so different entry points can be told
     *   apart when reconciling dispatch against outcome.
     * @return true when a title was written (LLM or fallback).
     */
    suspend fun regenerate(id: String, origin: String): Boolean {
        val startedAt = System.currentTimeMillis()
        var firstUserRaw: String? = null
        try {
                val session = chatRepository.getSession(id) ?: return false
                val messages = chatRepository.loadMessages(id)
                if (messages.isEmpty()) return false

                // [T-titlegen-context-first-last-pair] Summary = first user +
                // first assistant, plus (when the session has more than one user
                // turn) the last user + last assistant, each truncated to 200
                // chars — so a regenerated title reflects a mid/late topic shift
                // rather than only the opener.
                val userMessages = messages.filter { it.role == "user" }
                // Keep the untruncated first user message for the fallback path.
                firstUserRaw = userMessages.firstOrNull()?.let { extractText(it.partsJson) }
                val userText = firstUserRaw?.take(200) ?: return false
                // First/last assistant *text* message — skip tool-only messages
                // whose extracted text is blank so the summary carries real prose.
                val assistantTexts = messages.filter { it.role == "assistant" }
                    .map { extractText(it.partsJson) }
                    .filter { it.isNotBlank() }
                val firstAssistantText = assistantTexts.firstOrNull()?.take(200) ?: ""
                val hasMultipleUserTurns = userMessages.size > 1
                val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.let { extractText(it.partsJson) }?.take(200) ?: "" else ""
                val lastAssistantText = if (hasMultipleUserTurns) assistantTexts.lastOrNull()?.take(200) ?: "" else ""

                val prompt = buildString {
                    append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                    append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                    append("You MUST respond with valid JSON only. Example:\n")
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                    append("User: $userText\n")
                    if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                    if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                    if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                    append(com.openminis.app.ui.chat.titleLanguageDirective())
                }

                val candidates = com.openminis.app.ui.chat.TitleCandidates.forSession(
                    providerRepository, id,
                    com.openminis.app.ui.chat.TitleCandidates.PrimarySource.parse(session.modelBinding),
                    session.modelId,
                )
                val written = com.openminis.app.ui.chat.TitleCandidates.walk(
                    candidates, origin = origin,
                    shouldStop = { chatRepository.getSession(id)?.title != session.title },
                ) { entry ->
                    val provider = com.openminis.app.ui.chat.TitleCandidates.providerFor(
                        providerRepository, context, entry, id,
                    ) ?: return@walk null

                    AppLogger.info(
                        "TitleGen",
                        "dispatch origin=$origin session=${id.take(8)} model=${entry.model.id}",
                    )

                        // T334: reasoning models burn the entire token budget on hidden thinking
                        // before emitting any content. With maxTokens=100 every reasoning candidate
                        // returned `finish_reason=length` with empty text, then the loop silently
                        // moved on. Give reasoning models a real budget (1024) so they can finish
                        // thinking and still emit the JSON title.
                        val titleMaxTokens = if (entry.model.supportsReasoning == true) 2048 else 100
                        // [T-android-titlegen-reasoning] Explicitly disable
                        // thinking (thinkingLevel = OFF), matching iOS
                        // callSubModelForTitle and the auto-title path. The
                        // provider's injectThinkingParams honors OFF (e.g.
                        // DeepSeek V4 → {"thinking":{"type":"disabled"}}), so a
                        // reasoning model doesn't spend the budget on hidden
                        // thinking; the T334 maxTokens bump above stays as a
                        // safety net for models where OFF is a no-op (Qwen3).
                        val response = provider.sendMessage(
                            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                            // [T-android-titlegen-systemprompt-unify] Shared with
                            // the auto path via TITLE_GEN_SYSTEM_PROMPT (iOS-aligned
                            // wording). Passed bare — AnthropicProvider handles the
                            // OAuth Claude Code prefix at the provider layer.
                            systemPrompt = com.openminis.app.ui.chat.TITLE_GEN_SYSTEM_PROMPT,
                            maxTokens = titleMaxTokens,
                            // [T-android-titlegen-temperature] null (not 0.3) so
                            // buildRequestBody omits the field — the gpt-5.x
                            // family only accepts temperature=1 and 400s on any
                            // other value, which would silently skip that
                            // candidate. Aligns with the auto-title path and iOS
                            // AIChatViewModel.swift:11244.
                            temperature = null,
                            thinkingLevel = ThinkingLevel.OFF,
                        )
                        val parsed = com.openminis.app.ui.chat.parseGeneratedTitle(response.text, response.stopReason)
                        val title = parsed?.title.orEmpty()
                        val category = parsed?.category
                        if (title.isNotEmpty()) {
                            if (chatRepository.getSession(id)?.title != session.title) return@walk false
                            if (!chatRepository.updateSessionTitleIfUnchanged(id, session.title, title, category)) return@walk false
                            AppLogger.info(
                                "TitleGen",
                                "outcome=set origin=$origin session=${id.take(8)} " +
                                    "model=${entry.model.id} elapsedMs=${System.currentTimeMillis() - startedAt}",
                            )
                            return@walk true
                        }
                        // T334: previously this empty-result path was silent — only the *last*
                        // failing candidate's exception got reported, masking budget exhaustion
                        // on reasoning models. Surface it explicitly so logs show the real cause.
                        Log.w(
                            TAG,
                            "Title regen empty from ${entry.model.displayName}: " +
                                "stopReason=${response.stopReason} textLen=${response.text.length} " +
                                "maxTokens=$titleMaxTokens supportsReasoning=${entry.model.supportsReasoning}",
                        )
                    null
                }
                if (written != null) return written
                AppLogger.warning("TitleGen", "outcome=no-title origin=$origin session=${id.take(8)} reason=all-candidates-exhausted")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && com.openminis.app.ui.chat.TitleCandidates.isRealCancellation(e)) throw e
            AppLogger.warning(
                "TitleGen",
                "outcome=exception origin=$origin session=${id.take(8)} " +
                    "${e.javaClass.simpleName}: ${e.message?.take(200)} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}",
            )
        }
        // Every failure exit lands here. Mirrors iOS applyFallbackTitle: the
        // session must never be left permanently untitled just because the LLM
        // was unreachable.
        return applyFallbackTitle(id, firstUserRaw, origin)
    }

    /**
     * [GH#210] Write a title derived from the first user message.
     *
     * Mirrors iOS `applyFallbackTitle` — including its re-read of the session
     * immediately before writing. That re-check is the guard that keeps this
     * from clobbering a title the user typed (or a concurrent attempt set)
     * while the LLM call was in flight, which can be tens of seconds.
     */
    private suspend fun applyFallbackTitle(id: String, firstUserRaw: String?, origin: String): Boolean {
        val current = chatRepository.getSession(id)?.title
        if (!current.isNullOrBlank() && current.trim() != NEW_CHAT_TITLE) {
            AppLogger.info(
                "TitleGen",
                "outcome=fallback-skipped origin=$origin session=${id.take(8)} reason=already-titled",
            )
            return false
        }
        val cleaned = fallbackTitleFrom(firstUserRaw)
        if (cleaned == null) {
            AppLogger.warning(
                "TitleGen",
                "outcome=fallback-unavailable origin=$origin session=${id.take(8)} " +
                    "reason=first-user-message-empty-after-cleanup",
            )
            return false
        }
        if (!chatRepository.updateSessionTitleIfUnchanged(id, current, cleaned)) return false
        // Length only — never the prompt text itself.
        AppLogger.info(
            "TitleGen",
            "outcome=fallback origin=$origin session=${id.take(8)} titleLen=${cleaned.length}",
        )
        return true
    }

    /**
     * Strip the composer's `<user-attached-files>` block, collapse whitespace
     * and truncate to 30 chars. Same shape as iOS `fallbackTitle(fromFirst‑
     * UserMessage:)` and ChatViewModel.applyFallbackTitleFromFirstMessage, so
     * a title recovered here is indistinguishable from one written by the auto
     * path. Returns null when nothing usable remains.
     */
    private fun fallbackTitleFrom(raw: String?): String? {
        var text = raw ?: return null
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx >= 0) {
            val endTag = "</user-attached-files>"
            val endIdx = text.indexOf(endTag, startIdx)
            text = if (endIdx >= 0) {
                text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
            } else {
                text.substring(0, startIdx)
            }
        }
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return null
        return if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
    }

    private fun extractText(partsJson: String): String {
        return try {
            val arr = org.json.JSONArray(partsJson)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                if (obj.optString("type") != "text") return@mapNotNull null
                val value = obj.optString("value")
                // [T-android-retry-attachment-loss] The user-message
                // <user-attached-files> XML is now persisted as a text part so
                // the model keeps file paths across retry/reload. Drop it from
                // session-list previews + search snippets so the inventory XML
                // never surfaces as visible session content (iOS strips it the
                // same way in ChatStore.toChatMessage).
                if (value.contains("<user-attached-files>")) {
                    val start = value.indexOf("<user-attached-files>")
                    val endTag = "</user-attached-files>"
                    val end = value.indexOf(endTag, start)
                    val cleaned = if (end >= 0) {
                        value.substring(0, start) + value.substring(end + endTag.length)
                    } else {
                        value.substring(0, start)
                    }.trim()
                    cleaned.ifEmpty { null }
                } else {
                    value
                }
            }.joinToString("\n")
        } catch (_: Exception) {
            partsJson
        }
    }
}
