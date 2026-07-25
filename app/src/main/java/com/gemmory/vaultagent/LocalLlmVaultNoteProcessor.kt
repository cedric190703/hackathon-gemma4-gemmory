package com.gemmory.vaultagent

import com.gemmory.core.logging.AppLog
import com.gemmory.inference.EngineController
import com.gemmory.inference.EngineState
import com.gemmory.inference.GenerationEvent
import com.gemmory.inference.GenerationOptions
import com.gemmory.vault.domain.ProcessedVaultNoteDraft
import com.gemmory.vault.domain.VaultNoteProcessor
import com.gemmory.vault.domain.VaultProcessingExistingNote
import com.gemmory.vault.domain.VaultProcessingInboxEntry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

class LocalLlmVaultNoteProcessor(
    private val engineController: EngineController,
    private val maxInputCharacters: Int = DEFAULT_MAX_INPUT_CHARACTERS,
) : VaultNoteProcessor {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun processInbox(
        entries: List<VaultProcessingInboxEntry>,
        existingNotes: List<VaultProcessingExistingNote>,
    ): List<ProcessedVaultNoteDraft>? {
        val cleanEntries = entries
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotBlank() }
        if (cleanEntries.isEmpty()) return emptyList()
        if (engineController.engine.diagnostics.value.state !is EngineState.Ready) return null

        val conversationId = "process-vault-${UUID.randomUUID()}"
        engineController.resetConversation(
            conversationId = conversationId,
            history = emptyList(),
            options = GenerationOptions.VaultNoteProcessing,
        )

        val builder = StringBuilder()
        var completed = false
        var failed = false
        engineController.generate(
            conversationId = conversationId,
            prompt = buildPrompt(cleanEntries, existingNotes),
            options = GenerationOptions.VaultNoteProcessing,
        ).collect { event ->
            when (event) {
                GenerationEvent.Started,
                is GenerationEvent.Metrics,
                -> Unit

                is GenerationEvent.Token -> builder.append(event.text)
                GenerationEvent.Completed -> completed = true
                GenerationEvent.Cancelled -> failed = true
                is GenerationEvent.Failed -> {
                    AppLog.w(TAG, "vault note processing failed: ${event.error::class.simpleName}")
                    failed = true
                }
            }
        }

        if (failed || !completed) return null
        return parseResponse(builder.toString(), cleanEntries.map { it.id }.toSet())
    }

    private fun buildPrompt(
        entries: List<VaultProcessingInboxEntry>,
        existingNotes: List<VaultProcessingExistingNote>,
    ): String = buildString {
        appendLine("MODE: PROCESS_INBOX_NOTES")
        appendLine()
        appendLine("Task:")
        appendLine("- Transform rough inbox captures into durable Markdown vault notes.")
        appendLine("- Rewrite fragmented thoughts into clear prose while preserving all concrete facts.")
        appendLine("- Make useful connections to existing vault notes with exact wiki links like [[Existing title]].")
        appendLine("- Do not invent facts, dates, names, sources, or links that are not supported by the inbox text or existing note list.")
        appendLine("- You may merge closely related inbox entries or split unrelated thoughts into separate notes.")
        appendLine("- Every sourceInboxIds value must come from the inbox entries below, and every inbox entry must appear in at least one output note.")
        appendLine("- Return only JSON. No Markdown fences, commentary, or explanation.")
        appendLine()
        appendLine("JSON schema:")
        appendLine("""{"notes":[{"title":"Short note title","sourceInboxIds":["inbox-id"],"tags":["optional-tag"],"aliases":["optional alias"],"bodyMarkdown":"## Summary\nRewritten note body with [[Existing title]] links.\n\n## Connections\n- [[Existing title]]: why it relates."}]}""")
        appendLine()
        appendLine("Existing vault notes available for links:")
        if (existingNotes.isEmpty()) {
            appendLine("- none")
        } else {
            existingNotes
                .sortedBy { it.title.lowercase() }
                .take(MAX_EXISTING_NOTES_IN_PROMPT)
                .forEach { note ->
                    val aliases = note.aliases.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " aliases: ") ?: ""
                    val tags = note.tags.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " tags: ") ?: ""
                    appendLine("- [[${note.title}]] (${note.path})$aliases$tags")
                }
        }
        appendLine()
        appendLine("Inbox entries:")

        var remaining = maxInputCharacters
        entries.forEach { entry ->
            if (remaining <= 0) return@forEach
            val excerpt = entry.text.take(remaining)
            remaining -= excerpt.length
            appendLine("ID: ${entry.id}")
            appendLine("```text")
            appendLine(excerpt)
            appendLine("```")
            appendLine()
        }
    }

    private fun parseResponse(text: String, allowedSourceIds: Set<String>): List<ProcessedVaultNoteDraft>? {
        val jsonText = extractJsonObject(text) ?: return null
        val response = runCatching { json.decodeFromString(ProcessingResponse.serializer(), jsonText) }
            .onFailure { AppLog.w(TAG, "vault note processing returned invalid JSON: ${it::class.simpleName}") }
            .getOrNull()
            ?: return null

        return response.notes.mapNotNull { note ->
            val title = note.title.singleLine().take(MAX_TITLE_CHARACTERS)
            val body = note.bodyMarkdown.trim()
            val sourceIds = note.sourceInboxIds
                .map { it.trim() }
                .filter { it in allowedSourceIds }
                .distinct()
            if (title.isBlank() || body.isBlank() || sourceIds.isEmpty()) {
                null
            } else {
                ProcessedVaultNoteDraft(
                    title = title,
                    sourceInboxIds = sourceIds,
                    bodyMarkdown = body,
                    tags = note.tags.cleanList(MAX_TAGS),
                    aliases = note.aliases.cleanList(MAX_ALIASES),
                )
            }
        }
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            when {
                escaped -> escaped = false
                char == '\\' && inString -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> depth++
                !inString && char == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun List<String>.cleanList(maxItems: Int): List<String> =
        map { it.singleLine() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(maxItems)

    private fun String.singleLine(): String =
        replace(Regex("""\s+"""), " ").trim()

    @Serializable
    private data class ProcessingResponse(
        val notes: List<ProcessingNote> = emptyList(),
    )

    @Serializable
    private data class ProcessingNote(
        val title: String = "",
        val sourceInboxIds: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val aliases: List<String> = emptyList(),
        val bodyMarkdown: String = "",
    )

    private companion object {
        const val TAG = "VaultNoteProcessor"
        const val DEFAULT_MAX_INPUT_CHARACTERS = 16_000
        const val MAX_EXISTING_NOTES_IN_PROMPT = 80
        const val MAX_TITLE_CHARACTERS = 96
        const val MAX_TAGS = 8
        const val MAX_ALIASES = 8
    }
}
