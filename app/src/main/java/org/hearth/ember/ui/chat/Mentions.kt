package org.hearth.ember.ui.chat

import org.hearth.ember.data.User

/** Mentions: typing @ in a group offers its members, and a name after @ in a message is shown highlighted. The web app follows the same rules. */
object Mentions {
    /** Where the @ is in the text, and what has been typed after it. */
    data class Trigger(val start: Int, val query: String)

    /** A stretch of a message: plain words, or a mention of [person]. */
    data class Piece(val text: String, val person: User? = null)

    private const val QUERY_MAX = 30
    private val opening = Regex("[\\s(\\[{\"‘“]")
    private val splitters = Regex("[\\s._-]+")

    private fun isWord(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')

    private fun label(u: User) = u.displayName.ifBlank { u.username }

    /**
     * Whether the caret is just after an @ that starts a mention. The @ must stand at the start of the text or after a space or an opening
     * bracket, so an email address (anna@example.com) never opens the list. The name typed after it has no space in it yet.
     */
    fun trigger(text: String, caret: Int): Trigger? {
        val before = text.substring(0, caret.coerceIn(0, text.length))
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        val query = before.substring(at + 1)
        if (query.length > QUERY_MAX || query.any { it.isWhitespace() }) return null
        if (at > 0 && !opening.matches(before[at - 1].toString())) return null
        return Trigger(at, query)
    }

    private fun words(name: String) = name.lowercase().split(splitters).filter { it.isNotEmpty() }

    /** The members that fit what was typed: names that begin with it first, then names with a word that begins with it. Never yourself. */
    fun matches(members: List<User>, query: String, meId: String, limit: Int = 6): List<User> {
        val q = query.lowercase()
        return members.filter { it.id != meId }.mapNotNull { p ->
            val name = label(p).lowercase()
            val user = p.username.lowercase()
            val rank = when {
                q.isEmpty() -> 2
                name.startsWith(q) || user.startsWith(q) -> 0
                words(label(p)).any { it.startsWith(q) } || words(p.username).any { it.startsWith(q) } -> 1
                else -> return@mapNotNull null
            }
            rank to p
        }.sortedWith(compareBy({ it.first }, { label(it.second).lowercase() })).take(limit).map { it.second }
    }

    /** The text with the typed @query swapped for the chosen name and a space, and where the caret goes after it. */
    fun insert(text: String, trigger: Trigger, caret: Int, person: User): Pair<String, Int> {
        val insert = "@" + label(person) + " "
        return (text.substring(0, trigger.start) + insert + text.substring(caret.coerceIn(0, text.length))) to (trigger.start + insert.length)
    }

    /** Splits a message into plain pieces and mentions of members (@Their Name or @theirname), longest names first so "Ann Lee" beats "Ann". */
    fun split(text: String, members: List<User>): List<Piece> {
        if (!text.contains('@') || members.isEmpty()) return listOf(Piece(text))
        val names = members.flatMap { p ->
            val out = mutableListOf(label(p).lowercase() to p)
            if (p.username.isNotEmpty() && p.username.lowercase() != label(p).lowercase()) out += p.username.lowercase() to p
            out
        }.filter { it.first.isNotEmpty() }.sortedByDescending { it.first.length }
        val lower = text.lowercase()
        val out = mutableListOf<Piece>()
        val plain = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '@' && (i == 0 || !isWord(text[i - 1]))) {
                val hit = names.firstOrNull { (n, _) -> lower.startsWith(n, i + 1) && !isWord(text.getOrNull(i + 1 + n.length)) }
                if (hit != null) {
                    if (plain.isNotEmpty()) { out += Piece(plain.toString()); plain.clear() }
                    out += Piece(text.substring(i, i + 1 + hit.first.length), hit.second)
                    i += 1 + hit.first.length
                    continue
                }
            }
            plain.append(text[i])
            i++
        }
        if (plain.isNotEmpty()) out += Piece(plain.toString())
        return out
    }
}
