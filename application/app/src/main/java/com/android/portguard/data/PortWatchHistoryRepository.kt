package com.android.portguard.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class PortWatchEvent(
    val occurredAtMillis: Long,
    val portKey: String,
)

/**
 * Device-local bounded log. Records observed watch transitions, never traffic content.
 * No network upload, no long-running background service.
 */
class PortWatchHistoryRepository(context: Context) {
    private val preferences = context.getSharedPreferences("portguard_shizuku_diagnostics", Context.MODE_PRIVATE)

    fun read(): List<PortWatchEvent> {
        val raw = preferences.getString(EVENTS_KEY, null) ?: return emptyList()
        return runCatching {
            val rows = JSONArray(raw)
            (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val key = row.optString("port", "")
                val time = row.optLong("time", 0L)
                if (time <= 0L || !PortWatchRules.isValidKey(key)) null
                else PortWatchEvent(time, key)
            }.take(MAX_EVENTS)
        }.getOrElse { emptyList() }
    }

    fun append(keys: List<String>, timestamp: Long): List<PortWatchEvent> {
        val fresh = keys.distinct()
            .filter(PortWatchRules::isValidKey)
            .map { PortWatchEvent(timestamp, it) }
        if (fresh.isEmpty()) return read()
        val combined = (fresh + read()).take(MAX_EVENTS)
        write(combined)
        return combined
    }

    fun clear() {
        preferences.edit().remove(EVENTS_KEY).apply()
    }

    private fun write(items: List<PortWatchEvent>) {
        val array = JSONArray()
        items.take(MAX_EVENTS).forEach {
            array.put(JSONObject().put("time", it.occurredAtMillis).put("port", it.portKey))
        }
        preferences.edit().putString(EVENTS_KEY, array.toString()).apply()
    }

    private companion object {
        const val EVENTS_KEY = "watched_port_events_v1"
        const val MAX_EVENTS = 100
    }
}
