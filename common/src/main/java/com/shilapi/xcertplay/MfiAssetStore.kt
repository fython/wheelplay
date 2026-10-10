// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipFile

/** Local provisioning only. No APK code is loaded and no archive paths are extracted. */
internal class MfiAssetStore(private val root: File) {
    private val target get() = File(root, LocalMfiAuthenticationClient.DIRECTORY)
    private val previous get() = File(root, "offline-mfi.previous")

    fun load(): LocalMfiAuthenticationClient = synchronized(lock) {
        recover()
        cleanAbandonedImports()
        LocalMfiAuthenticationClient.load(target)
    }

    fun exists(): Boolean = synchronized(lock) { recover(); cleanAbandonedImports(); target.exists() }

    fun installFiles(key: () -> InputStream, certificate: () -> InputStream,
                     canInstall: () -> Boolean = { true }) = synchronized(lock) {
        cleanAbandonedImports()
        install(canInstall) { staging ->
            key().use { copyBounded(it, File(staging, KEY), MAX_RESOURCE_BYTES) }
            certificate().use { copyBounded(it, File(staging, CERTIFICATE), MAX_RESOURCE_BYTES) }
        }
    }

    fun installApk(input: () -> InputStream, canInstall: () -> Boolean = { true }) = synchronized(lock) {
        prepareRoot()
        cleanAbandonedImports()
        val apk = File.createTempFile("mfi-import-", ".apk", root)
        try {
            input().use { copyBounded(it, apk, MAX_APK_BYTES) }
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().filter { it.name in APK_ENTRIES }.toList()
                require(entries.size == 2 && entries.map { it.name }.toSet() == APK_ENTRIES) {
                    "APK must contain exactly one of each authentication resource"
                }
                install(canInstall) { staging ->
                    for (entry in entries) {
                        require(!entry.isDirectory && entry.size <= MAX_RESOURCE_BYTES) { "Invalid resource size" }
                        val name = if (entry.name == "assets/offline-mfi/$KEY") KEY else CERTIFICATE
                        zip.getInputStream(entry).use { copyBounded(it, File(staging, name), MAX_RESOURCE_BYTES) }
                    }
                }
            }
        } finally {
            apk.delete()
        }
    }

    private fun install(canInstall: () -> Boolean, write: (File) -> Unit) {
        check(canInstall()) { "Disconnect iPhone before importing" }
        prepareRoot()
        recover()
        val staging = Files.createTempDirectory(root.toPath(), "mfi-import-").toFile()
        try {
            write(staging)
            // Includes P-256 checks and a challenge signature to prove that the pair matches.
            LocalMfiAuthenticationClient.load(staging)
            check(canInstall()) { "Disconnect iPhone before importing" }
            if (target.exists()) check(target.renameTo(previous)) { "Could not preserve current resources" }
            if (!staging.renameTo(target)) {
                if (previous.exists()) check(previous.renameTo(target)) { "Could not restore current resources" }
                error("Could not install resources")
            }
            previous.deleteRecursively()
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Recover a process death between the two directory renames. */
    private fun recover() {
        if (!previous.exists()) return
        if (target.exists() && runCatching { LocalMfiAuthenticationClient.load(target) }.isSuccess) {
            check(previous.deleteRecursively()) { "Could not clean up previous resources" }
        } else {
            LocalMfiAuthenticationClient.load(previous)
            check(target.deleteRecursively() && previous.renameTo(target)) { "Could not restore resources" }
        }
    }

    private fun cleanAbandonedImports() {
        root.listFiles()?.filter { it.name.startsWith("mfi-import-") }?.forEach {
            check(it.deleteRecursively()) { "Could not clean up interrupted import" }
        }
    }

    private fun prepareRoot() {
        check(root.isDirectory || root.mkdirs()) { "Could not prepare private storage" }
    }

    companion object {
        const val KEY = "identity.pk8"
        const val CERTIFICATE = "certificate.p7b"
        const val MAX_RESOURCE_BYTES = 16L * 1024
        const val MAX_APK_BYTES = 256L * 1024 * 1024
        private val APK_ENTRIES = setOf("assets/offline-mfi/$KEY", "assets/offline-mfi/$CERTIFICATE")
        private val lock = Any()

        private fun copyBounded(input: InputStream, target: File, limit: Long) {
            // All staging files reside under the application's noBackupFilesDir.
            check(target.createNewFile() || target.isFile) { "Could not create resource" }
            check(target.setReadable(false, false) && target.setReadable(true, true) &&
                target.setWritable(false, false) && target.setWritable(true, true)) { "Could not protect resource" }
            val buffer = ByteArray(8192)
            try {
                target.outputStream().use { output ->
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= limit) { "Import file is too large" }
                        output.write(buffer, 0, count)
                    }
                    require(total > 0) { "Import file is empty" }
                    output.fd.sync()
                }
            } finally {
                buffer.fill(0)
            }
        }
    }
}
