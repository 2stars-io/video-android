package io.twostars.sdk

/**
 * Placeholder for the chat message envelope. Wire format and the
 * encrypt / decrypt pipeline land in Stage A4.
 */
public data class Message(
    val senderId: String,
    val ciphertextBase64: String,
    val ivBase64: String,
    val sentAtMs: Long,
)
