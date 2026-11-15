package moe.feng.wheelplay.settings

import android.annotation.SuppressLint
import android.widget.FrameLayout
import androidx.compose.remote.core.CoreDocument
import androidx.compose.remote.core.Limits
import androidx.compose.remote.core.operations.Header
import androidx.compose.remote.core.operations.utilities.IntMap
import androidx.compose.remote.player.core.RemoteDocument
import androidx.compose.remote.player.core.platform.BitmapLoader
import androidx.compose.remote.player.core.platform.AndroidRemoteContext
import androidx.compose.remote.player.core.action.StateUpdaterActionCallback
import androidx.compose.remote.player.view.RemoteComposePlayer
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.feng.wheelplay.plugin.PluginSettingsDocument
import java.util.concurrent.CancellationException

/** Keep the alpha/restricted AndroidX APIs in one adapter, pinned with the producer version. */
@SuppressLint("RestrictedApi")
internal object RemoteSettingsPlayer {
    fun parse(document: PluginSettingsDocument): RemoteDocument {
        Limits.MAX_DECOMPRESSED_SIZE = 2 * 1024 * 1024
        Limits.MAX_OP_COUNT = 4000
        Limits.MAX_IMAGE_DIMENSION = 2048
        Limits.MAX_BITMAP_MEMORY = 8 * 1024 * 1024
        Limits.MAX_FPS = 30
        Limits.DEFAULT_MAX_FPS = 30
        Limits.ENABLE_IMAGE_URLS = false
        Limits.ENABLE_IMAGE_FILES = false
        Limits.ENABLE_RUN_ACTION_HOST_ACTIONS = false
        return RemoteDocument(document.bytes).also {
            require(it.canBeDisplayed(CoreDocument.MAJOR_VERSION, CoreDocument.MINOR_VERSION, 0)) {
                "Remote Compose document version is unsupported"
            }
            // Viewport dimensions are set only when a player initializes the document.
            // Inspect the header without initializing any resources or running operations.
            val header = it.document.operations.filterIsInstance<Header>().single()
            header.apply(object : AndroidRemoteContext() {
                override fun header(majorVersion: Int, minorVersion: Int, patchVersion: Int,
                    width: Int, height: Int, capabilities: Long, properties: IntMap<Any>?) {
                    require(width == document.width && height == document.height) {
                        "Remote Compose dimensions do not match the descriptor"
                    }
                }
            })
        }
    }

    @Composable
    fun Content(document: PluginSettingsDocument, enabled: Boolean, onAction: (Int, String?) -> Unit,
        onNamedAction: (String, Any?) -> Unit, onFailure: (String) -> Unit) {
        var parsed by remember(document) { mutableStateOf<RemoteDocument?>(null) }
        val currentAction by rememberUpdatedState(onAction)
        val currentEnabled by rememberUpdatedState(enabled)
        val currentNamedAction by rememberUpdatedState(onNamedAction)
        LaunchedEffect(document) {
            try { parsed = withContext(Dispatchers.IO) { parse(document) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { onFailure(error.message.orEmpty()) }
        }
        val value = parsed ?: return
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val height = (maxWidth * document.height.toFloat() / document.width).coerceIn(120.dp, 1200.dp)
            AndroidView(modifier = Modifier.fillMaxWidth().height(height).testTag("plugin:remote-compose"),
                factory = { context -> FrameLayout(context).apply {
                    val frame = this
                    addView(RemoteComposePlayer(context).apply {
                        setBitmapLoader(BitmapLoader.UNSUPPORTED)
                        setShaderControl { false }
                        setDefaultMaxFps(30)
                        pivotX = 0f; pivotY = 0f
                    }, FrameLayout.LayoutParams(document.width, document.height))
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scale(frame) }
                } },
                update = { frame ->
                    val player = frame.getChildAt(0) as RemoteComposePlayer
                    try { if (player.tag !== value) {
                        player.layoutParams = FrameLayout.LayoutParams(document.width, document.height)
                        player.setDocument(value)
                        check(player.document === value) { "Remote Compose player rejected the document" }
                        player.addIdActionListener { id, metadata ->
                            if (currentEnabled && frame.isEnabled) currentAction(id, metadata)
                        }
                        value.document.addActionCallback(object : StateUpdaterActionCallback(player.stateUpdater,
                            { name, payload, _ -> if (currentEnabled && frame.isEnabled) currentNamedAction(name, payload) }) {})
                        player.tag = value
                        scale(frame)
                    } }
                    catch (error: Exception) { onFailure(error.message.orEmpty()) }
                },
                onRelease = { it.isEnabled = false; it.removeAllViews() },
            )
        }
    }

    private fun scale(frame: FrameLayout) {
        val player = frame.getChildAt(0) ?: return
        if (frame.width == 0 || frame.height == 0) return
        val scale = minOf(frame.width.toFloat() / player.layoutParams.width,
            frame.height.toFloat() / player.layoutParams.height)
        player.scaleX = scale; player.scaleY = scale
    }
}
