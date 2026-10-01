package com.shilapi.xcertplay.web

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Installation-local browser credentials. Only hashes of the browser secret are persisted. */
internal class RememberedBrowsers(context: Context, private val now: () -> Long = { System.currentTimeMillis() }) {
    data class Device(val id: String, val name: String, val peer: String, val createdAt: Long, val lastUsedAt: Long)
    data class Credential(val device: Device, val secret: String)
    private data class Entry(val device: Device, val hash: ByteArray)
    private val preferences = context.getSharedPreferences("wheelplay_browser_devices", Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val entries = linkedMapOf<String, Entry>()

    init {
        runCatching {
            val data = JSONArray(preferences.getString("devices", "[]"))
            for (i in 0 until minOf(data.length(), MAX_DEVICES)) {
                runCatching {
                    val item = data.getJSONObject(i)
                    val device = Device(item.getString("id"), item.getString("name"), item.getString("peer"),
                        item.getLong("createdAt"), item.getLong("lastUsedAt"))
                    val hash = Base64.getDecoder().decode(item.getString("hash"))
                    if (hash.size == 32) entries[device.id] = Entry(device, hash)
                }
            }
        }
    }

    private fun secret() = ByteArray(24).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private fun hash(secret: String) = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
    private fun save(): Boolean {
        val data = JSONArray()
        entries.values.forEach { entry ->
            val device = entry.device
            data.put(JSONObject().put("id", device.id).put("name", device.name).put("peer", device.peer)
                .put("createdAt", device.createdAt).put("lastUsedAt", device.lastUsedAt)
                .put("hash", Base64.getEncoder().encodeToString(entry.hash)))
        }
        return preferences.edit().putString("devices", data.toString()).commit()
    }

    @Synchronized fun list(): List<Device> = entries.values.map { it.device }.sortedByDescending { it.lastUsedAt }
    @Synchronized fun remember(name: String, peer: String): Credential? {
        if (entries.size >= MAX_DEVICES) return null
        val credentialSecret = secret()
        val device = Device(secret(), name.filterNot { it.isISOControl() }.trim().take(80).ifEmpty { "浏览器" },
            peer.take(80), now(), now())
        entries[device.id] = Entry(device, hash(credentialSecret))
        if (!save()) { entries.remove(device.id); return null }
        return Credential(device, credentialSecret)
    }

    @Synchronized fun authenticate(id: String?, secret: String?, peer: String): Device? {
        if (id == null || secret == null || secret.length != 32) return null
        val entry = entries[id] ?: return null
        if (!MessageDigest.isEqual(entry.hash, hash(secret))) return null
        val device = entry.device.copy(peer = peer.take(80), lastUsedAt = now())
        entries[id] = entry.copy(device = device)
        save()
        return device
    }

    @Synchronized fun rename(id: String, name: String) {
        val entry = entries[id] ?: return
        val cleaned = name.filterNot { it.isISOControl() }.trim().take(80)
        if (cleaned.isEmpty()) return
        entries[id] = entry.copy(device = entry.device.copy(name = cleaned)); save()
    }
    @Synchronized fun remove(id: String) { entries.remove(id); save() }
    @Synchronized fun clear() { entries.clear(); save() }

    companion object { const val MAX_DEVICES = 32 }
}
