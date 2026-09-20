// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.about

/** One displayable block of a license document — see [LicenseDocument.parse]. */
sealed interface LicenseBlock {
    data class Heading(val level: Int, val text: String) : LicenseBlock

    data class Paragraph(val text: String) : LicenseBlock

    /** A markdown table row. [headers] are the table's header cells, used to label [cells] on a narrow screen. */
    data class TableRow(val headers: List<String>, val cells: List<String>) : LicenseBlock
}

/**
 * Turns the license texts bundled in the APK (`LICENSE`, `THIRD_PARTY_NOTICES.md`, `LICENSES/`, see
 * [LicenseAssets]) into blocks that read well on a phone. No iOS counterpart: iOS shows plist panes
 * from `Settings.bundle`, which Android has no equivalent of.
 *
 * The source files are hard-wrapped at 72–100 columns, far wider than a phone screen, so line breaks
 * inside a paragraph are joined instead of kept. A new paragraph starts at a blank line or at a list
 * item (`* `, `- `, `1. `, `a) `, `(a) `), which keeps license bullet lists apart.
 *
 * Markdown support is deliberately minimal and covers what those files use: `#` headings, `|`
 * tables, and fenced blocks, whose content is reflowed as plain text because here they only wrap
 * verbatim license texts. Inline links, autolinks, bold and code are reduced to plain text.
 */
object LicenseDocument {
    private val listItem = Regex("""^([*\-•]|\d{1,2}\.|\(?[a-z0-9]{1,3}\))\s""")
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val tableSeparator = Regex("""^\|?[\s:|-]*-{3,}[\s:|-]*$""")
    private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val autolink = Regex("""<(https?://[^>\s]+)>""")

    fun parse(text: String, isMarkdown: Boolean): List<LicenseBlock> {
        val blocks = mutableListOf<LicenseBlock>()
        val paragraph = mutableListOf<String>()
        var inFence = false
        var tableHeaders: List<String>? = null

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val joined = paragraph.joinToString(" ")
            blocks += LicenseBlock.Paragraph(if (isMarkdown && !inFence) inline(joined) else joined)
            paragraph.clear()
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()

            if (isMarkdown && line.startsWith("```")) {
                flushParagraph()
                tableHeaders = null
                inFence = !inFence
                continue
            }

            if (isMarkdown && !inFence) {
                if (line.startsWith("|")) {
                    flushParagraph()
                    val headers = tableHeaders
                    val cells = line.trim('|').split('|').map { inline(it.trim()) }
                    when {
                        headers == null -> tableHeaders = cells
                        tableSeparator.matches(line) -> Unit
                        else -> blocks += LicenseBlock.TableRow(headers, cells)
                    }
                    continue
                }
                tableHeaders = null

                val headingMatch = heading.matchEntire(line)
                if (headingMatch != null) {
                    flushParagraph()
                    blocks += LicenseBlock.Heading(headingMatch.groupValues[1].length, inline(headingMatch.groupValues[2]))
                    continue
                }
            }

            if (line.isEmpty()) {
                flushParagraph()
                continue
            }
            if (listItem.containsMatchIn(line)) flushParagraph()
            paragraph += line
        }
        flushParagraph()
        return blocks
    }

    /** Reduces inline markdown to plain text: `[text](url)` → text, `<https://…>` → the URL, `<br>` → space, bold and code markers dropped. */
    internal fun inline(text: String): String =
        text.replace(link, "\$1")
            .replace(autolink, "\$1")
            .replace("<br>", " ")
            .replace("**", "")
            .replace("`", "")
}
