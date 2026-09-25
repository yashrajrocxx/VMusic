package app.pulse.android.backup

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.pulse.android.backup.PreferencesProto.PrefValue
import app.pulse.android.preferences.AccountPreferences
import app.pulse.android.preferences.AppearancePreferences
import app.pulse.android.preferences.DataPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges our SharedPreferences (`preferences.xml`) and MetroList's
 * `settings.preferences_pb`, plus a full-fidelity JSON snapshot used for
 * complete restores of our own backups.
 *
 * Only keys with verified-identical semantics travel through the proto map
 * (authentication, identity and a small set of behavior flags). Everything else
 * round-trips losslessly through `pulse_settings.json`, which MetroList skips.
 */
object SettingsSnapshot {
    const val PREFS_NAME = "preferences"

    /** MetroList proto key to our SharedPreferences key. */
    private val protoToOurs = mapOf(
        "innerTubeCookie" to "innerTubeCookie",
        "visitorData" to "visitorData",
        "dataSyncId" to "dataSyncId",
        "innerTubeAuthUser" to "authUser",
        "accountName" to "accountName",
        "accountEmail" to "accountEmail",
        "accountChannelHandle" to "accountChannelHandle",
        "useLoginForBrowse" to "useLoginForBrowse",
        "hideExplicit" to "hideExplicit",
        "pauseListenHistory" to "pauseHistory",
        "pauseSearchHistory" to "pauseSearchHistory"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Values we publish to MetroList-shaped backups. */
    fun exportProtoEntries(): Map<String, PrefValue> = mapOf(
        "innerTubeCookie" to PrefValue.StringV(AccountPreferences.innerTubeCookie),
        "visitorData" to PrefValue.StringV(AccountPreferences.visitorData),
        "dataSyncId" to PrefValue.StringV(AccountPreferences.dataSyncId),
        "innerTubeAuthUser" to PrefValue.StringV(AccountPreferences.authUser),
        "accountName" to PrefValue.StringV(AccountPreferences.accountName),
        "accountEmail" to PrefValue.StringV(AccountPreferences.accountEmail),
        "accountChannelHandle" to PrefValue.StringV(AccountPreferences.accountChannelHandle),
        "useLoginForBrowse" to PrefValue.BooleanV(AccountPreferences.useLoginForBrowse),
        "hideExplicit" to PrefValue.BooleanV(AppearancePreferences.hideExplicit),
        "pauseListenHistory" to PrefValue.BooleanV(DataPreferences.pauseHistory),
        "pauseSearchHistory" to PrefValue.BooleanV(DataPreferences.pauseSearchHistory)
    )

    fun hasAuthData(entries: Map<String, PrefValue>): Boolean {
        val cookie = (entries["innerTubeCookie"] as? PrefValue.StringV)?.value.orEmpty()
        return "SAPISID" in cookie
    }

    fun accountName(entries: Map<String, PrefValue>): String? =
        (entries["accountName"] as? PrefValue.StringV)?.value?.takeIf { it.isNotBlank() }

    fun accountEmail(entries: Map<String, PrefValue>): String? =
        (entries["accountEmail"] as? PrefValue.StringV)?.value?.takeIf { it.isNotBlank() }

    /** Applies the understood subset of a MetroList settings blob. Unknown keys are ignored. */
    fun applyProtoEntries(context: Context, entries: Map<String, PrefValue>) {
        prefs(context).edit(commit = true) {
            for ((protoKey, ourKey) in protoToOurs) {
                val value = entries[protoKey] ?: continue
                when (ourKey) {
                    "useLoginForBrowse", "hideExplicit", "pauseHistory", "pauseSearchHistory" ->
                        coerceBoolean(value)?.let { putBoolean(ourKey, it) }

                    else -> coerceString(value)?.let { putString(ourKey, it) }
                }
            }
        }
    }

    /** Complete dump of our settings for `pulse_settings.json`. */
    fun dumpJson(context: Context): ByteArray {
        val array = JSONArray()
        for ((key, value) in prefs(context).all) {
            val entry = JSONObject().put("k", key)
            when (value) {
                is Boolean -> entry.put("t", "b").put("v", value)
                is Float -> entry.put("t", "f").put("v", value.toDouble())
                is Int -> entry.put("t", "i").put("v", value)
                is Long -> entry.put("t", "l").put("v", value)
                is String -> entry.put("t", "s").put("v", value)
                is Set<*> -> entry.put("t", "ss")
                    .put("v", JSONArray(value.filterIsInstance<String>()))

                else -> continue
            }
            array.put(entry)
        }
        return JSONObject().put("v", 1).put("prefs", array).toString().toByteArray(Charsets.UTF_8)
    }

    /** Complete restore of our settings. Returns false when the blob is not ours. */
    fun applyJson(context: Context, bytes: ByteArray): Boolean {
        return runCatching {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            if (root.optInt("v", -1) != 1) return false
            val array = root.getJSONArray("prefs")
            prefs(context).edit(commit = true) {
                clear()
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    val key = entry.getString("k")
                    when (entry.getString("t")) {
                        "b" -> putBoolean(key, entry.getBoolean("v"))
                        "f" -> putFloat(key, entry.getDouble("v").toFloat())
                        "i" -> putInt(key, entry.getInt("v"))
                        "l" -> putLong(key, entry.getLong("v"))
                        "s" -> putString(key, entry.optString("v", ""))
                        "ss" -> {
                            val items = entry.getJSONArray("v")
                            putStringSet(key, buildSet {
                                for (j in 0 until items.length()) add(items.getString(j))
                            })
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun coerceBoolean(value: PrefValue): Boolean? = when (value) {
        is PrefValue.BooleanV -> value.value
        is PrefValue.IntV -> value.value != 0
        is PrefValue.LongV -> value.value != 0L
        is PrefValue.StringV -> when (value.value.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }

        else -> null
    }

    private fun coerceString(value: PrefValue): String? = when (value) {
        is PrefValue.StringV -> value.value
        is PrefValue.IntV -> value.value.toString()
        is PrefValue.LongV -> value.value.toString()
        is PrefValue.FloatV -> value.value.toString()
        is PrefValue.BooleanV -> value.value.toString()
        else -> null
    }
}
