package io.twostars.sample

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.twostars.sample.databinding.ActivityMainBinding
import io.twostars.sdk.Room
import io.twostars.sdk.RoomEvent
import io.twostars.sdk.StarsClient
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Minimal A1 sample. Type a base URL + participant token + room code,
 * tap **Connect**. Once authenticated and joined, the activity logs
 * peer-joined / peer-left events.
 *
 * The participant token comes from your backend's `POST /api/v1/tokens`
 * call against the 2Stars API.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var room: Room? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.connectBtn.setOnClickListener { connect() }
        b.leaveBtn.setOnClickListener { leave() }
        b.leaveBtn.isEnabled = false
    }

    private fun connect() {
        val baseUrl = b.baseUrlInput.text.toString().trim().ifEmpty {
            "https://video-api.2stars.io"
        }
        val token = b.tokenInput.text.toString().trim()
        val roomCode = b.roomCodeInput.text.toString().trim()
        if (token.isEmpty() || roomCode.isEmpty()) {
            toast("token + roomCode required")
            return
        }

        b.connectBtn.isEnabled = false
        b.statusText.text = "Connecting…"
        b.peersList.text = ""

        lifecycleScope.launch {
            try {
                val client = StarsClient(baseUrl)
                val r = client.connect(token).also { room = it }
                b.statusText.text = "Authenticated as ${r.self.participantId}"

                val join = r.join(roomCode)
                b.statusText.text = "Joined ${join.roomCode} mode=${join.mode}"
                b.leaveBtn.isEnabled = true

                launch {
                    r.peers.collectLatest { peers ->
                        b.peersList.text = peers.values.joinToString("\n") {
                            "• ${it.displayName ?: it.participantId} (${it.participantId})"
                        }
                    }
                }
                launch {
                    r.events.collectLatest { ev ->
                        when (ev) {
                            is RoomEvent.PeerJoined  -> log("+ ${ev.peer.displayName ?: ev.peer.participantId}")
                            is RoomEvent.PeerLeft    -> log("- ${ev.participantId}")
                            is RoomEvent.ModeChanged -> log("mode → ${ev.mode}")
                            is RoomEvent.Disconnected -> log("disconnected: ${ev.reason}")
                        }
                    }
                }
            } catch (t: Throwable) {
                b.statusText.text = "Error: ${t.message}"
                b.connectBtn.isEnabled = true
                b.leaveBtn.isEnabled = false
            }
        }
    }

    private fun leave() {
        val r = room ?: return
        lifecycleScope.launch {
            r.leave()
            room = null
            b.statusText.text = "Left room"
            b.connectBtn.isEnabled = true
            b.leaveBtn.isEnabled = false
        }
    }

    private fun log(line: String) {
        b.eventsLog.append(line)
        b.eventsLog.append("\n")
        b.eventsScroll.post { b.eventsScroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
