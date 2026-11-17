package app.pulse.providers.listentogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class DriftHealth {
    INSYNC,
    MICRO_ADJUST,
    HARD_SNAP
}

/**
 * The host's authoritative playhead sample. `positionMs` is the source
 * position at `capturedAtMs` (wall clock of this client when the packet was
 * processed); recipients extrapolate with their own clock.
 */
data class SyncAnchor(
    val trackId: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val capturedAtMs: Long
)

/** Drift status snapshot for UI consumption. */
data class DriftSnapshot(
    val health: DriftHealth = DriftHealth.INSYNC,
    /** Positive: we lead the host; negative: we lag. */
    val deltaMs: Long = 0,
    val activeSpeed: Float = 1f
)

/**
 * High-depth audio drift compensation.
 *
 * Guest loop (250ms cadence on an isolated worker, `DRIFT_POLL_INTERVAL_MS`):
 *  - delta < 50ms    -> comfort window, 1.0x
 *  - 50–750ms        -> tempo warp (1.02x lagging / 0.98x leading), no seek;
 *                       returns to 1.0x once the delta settles under 50ms
 *  - > 750ms         -> hard snap: absolute `seekTo(hostTimestamp)`
 *
 * Host loop (one playhead packet / 8s while playing): continuously publishes
 * the local playhead so guests always have a fresh anchor target.
 */
class DriftEngine(
    private val config: ListenTogetherConfig,
    private val player: IMetrolistSyncPlayer
) {
    private val _drift = MutableStateFlow(DriftSnapshot())
    val drift: StateFlow<DriftSnapshot> = _drift

    @Volatile
    private var anchor: SyncAnchor? = null

    private var guestJob: Job? = null
    private var hostJob: Job? = null

    @Volatile
    private var lastBroadcastAt = 0L

    fun updateAnchor(anchor: SyncAnchor) {
        this.anchor = anchor
    }

    @Volatile
    private var appliedSpeed = 1f

    /** Last health/rate pushed to the UI flow — changes only are worth a copy. */
    @Volatile
    private var publishedHealth: DriftHealth? = null

    @Volatile
    private var publishedRate = 1f

    private fun publish(health: DriftHealth, deltaMs: Long, rate: Float) {
        if (health == publishedHealth && rate == publishedRate) return
        publishedHealth = health
        publishedRate = rate
        _drift.value = DriftSnapshot(health, deltaMs, rate)
    }

    /** Writes only when the rate actually changes — keeps the player calm. */
    private fun applySpeed(rate: Float) {
        if (appliedSpeed == rate) return
        appliedSpeed = rate
        player.setSpeed(rate)
    }

    /** Expected host playhead right now, extrapolated with our local clock. */
    fun expectedPositionMs(nowMs: Long = System.currentTimeMillis()): Long {
        val a = anchor ?: return 0L
        if (!a.isPlaying) return a.positionMs
        val elapsed = nowMs - a.capturedAtMs
        val driftAhead = elapsed.coerceAtLeast(0L)
        return a.positionMs + driftAhead
    }

    /** Starts the guest polling loop. */
    fun startGuestLoop(scope: CoroutineScope) {
        stopGuestLoop()
        guestJob = scope.launch {
            while (isActive) {
                delay(config.DRIFT_POLL_INTERVAL_MS)
                val a = anchor ?: continue
                val trackId = player.currentTrackId()
                if (trackId != null && trackId != a.trackId) {
                    // Different medium: wait for the change_track flow.
                    continue
                }

                val expected = expectedPositionMs()
                val actual = player.positionMs()
                val delta = actual - expected
                val absDelta = when {
                    delta >= 0 -> delta
                    else -> -delta
                }

                when {
                    absDelta < config.RESYNC_SETTLE_MS -> {
                        // Fully settled: restore normal speed.
                        applySpeed(1f)
                        publish(DriftHealth.INSYNC, delta, 1f)
                    }

                    absDelta < config.HARD_SNAP_THRESHOLD_MS -> {
                        // Micro-adjust band: apply consistent warp in the needed direction.
                        // Do NOT reset to 1f here — that caused oscillation. Keep the
                        // correction until we drop back into the settle zone.
                        val rate = if (delta < 0)
                            1f + config.COMPENSATION_SPEED_MULTIPLIER
                        else
                            1f - config.COMPENSATION_SPEED_MULTIPLIER
                        applySpeed(rate)
                        publish(DriftHealth.MICRO_ADJUST, delta, rate)
                    }

                    else -> {
                        // Destructive seek — last resort only.
                        applySpeed(1f)
                        player.seekTo(expected.coerceAtLeast(0L))
                        publish(DriftHealth.HARD_SNAP, delta, 1f)
                    }
                }
            }
        }
    }

    fun stopGuestLoop() {
        guestJob?.cancel()
        guestJob = null
    }

    /**
     * Starts the host playhead publisher. The callback must forward a
     * playback_action; throttling keeps the socket flat at one packet/s.
     */
    fun startHostLoop(
        scope: CoroutineScope,
        broadcast: (positionMs: Long, isPlaying: Boolean, trackId: String?) -> Unit
    ) {
        stopHostLoop()
        // Metrolist parity: an 8s heartbeat, delivered only while a track is
        // actually playing and ready — silent otherwise.
        val interval = config.HOST_BROADCAST_INTERVAL_MS.coerceAtLeast(8_000L)
        hostJob = scope.launch {
            while (isActive) {
                delay(interval)
                val trackId = player.currentTrackId()
                if (trackId != null && player.isPlaying() && player.isReady()) {
                    val now = System.currentTimeMillis()
                    if (now - lastBroadcastAt >= interval) {
                        broadcast(player.positionMs(), true, trackId)
                        lastBroadcastAt = now
                    }
                }
            }
        }
    }

    fun stopHostLoop() {
        hostJob?.cancel()
        hostJob = null
    }

    fun reset() {
        anchor = null
        publishedHealth = null
        publishedRate = 1f
        _drift.value = DriftSnapshot()
        lastBroadcastAt = 0L
        applySpeed(1f)
    }
}
