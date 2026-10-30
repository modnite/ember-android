package org.hearth.ember.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Ember = lightColorScheme(
    primary = Color(0xFFE8590C), onPrimary = Color.White, primaryContainer = Color(0xFFFFDBCB), onPrimaryContainer = Color(0xFF331100),
    background = Color(0xFFFFFBF8), surface = Color(0xFFFFFBF8), surfaceVariant = Color(0xFFF5DED3), onSurfaceVariant = Color(0xFF53433C),
)

fun main(args: Array<String>) = application {
    Window(onCloseRequest = ::exitApplication, title = "Ember Backup Reader", state = rememberWindowState(width = 1100.dp, height = 760.dp)) {
        MaterialTheme(colorScheme = Ember) { Surface { Reader(args.firstOrNull()?.let(::File)?.takeIf { it.isFile }) } }
    }
}

@Composable
private fun Surface(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

/** What is open right now. */
private sealed interface Open {
    data object Nothing : Open
    class Locked(val file: File, val keyId: String?, val why: String, val askPassphrase: Boolean, val askPassword: Boolean = false) : Open
    class Core(val file: File, val content: String) : Open
    class Archive(val file: File, val archive: OpenArchive) : Open
}

private fun pickFile(title: String, load: Boolean, name: String? = null): File? {
    val d = FileDialog(null as Frame?, title, if (load) FileDialog.LOAD else FileDialog.SAVE)
    if (name != null) d.file = name
    d.isVisible = true
    val f = d.file ?: return null
    return File(d.directory, f)
}

private fun pickFolder(title: String): File? {
    val c = JFileChooser().apply { dialogTitle = title; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
    return if (c.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile else null
}

private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.systemDefault())
private fun whenOf(ms: Long) = if (ms > 0) dateTime.format(Instant.ofEpochMilli(ms)) else ""
private fun sizeOf(n: Long) = when { n >= 1 shl 20 -> "%.1f MB".format(n / 1048576.0); n >= 1024 -> "${n / 1024} KB"; else -> "$n B" }

@Composable
private fun Reader(initial: File?) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<Open>(Open.Nothing) }
    var key by remember { mutableStateOf<AccountKey?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    /** Opens [file] with what is known: a key (from signing in or pasted) and, for old backups, a passphrase. */
    fun load(file: File, passphrase: String? = null, password: String? = null) {
        scope.launch {
            busy = true; problem = null
            try {
                open = withContext(Dispatchers.IO) {
                    if (file.extension.equals("zip", true)) {
                        Open.Archive(file, OpenArchive.open(file, key, passphrase, password))
                    } else {
                        val bytes = file.readBytes()
                        Open.Core(file, Format.openCore(bytes, key, passphrase, password))
                    }
                }
            } catch (e: ReaderException) {
                if (e.needsKey || e.needsPassphrase || e.needsPassword || e.wrongAccount) {
                    val keyId = runCatching { if (file.extension.equals("zip", true)) java.util.zip.ZipFile(file).use { Format.archiveInfo(it).keyId } else Format.info(file.readBytes()).keyId }.getOrNull()
                    open = Open.Locked(file, keyId, e.message ?: "", e.needsPassphrase, e.needsPassword)
                    problem = if (e.wrongAccount || (e.needsPassword && password != null)) e.message else null
                } else { open = Open.Nothing; problem = e.message }
            } catch (e: Exception) {
                open = Open.Nothing; problem = "That file could not be read: ${e.message}"
            }
            busy = false
        }
    }

    // A file given on the command line (or "Open with") opens straight away.
    androidx.compose.runtime.LaunchedEffect(initial) { initial?.let { load(it) } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Ember Backup Reader", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val current = when (val o = open) { is Open.Locked -> o.file; is Open.Core -> o.file; is Open.Archive -> o.file; else -> null }
            if (current != null) Text(current.name, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(end = 16.dp))
            OutlinedButton(onClick = { pickFile("Open an Ember backup", true)?.let { (open as? Open.Archive)?.archive?.close(); load(it) } }) { Text("Open a backup…", color = Color.White) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val o = open) {
                Open.Nothing -> Welcome(problem, busy) { f -> load(f) }
                is Open.Locked -> Unlock(o, key, busy, problem, onKey = { k -> key = k; problem = null; load(o.file) }, onPassphrase = { p -> load(o.file, p) }, onPassword = { p -> load(o.file, null, p) })
                is Open.Core -> CoreView(o)
                is Open.Archive -> ArchiveView(o.archive)
            }
            if (busy) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun Welcome(problem: String?, busy: Boolean, onFile: (File) -> Unit) {
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Read an Ember backup on this computer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Ember seals every backup with a key that belongs to your account, so only you can open yours. This program has no key of its own: it asks your Hearth server for yours " +
                "after you sign in (or you paste the key shown in Ember under Backup and restore). Nothing is kept afterwards.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 640.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(enabled = !busy, onClick = { pickFile("Open an Ember backup", true)?.let(onFile) }) { Text("Open a backup…") }
        Spacer(Modifier.height(8.dp))
        Text("A backup is ember-backup-….embkup (your key and settings) or ember-archive-….zip (your chats and files).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (problem != null) { Spacer(Modifier.height(16.dp)); Text(problem, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun Unlock(o: Open.Locked, key: AccountKey?, busy: Boolean, problem: String?, onKey: (AccountKey) -> Unit, onPassphrase: (String) -> Unit, onPassword: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(if (o.askPassphrase) 2 else if (o.askPassword) 3 else 0) }
    var accountPassword by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(problem) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 560.dp).padding(24.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This backup is sealed", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    if (o.askPassphrase) "It was made by an older Ember with a passphrase." else "Only the account that made it can open it" + (o.keyId?.let { " (key $it)." } ?: "."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Sign in") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Backup key") })
                    Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Old passphrase") })
                    Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("Password") })
                }
                when (tab) {
                    0 -> {
                        OutlinedTextField(server, { server = it }, singleLine = true, label = { Text("Your Hearth server") }, placeholder = { Text("chat.example.com") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(user, { user = it }, singleLine = true, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Text("This signs in to your server once to ask for your backup key. The password is not stored.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(enabled = !working && !busy, onClick = {
                            scope.launch {
                                working = true; error = null
                                runCatching { withContext(Dispatchers.IO) { Account.fetchKey(server, user, pass) } }.onSuccess { onKey(it) }.onFailure { error = it.message }
                                working = false
                            }
                        }) { Text(if (working) "Signing in…" else "Sign in and open") }
                    }
                    1 -> {
                        OutlinedTextField(recovery, { recovery = it }, singleLine = true, label = { Text("Backup key") }, placeholder = { Text("shown in Ember: Settings, Backup and restore, Backup key") }, modifier = Modifier.fillMaxWidth())
                        Text("Use this when your server is not reachable. Anyone with the key and the file can read the backup, so keep the key private.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(enabled = recovery.isNotBlank() && !busy, onClick = { runCatching { AccountKey.parse(recovery) }.onSuccess { error = null; onKey(it) }.onFailure { error = it.message } }) { Text("Open") }
                    }
                    3 -> {
                        OutlinedTextField(accountPassword, { accountPassword = it }, singleLine = true, label = { Text("Your account password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Text("Newer backups are sealed under your Hearth account password. It is used here only to open this file: nothing is sent anywhere.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(enabled = accountPassword.isNotEmpty() && !busy, onClick = { onPassword(accountPassword) }) { Text("Open") }
                    }
                    else -> {
                        OutlinedTextField(passphrase, { passphrase = it }, singleLine = true, label = { Text("Passphrase") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        Button(enabled = passphrase.isNotEmpty() && !busy, onClick = { onPassphrase(passphrase) }) { Text("Open") }
                    }
                }
                (error ?: problem)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (key != null && problem == null) Text("Using key ${key.id}.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CoreView(o: Open.Core) {
    val data = remember(o) { Json.parseToJsonElement(o.content).jsonObject }
    val settings = data["settings"] as? JsonObject
    val stickers = (data["stickers"] as? JsonArray)?.size ?: 0
    val gifs = (data["gifs"] as? JsonArray)?.size ?: 0
    val secret = data["e2eeSecret"]?.jsonPrimitive?.contentOrNull.orEmpty()
    var shown by remember(o) { mutableStateOf(false) }
    var saved by remember(o) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Opened", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Made with ${data["createdWith"]?.jsonPrimitive?.contentOrNull ?: "Ember"} for ${data["userId"]?.jsonPrimitive?.contentOrNull ?: "an account"} on ${data["server"]?.jsonPrimitive?.contentOrNull ?: "a server"}.")
        HorizontalDivider()
        Text("Encryption key for your encrypted chats: " + if (secret.isEmpty()) "not in this backup" else "in this backup")
        if (secret.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") }
                OutlinedButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(secret), null); saved = "Copied the encryption key." }) { Text("Copy") }
            }
            if (shown) SelectionContainer { Text(secret, fontFamily = FontFamily.Monospace) }
        }
        Text("Settings: ${settings?.size ?: 0} saved.   Stickers: $stickers.   GIFs: $gifs.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                pickFile("Save the opened backup as JSON", false, o.file.nameWithoutExtension + ".json")?.let { it.writeText(o.content); saved = "Saved ${it.name}. It holds your encryption key: keep it private." }
            }) { Text("Save as JSON…") }
            if (stickers + gifs > 0) OutlinedButton(onClick = {
                pickFolder("Save stickers and GIFs into")?.let { dir ->
                    var n = 0
                    for ((name, arr) in listOf("sticker" to data["stickers"], "gif" to data["gifs"])) (arr as? JsonArray)?.forEachIndexed { i, e ->
                        val bytes = Base64.getDecoder().decode(e.jsonPrimitive.content)
                        val ext = if (bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte()) "gif" else if (bytes.size > 12 && String(bytes, 8, 4) == "WEBP") "webp" else "png"
                        File(dir, "$name-${i + 1}.$ext").writeBytes(bytes); n++
                    }
                    saved = "Saved $n files into ${dir.name}."
                }
            }) { Text("Save stickers and GIFs…") }
        }
        saved?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        Text("To read your conversations, open the ember-archive-….zip next to this file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ArchiveView(archive: OpenArchive) {
    var selected by remember(archive) { mutableStateOf(archive.chats.firstOrNull()) }
    var chatSearch by remember(archive) { mutableStateOf("") }
    var messageSearch by remember(archive) { mutableStateOf("") }
    val chats = archive.chats.filter { it.name.contains(chatSearch, true) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
            OutlinedTextField(chatSearch, { chatSearch = it }, singleLine = true, placeholder = { Text("Find a chat") }, modifier = Modifier.fillMaxWidth().padding(10.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(chats) { c ->
                    Column(Modifier.fillMaxWidth().background(if (c === selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).clickable { selected = c }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${c.messages.size} messages" + (if (c.encrypted) ", end-to-end encrypted" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (archive.skipped.isNotEmpty()) item {
                    Text("Left out: " + archive.skipped.joinToString("; "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
            if (archive.madeAt > 0) Text("Archive made ${whenOf(archive.madeAt)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
        val chat = selected
        if (chat == null) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { Text("This archive has no chats.") }
        else Conversation(archive, chat, messageSearch) { messageSearch = it }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Conversation(archive: OpenArchive, chat: Chat, search: String, onSearch: (String) -> Unit) {
    val messages = remember(chat, search) { if (search.isBlank()) chat.messages else chat.messages.filter { it.text.contains(search, true) || it.from.contains(search, true) || it.attachments.any { a -> a.filename.contains(search, true) } } }
    var note by remember(chat) { mutableStateOf<String?>(null) }
    Column(Modifier.weight(1f).fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(chat.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (chat.members.isNotEmpty()) Text(chat.members.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            OutlinedTextField(search, onSearch, singleLine = true, placeholder = { Text("Search this chat") }, modifier = Modifier.width(240.dp))
            OutlinedButton(onClick = {
                pickFile("Save this chat as text", false, chat.name.replace(Regex("[^A-Za-z0-9 _-]"), "_") + ".txt")?.let { it.writeText(asText(chat)); note = "Saved ${it.name}." }
            }) { Text("Save as text…") }
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp)) }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { m -> Bubble(archive, m) { note = it } }
            if (messages.isEmpty()) item { Text("Nothing found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Bubble(archive: OpenArchive, m: Message, onNote: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.from, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(whenOf(m.at) + (if (m.editedAt != null) " (edited)" else ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (m.deleted) Text("This message was deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (m.text.isNotEmpty()) SelectionContainer { Text(m.text) }
        for (a in m.attachments) Attachment(archive, a, onNote)
    }
}

@Composable
private fun Attachment(archive: OpenArchive, a: org.hearth.ember.reader.Attachment, onNote: (String) -> Unit) {
    val bytes = remember(a.id) { runCatching { archive.media(a.id) }.getOrNull() }
    val image = remember(a.id, bytes) {
        if (bytes != null && a.mime.startsWith("image/")) runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() else null
    }
    Column(Modifier.padding(top = 8.dp)) {
        if (image != null) androidx.compose.foundation.Image(image, a.filename, Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(10.dp)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${a.filename}  ·  ${sizeOf(a.size)}", style = MaterialTheme.typography.bodySmall)
            if (bytes != null) TextButton(onClick = { pickFile("Save ${a.filename}", false, a.filename)?.let { it.writeBytes(bytes); onNote("Saved ${it.name}.") } }) { Text("Save…") }
            else Text("  (not in this archive)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A whole chat as plain text, oldest first. */
internal fun asText(chat: Chat): String = buildString {
    appendLine(chat.name)
    if (chat.members.isNotEmpty()) appendLine("With: " + chat.members.joinToString(", "))
    appendLine()
    for (m in chat.messages) {
        append("[").append(whenOf(m.at)).append("] ").append(m.from).append(": ")
        appendLine(if (m.deleted) "(deleted)" else m.text)
        for (a in m.attachments) appendLine("    attachment: ${a.filename} (${sizeOf(a.size)})")
    }
}
