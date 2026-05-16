package io.twostars.sdk

/**
 * One recognised utterance broadcast on [Room.transcripts].
 *
 * Server emits one [Transcript] per VAD-endpointed audio chunk after
 * the STT model returns text — including the local participant's own
 * utterances (the server doesn't filter), so consumers can render a
 * unified room-wide transcript without having to splice their own
 * captions in separately.
 */
public data class Transcript(
    /** Speaker's `participantId` (matches [Peer.participantId] or [SelfPresence.participantId]). */
    public val speakerId: String,
    /** Speaker's display name when the server knew it; `null` otherwise. */
    public val speakerDisplayName: String?,
    /** Recognised text. Server may emit empty strings for false-positive utterances. */
    public val text: String,
    /**
     * BCP-47 language hint that was passed to [Room.startTranscription]
     * (or `"auto"` for autodetect). Mostly informational — the server
     * may have detected a different language at recognition time.
     */
    public val language: String,
    /** Server-assigned ms-since-epoch. */
    public val timestampMs: Long,
)
