package io.twostars.sdk

/**
 * Structured response from a one-shot [Room.askAssistant] command.
 *
 * The server's LLM classifies the request and replies with a [type]
 * that hints at how the UI should render the result:
 *
 *  - `"chat"` — free-form conversational answer; render in a chat tile.
 *  - `"document"` — a generated document (PDF / structured text);
 *    [metadata] usually carries a download URL.
 *  - `"action"` — the assistant performed a side-effect (e.g. attached
 *    a doc to the room, sent a webhook); [metadata] describes what.
 */
public data class AssistantResponse(
    public val type: String,
    public val content: String,
    /** Free-form per-type metadata. JSON object serialised to a string. */
    public val metadata: String?,
)

/**
 * One assistant chat message. Fired as a `hebbs-message` event after
 * [Room.askAssistantInChat] (or any inbound `@Hebbs ...` command from
 * any participant — server broadcasts the response either to the
 * room or only to the requester depending on the `private` flag).
 */
public data class AssistantMessage(
    public val id: String,
    public val senderId: String,
    public val senderDisplayName: String?,
    public val text: String,
    /** Same `chat`/`document`/`action` classifier as [AssistantResponse]. */
    public val type: String,
    public val metadata: String?,
    /** participantId of the human who triggered this Hebbs reply. */
    public val replyToParticipantId: String?,
    /** True if the server only sent it to the requester. */
    public val isPrivate: Boolean,
    public val timestampMs: Long,
)

/**
 * Live AI participant info — when the room's operator has called
 * [Room.setAssistantEnabled]`(true)`, the server spawns a virtual
 * participant. Its identity (id / display name / avatar) is reported
 * via [Room.assistantParticipant], and its voice utterances arrive
 * on [Room.assistantVoice].
 */
public data class AssistantParticipant(
    public val participantId: String,
    public val displayName: String?,
    /** Optional URL the host app can render as the assistant's tile. */
    public val avatarUrl: String?,
)

/**
 * One voice clip from the live AI participant — the server's TTS
 * pass over what the assistant just said. Same playback story as
 * [TranslatedAudio]: SDK gives you the raw bytes, host decides how
 * to mix into the call audio.
 */
public data class AssistantVoice(
    public val audio: String,
    public val mimeType: String,
    public val text: String?,
)
