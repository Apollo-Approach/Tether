package com.rover.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rover.remote.ui.theme.*

/**
 * Lightweight Markdown renderer for Compose.
 * Supports: headers, bold, italic, inline code, code blocks,
 * bullet/numbered lists, horizontal rules, and blockquotes.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary,
    codeBackground: Color = DarkSurfaceElevated,
    fontSize: Float = 14f
) {
    val blocks = parseMarkdownBlocks(markdown)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Header -> {
                    val (style, weight) = when (block.level) {
                        1 -> (fontSize + 8f) to FontWeight.Bold
                        2 -> (fontSize + 5f) to FontWeight.Bold
                        3 -> (fontSize + 2f) to FontWeight.SemiBold
                        else -> (fontSize + 1f) to FontWeight.Medium
                    }
                    Text(
                        text = buildInlineAnnotatedString(block.content, textColor, fontSize),
                        color = textColor,
                        fontSize = style.sp,
                        fontWeight = weight,
                        modifier = Modifier.padding(top = if (block.level <= 2) 8.dp else 4.dp, bottom = 2.dp)
                    )
                    if (block.level <= 2) {
                        HorizontalDivider(
                            color = DividerColor,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                }

                is MdBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeBackground, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = (fontSize - 1.5f).sp,
                            color = Amber.copy(alpha = 0.9f),
                            lineHeight = (fontSize + 4f).sp
                        )
                    }
                }

                is MdBlock.Blockquote -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(IntrinsicSize.Min)
                                .background(AccentPurple.copy(alpha = 0.6f), RoundedCornerShape(1.5.dp))
                        )
                        // Recursively render blockquote content
                        MarkdownText(
                            markdown = block.content,
                            modifier = Modifier.padding(start = 12.dp),
                            textColor = TextSecondary,
                            codeBackground = codeBackground,
                            fontSize = fontSize
                        )
                    }
                }

                is MdBlock.ListItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = (block.indent * 16).dp)
                    ) {
                        Text(
                            text = block.bullet,
                            color = Amber.copy(alpha = 0.6f),
                            fontSize = fontSize.sp,
                            modifier = Modifier.width(20.dp)
                        )
                        Text(
                            text = buildInlineAnnotatedString(block.content, textColor, fontSize),
                            color = textColor,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 6f).sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.HorizontalRule -> {
                    HorizontalDivider(
                        color = DividerColor,
                        thickness = 1.dp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                is MdBlock.Paragraph -> {
                    if (block.content.isNotBlank()) {
                        Text(
                            text = buildInlineAnnotatedString(block.content, textColor, fontSize),
                            color = textColor,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize + 6f).sp
                        )
                    }
                }
            }
        }
    }
}

// ─── Block-level parsing ───

private sealed class MdBlock {
    data class Header(val level: Int, val content: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class Blockquote(val content: String) : MdBlock()
    data class ListItem(val bullet: String, val content: String, val indent: Int = 0) : MdBlock()
    data object HorizontalRule : MdBlock()
    data class Paragraph(val content: String) : MdBlock()
}

private fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()

        when {
            // Code block
            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.CodeBlock(language, codeLines.joinToString("\n")))
                i++ // skip closing ```
            }

            // Horizontal rule
            trimmed.matches(Regex("^[-*_]{3,}$")) -> {
                blocks.add(MdBlock.HorizontalRule)
                i++
            }

            // Header
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length
                val content = trimmed.drop(level).trimStart()
                blocks.add(MdBlock.Header(level.coerceIn(1, 6), content))
                i++
            }

            // Blockquote
            trimmed.startsWith("> ") || trimmed == ">" -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size) {
                    val ql = lines[i].trimStart()
                    if (ql.startsWith("> ")) {
                        quoteLines.add(ql.removePrefix("> "))
                    } else if (ql == ">") {
                        quoteLines.add("")
                    } else {
                        break
                    }
                    i++
                }
                blocks.add(MdBlock.Blockquote(quoteLines.joinToString("\n")))
            }

            // Unordered list
            trimmed.matches(Regex("^[-*+] .+")) -> {
                val indent = line.length - line.trimStart().length
                val content = trimmed.drop(2)
                blocks.add(MdBlock.ListItem("•", content, indent / 2))
                i++
            }

            // Ordered list
            trimmed.matches(Regex("^\\d+[.)] .+")) -> {
                val num = trimmed.takeWhile { it.isDigit() || it == '.' || it == ')' }
                val content = trimmed.drop(num.length).trimStart()
                val indent = line.length - line.trimStart().length
                blocks.add(MdBlock.ListItem(num, content, indent / 2))
                i++
            }

            // Empty line
            trimmed.isEmpty() -> {
                i++
            }

            // Paragraph (collect consecutive non-empty lines)
            else -> {
                val paraLines = mutableListOf<String>()
                while (i < lines.size) {
                    val pl = lines[i]
                    val plt = pl.trimStart()
                    if (plt.isEmpty() || plt.startsWith("#") || plt.startsWith("```") ||
                        plt.startsWith("> ") || plt.matches(Regex("^[-*+] .+")) ||
                        plt.matches(Regex("^\\d+[.)] .+")) || plt.matches(Regex("^[-*_]{3,}$"))
                    ) break
                    paraLines.add(pl)
                    i++
                }
                blocks.add(MdBlock.Paragraph(paraLines.joinToString(" ")))
            }
        }
    }
    return blocks
}

// ─── Inline parsing (bold, italic, code, links) ───

private fun buildInlineAnnotatedString(
    text: String,
    baseColor: Color,
    fontSize: Float
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold + Italic (***text***)
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Bold (**text**)
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Italic (*text* or _text_)
                (text[i] == '*' || text[i] == '_') && (i == 0 || text[i - 1] == ' ') -> {
                    val delim = text[i]
                    val end = text.indexOf(delim, i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor.copy(alpha = 0.85f))) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Inline code (`code`)
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = DarkSurfaceElevated,
                                color = Amber.copy(alpha = 0.9f),
                                fontSize = (fontSize - 1f).sp
                            )
                        ) {
                            append(" ${text.substring(i + 1, end)} ")
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                // Link [text](url) — render text only, styled
                text.startsWith("[", i) -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    val openParen = if (closeBracket != -1 && closeBracket + 1 < text.length) {
                        if (text[closeBracket + 1] == '(') closeBracket + 1 else -1
                    } else -1
                    val closeParen = if (openParen != -1) text.indexOf(')', openParen + 1) else -1

                    if (closeBracket != -1 && closeParen != -1) {
                        val linkText = text.substring(i + 1, closeBracket)
                        withStyle(SpanStyle(color = AccentBlue, fontWeight = FontWeight.Medium)) {
                            append(linkText)
                        }
                        i = closeParen + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }

                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
