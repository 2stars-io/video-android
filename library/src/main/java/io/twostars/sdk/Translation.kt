package io.twostars.sdk

/**
 * One translated utterance, delivered on [Room.translations].
 *
 * The server fans these out per-listener: only sockets that called
 * [Room.setTranslationLanguage] with a target language *different
 * from the speaker's source language* get a [Translation] for that
 * utterance. So a Hebrew listener doesn't receive a Hebrew→Hebrew
 * "translation" of a Hebrew-speaking peer, and the speaker themselves
 * never sees their own utterance translated back.
 *
 * Pair with [Transcript] when rendering: each transcription event for
 * the original language can be augmented (or replaced, depending on
 * UX) by the matching translation event for the listener's tongue.
 * Match on [originalText] + [speakerId] + [timestampMs] to correlate.
 */
public data class Translation(
    public val speakerId: String,
    public val speakerDisplayName: String?,
    public val originalText: String,
    public val originalLanguage: String,
    public val translatedText: String,
    /** BCP-47 the listener requested via [Room.setTranslationLanguage]. */
    public val targetLanguage: String,
    public val timestampMs: Long,
)

/**
 * Voice-translated audio chunk, delivered on [Room.translatedAudio].
 *
 * Same listener-side opt-in as [Translation] (via
 * [Room.setTranslationLanguage]) but additionally requires the room's
 * `voice-translation` feature flag — if the operator only enabled
 * `translation`, you'll see [Translation] events but no
 * [TranslatedAudio]. Server runs the translated text through the
 * room's TTS plugin and ships the resulting audio bytes inline.
 *
 * Playback is intentionally not handled by the SDK — host apps want
 * to mix voice translations into their own audio routing (separate
 * volume, spatial audio, ducking against the original speaker, …).
 * Decode [audio] to a raw byte stream via `Base64.decode(audio, Base64.DEFAULT)`,
 * then either feed it to `MediaPlayer` from a temp file or to
 * `AudioTrack` if you want sample-level control.
 */
public data class TranslatedAudio(
    /** Base64-encoded audio bytes — typical mime is `audio/mpeg` or `audio/wav`. */
    public val audio: String,
    public val mimeType: String,
    public val originalText: String,
    public val translatedText: String,
    public val originalLanguage: String,
    public val targetLanguage: String,
)
