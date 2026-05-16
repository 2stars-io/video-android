package io.twostars.sdk

/**
 * One fact-check finding emitted on [Room.factChecks].
 *
 * Server runs fact-check automatically for every transcript when:
 *   - the room has the `fact-check` feature enabled, AND
 *   - the room has at least one supporting document attached (the LLM
 *     compares the speaker's claim against the doc corpus).
 *
 * Events are fired **privately to the speaker only** — corrections
 * intentionally don't interrupt the room. The speaker's UI typically
 * shows them as a discreet notification (e.g. a small overlay on the
 * speaker's tile) so they can self-correct.
 */
public data class FactCheck(
    /** The exact phrase from the speaker's transcript that triggered the check. */
    public val claim: String,
    /** Speaker's `participantId` — always equal to your own id (server only sends to speaker). */
    public val speakerId: String,
    /** What the room documents actually say. */
    public val correction: String,
    /** Where the correction came from (defaults to `"room-documents"`). */
    public val source: String,
    /** Model's confidence in the correction (0.0..1.0). */
    public val confidence: Double,
    /** The full transcript line the claim was extracted from. */
    public val originalTranscript: String,
    /** Server timestamp ms-since-epoch. */
    public val timestampMs: Long,
)
