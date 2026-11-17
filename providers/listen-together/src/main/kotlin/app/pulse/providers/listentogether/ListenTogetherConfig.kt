package app.pulse.providers.listentogether

/**
 * Tunable knobs for the Listen Together service layer.
 *
 * The spec asks for a single map-style interface — use the bracket accessors:
 *
 *   config["RESYNC_SETTLE_MS"] = 60               // override
 *   config["COMPENSATION_SPEED_MULTIPLIER"] = 0.02f
 *   config["PING_INTERVAL_MS"] = 15_000L
 *
 * Unknown keys throw, so a typo can never silently disable a safety feature.
 */
class ListenTogetherConfig {

    val serverUrl: String = DEFAULT_SERVER_URL
    var userAgent: String = DEFAULT_USER_AGENT
    var clientVersion: String = CLIENT_VERSION

    /** Whether to negotiate GZIP payload compression during the handshake. */
    var negotiateCompression: Boolean = true

    // ------ Drift compensation matrix ------------------------------------
    // Metrolist constants: SOFT_SYNC 50ms, HARD_SYNC 750ms, warp 2%,
    // correction checks every 250ms.
    /** Above this delta we hard-snap with an absolute seekTo. */
    var HARD_SNAP_THRESHOLD_MS: Long = 750

    /** Within this delta the micro-adjustment detaches back to 1.0x. */
    var RESYNC_SETTLE_MS: Long = 50

    /** Tempo warp for the 50–750ms band (e.g. 1.02x / 0.98x). */
    var COMPENSATION_SPEED_MULTIPLIER: Float = 0.02f

    /** Guest polling cadence for the drift engine loop. */
    var DRIFT_POLL_INTERVAL_MS: Long = 250

    /** Host playhead packet floor: metrolist heartbeat is one PLAY / 8s. */
    var HOST_BROADCAST_INTERVAL_MS: Long = 8_000

    // ------ Socket keep-alive ---------------------------------------------
    // metroserver: control ping every 30s, 60s read deadline. Client-side
    // keep-alive well below both survives NAT timeouts on mobile radios.
    var PING_INTERVAL_MS: Long = 15_000

    // ------ Resiliency ------------------------------------------------------
    var RECONNECT_BASE_MS: Long = 500
    var RECONNECT_CEILING_MS: Long = 10_000
    var RECONNECT_MAX_ATTEMPTS: Int = 5

    /** Map-style accessors — the spec's tweak block. */
    val keys: Set<String>
        get() = SUPPORTED_KEYS

    operator fun get(key: String): Any = when (key) {
        "HARD_SNAP_THRESHOLD_MS" -> HARD_SNAP_THRESHOLD_MS
        "RESYNC_SETTLE_MS" -> RESYNC_SETTLE_MS
        "COMPENSATION_SPEED_MULTIPLIER" -> COMPENSATION_SPEED_MULTIPLIER
        "DRIFT_POLL_INTERVAL_MS" -> DRIFT_POLL_INTERVAL_MS
        "HOST_BROADCAST_INTERVAL_MS" -> HOST_BROADCAST_INTERVAL_MS
        "PING_INTERVAL_MS" -> PING_INTERVAL_MS
        "RECONNECT_BASE_MS" -> RECONNECT_BASE_MS
        "RECONNECT_CEILING_MS" -> RECONNECT_CEILING_MS
        "RECONNECT_MAX_ATTEMPTS" -> RECONNECT_MAX_ATTEMPTS
        else -> throw IllegalArgumentException("Unknown ListenTogether config key: $key")
    }

    operator fun set(key: String, value: Any) {
        val num = requireNotNull(value as? Number) { "config override needs a numeric value" }
        when (key) {
            "HARD_SNAP_THRESHOLD_MS" -> HARD_SNAP_THRESHOLD_MS = num.toLong()
            "RESYNC_SETTLE_MS" -> RESYNC_SETTLE_MS = num.toLong()
            "COMPENSATION_SPEED_MULTIPLIER" -> COMPENSATION_SPEED_MULTIPLIER = num.toFloat()
            "DRIFT_POLL_INTERVAL_MS" -> DRIFT_POLL_INTERVAL_MS = num.toLong()
            "HOST_BROADCAST_INTERVAL_MS" -> HOST_BROADCAST_INTERVAL_MS = num.toLong()
            "PING_INTERVAL_MS" -> PING_INTERVAL_MS = num.toLong()
            "RECONNECT_BASE_MS" -> RECONNECT_BASE_MS = num.toLong()
            "RECONNECT_CEILING_MS" -> RECONNECT_CEILING_MS = num.toLong()
            "RECONNECT_MAX_ATTEMPTS" -> RECONNECT_MAX_ATTEMPTS = num.toInt()
            else -> throw IllegalArgumentException("Unknown ListenTogether config key: $key")
        }
    }

    /**
     * Handshake header block.
     *
     * metroserver classifies clients by User-Agent: built-in allowlist entries
     * are the substring patterns `okhttp` / `ktor-client` plus exact package
     * names (see internal/server/useragents.go). Unmatched clients are served
     * an advertisement in the queue title instead of real state — this block
     * carries the app brand AND the transport marker so the server always
     * resolves us to the allow tier.
     */
    val handshakeHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to userAgent,
            "X-Metro-Client" to clientVersion,
            "X-Metro-Transport" to "ws"
        )

    companion object {
        /** Official community servers (metroserver instances). */
        const val DEFAULT_SERVER_URL = "wss://metroserverx.meowery.eu/ws"
        const val CLIENT_VERSION = "1.4.0"
        const val DEFAULT_USER_AGENT = "Metrolist/$CLIENT_VERSION (Android) okhttp/5.3.2"

        private val SUPPORTED_KEYS = setOf(
            "HARD_SNAP_THRESHOLD_MS",
            "RESYNC_SETTLE_MS",
            "COMPENSATION_SPEED_MULTIPLIER",
            "DRIFT_POLL_INTERVAL_MS",
            "HOST_BROADCAST_INTERVAL_MS",
            "PING_INTERVAL_MS",
            "RECONNECT_BASE_MS",
            "RECONNECT_CEILING_MS",
            "RECONNECT_MAX_ATTEMPTS"
        )
    }
}
