package dev.relay.app

data class Pending(val text: String, val at: Long)

/** Slash commands offered while typing `/`: the user's chips first, then each tool's common built-ins. */
object SlashCatalog {
    private val builtin = mapOf(
        "claude" to listOf("/clear", "/compact", "/context", "/cost", "/usage", "/model", "/effort", "/resume", "/rewind", "/memory",
            "/permissions", "/config", "/status", "/mcp", "/agents", "/hooks", "/init", "/review", "/export", "/doctor", "/help", "/add-dir", "/rename"),
        "codex" to listOf("/model", "/approvals", "/status", "/new", "/compact", "/diff", "/mention", "/init", "/review", "/quit"),
        "agy" to listOf("/model", "/help", "/clear", "/mcp"),
    )
    fun forTool(tool: String, user: List<String>) = (user + builtin[tool].orEmpty()).distinct()
    fun matches(all: List<String>, text: String): List<String> =
        if (!text.startsWith("/") || text.any { it.isWhitespace() }) emptyList()
        else all.filter { it.startsWith(text, ignoreCase = true) && it != text }.take(10)
}

/** A reply split into prose, fenced code and GFM tables (the renderer draws tables and code itself, scrollable). */
sealed interface MdBlock {
    data class Prose(val md: String) : MdBlock
    data class Code(val lang: String, val body: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, val right: List<Boolean>) : MdBlock
}

object MdBlocks {
    private val fence = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([\\w+#.-]*)")
    private val sepRow = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    fun split(md: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val prose = StringBuilder()
        fun flush() { if (prose.isNotBlank()) out.add(MdBlock.Prose(prose.toString().trim('\n'))); prose.clear() }
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.find(line)
            if (f != null) {
                flush()
                val mark = f.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !(lines[i].trim().startsWith(mark) && lines[i].trim().all { it == mark[0] })) body.add(lines[i++])
                i++ // closing fence (a missing one just ends the block at the end of the text)
                out.add(MdBlock.Code(f.groupValues[2], body.joinToString("\n")))
                continue
            }
            if (line.contains('|') && i + 1 < lines.size && lines[i + 1].contains('-') && sepRow.matches(lines[i + 1])) {
                val header = cells(line)
                val seps = cells(lines[i + 1])
                if (header.size >= 2 && seps.size == header.size) {
                    flush()
                    val rows = ArrayList<List<String>>()
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows.add(cells(lines[i]).let { r -> List(header.size) { r.getOrElse(it) { "" } } }); i++ }
                    out.add(MdBlock.Table(header, rows, seps.map { it.endsWith(":") && !it.startsWith(":") }))
                    continue
                }
            }
            prose.append(line).append('\n'); i++
        }
        flush()
        return out
    }

    /** Cells of one table row; `\|` stays inside a cell. */
    fun cells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.drop(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val out = ArrayList<String>(); val cur = StringBuilder()
        var k = 0
        while (k < s.length) {
            if (s[k] == '\\' && k + 1 < s.length && s[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (s[k] == '|') { out.add(cur.toString().trim()); cur.clear() } else cur.append(s[k])
            k++
        }
        out.add(cur.toString().trim())
        return out
    }

    private val link = Regex("!?\\[([^\\]]*)]\\([^)]*\\)")
    private val bold = Regex("(\\*\\*|__)(.+?)\\1")
    private val ital = Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)\\*(?![\\w*])")
    private val code = Regex("`([^`]*)`")
    private val tag = Regex("</?(br|b|i|strong|em|code)\\s*/?>", RegexOption.IGNORE_CASE)

    /** Inline marks removed: a table cell or preview line as plain text. */
    fun plain(s: String): String = s.replace(tag, " ").replace(link, "$1").replace(code, "$1").replace(bold, "$2").replace(ital, "$1").trim()

    /** Sensible column widths in characters (clamped), from the longest word and the longest cell. */
    fun colChars(t: MdBlock.Table): List<Int> = t.header.indices.map { c ->
        val all = listOf(t.header[c]) + t.rows.map { it[c] }
        val longest = all.maxOf { plain(it).length }
        val word = all.maxOf { cell -> plain(cell).split(' ').maxOfOrNull { it.length } ?: 0 }
        longest.coerceAtMost(34).coerceAtLeast(word.coerceAtMost(24)).coerceAtLeast(4)
    }
}

/** Pure helpers for the chat (unit-tested). */
object ChatText {
    enum class Kind { USER, COMMAND, ASSISTANT, OUTPUT, STEPS, EVENT, NOTE }
    data class Row(val id: String, val kind: Kind, val text: String, val tools: List<ToolCall> = emptyList(), val ts: Double = 0.0,
                   val pending: Boolean = false, val lines: Int = 6, val detail: String = "", val status: String = "", val files: List<Attached> = emptyList())

    /** A file this phone (or the share sheet) uploaded to the Desktop, found as a path line in a user message. */
    data class Attached(val path: String, val name: String, val image: Boolean)

    private val uploaded = Regex("^(?:~|/home/[^/\\s]+)/\\.local/share/notifyd/uploads/\\S.*$")

    /** A user message split into its text and the uploaded-file paths (one per line) it carries. */
    fun attachments(text: String): Pair<String, List<Attached>> {
        val lines = text.lines()
        val files = lines.map { it.trim() }.filter { uploaded.matches(it) }
        if (files.isEmpty()) return text to emptyList()
        val rest = lines.filterNot { uploaded.matches(it.trim()) }.joinToString("\n").trim()
        return rest to files.map { p -> p.substringAfterLast('/').let { n -> Attached(p, n, Attach.isImage(n, null)) } }
    }

    private fun userRow(id: String, t: String, ts: Double, pending: Boolean = false) =
        attachments(t.trim()).let { (text, files) -> Row(id, Kind.USER, text, ts = ts, pending = pending, files = files) }

    /** The transcript doesn't record `/commands`: show what was sent, in order, as a chip. */
    fun localCommand(t: String, now: Long): TMessage {
        val name = t.substringBefore(' '); val args = t.substringAfter(' ', "")
        return TMessage("local-cmd-$now", "user", "<command-name>$name</command-name><command-args>$args</command-args>", emptyList(), now / 1000.0)
    }

    private val dot = RegexOption.DOT_MATCHES_ALL
    private val cmdName = Regex("<command-name>\\s*(.*?)\\s*</command-name>", dot)
    private val cmdArgs = Regex("<command-args>\\s*(.*?)\\s*</command-args>", dot)
    private val stdout = Regex("<local-command-std(?:out|err)>(.*?)</local-command-std(?:out|err)>", dot)
    private val caveat = Regex("<local-command-caveat>.*?</local-command-caveat>", dot)
    private val reminder = Regex("<system-reminder>.*?(</system-reminder>|$)", dot)
    private val bashIn = Regex("<bash-input>(.*?)</bash-input>", dot)
    private val bashOut = Regex("<bash-std(?:out|err)>(.*?)</bash-std(?:out|err)>", dot)
    private val interrupted = Regex("^\\[Request interrupted[^\\]]*]$")
    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

    private fun tagOf(name: String, t: String) = Regex("<$name>(.*?)</$name>", dot).find(t)?.groupValues?.get(1)?.trim().orEmpty()

    fun stripReminders(t: String) = if (t.contains("<system-reminder>")) reminder.replace(t, "").trim() else t

    /** Background task / agent notice (`<task-notification>`), as an event row; null when [t] is not one. */
    private fun notice(id: String, t: String, ts: Double): Row? {
        if (!t.contains("<task-notification>")) return null
        val status = tagOf("status", t).ifEmpty { "completed" }
        val summary = tagOf("summary", t).ifEmpty { "Background task $status" }
        return Row(id, Kind.EVENT, summary, ts = ts, detail = tagOf("result", t), status = status)
    }

    /** One transcript message as chat rows (usually one). Claude logs `/command` runs and `!` shell escapes as tagged user messages. */
    fun rowsOf(m: TMessage): List<Row> {
        val t = stripReminders(m.text)
        if (t.isBlank() && m.tools.isEmpty()) return emptyList()
        notice(m.id, t, m.ts)?.let { return listOf(it) }
        interrupted.find(t.trim())?.let { return listOf(Row(m.id, Kind.NOTE, t.trim().trim('[', ']'), ts = m.ts)) }
        cmdName.find(t)?.let { n ->
            val args = cmdArgs.find(t)?.groupValues?.get(1).orEmpty()
            return listOf(Row(m.id, Kind.COMMAND, (n.groupValues[1].let { if (it.startsWith("/")) it else "/$it" } + " " + args).trim(), ts = m.ts))
        }
        val out = ArrayList<Row>()
        bashIn.find(t)?.let { out.add(Row(m.id, Kind.COMMAND, "! " + it.groupValues[1].trim(), ts = m.ts)) }
        val so = bashOut.findAll(t).joinToString("\n") { it.groupValues[1].trim() }.trim()
        if (so.isNotEmpty()) out.add(Row(m.id + "-out", Kind.OUTPUT, so.replace(ansi, ""), ts = m.ts, lines = 8))
        if (out.isNotEmpty() || t.contains("<bash-stdout>") || t.contains("<bash-stderr>")) return out
        stdout.find(t)?.let { o -> return listOfNotNull(o.groupValues[1].replace(ansi, "").trim().takeIf { it.isNotEmpty() }?.let { Row(m.id, Kind.OUTPUT, it, ts = m.ts) }) }
        if (caveat.containsMatchIn(t) && caveat.replace(t, "").isBlank()) return emptyList()
        return listOf(when (m.role) {
            "user" -> userRow(m.id, t, m.ts)
            "assistant" -> Row(m.id, Kind.ASSISTANT, t, m.tools, m.ts)
            "system" -> Row(m.id, Kind.NOTE, t.trim(), ts = m.ts)
            else -> Row(m.id, Kind.OUTPUT, "↳ " + t.trim(), ts = m.ts, lines = 1) // tool output: one faint line
        })
    }

    fun row(m: TMessage): Row? = rowsOf(m).firstOrNull()

    /**
     * Newest first (the list is reverse-laid out); pending sends sit at the bottom until the transcript has them.
     * Like the Claude app, tool calls between two texts fold into one STEPS row and tool output is not shown.
     */
    fun rows(msgs: List<TMessage>, pending: List<Pending>): List<Row> {
        val out = ArrayList<Row>()
        fun steps(id: String, tools: List<ToolCall>, ts: Double) {
            val last = out.lastOrNull()
            if (last?.kind == Kind.STEPS) out[out.size - 1] = last.copy(tools = last.tools + tools, ts = ts)
            else out.add(Row("steps-$id", Kind.STEPS, "", tools, ts))
        }
        for (m in msgs) {
            if (m.role == "tool") { if (out.lastOrNull()?.kind != Kind.STEPS) steps(m.id, emptyList(), m.ts); continue }
            for (r in rowsOf(m)) {
                if (r.kind == Kind.ASSISTANT) {
                    if (r.text.isNotBlank()) out.add(r.copy(tools = emptyList()))
                    if (r.tools.isNotEmpty()) steps(m.id, r.tools, m.ts)
                } else out.add(r)
            }
        }
        return pending.reversed().mapIndexed { i, p -> userRow("pending-$i-${p.at}", p.text, 0.0, pending = true) } + out.asReversed()
    }

    /** "Ran 3 commands, read 2 files" for a folded STEPS row. */
    fun stepsLabel(tools: List<ToolCall>): String {
        if (tools.isEmpty()) return "Working"
        val counts = LinkedHashMap<String, Int>()
        tools.forEach { counts.merge(toolVerb(it.name), 1, Int::plus) }
        return counts.entries.joinToString(", ") { (verb, n) ->
            val (one, many) = verbNouns.getValue(verb)
            "$verb $n ${if (n == 1) one else many}"
        }.replaceFirstChar { it.uppercase() }
    }

    private val verbNouns = mapOf("ran" to ("command" to "commands"), "read" to ("file" to "files"), "edited" to ("file" to "files"),
        "searched" to ("time" to "times"), "fetched" to ("page" to "pages"), "started" to ("agent" to "agents"),
        "updated" to ("plan" to "plans"), "used" to ("tool" to "tools"))

    private fun toolVerb(name: String) = when (name.lowercase()) {
        "bash", "shell", "exec_command", "local_shell", "run_command" -> "ran"
        "read", "notebookread", "view_file" -> "read"
        "edit", "multiedit", "write", "notebookedit", "apply_patch", "write_to_file", "replace_file_content" -> "edited"
        "grep", "glob", "ls", "search", "find", "grep_search" -> "searched"
        "webfetch", "websearch", "web_search", "read_url_content" -> "fetched"
        "task", "agent" -> "started"
        "todowrite", "update_plan" -> "updated"
        else -> "used"
    }

    fun delivered(p: Pending, msgs: List<TMessage>): Boolean =
        msgs.asReversed().take(30).any { it.role == "user" && it.text.trim().let { t -> t == p.text || t.contains(p.text) } }

    /** Last [n] lines of a captured screen, trailing blank lines dropped. */
    fun tail(screen: String, n: Int): String = screen.trimEnd().lines().takeLast(n).joinToString("\n") { it.trimEnd() }

    fun toolIcon(name: String) = when (name.lowercase()) {
        "bash", "shell", "exec_command", "local_shell", "run_command" -> "❯"
        "read", "notebookread", "view_file" -> "📄"
        "edit", "multiedit", "write", "notebookedit", "apply_patch", "write_to_file", "replace_file_content" -> "✎"
        "grep", "glob", "ls", "search", "find", "grep_search" -> "🔍"
        "webfetch", "websearch", "web_search", "read_url_content" -> "🌐"
        "task", "agent" -> "🤖"
        "todowrite", "update_plan" -> "☑"
        else -> "•"
    }

    fun statusIcon(status: String) = when (status.lowercase()) {
        "completed", "done", "success" -> "✓"
        "failed", "error" -> "✗"
        "killed", "stopped", "cancelled" -> "■"
        else -> "●"
    }

    /** First [max] lines of a long text and the number of lines left out (0 = nothing to fold). */
    fun fold(text: String, max: Int = 12): Pair<String, Int> {
        val l = text.lines()
        return if (l.size <= max) text to 0 else l.take(max).joinToString("\n").trimEnd() to (l.size - max)
    }

    private val rule = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")
    private val lead = Regex("^\\s*(#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+[.)]\\s+)")

    /** The overview's last-message preview as plain text: markdown marks, table pipes, links and tags removed. */
    fun preview(text: String): String {
        val t = stripReminders(text)
        notice("", t, 0.0)?.let { return it.text }
        return t.lines().asSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") && !it.startsWith("~~~") && !rule.matches(it) }
            .map { l -> l.replace(lead, "").let { if (it.contains('|')) MdBlocks.cells(it).filter { c -> c.isNotEmpty() }.joinToString(" · ") else it } }
            .map { MdBlocks.plain(it).replace(Regex("</?[a-z-]+>"), "").trim() }
            .filter { it.isNotEmpty() }.joinToString(" ")
    }
}
