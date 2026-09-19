package com.lightphone.calls.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class CallLogEntry(val peer: String, val outgoing: Boolean, val missed: Boolean, val at: Long)

/** JSON-file call log (last 200 entries) — written by [WaCallEngine] on call
 *  end, rendered by the tool's home screen. */
class CallLogStore(dir: File) {
    private val file = File(dir, "calllog.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun add(entry: CallLogEntry) {
        val list = all() + entry
        file.writeText(json.encodeToString(ListSerializer(CallLogEntry.serializer()), list.takeLast(200)))
    }

    @Synchronized
    fun all(): List<CallLogEntry> =
        runCatching { json.decodeFromString(ListSerializer(CallLogEntry.serializer()), file.readText()) }
            .getOrDefault(emptyList())
}
