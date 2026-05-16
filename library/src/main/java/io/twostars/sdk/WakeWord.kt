package io.twostars.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Result of a successful wake-word match. [command] is the text that
 * came after the wake word — what the user actually wants the
 * assistant to do. [matchedToken] is the literal token the matcher
 * recognised (`"hebbs"`, `"hep"`, `"hubs"`, …) — useful for analytics.
 * [prefix] is the politeness word that came before, if any (`"hey"`,
 * `"ok"`, `"hi"`, `"yo"`).
 */
public data class WakeMatch(
    public val command: String,
    public val matchedToken: String,
    public val prefix: String?,
)

/**
 * Client-side wake-word matchers. Mirror of the JS SDK's
 * `WakeWordMatcher.js` so transcript text decoded by either client
 * gets matched the same way.
 *
 * Use [WakeWord] for the listening-loop wrapper; use these matchers
 * directly when you want custom routing (e.g. analytics on near-misses
 * before deciding whether to invoke the assistant).
 */
public object WakeWordMatcher {
    private const val HEBBS_TARGET = "hebbs"
    private val PREFIX_WORDS = setOf("hey", "ok", "okay", "hi", "yo")

    /** Tiny Levenshtein. Words are short (<10 chars), so straight-up DP is fine. */
    public fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val n = b.length
        val prev = IntArray(n + 1) { it }
        val curr = IntArray(n + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
            }
            for (j in 0..n) prev[j] = curr[j]
        }
        return prev[n]
    }

    /**
     * True if [token] is plausibly a mishearing of "hebbs" — h-prefix,
     * a labial consonant (b/p/v/f), edit distance ≤ 2, with a
     * special case for 3-letter near-misses ("hep", "hef", "hub").
     */
    public fun isHebbsLike(token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        val t = token.lowercase()
        if (t == HEBBS_TARGET) return true
        if (t.length < 3 || t.length > 7) return false
        if (t[0] != 'h') return false
        if (!t.any { it in "bpvf" }) return false
        if (t.length == 3 && Regex("^h[aeiouy][bpvf]$").matches(t)) return true
        return editDistance(t, HEBBS_TARGET) <= 2
    }

    /**
     * Find the first wake-word-like token in [text] that has a
     * non-empty command after it. Returns null otherwise (use
     * [isBareWakeUtterance] to detect the "wake word with no command"
     * case).
     */
    public fun findWakeCommand(text: String?): WakeMatch? {
        if (text.isNullOrEmpty()) return null
        val re = Regex("[a-zA-Z'’]+")
        val tokens = re.findAll(text).map { it.value to it.range }.toList()
        for ((i, pair) in tokens.withIndex()) {
            val (word, range) = pair
            if (!isHebbsLike(word)) continue
            var cmdStart = range.last + 1
            // Allow an "AI" filler between the wake word and the command.
            if (i + 1 < tokens.size && tokens[i + 1].first.equals("ai", ignoreCase = true)) {
                cmdStart = tokens[i + 1].second.last + 1
            }
            while (cmdStart < text.length && text[cmdStart] in " ,.:!?-\t\n") cmdStart += 1
            val command = text.substring(cmdStart).trim()
            if (command.isEmpty()) continue
            val prefix = if (i > 0 && PREFIX_WORDS.contains(tokens[i - 1].first.lowercase()))
                tokens[i - 1].first.lowercase() else null
            return WakeMatch(command = command, matchedToken = word, prefix = prefix)
        }
        return null
    }

    /**
     * True if the text is essentially "just the wake word" with no
     * command — e.g. user said "Hey Hebbs." and is waiting for the
     * assistant to acknowledge. Useful for arming a follow-up listener.
     */
    public fun isBareWakeUtterance(text: String?): Boolean {
        val trimmed = (text ?: "").trim()
        if (trimmed.isEmpty()) return false
        val toks = Regex("[a-z'’]+").findAll(trimmed.lowercase())
            .map { it.value }.toList()
        if (toks.isEmpty() || toks.size > 3) return false
        if (!isHebbsLike(toks.last())) return false
        for (i in 0 until toks.size - 1) {
            if (!PREFIX_WORDS.contains(toks[i])) return false
        }
        return true
    }
}

/**
 * Wake-word listener — wires the matcher into a [Room.transcripts]
 * subscription so [onWake] fires whenever a participant's transcript
 * contains a wake phrase + command.
 *
 * Two modes:
 *  - **AUTO** (default): subscribes to the room's transcript stream
 *    and matches each incoming line client-side. Cheap, depends on
 *    transcription being enabled.
 *  - **MANUAL**: no transcript subscription. Caller drives [trigger]
 *    themselves — typically wired to a push-to-talk button.
 *
 * The SDK doesn't ship a streaming acoustic model (Porcupine /
 * openWakeWord); auto mode is text-driven off the same STT pipeline
 * the rest of the room shares. If you need a true streaming detector
 * later, layer it on top of [AudioRecordCapture] and call [trigger]
 * from your own callback.
 *
 * Usage:
 * ```kotlin
 * val ww = WakeWord(room, mode = WakeWord.Mode.AUTO) { match ->
 *     // match.command is what the user wants the assistant to do
 *     scope.launch { room.askAssistant(match.command) }
 * }
 * ww.start()
 * // …
 * ww.stop()
 * ```
 */
public class WakeWord
@JvmOverloads
constructor(
    private val transcripts: SharedFlow<Transcript>,
    private val mode: Mode = Mode.AUTO,
    private val onWake: (WakeMatch) -> Unit,
) {

    public enum class Mode { AUTO, MANUAL }

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var running: Boolean = false

    /** True between [start] and [stop]. */
    public val isListening: Boolean get() = running

    public fun start() {
        if (running) return
        running = true
        if (mode == Mode.AUTO) {
            job = scope.launch {
                transcripts.collect { t ->
                    val match = WakeWordMatcher.findWakeCommand(t.text) ?: return@collect
                    try { onWake(match) } catch (_: Throwable) { /* drop */ }
                }
            }
        }
    }

    public fun stop() {
        running = false
        job?.cancel(); job = null
    }

    /**
     * Manually fire the wake callback with [command] as the recognised
     * command text. Use this from a push-to-talk button when you want
     * to bypass the wake-word match entirely.
     */
    public fun trigger(command: String) {
        if (!running) return
        try { onWake(WakeMatch(command = command, matchedToken = "manual", prefix = null)) }
        catch (_: Throwable) { /* drop */ }
    }

    /** Release coroutines. Call this when the host activity tears down. */
    public fun release() {
        stop()
        scope.cancel()
    }
}
