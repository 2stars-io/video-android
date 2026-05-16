package io.twostars.sdk

/**
 * Result of a [Room.requestCorrection] call.
 *
 * Server runs the original [original] text through the room's LLM
 * with a tone-and-grammar pass and returns [suggestion] — a polished
 * version of the same intent. [changes] is a structured list the LLM
 * returns describing what it altered (free-form strings — depends on
 * the underlying provider).
 *
 * If the server skipped the request (rate-limit / empty / too-long /
 * feature disabled), [requestCorrection] returns `null` instead of a
 * [Correction]; the skip reason is in the ack response.
 */
public data class Correction(
    public val original: String,
    public val suggestion: String,
    public val changes: List<String>,
    public val tone: String?,
    public val language: String?,
    public val requestedAtMs: Long,
)
