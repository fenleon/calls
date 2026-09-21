package com.lightphone.calls.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ContactEntry(val id: String, val name: String)

/** JSON-file peer contacts (JID + push name, no message content) — filled by
 *  the history-sync contacts import, rendered by the contacts screen. */
class ContactStore(dir: File) {
    private val file = File(dir, "contacts.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** Merges by JID; the newest non-empty name wins (live push-name
     *  updates keep names current after the pairing-time import). */
    @Synchronized
    fun merge(contacts: List<ContactEntry>) {
        val byId = (all() + contacts)
            .groupBy { it.id }
            .mapValues { (_, group) -> group.lastOrNull { it.name.isNotEmpty() } ?: group.last() }
        file.writeText(json.encodeToString(ListSerializer(ContactEntry.serializer()), byId.values.sortedBy { it.id }))
    }

    @Synchronized
    fun all(): List<ContactEntry> =
        runCatching { json.decodeFromString(ListSerializer(ContactEntry.serializer()), file.readText()) }
            .getOrDefault(emptyList())
}
