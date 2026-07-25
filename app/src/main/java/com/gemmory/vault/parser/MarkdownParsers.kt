package com.gemmory.vault.parser

data class ParsedFrontmatter(
    val fields: Map<String, List<String>>,
    val body: String,
)

data class WikiLinkToken(
    val rawTarget: String,
    val label: String?,
    val start: Int,
    val end: Int,
)

object MarkdownFrontmatterParser {
    fun parse(markdown: String): ParsedFrontmatter {
        if (!markdown.startsWith("---\n")) return ParsedFrontmatter(emptyMap(), markdown)
        val end = markdown.indexOf("\n---", startIndex = 4)
        if (end < 0) return ParsedFrontmatter(emptyMap(), markdown)

        val block = markdown.substring(4, end).lineSequence().toList()
        val fields = linkedMapOf<String, MutableList<String>>()
        var activeKey: String? = null
        for (line in block) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("- ") && activeKey != null ->
                    fields.getOrPut(activeKey) { mutableListOf() }.add(trimmed.removePrefix("- ").trim('"'))

                ":" in trimmed -> {
                    val key = trimmed.substringBefore(":").trim()
                    val value = trimmed.substringAfter(":").trim()
                    activeKey = key
                    if (value.isNotEmpty()) {
                        fields.getOrPut(key) { mutableListOf() }.add(value.trim('"'))
                    } else {
                        fields.getOrPut(key) { mutableListOf() }
                    }
                }
            }
        }
        val bodyStart = (end + "\n---".length).let { if (markdown.getOrNull(it) == '\n') it + 1 else it }
        return ParsedFrontmatter(fields, markdown.substring(bodyStart))
    }
}

object WikiLinkParser {
    private val pattern = Regex("""\[\[([^\]|]+)(?:\|([^\]]+))?]]""")

    fun parse(markdown: String): List<WikiLinkToken> =
        pattern.findAll(markdown).map { match ->
            WikiLinkToken(
                rawTarget = match.groupValues[1].trim(),
                label = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.trim(),
                start = match.range.first,
                end = match.range.last + 1,
            )
        }.toList()
}
