package io.twostars.sdk

/**
 * A decrypted chat message in the room.
 *
 * Created by [Room.sendMessage] (for outbound) and emitted on
 * [Room.messages] for inbound. The room's E2E key is used client-
 * side to AES-GCM-decrypt — the server only ever holds the
 * ciphertext + IV.
 *
 * @property id Server-assigned UUID; durable across reconnects.
 * @property senderId The sending participant's `participantId`.
 * @property senderDisplayName The sender's display name at send-time
 *   (may be null if the sender never set one). Cached server-side
 *   under `messages.sender_display_name` so message history shows the
 *   right name even after the sender has left.
 * @property text Decrypted plaintext.
 * @property timestampMs Server-assigned wall-clock timestamp, ms
 *   since epoch.
 * @property encryptedB64 Base64URL ciphertext as it sat on the wire.
 * @property ivB64 Base64URL 12-byte AES-GCM IV.
 */
public data class Message(
    val id: String,
    val senderId: String,
    val senderDisplayName: String?,
    val text: String,
    val timestampMs: Long,
    val encryptedB64: String,
    val ivB64: String,
)
