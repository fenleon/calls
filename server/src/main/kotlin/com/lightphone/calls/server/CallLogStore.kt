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
    /** Accepted on another linked device: the wire omits the duration (our
     *  device wasn't a party to the connected call). */
    val answeredElsewhere: Boolean = false,
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
     *  record for the same call) must beat what an earlier import wrote.
     *  Fuzzy pass: a live entry is stamped at placement/connect, the server
     *  record at offer creation — seconds apart for the same call. Matching
     *  within 90s collapses them onto the server record (its duration +
     *  missed flag are authoritative) instead of duplicating the row. */
    @Synchronized
    fun addAll(entries: List<CallLogEntry>) {
        val existing = all()
        val list = existing.toMutableList()
        entries.forEach { entry ->
            val exact = list.indexOfFirst {
                it.peer == entry.peer && it.at == entry.at && it.outgoing == entry.outgoing
            }
            if (exact >= 0) {
                list[exact] = entry
                return@forEach
            }
            // ponytail: ±90s window; two real calls to one peer inside it merge.
            val fuzzy = list.indexOfFirst {
                it.peer == entry.peer && it.outgoing == entry.outgoing &&
                    kotlin.math.abs(it.at - entry.at) <= 90_000
            }
            if (fuzzy >= 0) list[fuzzy] = entry else list.add(entry)
        }
        val newList = list.sortedBy { it.at }.takeLast(200)
        if (newList == existing) {
            android.util.Log.i("CallLogStore", "addAll: skip (unchanged)")
            return
        }
        android.util.Log.i("CallLogStore", "addAll: n=${newList.size} wrote")
        file.writeText(json.encodeToString(ListSerializer(CallLogEntry.serializer()), newList))
    }

    /** Fills in the live entry's connected seconds at `CallEnded` — live
     *  entries log at placement/start with duration 0. */
    @Synchronized
    fun updateDuration(peer: String, at: Long, outgoing: Boolean, duration: Long) {
        val list = all()
        val idx = list.indexOfFirst { it.peer == peer && it.at == at && it.outgoing == outgoing }
        if (idx < 0) return
        val updated = list.toMutableList()
        updated[idx] = updated[idx].copy(duration = duration)
        file.writeText(json.encodeToString(ListSerializer(CallLogEntry.serializer()), updated))
    }

    /** Drops rows whose peer is one of the given JID user parts (self rows:
     *  our own identity leaked in as the peer). */
    @Synchronized
    fun removePeers(ownUsers: Set<String>) {
        val list = all().filter {
            it.peer.substringBefore('@').substringBefore('.').substringBefore(':') !in ownUsers
        }
        if (list.size == all().size) return
        file.writeText(json.encodeToString(ListSerializer(CallLogEntry.serializer()), list))
    }

    /** Identity heal: rewrites peers whose raw LID resolved to a phone JID.
     *  The same call delivered under both forms (raw-LID import, then a
     *  resolved live re-emission) collapses onto the phone-number row. */
    @Synchronized
    fun remap(mapping: Map<String, String>) {
        if (mapping.isEmpty()) return
        val list = all()
            .map { entry -> entry.copy(peer = mapping[entry.peer] ?: entry.peer) }
            .distinctBy { Triple(it.peer, it.at, it.outgoing) }
            .sortedBy { it.at }
            .takeLast(200)
        file.writeText(json.encodeToString(ListSerializer(CallLogEntry.serializer()), list))
    }

    @Synchronized
    fun all(): List<CallLogEntry> =
        runCatching { json.decodeFromString(ListSerializer(CallLogEntry.serializer()), file.readText()) }
            .getOrDefault(emptyList())
}
