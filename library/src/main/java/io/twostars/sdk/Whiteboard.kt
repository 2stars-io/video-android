package io.twostars.sdk

import kotlinx.serialization.json.JsonArray

/**
 * Server-broadcast whiteboard regeneration. Fired when *anyone* in
 * the room calls [Room.generateWhiteboard] — the LLM produces a fresh
 * SVG which the server fans out to every participant. Render the
 * [svg] string into a `WebView` (or any SVG renderer) to update the
 * whiteboard tile.
 *
 * [version] increments monotonically per room, so consumers can
 * reject out-of-order broadcasts (e.g. a slow network re-delivering
 * an old frame).
 */
public data class WhiteboardUpdate(
    public val svg: String,
    public val version: Int,
    public val prompt: String,
    public val author: String?,
    public val authorDisplayName: String?,
    public val generatedAtMs: Long,
)

/**
 * One human-drawn freehand annotation overlay on top of the AI's
 * SVG. Server broadcasts these via `whiteboard-annotations` whenever
 * any participant calls [Room.annotateWhiteboard]. The receiver
 * appends to the in-memory annotation list and re-renders.
 *
 * [strokes] is passed through verbatim — the wire shape is
 * intentionally free-form so callers can layer their own typed
 * stroke model (vertex points, pressure, colour, …) without the
 * SDK locking it down. JSON-array of whatever shape the room's
 * other clients agree on.
 */
public data class WhiteboardAnnotation(
    public val id: String,
    public val authorId: String,
    public val authorDisplayName: String?,
    public val strokes: JsonArray,
    public val addedAtMs: Long,
)

/**
 * Full whiteboard state, returned by [Room.fetchWhiteboard]. Useful
 * for late joiners who need to catch up on the current canvas before
 * subscribing to [Room.whiteboardUpdates] / [Room.whiteboardAnnotations]
 * for live deltas.
 */
public data class WhiteboardSnapshot(
    public val svg: String?,
    public val version: Int,
    public val prompt: String?,
    public val generatedAtMs: Long?,
    public val annotations: List<WhiteboardAnnotation>,
)
