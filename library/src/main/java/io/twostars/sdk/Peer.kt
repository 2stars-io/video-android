package io.twostars.sdk

/**
 * A remote participant in the room.
 *
 * In Stage A1 a Peer is presence-only — it tells you who's in the room,
 * but no media tracks are carried yet. A2 adds video/audio track refs
 * (`videoTrack`, `audioTrack`); A5 adds a `screenTrack`.
 */
public data class Peer(
    val participantId: String,
    val displayName: String?,
    /** When this peer's join was first observed, in ms since epoch. */
    val joinedAtMs: Long,
)
