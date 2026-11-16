package com.shilapi.xcertplay.projection.plugin

import android.os.Bundle
import java.util.UUID

/** User intent captured before leaving the host; never resumes a changed or busy source. */
internal data class PluginPreparation(val sourceId: String, val identity: String, val generation: Long, val request: String) {
    fun accepts(plugin: InstalledProjectionPlugin?, selectedId: String, currentGeneration: Long,
        occupied: Boolean, enabled: Boolean, returnedRequest: String?): Boolean =
        plugin != null && enabled && plugin.id == sourceId && selectedId == sourceId &&
            identity == identity(plugin) && currentGeneration == generation && !occupied && returnedRequest == request

    fun toBundle() = Bundle().apply {
        putString("sourceId", sourceId); putString("identity", identity)
        putLong("generation", generation); putString("request", request)
    }
    companion object {
        private fun identity(plugin: InstalledProjectionPlugin) =
            "${plugin.component.flattenToString()}|${plugin.uid}|${plugin.signer}|${plugin.installedAt}|${plugin.updatedAt}|${plugin.version}|${plugin.preparation}"
        fun create(plugin: InstalledProjectionPlugin, generation: Long) =
            PluginPreparation(plugin.id, identity(plugin), generation, UUID.randomUUID().toString())
        fun fromBundle(bundle: Bundle?) = bundle?.let {
            runCatching { PluginPreparation(requireNotNull(it.getString("sourceId")), requireNotNull(it.getString("identity")),
                it.getLong("generation"), requireNotNull(it.getString("request"))) }.getOrNull()
        }
    }
}
