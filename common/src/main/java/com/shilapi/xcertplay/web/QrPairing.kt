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
    private data class Token(val expiresAt: Long, var deviceId: String? = null)
    private val tokens = linkedMapOf<String, Token>()
    private fun prune() {
        requests.entries.removeAll { it.value.expiresAt <= now() }
        tokens.entries.removeAll { it.value.expiresAt <= now() }
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
        val token = issueToken() ?: return false
        requests[request.id]?.token = token
        return true
    }
    @Synchronized fun issueToken(deviceId: String? = null): String? {
        prune()
        if (deviceId != null) tokens.entries.firstOrNull { it.value.deviceId == deviceId }?.let {
            tokens[it.key] = Token(now() + 12 * 60 * 60_000L, deviceId)
            return it.key
        }
        if (tokens.size >= 32) return null
        return secret().also { tokens[it] = Token(now() + 12 * 60 * 60_000L, deviceId) }
    }
    @Synchronized fun deviceId(token: String?): String? { prune(); return tokens[token]?.deviceId }
    @Synchronized fun bindDevice(token: String, deviceId: String): Boolean {
        prune()
        val session = tokens[token] ?: return false
        session.deviceId = deviceId
        return true
    }
    @Synchronized fun revokeDevice(deviceId: String) {
        tokens.entries.removeAll { it.value.deviceId == deviceId }
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
