package io.twostars.sdk

import io.twostars.sdk.internal.SocketTransport
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Top-level entry point for the 2Stars Android SDK.
 *
 * Construct one with the API base URL of your 2Stars deployment, then
 * call [connect] with a participant token to obtain a [Room] handle.
 *
 * ```kotlin
 * val client = StarsClient(baseUrl = "https://video-api.2stars.io")
 * val room = client.connect(participantJwt)
 * room.join("abc-defg-hij")
 * ```
 *
 * Participant tokens are minted by your backend via
 * `POST /api/v1/tokens` against the 2Stars API. **Never** embed your
 * 2Stars API key in the mobile app.
 */
public class StarsClient(
    private val baseUrl: String,
) {

    /**
     * Open a Socket.IO connection to the 2Stars API and authenticate
     * with [participantToken]. Suspends until the server confirms auth
     * (`authenticated` event) or returns an [AuthException] on failure.
     *
     * Cancelling the calling coroutine cancels the connection.
     */
    public suspend fun connect(participantToken: String): Room =
        suspendCancellableCoroutine { cont ->
            val transport = SocketTransport(baseUrl, participantToken)

            transport.onAuthenticated { presence ->
                if (cont.isActive) cont.resume(Room(transport, presence))
            }
            transport.onAuthError { msg ->
                if (cont.isActive) cont.resumeWithException(AuthException(msg))
            }
            transport.onConnectError { msg ->
                if (cont.isActive) cont.resumeWithException(ConnectException(msg))
            }

            cont.invokeOnCancellation { transport.close() }
            transport.connect()
        }
}

/** Server rejected the participant token. */
public class AuthException(message: String) : Exception(message)

/** Network / transport-level failure before auth could complete. */
public class ConnectException(message: String) : Exception(message)
