// Backup contract excerpt from upstream b4c0661d5631ebab4d1a2e6f3fd4c805d4030a6c.
struct ChatSession: Identifiable, Codable, Hashable {
    let id: String
    var title: String?
    var category: String?
    let modelId: String
    let createdAt: Date
    var updatedAt: Date
    var lastMessage: String?
    var source: String?       // e.g. "shortcut" for Shortcuts-triggered sessions
    var lastSyncedAt: Date?   // non-nil if successfully synced to iCloud
    var remoteDeviceId: String?   // non-nil if this session came from another device via iCloud
    var remoteDeviceName: String? // human-readable name of the remote device
    var pinnedAt: Date?       // non-nil if session is pinned; timestamp of when it was pinned
    var folderId: String?     // non-nil if filed into a folder; NULL = ungrouped
    /// [T-p1-delegate-task] Non-nil ⇒ this is a hidden CHILD session (a
    /// helper run spawned by `delegate_task`, later also `minis-scheduled
    /// --target child-of-current`). Visibility is derived from this one
    /// column: nil → home list; set → reachable only from the parent.
    var parentSessionId: String?
    /// The parent's `delegate_task` tool_use id that spawned this child, or
    /// nil for a child created without a tool block (scheduled, P2).
    var parentToolUseId: String?

    /// Whether this session is a hidden child of another session.
    var isChild: Bool { parentSessionId != nil }

    /// Whether this session is from a remote device (read-only).
    var isRemote: Bool { remoteDeviceId != nil }

    /// Whether this session is pinned to the top of the list.
    var isPinned: Bool { pinnedAt != nil }

    /// Whether this session belongs to a folder.
    var isFiled: Bool { folderId != nil }

    // MARK: - Equatable / Hashable (cheap, content-via-proxy)
    //
    // [T-ios-session-list-equatable-jank] The compiler-synthesized
    // `==`/`hash` compared EVERY stored field — including the long
    // `lastMessage` / `title` strings. SwiftUI's sidebar `ForEach(groupedSessions)`
    // makes AttributeGraph deep-compare the whole `[ChatSession]` array on every
    // transaction flush (session switch, tap, foreground, iCloud inbound). In
    // DEBUG that synthesized `String.==` runs the Unicode NFC normalization
    // slow path (no inlining / ARC elision), and across a long session list it
    // pinned the main thread for 1–2s+ (HangDetector: ChatSession
    // __derived_struct_equals → _stringCompareSlow → _NFCNormalizer). Release
    // optimizes it away, which is why Release didn't stall.
    //
    // Replace with an explicit cheap comparison: identity + the short
    // change-driving fields, using `updatedAt` as the proxy for the heavy
    // `lastMessage` (and any other body content) — every content mutation bumps
    // `updatedAt`, so we never need to NFC-compare the long string to detect a
    // change. `title`/`category`/`source` ARE compared (they can change without
    // a content write, e.g. rename / categorize) but they're short. Net: the
    // sidebar diff no longer touches the long strings.
    static func == (lhs: ChatSession, rhs: ChatSession) -> Bool {
        lhs.id == rhs.id
            && lhs.updatedAt == rhs.updatedAt
            && lhs.pinnedAt == rhs.pinnedAt
            // `folderId` MUST be compared: moving a session between folders
            // changes neither `updatedAt` nor any other compared field, so
            // without this the sidebar would keep rendering the row in its old
            // section until some unrelated mutation bumped the diff.
            && lhs.folderId == rhs.folderId
            // Promotion (clearing the parent) must re-sort the list.
            && lhs.parentSessionId == rhs.parentSessionId
            && lhs.title == rhs.title
            && lhs.category == rhs.category
            && lhs.source == rhs.source
            && lhs.lastSyncedAt == rhs.lastSyncedAt
            && lhs.remoteDeviceId == rhs.remoteDeviceId
            && lhs.remoteDeviceName == rhs.remoteDeviceName
        // Intentionally NOT comparing `lastMessage` (long string; `updatedAt`
        // is its change proxy) or the immutable `modelId` / `createdAt`.
    }

    func hash(into hasher: inout Hasher) {
        // Mirror ==: cheap, identity + updatedAt. Avoids hashing long strings.
        hasher.combine(id)
        hasher.combine(updatedAt)
    }
}
struct ChatFolder: Identifiable, Codable, Hashable {
    let id: String
    var name: String
    var icon: String?         // SF Symbol name; nil = use the composed icon
    var color: String?        // theme color token
    var origin: String        // "manual" | "ai" — provenance only, no behavior
    var sortIndex: Int        // reserved for V2 drag-reorder
    var pinnedAt: Date?       // non-nil = folder pinned above unpinned folders
    /// One-sentence description (≤100 chars). Auto-grouping context; never
    /// rendered in the home list, but shown as the Move-to-Group picker row
    /// subtitle and surfaced for editing in the rename dialog.
    var desc: String?
    let createdAt: Date
    var updatedAt: Date

    var isPinned: Bool { pinnedAt != nil }

    init(
        id: String = UUID().uuidString,
        name: String,
        icon: String? = nil,
        color: String? = nil,
        origin: String = "manual",
        sortIndex: Int = 0,
        pinnedAt: Date? = nil,
        desc: String? = nil,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.name = name
        self.icon = icon
        self.color = color
        self.origin = origin
        self.sortIndex = sortIndex
        self.pinnedAt = pinnedAt
        self.desc = desc
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }
}
struct CompactMarker: Identifiable, Codable {
    let id: String
    let sessionId: String
    /// LLM-generated summary of the compacted messages.
    let summary: String
    /// LEGACY: sort_order of the first message AFTER the compacted range.
    /// Only used as fallback when firstKeptMessageId resolution fails.
    let firstKeptSortOrder: Int
    /// Number of raw messages that were compacted. UI display only.
    let compactedCount: Int
    let createdAt: Date
    /// LEGACY: sort_order of the UI boundary message.
    /// Only used as fallback for divider positioning on legacy markers.
    let uiBoundarySortOrder: Int?
    /// LEGACY: DB message ID of the boundary message (pre-Phase A name for firstKeptMessageId).
    /// Kept for cross-device sync compatibility with older builds.
    let boundaryMessageId: String?
    /// PRIMARY: DB message ID of the first kept (active-region) message.
    /// All restore / compact logic resolves boundaries through this id.
    /// Optional only for legacy markers written before Phase A.
    let firstKeptMessageId: String?
    /// PRIMARY: DB message ID of the last compacted message (right edge of the
    /// compacted range). Used by prune protection to avoid deleting marker anchors.
    /// Optional only for legacy markers written before Phase A.
    let lastCompactedMessageId: String?
    /// Marker schema version. 1 = legacy multi-field model (firstKept/boundary/
    /// sortOrder fallback chain). 2 = simplified id-only model: only
    /// `lastCompactedMessageId` is authoritative; everything before it (incl.)
    /// is the compacted range, everything after it is "live anchor + new msgs".
    /// `compactedCount` and `summary` are still meaningful for UI; all other
    /// columns are ignored for v2 markers.
    var version: Int = 1
}
struct RawMessage: Identifiable, Codable, Hashable {
    let id: String
    let sessionId: String
    let role: MessageRole
    let parts: [ContentPart]
    let createdAt: Date
    var tokenUsage: StoredTokenUsage?
    /// Reasoning content from thinking models (Kimi, DeepSeek, QwQ, etc.) — must be echoed back.
    var reasoningContent: String?
    /// Number of mid-stream auto-retries that occurred before this message completed successfully.
    /// 0 means no interruption. Persisted to DB for display after session reload.
    var streamInterruptCount: Int = 0
    /// Database sort_order value. Populated during loadMessages, used for compact boundary tracking.
    var sortOrder: Int = 0
    /// [T-error-persist-ios] Device-local error string for a failed assistant
    /// turn. Mirrors ChatMessage.error; persisted to the messages.error_info
    /// column so the error indicator survives reload. nil = no error.
    var errorInfo: String? = nil

    /// [T-token-attribution-snapshot] The model that ACTUALLY produced this
    /// message, snapshotted when it was written.
    ///
    /// Snapshot rather than a reference to resolve later: usage is a record of
    /// what happened, and it must not change when the configuration it once
    /// pointed at changes. `modelDisplayName` / `providerType` travel with the
    /// id so a deleted provider does not make history unreadable.
    ///
    /// nil on rows written before these columns existed — the signal the Usage
    /// page uses to mark a row estimated rather than measured.
    var modelId: String? = nil
    var modelDisplayName: String? = nil
    /// `ProviderType` **rawValue** (e.g. `openAI`), never a display name.
    var providerType: String? = nil

    /// [T-responses-echo-persist] Serialized `ReasoningEcho` for this assistant
    /// turn — the OpenAI Responses `reasoning` head (item ids + summaries) plus
    /// the upstream that minted them.
    ///
    /// Stored as JSON in `messages.reasoning_echo` rather than as typed columns
    /// because it is a small, provider-shaped payload that only the provider
    /// layer interprets.
    ///
    /// `encrypted_content` is deliberately NOT persisted: it is large (base64),
    /// endpoint-bound, and worthless once the session may resume against a
    /// different upstream. Dropping it degrades to an id-only replay, which the
    /// live endpoint accepts (measured 200 with `store:false`) and which
    /// `upstreamIdentity` already makes safe.
    ///
    /// nil on rows written before this column existed, and on turns that
    /// genuinely produced no reasoning.
    var reasoningEchoJSON: String? = nil
    /// Diagnostics / disambiguation only; the UI never resolves through it.
    var providerInstanceId: String? = nil

    /// True if this message contains only tool results (no user text).
    /// These are internal agent loop messages that shouldn't render as user bubbles.
    var isToolResultOnly: Bool {
        role == .user && !parts.isEmpty && parts.allSatisfy {
            if case .toolResult = $0 { return true }
            return false
        }
    }

    /// [T-bridge-message-ui-leak] The internal assistant "bridge" row inserted
    /// when a queued user message interrupts a tool loop (#579): it exists
    /// purely to keep agentHistory role-alternation intact
    /// (…user(tool_result) → assistant(bridge) → user(queued)…) and must
    /// never render in the chat UI. Detected by content match rather than a
    /// schema flag: we generate this text verbatim, a content match also hides
    /// rows persisted by OLDER builds (a new column could not), and no
    /// DB/iCloud-sync wiring is needed.
    static let internalBridgeText =
        "(Interrupted mid-task by a new user message. Decide based on the new message and overall context whether the prior task should continue — do not forget or abandon it unless the user explicitly says to stop, or the new message makes clear it is no longer needed.)"

    /// Every bridge text this app has ever generated. A row persisted by an
    /// OLDER build carries the PREVIOUS wording; matching the current constant
    /// alone would fail and leak that row into the UI (the exact regression seen
    /// after the 2026-07-23 wording change, d2e111e9). Match against the full
    /// set so old and new persisted bridges are both recognized. Prefix-match
    /// (not equality) tolerates trailing-whitespace / normalization drift from
    /// DB round-trips.
    static let internalBridgeTexts: [String] = [
        internalBridgeText,
        // Pre-d2e111e9 wording.
        "(Interrupted mid-task to handle your new message. Will return to the prior task after.)",
    ]

    /// True when `text` is any known internal-bridge string. Shared by
    /// RawMessage and ChatMessage so every layer recognizes the same rows.
    static func isInternalBridgeText(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return internalBridgeTexts.contains { trimmed == $0 }
    }

    var isInternalBridge: Bool {
        guard role == .assistant, parts.count == 1,
              case .text(let s) = parts[0] else { return false }
        return Self.isInternalBridgeText(s)
    }
}
