package io.twostars.sdk

/**
 * Lifecycle events emitted on [Room.events]. Use the [Room.peers] /
 * [Room.mode] state flows for current state; use this stream when the
 * *transition* itself matters (e.g. "play a sound on peer-joined").
 */
public sealed class RoomEvent {
    public data class PeerJoined(val peer: Peer) : RoomEvent()
    public data class PeerLeft(val participantId: String) : RoomEvent()
    public data class ModeChanged(val mode: Room.Mode) : RoomEvent()
    public data class Disconnected(val reason: String?) : RoomEvent()
}
