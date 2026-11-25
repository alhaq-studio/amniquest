package com.alhaq.amniquest.app.screens.etc

import com.alhaq.amniquest.data.json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMakerTest {

    @Test
    fun parseMarkdown_parsesAllSupportedComponentTypes() {
        val markdown = """
            # Main Title
            ### Sub Heading

            First paragraph line
            Second paragraph line

            - First bullet
            * Second bullet

            - [ ] Unchecked task
            - [X] Checked task

            > Stay consistent every day

            ![Desk Photo](https://example.com/desk.jpg)

            [Open Docs](https://example.com/docs)

            ```
            val x = 42
            println(x)
            ```

            | Col A | Col B |
            | :--- | ---: |
            | v1 | v2 |
            | short |
            | a | b | extra |
        """.trimIndent()

        val components = parseMarkdown(markdown)
        assertEquals(12, components.size)

        assertEquals(ComponentType.HEADER, components[0].type)
        assertEquals(1, components[0].level)
        assertEquals("Main Title", components[0].content)

        assertEquals(ComponentType.HEADER, components[1].type)
        assertEquals(3, components[1].level)
        assertEquals("Sub Heading", components[1].content)

        assertEquals(ComponentType.TEXT, components[2].type)
        assertEquals("First paragraph line\nSecond paragraph line", components[2].content)

        assertEquals(ComponentType.LIST, components[3].type)
        assertEquals("First bullet", components[3].content)

        assertEquals(ComponentType.LIST, components[4].type)
        assertEquals("Second bullet", components[4].content)

        assertEquals(ComponentType.CHECKBOX, components[5].type)
        assertEquals("todo|Unchecked task", components[5].content)

        assertEquals(ComponentType.CHECKBOX, components[6].type)
        assertEquals("done|Checked task", components[6].content)

        assertEquals(ComponentType.QUOTE, components[7].type)
        assertEquals("Stay consistent every day", components[7].content)

        assertEquals(ComponentType.IMAGE, components[8].type)
        assertEquals("Desk Photo|https://example.com/desk.jpg", components[8].content)

        assertEquals(ComponentType.LINK, components[9].type)
        assertEquals("Open Docs|https://example.com/docs", components[9].content)

        assertEquals(ComponentType.CODE, components[10].type)
        assertEquals("val x = 42\nprintln(x)", components[10].content)

        assertEquals(ComponentType.TABLE, components[11].type)
        val table = json.decodeFromString<TableData>(components[11].content)
        assertEquals(listOf("Col A", "Col B"), table.headers)
        assertEquals(listOf("v1", "v2"), table.rows[0])
        assertEquals(listOf("short", ""), table.rows[1])
        assertEquals(listOf("a", "b"), table.rows[2])

        val barePipeTable = parseMarkdown("H1 | H2\n---|---\n1 | 2")
        assertEquals(1, barePipeTable.size)
        assertEquals(ComponentType.TABLE, barePipeTable[0].type)
        val parsedBare = json.decodeFromString<TableData>(barePipeTable[0].content)
        assertEquals(listOf("H1", "H2"), parsedBare.headers)
        assertEquals(listOf(listOf("1", "2")), parsedBare.rows)
    }

    @Test
    fun parseMarkdown_handlesUnclosedCodeBlockSafely() {
        val components = parseMarkdown("```\nunclosed code")
        assertEquals(1, components.size)
        assertEquals(ComponentType.CODE, components[0].type)
        assertEquals("unclosed code", components[0].content)
    }

    @Test
    fun generateComponentMarkdown_and_generateMarkdown_roundTripCleanlyAndHandleEdgeCases() {
        val tableJson = json.encodeToString(
            TableData(
                headers = listOf("Habit", "Points"),
                rows = listOf(listOf("Read", "10"), listOf("Run", "20"))
            )
        )
        val components = listOf(
            MdComponent(type = ComponentType.HEADER, content = "Daily Plan", level = 2),
            MdComponent(type = ComponentType.TEXT, content = "Execute focused blocks."),
            MdComponent(type = ComponentType.LIST, content = "Hydrate"),
            MdComponent(type = ComponentType.CHECKBOX, content = "done|Warm up"),
            MdComponent(type = ComponentType.CHECKBOX, content = "todo|Cool down"),
            MdComponent(type = ComponentType.QUOTE, content = "Small steps"),
            MdComponent(type = ComponentType.CODE, content = "echo ok"),
            MdComponent(type = ComponentType.LINK, content = "Guide | https://amni.dev"),
            MdComponent(type = ComponentType.IMAGE, content = "Badge | https://amni.dev/b.png"),
            MdComponent(type = ComponentType.TABLE, content = tableJson)
        )

        val generated = generateMarkdown(components)
        assertTrue(generated.contains("## Daily Plan"))
        assertTrue(generated.contains("Execute focused blocks."))
        assertTrue(generated.contains("- Hydrate"))
        assertTrue(generated.contains("- [x] Warm up"))
        assertTrue(generated.contains("- [ ] Cool down"))
        assertTrue(generated.contains("> Small steps"))
        assertTrue(generated.contains("```\necho ok\n```"))
        assertTrue(generated.contains("[Guide](https://amni.dev)"))
        assertTrue(generated.contains("![Badge](https://amni.dev/b.png)"))
        assertTrue(generated.contains("Habit|Points\n---|---\nRead|10\nRun|20"))

        // Malformed table JSON returns empty string without throwing
        assertEquals("", generateComponentMarkdown(MdComponent(type = ComponentType.TABLE, content = "{bad-json")))
        // Unsplit link/image/checkbox fallback
        assertEquals("raw-link", generateComponentMarkdown(MdComponent(type = ComponentType.LINK, content = "raw-link")))
        assertEquals("raw-img", generateComponentMarkdown(MdComponent(type = ComponentType.IMAGE, content = "raw-img")))
        assertEquals("- [ ] plain", generateComponentMarkdown(MdComponent(type = ComponentType.CHECKBOX, content = "plain")))
    }
}
