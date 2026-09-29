package com.shilapi.xcertplay.web

import java.security.SecureRandom
import java.util.Base64

/** Browser challenges can only be approved locally. No HTTP route can approve them. */
internal class QrPairing(private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    data class Request(val id: String, val pollSecret: String, val payload: String, val peer: String,
                       val expiresAt: Long, var token: String? = null)
    private val random = SecureRandom()
    private fun secret() = ByteArray(24).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val instance = secret()
    private val requests = linkedMapOf<String, Request>()
    private val tokens = linkedMapOf<String, Long>()
    private fun prune() {
        requests.entries.removeAll { it.value.expiresAt <= now() }
        tokens.entries.removeAll { it.value <= now() }
    }
    @Synchronized fun create(peer: String): Request? {
        prune()
        if (requests.size >= 32 || requests.values.count { it.peer == peer } >= 4) return null
        val id = secret()
        return Request(id, secret(), "wheelplay-pair:v1:$instance:$id:${secret()}", peer, now() + 120_000)
            .also { requests[id] = it }
    }
    @Synchronized fun get(id: String?, pollSecret: String?): Request? {
        prune()
        return requests[id]?.takeIf { it.pollSecret == pollSecret }?.copy()
    }
    @Synchronized fun inspect(payload: String): Request? {
        prune()
        if (payload.length > 256) return null
        return requests.values.firstOrNull { it.payload == payload && it.token == null }?.copy()
    }
    @Synchronized fun approve(payload: String): Boolean {
        val request = inspect(payload) ?: return false
        if (tokens.size >= 32) return false
        val token = secret()
        requests[request.id]?.token = token
        tokens[token] = now() + 12 * 60 * 60_000L
        return true
    }
    @Synchronized fun authorized(token: String?): Boolean { prune(); return token != null && tokens.containsKey(token) }
    @Synchronized fun cancel(id: String?, pollSecret: String?) {
        val request = get(id, pollSecret) ?: return
        request.token?.let { tokens.remove(it) }
        requests.remove(request.id)
    }
    @Synchronized fun revoke(token: String?) { tokens.remove(token) }
    @Synchronized fun clear() { requests.clear(); tokens.clear() }
}
