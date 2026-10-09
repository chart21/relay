package dev.relay.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/** An assistant reply: prose through the markdown renderer, tables and code blocks drawn here so they scroll sideways at phone width. */
@Composable
fun ReplyMarkdown(md: String, modifier: Modifier = Modifier, query: String = "") {
    val mark = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val blocks = remember(md) { MdBlocks.split(md) }
    SelectionContainer {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Prose -> if (ChatSearch.ranges(MdBlocks.plain(b.md), query).isNotEmpty()) Text(ChatSearch.highlight(MdBlocks.plain(b.md), query, mark), style = ReplyStyle) else Markdown(b.md, typography = markdownTypography(paragraph = ReplyStyle, bullet = ReplyStyle, ordered = ReplyStyle, list = ReplyStyle,
                        text = ReplyStyle, quote = ReplyStyle.copy(fontStyle = FontStyle.Italic)))
                    is MdBlock.Code -> CodeBlock(b, query)
                    is MdBlock.Table -> MdTable(b, query)
                }
            }
        }
    }
}

@Composable
fun CodeBlock(b: MdBlock.Code, query: String = "") {
    val cs = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (b.lang.isNotBlank()) Text(b.lang, style = MaterialTheme.typography.labelSmall, color = cs.outline)
            Text(ChatSearch.highlight(b.body, query, cs.primary.copy(alpha = 0.3f)), Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp,
                softWrap = false, color = cs.onSurface)
        }
    }
}

@Composable
fun MdTable(t: MdBlock.Table, query: String = "") {
    val cs = MaterialTheme.colorScheme
    val widths = remember(t) { MdBlocks.colChars(t).map { (it * 7.2f + 22f).dp } }
    val line = cs.outlineVariant
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).drawBehind {
        drawRect(line, style = androidx.compose.ui.graphics.drawscope.Stroke(2f)) // thin outer border
    }.horizontalScroll(rememberScrollState())) {
        @Composable fun row(cells: List<String>, header: Boolean, odd: Boolean) {
            Row(Modifier.height(IntrinsicSize.Min).background(if (header) cs.surfaceContainerHigh else if (odd) cs.surfaceContainerLow else cs.background)
                .drawBehind { drawLine(line, Offset(0f, size.height), Offset(size.width, size.height), 1f) }) {
                cells.forEachIndexed { c, cell ->
                    Box(Modifier.width(widths[c]).fillMaxHeight().drawBehind { if (c < cells.lastIndex) drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), 1f) }
                        .padding(horizontal = 8.dp, vertical = 6.dp), contentAlignment = if (t.right[c]) Alignment.TopEnd else Alignment.TopStart) {
                        Text(ChatSearch.highlight(MdBlocks.plain(cell), query, cs.primary.copy(alpha = 0.3f)), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                            textAlign = if (t.right[c]) TextAlign.End else TextAlign.Start, color = cs.onSurface)
                    }
                }
            }
        }
        row(t.header, true, false)
        t.rows.forEachIndexed { i, r -> row(r, false, i % 2 == 1) }
    }
}
