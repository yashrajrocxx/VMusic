package app.pulse.android.listentogether

import app.pulse.android.GlobalPreferencesHolder

/**
 * Persisted "Together" session: the stable display name and the reconnect
 * token that lets a re-handshake re-attach to a room silently after a process
 * death or radio transition.
 */
object ListenTogetherPreferences : GlobalPreferencesHolder() {
    private val RANDOM_NAMES = listOf(
        "Arjuna", "Krishna", "Bhima", "Yudhishthira", "Nakula", "Sahadeva",
        "Karna", "Duryodhana", "Dushasana", "Bhishma", "Drona", "Kripa",
        "Ashwatthama", "Abhimanyu", "Ghatotkacha", "Satyaki", "Drupada",
        "Virata", "Shalya", "Jayadratha", "Shakuni", "Gandhari", "Kunti",
        "Draupadi", "Subhadra", "Uttara", "Sutasoma", "Shrutakarma",
        "Satanika", "Shrutasena", "Vrika", "Babhruvahana"
    )

    var userName by string("")

    var sessionToken by string("")

    init {
        // Freeze the first real name so a process restart does not present
        // the user under a new one. Default is empty (never a visible name);
        // only one random draw ever happens, inside this init.
        if (!contains("userName") || userName.isBlank()) {
            userName = RANDOM_NAMES.random()
        }
    }
}
