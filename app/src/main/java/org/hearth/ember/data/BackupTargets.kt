package org.hearth.ember.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import okhttp3.OkHttpClient

/** A place backups are kept, as far as backing up needs it: put a file, see what is there, fetch one, delete one. */
interface BackupTarget {
    val label: String
    fun put(name: String, file: File)
    fun list(): List<RemoteEntry>
    fun get(name: String, to: File)
    fun delete(name: String)
}

/**
 * A folder the person chose with Android's folder picker. That reaches this phone's storage, an SD card, a USB drive, and anything a storage
 * app exposes to Android (network shares through an SMB or NFS app, cloud drives), without the backup knowing which.
 */
class FolderTarget(private val context: Context, private val destination: Destination) : BackupTarget {
    override val label get() = destination.label
    private fun root(): DocumentFile = DocumentFile.fromTreeUri(context, Uri.parse(destination.uri))?.takeIf { it.exists() && it.canWrite() }
        ?: throw BackupException("The folder \"${destination.label}\" cannot be reached. Choose it again in the backup settings.")

    override fun put(name: String, file: File) {
        val dir = root()
        dir.findFile(name)?.delete()
        val mime = if (name.endsWith(".zip")) "application/zip" else "application/octet-stream"
        val target = dir.createFile(mime, name) ?: throw BackupException("Could not create a file in \"${destination.label}\".")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: throw BackupException("Could not write to \"${destination.label}\".")
    }

    override fun list(): List<RemoteEntry> = root().listFiles().filter { it.isFile }.mapNotNull { f -> f.name?.let { RemoteEntry(it, f.lastModified(), f.length()) } }

    override fun get(name: String, to: File) {
        val f = root().findFile(name) ?: throw BackupException("$name is not in \"${destination.label}\" any more.")
        context.contentResolver.openInputStream(f.uri)?.use { input -> to.outputStream().use { input.copyTo(it) } } ?: throw BackupException("Could not read $name.")
    }

    override fun delete(name: String) { root().findFile(name)?.delete() }
}

/** A WebDAV or Nextcloud folder. No cookies: the sign-in cookie of the Hearth server must never travel to another server. */
class DavTarget(client: OkHttpClient, private val destination: Destination) : BackupTarget {
    private val dav = WebDav(client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build(), destination.uri, destination.user, destination.pass)
    override val label get() = destination.label
    override fun put(name: String, file: File) { dav.put(name, file) }
    override fun list() = dav.list()
    override fun get(name: String, to: File) = dav.get(name, to)
    override fun delete(name: String) = dav.delete(name)
}
