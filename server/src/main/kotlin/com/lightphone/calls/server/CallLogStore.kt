package com.lightphone.calls.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CallLogEntry(
    val peer: String,
    val outgoing: Boolean,
    val missed: Boolean,
    val at: Long,
    /** Connected seconds — 0 for unanswered calls and live entries whose
     *  server record (the only duration source) hasn't landed yet. */
    val duration: Long = 0,
)

/** JSON-file call log (last 200 entries) — written by [WaCallEngine] on call
 *  end, rendered by the tool's home screen. */
class CallLogStore(dir: File) {
    private val file = File(dir, "calllog.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun add(entry: CallLogEntry) {
        addAll(listOf(entry))
    }

    /** Bulk import (history-sync backfill): deduped on (peer, at, outgoing),
     *  re-sorted so "newest last" holds, cap enforced. A duplicate's `missed`
     *  overwrites the stored one — the corrected result (a later app-state
     *  record for the same call) must beat what an earlier import wrote. */
    @Synchronized
    fun addAll(entries: List<CallLogEntry>) {
        val existing = all()
        val byKey = existing.associateByTo(LinkedHashMap()) { Triple(it.peer, it.at, it.outgoing) }
        entries.forEach { byKey[Triple(it.peer, it.at, it.outgoing)] = it }
        val list = byKey.values.sortedBy { it.at }.takeLast(200)
        if (list == existing) return
        file.writeText(json.encodeToString(ListSerializer(CallLogEntry.serializer()), list))
    }

    @Synchronized
    fun all(): List<CallLogEntry> =
        runCatching { json.decodeFromString(ListSerializer(CallLogEntry.serializer()), file.readText()) }
            .getOrDefault(emptyList())
}
