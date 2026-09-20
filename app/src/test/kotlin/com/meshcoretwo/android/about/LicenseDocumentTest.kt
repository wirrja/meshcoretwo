// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.about

import com.meshcoretwo.android.about.LicenseBlock.Heading
import com.meshcoretwo.android.about.LicenseBlock.Paragraph
import com.meshcoretwo.android.about.LicenseBlock.TableRow
import org.junit.Assert.assertEquals
import org.junit.Test

class LicenseDocumentTest {
    @Test
    fun `hard-wrapped lines are joined and blank lines separate paragraphs`() {
        val text = """
            Permission is hereby granted, free of charge,
              to any person obtaining a copy.

            THE SOFTWARE IS PROVIDED "AS IS".
        """.trimIndent()

        assertEquals(
            listOf(
                Paragraph("Permission is hereby granted, free of charge, to any person obtaining a copy."),
                Paragraph("THE SOFTWARE IS PROVIDED \"AS IS\"."),
            ),
            LicenseDocument.parse(text, isMarkdown = false),
        )
    }

    @Test
    fun `list items start a new paragraph without a blank line`() {
        val text = """
            met:
            * Redistributions of source code must retain the above
              copyright notice.
            - Second item
            1. Numbered
            a) Lettered
            (b) Parenthesized
        """.trimIndent()

        assertEquals(
            listOf(
                Paragraph("met:"),
                Paragraph("* Redistributions of source code must retain the above copyright notice."),
                Paragraph("- Second item"),
                Paragraph("1. Numbered"),
                Paragraph("a) Lettered"),
                Paragraph("(b) Parenthesized"),
            ),
            LicenseDocument.parse(text, isMarkdown = false),
        )
    }

    @Test
    fun `markdown headings keep their level and lose link syntax`() {
        val text = """
            # Third-party notices
            ### [Maplibre Native](https://github.com/maplibre/maplibre-native/)
        """.trimIndent()

        assertEquals(
            listOf(Heading(1, "Third-party notices"), Heading(3, "Maplibre Native")),
            LicenseDocument.parse(text, isMarkdown = true),
        )
    }

    @Test
    fun `fenced content is reflowed as plain text and not interpreted`() {
        val text = """
            ```
            # not a heading
            | not | a table |
            see [a](b)
            ```
            after
        """.trimIndent()

        assertEquals(
            listOf(Paragraph("# not a heading | not | a table | see [a](b)"), Paragraph("after")),
            LicenseDocument.parse(text, isMarkdown = true),
        )
    }

    @Test
    fun `tables become labelled rows without the separator`() {
        val text = """
            | Component | License |
            |---|---|
            | [OkHttp](https://square.github.io/okhttp/) | Apache-2.0 |
            | `okio` | **MIT** |
        """.trimIndent()

        val headers = listOf("Component", "License")
        assertEquals(
            listOf(TableRow(headers, listOf("OkHttp", "Apache-2.0")), TableRow(headers, listOf("okio", "MIT"))),
            LicenseDocument.parse(text, isMarkdown = true),
        )
    }

    @Test
    fun `a paragraph between tables resets the headers`() {
        val text = """
            | A |
            |---|
            | 1 |
            Between.
            | B |
            |---|
            | 2 |
        """.trimIndent()

        assertEquals(
            listOf(TableRow(listOf("A"), listOf("1")), Paragraph("Between."), TableRow(listOf("B"), listOf("2"))),
            LicenseDocument.parse(text, isMarkdown = true),
        )
    }

    @Test
    fun `inline markdown is reduced to plain text`() {
        assertEquals(
            "See LICENSE and https://www.gnu.org/licenses/ for GPL-3.0-only here",
            LicenseDocument.inline("See [LICENSE](LICENSE) and <https://www.gnu.org/licenses/> for `GPL-3.0-only`<br>**here**"),
        )
    }

    @Test
    fun `plain text keeps markdown-looking characters`() {
        assertEquals(
            listOf(Paragraph("# 1 | 2 [a](b)")),
            LicenseDocument.parse("# 1 | 2 [a](b)", isMarkdown = false),
        )
    }
}
