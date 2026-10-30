package org.hearth.ember.data

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * More places backups can live, for people who run their own things: this Hearth server itself, anything that speaks S3 (MinIO, Backblaze B2,
 * Wasabi, Cloudflare R2, Garage, Ceph, Amazon), FTP and FTPS, and SFTP. Each is one small class with the same four moves as every other place.
 * Everything is sealed on the phone before it is sent, so none of these places can read a backup.
 */

/** The backups kept on the Hearth server the person is signed in to (their own folder there; see server/src/serverBackups.ts). */
class ServerTarget(private val client: OkHttpClient, private val base: String, private val destination: Destination) : BackupTarget {
    override val label get() = destination.label
    private fun url(name: String = "") = base.trimEnd('/') + "/api/backup/files" + if (name.isEmpty()) "" else "/" + URLEncoder.encode(name, "UTF-8")

    private fun check(r: okhttp3.Response, what: String) {
        if (r.isSuccessful) return
        val msg = runCatching { org.json.JSONObject(r.body?.string().orEmpty()).optString("error") }.getOrDefault("").ifBlank { "HTTP ${r.code}" }
        throw BackupException(if (r.code == 401) "Sign in to this server again to $what." else "The server would not $what: $msg")
    }

    override fun put(name: String, file: File) {
        val req = Request.Builder().url(url(name)).put(file.asRequestBody("application/octet-stream".toMediaType())).build()
        client.newCall(req).execute().use { check(it, "keep that backup") }
    }

    override fun list(): List<RemoteEntry> = client.newCall(Request.Builder().url(url()).get().build()).execute().use { r ->
        check(r, "list your backups")
        val files = org.json.JSONObject(r.body?.string().orEmpty()).optJSONArray("files") ?: return@use emptyList()
        (0 until files.length()).map { i -> files.getJSONObject(i).let { RemoteEntry(it.getString("name"), it.optLong("modifiedAt"), it.optLong("size")) } }
    }

    override fun get(name: String, to: File) {
        client.newCall(Request.Builder().url(url(name)).get().build()).execute().use { r ->
            check(r, "send that backup")
            r.body!!.byteStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        }
    }

    override fun delete(name: String) { client.newCall(Request.Builder().url(url(name)).delete().build()).execute().use { check(it, "delete that backup") } }
}

/** AWS Signature Version 4, just enough to sign the four requests a backup makes. Pure, so it is tested against Amazon's published example. */
object SigV4 {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hmac(key: ByteArray, data: String): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data.toByteArray()) }
    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(data))

    /** The headers to send: Authorization, x-amz-date and x-amz-content-sha256. [extra] are other headers that are signed too. */
    fun headers(
        method: String, host: String, path: String, query: String, payloadHash: String, region: String, accessKey: String, secretKey: String,
        amzDate: String, extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val date = amzDate.substring(0, 8)
        val all = (extra + mapOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to amzDate)).mapKeys { it.key.lowercase() }.toSortedMap()
        val signed = all.keys.joinToString(";")
        val canonical = listOf(method, path, query, all.entries.joinToString("") { "${it.key}:${it.value.trim()}\n" }, signed, payloadHash).joinToString("\n")
        val scope = "$date/$region/s3/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical.toByteArray())}"
        val kDate = hmac("AWS4$secretKey".toByteArray(), date)
        val signature = hex(hmac(hmac(hmac(hmac(kDate, region), "s3"), "aws4_request"), toSign))
        return mapOf(
            "x-amz-date" to amzDate, "x-amz-content-sha256" to payloadHash,
            "Authorization" to "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signed, Signature=$signature",
        )
    }

    fun now(): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /** A path or key with each piece percent-encoded the way S3 signs it (slashes stay). */
    fun encodePath(path: String): String = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~") }
}

/**
 * Anything that speaks S3: the address (https://s3.example.com), a bucket and an optional folder inside it, and an access key and secret. The
 * bucket is in the path (not the host name), which every self-hosted S3 and most hosted ones accept. Stored as uri = address,
 * extra = "region|bucket|folder", user = access key, pass = secret.
 */
class S3Target(private val client: OkHttpClient, private val destination: Destination) : BackupTarget {
    private val parts = destination.extra.split("|")
    private val region = parts.getOrElse(0) { "" }.ifBlank { "us-east-1" }
    private val bucket = parts.getOrElse(1) { "" }
    private val prefix = parts.getOrElse(2) { "" }.trim('/').let { if (it.isEmpty()) "" else "$it/" }
    private val endpoint = destination.uri.trimEnd('/')
    private val http = client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
    override val label get() = destination.label

    private fun request(method: String, key: String, query: Map<String, String> = emptyMap(), body: okhttp3.RequestBody? = null, hash: String = "UNSIGNED-PAYLOAD"): Request {
        if (bucket.isBlank()) throw BackupException("Give the bucket name.")
        val uri = URI(endpoint)
        val host = if (uri.port > 0 && uri.port != 80 && uri.port != 443) "${uri.host}:${uri.port}" else uri.host
        val path = SigV4.encodePath("${uri.path.trimEnd('/')}/$bucket" + if (key.isEmpty()) "" else "/$key")
        val q = query.toSortedMap().entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8").replace("+", "%20")}=${URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")}" }
        val headers = SigV4.headers(method, host, path, q, hash, region, destination.user, destination.pass, SigV4.now())
        return Request.Builder().url("${uri.scheme}://$host$path" + if (q.isEmpty()) "" else "?$q").method(method, body).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
    }

    private fun check(r: okhttp3.Response, what: String) {
        if (r.isSuccessful) return
        val text = r.body?.string().orEmpty()
        val code = Regex("<Code>(.*?)</Code>").find(text)?.groupValues?.get(1)
        throw BackupException(when {
            r.code == 403 -> "The storage refused the access key or secret${code?.let { " ($it)" } ?: ""}."
            r.code == 404 -> "That bucket or folder was not found${code?.let { " ($it)" } ?: ""}."
            else -> "Could not $what: ${code ?: "HTTP ${r.code}"}"
        })
    }

    override fun put(name: String, file: File) {
        http.newCall(request("PUT", SigV4.encodePath(prefix + name), body = file.asRequestBody("application/octet-stream".toMediaType()))).execute().use { check(it, "keep that backup") }
    }

    override fun list(): List<RemoteEntry> = http.newCall(request("GET", "", mapOf("list-type" to "2", "prefix" to prefix))).execute().use { r ->
        check(r, "list the backups")
        val xml = r.body?.string().orEmpty()
        Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            val key = Regex("<Key>(.*?)</Key>").find(block)?.groupValues?.get(1)?.removePrefix(prefix) ?: return@mapNotNull null
            if (key.isEmpty() || key.contains('/')) return@mapNotNull null
            val size = Regex("<Size>(\\d+)</Size>").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val modified = Regex("<LastModified>(.*?)</LastModified>").find(block)?.groupValues?.get(1)?.let { parseTime(it) } ?: 0L
            RemoteEntry(key, modified, size)
        }.toList()
    }

    private fun parseTime(text: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(text.take(19))!!.time
    }.getOrDefault(0L)

    override fun get(name: String, to: File) {
        http.newCall(request("GET", SigV4.encodePath(prefix + name))).execute().use { r ->
            check(r, "fetch that backup")
            r.body!!.byteStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        }
    }

    override fun delete(name: String) { http.newCall(request("DELETE", SigV4.encodePath(prefix + name))).execute().use { check(it, "delete that backup") } }
}

/** An FTP or FTPS (explicit TLS) folder. uri = ftp://host:port/folder or ftps://host:port/folder. */
class FtpTarget(private val destination: Destination) : BackupTarget {
    override val label get() = destination.label
    private val uri = URI(destination.uri)
    private val secure = uri.scheme.equals("ftps", true)

    private fun <T> session(block: (org.apache.commons.net.ftp.FTPClient) -> T): T {
        val ftp = if (secure) org.apache.commons.net.ftp.FTPSClient("TLS", false) else org.apache.commons.net.ftp.FTPClient()
        ftp.connectTimeout = 15_000
        ftp.defaultTimeout = 30_000
        try {
            ftp.connect(uri.host, if (uri.port > 0) uri.port else 21)
            if (!ftp.login(destination.user.ifBlank { "anonymous" }, destination.pass)) throw BackupException("The FTP server refused that username or password.")
            if (secure && ftp is org.apache.commons.net.ftp.FTPSClient) { ftp.execPBSZ(0); ftp.execPROT("P") }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
            val dir = uri.path.orEmpty().trim('/')
            if (dir.isNotEmpty()) {
                var cwd = ""
                for (piece in dir.split("/")) { cwd += "/$piece"; if (!ftp.changeWorkingDirectory(cwd)) { ftp.makeDirectory(cwd); ftp.changeWorkingDirectory(cwd) } }
            }
            return block(ftp)
        } catch (e: java.io.IOException) {
            val why = e.message ?: "connection failed"
            throw BackupException(if (secure && ("530" in why || "AUTH" in why || "TLS" in why)) "This FTP server does not offer encryption (FTPS). Turn off \"Encrypt the connection\" to use plain FTP, or enable TLS on the server." else "Could not reach the FTP server: $why")
        } finally {
            runCatching { if (ftp.isConnected) { ftp.logout(); ftp.disconnect() } }
        }
    }

    override fun put(name: String, file: File) { session { ftp -> file.inputStream().use { if (!ftp.storeFile(name, it)) throw BackupException("The FTP server would not take $name.") } } }
    override fun list(): List<RemoteEntry> = session { ftp -> ftp.listFiles().filter { it.isFile }.map { RemoteEntry(it.name, it.timestamp?.timeInMillis ?: 0L, it.size) } }
    override fun get(name: String, to: File) { session { ftp -> to.outputStream().use { if (!ftp.retrieveFile(name, it)) throw BackupException("$name is not on the FTP server any more.") } } }
    override fun delete(name: String) { session { ftp -> ftp.deleteFile(name) } }
}

/** An SFTP (SSH) folder with a username and password. uri = sftp://host:port/folder. The server's key is accepted the first time: this is for your own machines. */
class SftpTarget(private val destination: Destination) : BackupTarget {
    override val label get() = destination.label
    private val uri = URI(destination.uri)

    private fun <T> session(block: (com.jcraft.jsch.ChannelSftp) -> T): T {
        val jsch = com.jcraft.jsch.JSch()
        val s = jsch.getSession(destination.user, uri.host, if (uri.port > 0) uri.port else 22)
        s.setPassword(destination.pass)
        s.setConfig("StrictHostKeyChecking", "no")
        try {
            s.connect(15_000)
            val ch = s.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
            ch.connect(15_000)
            try {
                val dir = uri.path.orEmpty().trim('/')
                if (dir.isNotEmpty()) {
                    var cwd = ""
                    for (piece in dir.split("/")) { cwd += "/$piece"; runCatching { ch.cd(cwd) }.onFailure { ch.mkdir(cwd); ch.cd(cwd) } }
                }
                return block(ch)
            } finally { ch.disconnect() }
        } catch (e: com.jcraft.jsch.JSchException) {
            throw BackupException(if (e.message?.contains("Auth", true) == true) "The SFTP server refused that username or password." else "Could not reach the SFTP server: ${e.message}")
        } catch (e: com.jcraft.jsch.SftpException) {
            throw BackupException("The SFTP server said: ${e.message}")
        } finally { s.disconnect() }
    }

    override fun put(name: String, file: File) { session { ch -> file.inputStream().use { ch.put(it, name) } } }
    @Suppress("UNCHECKED_CAST")
    override fun list(): List<RemoteEntry> = session { ch ->
        (ch.ls(".") as java.util.Vector<com.jcraft.jsch.ChannelSftp.LsEntry>).filter { !it.attrs.isDir && !it.filename.startsWith(".") }.map { RemoteEntry(it.filename, it.attrs.mTime * 1000L, it.attrs.size) }
    }
    override fun get(name: String, to: File) { session { ch -> to.outputStream().use { ch.get(name, it) } } }
    override fun delete(name: String) { session { ch -> runCatching { ch.rm(name) } } }
}
