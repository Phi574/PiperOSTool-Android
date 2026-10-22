package com.piperostool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small private history so users can review transfers after closing the progress screen or app. */
object PiperDataTransferHistoryStore {
    data class Entry(
        val snapshot: PiperDataTransferProgressState.Snapshot,
        val savedAt: Long
    )

    private const val PREFS = "piper_data_transfer_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 60

    @Synchronized
    fun record(context: Context, snapshot: PiperDataTransferProgressState.Snapshot) {
        val entries = read(context).filterNot { it.snapshot.sessionId == snapshot.sessionId }.toMutableList()
        entries += Entry(snapshot, System.currentTimeMillis())
        save(context, entries)
    }

    @Synchronized
    fun delete(context: Context, sessionId: String) {
        save(context, read(context).filterNot { it.snapshot.sessionId == sessionId })
    }

    @Synchronized
    fun clear(context: Context) {
        save(context, emptyList())
    }

    private fun save(context: Context, entries: List<Entry>) {
        val json = JSONArray()
        entries.sortedByDescending(Entry::savedAt).take(MAX_ENTRIES).forEach { entry ->
            val state = entry.snapshot
            val items = JSONArray().apply {
                state.items.forEach { item ->
                    put(JSONObject()
                        .put("name", item.name)
                        .put("mime", item.mime)
                        .put("size", item.size)
                        .put("transferred", item.transferred)
                        .put("state", item.state.name))
                }
            }
            json.put(JSONObject()
                .put("sessionId", state.sessionId)
                .put("role", state.role)
                .put("items", items)
                .put("transferred", state.transferred)
                .put("total", state.total)
                .put("currentIndex", state.currentIndex)
                .put("complete", state.complete)
                .put("error", state.error)
                .put("receivedDirectory", state.receivedDirectory)
                .put("savedAt", entry.savedAt))
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ENTRIES, json.toString()).apply()
    }

    @Synchronized
    fun read(context: Context): List<Entry> {
        val stored = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val itemsJson = entry.optJSONArray("items") ?: JSONArray()
                val items = (0 until itemsJson.length()).mapNotNull { itemIndex ->
                    val item = itemsJson.optJSONObject(itemIndex) ?: return@mapNotNull null
                    PiperDataTransferProgressState.Item(
                        item.optString("name", "Tệp"),
                        item.optString("mime", "application/octet-stream"),
                        item.optLong("size", -1L),
                        item.optLong("transferred", 0L),
                        runCatching {
                            PiperDataTransferProgressState.ItemState.valueOf(item.optString("state", "WAITING"))
                        }.getOrDefault(PiperDataTransferProgressState.ItemState.WAITING)
                    )
                }
                val snapshot = PiperDataTransferProgressState.Snapshot(
                    sessionId = entry.optString("sessionId"),
                    role = entry.optString("role", PiperDataTransferProgressActivity.ROLE_SEND),
                    items = items,
                    transferred = entry.optLong("transferred", 0L),
                    total = entry.optLong("total", -1L),
                    currentIndex = entry.optInt("currentIndex", 0),
                    complete = entry.optBoolean("complete"),
                    error = entry.optString("error").takeIf(String::isNotBlank),
                    receivedDirectory = entry.optString("receivedDirectory").takeIf(String::isNotBlank)
                )
                Entry(snapshot, entry.optLong("savedAt", 0L))
            }.sortedByDescending(Entry::savedAt)
        }.getOrDefault(emptyList())
    }
}
