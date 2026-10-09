package app.olauncher.helper

import org.json.JSONArray
import org.json.JSONObject

/** Full backup file: every launcher setting (home apps, renames, hidden apps, …) plus the note, as JSON. */
object Backup {
    private const val FORMAT = "scratchpad-launcher-backup"

    class Data(val settings: Map<String, Any>, val scratchpad: String)

    // SharedPreferences values are typed, JSON numbers aren't, so each entry is stored as [type, value].
    fun encode(settings: Map<String, *>, scratchpad: String): String {
        val entries = JSONObject()
        for ((key, value) in settings) {
            val typed = when (value) {
                is Boolean -> JSONArray().put("b").put(value)
                is Int -> JSONArray().put("i").put(value)
                is Long -> JSONArray().put("l").put(value)
                is Float -> JSONArray().put("f").put(value.toDouble())
                is String -> JSONArray().put("s").put(value)
                is Set<*> -> JSONArray().put("set").put(JSONArray(value))
                else -> continue
            }
            entries.put(key, typed)
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("scratchpad", scratchpad)
            .put("settings", entries)
            .toString(2)
    }

    /** Null when [text] isn't a full backup (e.g. a plain scratchpad export); throws if it is one but corrupt. */
    fun decode(text: String): Data? {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString("format") != FORMAT) return null
        val entries = root.getJSONObject("settings")
        val settings = entries.keys().asSequence().associateWith { key ->
            val entry = entries.getJSONArray(key)
            when (val type = entry.getString(0)) {
                "b" -> entry.getBoolean(1)
                "i" -> entry.getInt(1)
                "l" -> entry.getLong(1)
                "f" -> entry.getDouble(1).toFloat()
                "s" -> entry.getString(1)
                "set" -> entry.getJSONArray(1).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> error("Unknown setting type $type")
            }
        }
        return Data(settings, root.getString("scratchpad"))
    }
}
