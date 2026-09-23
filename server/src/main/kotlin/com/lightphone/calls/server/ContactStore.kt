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

    /** Identity heal: rewrites LID ids to their resolved phone JID (merge
     *  collapses the duplicate) and drops junk rows ("0@s.whatsapp.net",
     *  the account-level JID history sync sometimes carries). */
    @Synchronized
    fun remap(mapping: Map<String, String>) {
        val current = all()
        if (mapping.isEmpty() && current.none { it.id.startsWith("0@") }) return
        val healed = current
            .filter { !it.id.startsWith("0@") }
            .map { it.copy(id = mapping[it.id] ?: it.id) }
            .associateByTo(LinkedHashMap()) { it.id }
            .values
            .sortedBy { it.id }
        file.writeText(json.encodeToString(ListSerializer(ContactEntry.serializer()), healed))
    }

    /** Drops entries whose id is one of the given JID user parts (self rows:
     *  our own identity leaked into the contact list). */
    @Synchronized
    fun removePeers(ownUsers: Set<String>) {
        val list = all().filter {
            it.id.substringBefore('@').substringBefore('.').substringBefore(':') !in ownUsers
        }
        if (list.size == all().size) return
        file.writeText(json.encodeToString(ListSerializer(ContactEntry.serializer()), list))
    }

    /** Pairing history-sync import: the authoritative 1:1 peer set —
     *  replaces the stored list (purges stale entries). */
    @Synchronized
    fun replaceAll(contacts: List<ContactEntry>) {
        val byId = contacts
            .filter { it.id.contains('@') }
            .associateBy { it.id }
            .mapValues { (_, c) -> c.copy(name = c.name.trim()) }
        file.writeText(json.encodeToString(ListSerializer(ContactEntry.serializer()), byId.values.sortedBy { it.id }))
    }

    /** Push-name updates: rename existing entries only — a pushname alone
     *  never adds a peer (it covers every group participant). */
    @Synchronized
    fun rename(contacts: List<ContactEntry>) {
        val names = contacts.filter { it.name.isNotEmpty() }.associateBy { it.id }
        if (names.isEmpty()) return
        val renamed = all().map { names[it.id]?.let { n -> it.copy(name = n.name) } ?: it }
        file.writeText(json.encodeToString(ListSerializer(ContactEntry.serializer()), renamed))
    }
}
