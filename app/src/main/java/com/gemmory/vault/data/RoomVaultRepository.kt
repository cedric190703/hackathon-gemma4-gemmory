package com.gemmory.vault.data

import androidx.room.withTransaction
import com.gemmory.core.dispatchers.AppDispatchers
import com.gemmory.inbox.data.InboxEntryEntity
import com.gemmory.inbox.domain.InboxEntry
import com.gemmory.inbox.domain.InboxEntryStatus
import com.gemmory.vault.data.entities.KnowledgeChatEntity
import com.gemmory.vault.data.entities.KnowledgeMessageEntity
import com.gemmory.vault.data.entities.VaultChangeSetEntity
import com.gemmory.vault.data.entities.VaultLinkEntity
import com.gemmory.vault.data.entities.VaultNoteEntity
import com.gemmory.vault.data.entities.VaultNoteFtsEntity
import com.gemmory.vault.data.entities.VaultRevisionEntity
import com.gemmory.vault.domain.ApplyResult
import com.gemmory.vault.domain.LinkResolutionStatus
import com.gemmory.vault.domain.ProposedVaultChangeSet
import com.gemmory.vault.domain.UndoResult
import com.gemmory.vault.domain.VaultEntry
import com.gemmory.vault.domain.VaultGraph
import com.gemmory.vault.domain.VaultGraphEdge
import com.gemmory.vault.domain.VaultGraphNode
import com.gemmory.vault.domain.VaultLink
import com.gemmory.vault.domain.VaultNote
import com.gemmory.vault.domain.VaultOperation
import com.gemmory.vault.domain.VaultOperationPreview
import com.gemmory.vault.domain.VaultRepository
import com.gemmory.vault.domain.VaultSearchResult
import com.gemmory.vault.parser.MarkdownFrontmatterParser
import com.gemmory.vault.parser.WikiLinkParser
import com.gemmory.vault.storage.MarkdownVaultStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID

class RoomVaultRepository(
    private val database: KnowledgeDatabase,
    private val dao: KnowledgeDao,
    private val storage: MarkdownVaultStorage,
    private val dispatchers: AppDispatchers,
) : VaultRepository {

    override fun observeInbox(): Flow<List<InboxEntry>> =
        dao.observeInbox().map { entries -> entries.map { it.toDomain() } }

    override fun observeNotes(): Flow<List<VaultEntry>> =
        dao.observeNotes().map { notes ->
            notes.map { VaultEntry(it.id, it.path, it.title, it.archived) }
        }

    override fun observeAllLinks(): Flow<List<VaultLink>> =
        dao.observeLinks().map { links -> links.map { it.toDomain() } }

    override fun observeGraph(): Flow<VaultGraph> =
        dao.observeNotes().combine(dao.observeLinks()) { notes, links ->
            buildGraph(notes, links)
        }

    override fun observeBacklinks(noteId: String): Flow<List<VaultLink>> =
        dao.observeBacklinks(noteId).map { links -> links.map { it.toDomain() } }

    override fun observeOutgoingLinks(noteId: String): Flow<List<VaultLink>> =
        dao.observeOutgoingLinks(noteId).map { links -> links.map { it.toDomain() } }

    override suspend fun captureInbox(text: String): InboxEntry = withContext(dispatchers.io) {
        val now = System.currentTimeMillis()
        val entry = InboxEntryEntity(
            id = UUID.randomUUID().toString(),
            text = text.trim(),
            status = InboxEntryStatus.READY.name,
            createdAt = now,
            updatedAt = now,
            processedAt = null,
            lastError = null,
            resultNoteIds = "",
        )
        dao.upsertInbox(entry)
        entry.toDomain()
    }

    override suspend fun updateInboxText(id: String, text: String) = withContext(dispatchers.io) {
        val entry = dao.inboxByIds(listOf(id)).firstOrNull() ?: return@withContext
        if (entry.status !in editableInboxStatuses) return@withContext
        dao.updateInbox(entry.copy(text = text.trim(), updatedAt = System.currentTimeMillis()))
    }

    override suspend fun proposeAllUnprocessed(): ProposedVaultChangeSet = withContext(dispatchers.io) {
        val entries = dao.inboxByStatuses(listOf(InboxEntryStatus.READY.name, InboxEntryStatus.FAILED.name))
        proposeProcessing(entries.map { it.id })
    }

    override suspend fun proposeProcessing(entryIds: List<String>): ProposedVaultChangeSet =
        withContext(dispatchers.io) {
            val entries = dao.inboxByIds(entryIds).filter { it.text.isNotBlank() }
            val operations = entries.map { entry ->
                val title = deriveTitle(entry.text)
                val path = "inbox/${slug(title)}.md"
                VaultOperation.CreateNote(
                    temporaryId = "tmp-${entry.id}",
                    proposedPath = path,
                    title = title,
                    markdown = buildMarkdown(
                        id = "pending",
                        title = title,
                        sourceInboxIds = listOf(entry.id),
                        body = structureBody(entry.text),
                    ),
                    sourceInboxIds = listOf(entry.id),
                )
            }
            preview(
                operations = operations,
                request = "Process ${entries.size} inbox entr${if (entries.size == 1) "y" else "ies"}",
                sourceInboxIds = entries.map { it.id },
            )
        }

    override suspend fun preview(
        operations: List<VaultOperation>,
        request: String,
        sourceInboxIds: List<String>,
    ): ProposedVaultChangeSet = withContext(dispatchers.io) {
        val errors = validate(operations)
        val previews = operations.mapNotNull { operation ->
            when (operation) {
                is VaultOperation.CreateNote -> VaultOperationPreview(
                    operationLabel = "Create",
                    noteId = null,
                    path = operation.proposedPath,
                    title = operation.title,
                    beforeMarkdown = null,
                    afterMarkdown = operation.markdown,
                )

                is VaultOperation.UpdateNote -> {
                    val note = dao.noteById(operation.noteId) ?: return@mapNotNull null
                    VaultOperationPreview(
                        operationLabel = "Update",
                        noteId = operation.noteId,
                        path = note.path,
                        title = note.title,
                        beforeMarkdown = storage.read(note.path).orEmpty(),
                        afterMarkdown = operation.replacementMarkdown,
                    )
                }

                is VaultOperation.MoveNote -> {
                    val note = dao.noteById(operation.noteId) ?: return@mapNotNull null
                    VaultOperationPreview("Move", note.id, operation.destinationPath, note.title, note.path, operation.destinationPath)
                }

                is VaultOperation.RenameNote -> {
                    val note = dao.noteById(operation.noteId) ?: return@mapNotNull null
                    VaultOperationPreview("Rename", note.id, note.path, operation.newTitle, note.title, operation.newTitle)
                }

                is VaultOperation.DeleteNote -> {
                    val note = dao.noteById(operation.noteId) ?: return@mapNotNull null
                    VaultOperationPreview("Archive", note.id, "archive/${note.path}", note.title, storage.read(note.path), null)
                }

                is VaultOperation.MergeNotes -> VaultOperationPreview(
                    operationLabel = "Merge",
                    noteId = operation.destinationNoteId,
                    path = "merged/${slug(operation.mergedTitle)}.md",
                    title = operation.mergedTitle,
                    beforeMarkdown = operation.sourceNoteIds.joinToString("\n") { it },
                    afterMarkdown = operation.mergedMarkdown,
                )
            }
        }
        ProposedVaultChangeSet(
            id = UUID.randomUUID().toString(),
            userRequest = request,
            sourceInboxIds = sourceInboxIds,
            operations = operations,
            validationErrors = errors,
            previews = previews,
        )
    }

    override suspend fun apply(changeSet: ProposedVaultChangeSet): ApplyResult = withContext(dispatchers.io) {
        require(changeSet.canApply) { "Cannot apply invalid change set" }
        val affected = mutableListOf<String>()
        val before = mutableListOf<String>()
        val after = mutableListOf<String>()
        val now = System.currentTimeMillis()

        database.withTransaction {
            for (operation in changeSet.operations) {
                when (operation) {
                    is VaultOperation.CreateNote -> {
                        val noteId = UUID.randomUUID().toString()
                        val markdown = operation.markdown.replace("id: \"pending\"", "id: \"$noteId\"")
                        val entity = noteEntity(
                            id = noteId,
                            path = operation.proposedPath,
                            title = operation.title,
                            markdown = markdown,
                            sourceInboxIds = operation.sourceInboxIds,
                            now = now,
                            revision = 1,
                        )
                        storage.write(entity.path, markdown)
                        dao.upsertNote(entity)
                        indexNote(entity, markdown)
                        affected += noteId
                        after += "${entity.id}|${entity.path}|${entity.title}|$markdown"
                    }

                    is VaultOperation.UpdateNote -> {
                        val existing = requireNotNull(dao.noteById(operation.noteId))
                        check(existing.revision == operation.expectedRevision)
                        val oldMarkdown = storage.read(existing.path).orEmpty()
                        storeRevision(existing, oldMarkdown, changeSet.id)
                        val updated = noteEntity(
                            id = existing.id,
                            path = existing.path,
                            title = titleFromMarkdown(operation.replacementMarkdown) ?: existing.title,
                            markdown = operation.replacementMarkdown,
                            sourceInboxIds = split(existing.sourceInboxIds),
                            now = now,
                            revision = existing.revision + 1,
                        )
                        storage.write(updated.path, operation.replacementMarkdown)
                        dao.upsertNote(updated)
                        indexNote(updated, operation.replacementMarkdown)
                        affected += existing.id
                        before += "${existing.id}|${existing.path}|${existing.title}|$oldMarkdown"
                        after += "${updated.id}|${updated.path}|${updated.title}|${operation.replacementMarkdown}"
                    }

                    is VaultOperation.MoveNote -> {
                        val existing = requireNotNull(dao.noteById(operation.noteId))
                        check(existing.revision == operation.expectedRevision)
                        val markdown = storage.read(existing.path).orEmpty()
                        storeRevision(existing, markdown, changeSet.id)
                        storage.move(existing.path, operation.destinationPath)
                        val moved = existing.copy(
                            path = operation.destinationPath,
                            updatedAt = now,
                            revision = existing.revision + 1,
                            contentHash = sha256(markdown),
                        )
                        dao.upsertNote(moved)
                        indexNote(moved, markdown)
                        affected += existing.id
                        before += "${existing.id}|${existing.path}|${existing.title}|$markdown"
                        after += "${moved.id}|${moved.path}|${moved.title}|$markdown"
                    }

                    is VaultOperation.RenameNote -> {
                        val existing = requireNotNull(dao.noteById(operation.noteId))
                        check(existing.revision == operation.expectedRevision)
                        val markdown = storage.read(existing.path).orEmpty()
                        storeRevision(existing, markdown, changeSet.id)
                        val renamedMarkdown = markdown.replaceFirst(Regex("""(?m)^# .+$"""), "# ${operation.newTitle}")
                        val renamed = existing.copy(
                            title = operation.newTitle,
                            updatedAt = now,
                            revision = existing.revision + 1,
                            contentHash = sha256(renamedMarkdown),
                        )
                        storage.write(renamed.path, renamedMarkdown)
                        dao.upsertNote(renamed)
                        indexNote(renamed, renamedMarkdown)
                        affected += existing.id
                        before += "${existing.id}|${existing.path}|${existing.title}|$markdown"
                        after += "${renamed.id}|${renamed.path}|${renamed.title}|$renamedMarkdown"
                    }

                    is VaultOperation.DeleteNote -> {
                        val existing = requireNotNull(dao.noteById(operation.noteId))
                        check(existing.revision == operation.expectedRevision)
                        val markdown = storage.read(existing.path).orEmpty()
                        storeRevision(existing, markdown, changeSet.id)
                        val archivePath = "archive/${existing.path}"
                        storage.move(existing.path, archivePath)
                        val archived = existing.copy(
                            path = archivePath,
                            archived = true,
                            updatedAt = now,
                            revision = existing.revision + 1,
                        )
                        dao.upsertNote(archived)
                        dao.deleteFts(existing.id)
                        dao.replaceLinks(existing.id, emptyList())
                        affected += existing.id
                        before += "${existing.id}|${existing.path}|${existing.title}|$markdown"
                        after += "${archived.id}|${archived.path}|${archived.title}|$markdown"
                    }

                    is VaultOperation.MergeNotes -> {
                        val destination = operation.destinationNoteId?.let { dao.noteById(it) }
                        val create = VaultOperation.CreateNote(
                            temporaryId = "merge-${UUID.randomUUID()}",
                            proposedPath = destination?.path ?: "merged/${slug(operation.mergedTitle)}.md",
                            title = operation.mergedTitle,
                            markdown = operation.mergedMarkdown,
                            sourceInboxIds = emptyList(),
                        )
                        apply(preview(listOf(create), "Merge notes", emptyList()))
                    }
                }
            }

            val processedEntries = changeSet.sourceInboxIds
                .takeIf { it.isNotEmpty() }
                ?.let { dao.inboxByIds(it) }
                .orEmpty()
            processedEntries.forEach { entry ->
                dao.updateInbox(
                    entry.copy(
                        status = InboxEntryStatus.PROCESSED.name,
                        processedAt = now,
                        updatedAt = now,
                        resultNoteIds = affected.distinct().joinToString(","),
                        lastError = null,
                    ),
                )
            }

            dao.insertChangeSet(
                VaultChangeSetEntity(
                    id = changeSet.id,
                    createdAt = now,
                    userRequest = changeSet.userRequest,
                    sourceInboxIds = changeSet.sourceInboxIds.joinToString(","),
                    reasoningSummary = "Applied locally validated vault operations.",
                    operations = changeSet.operations.joinToString("\n") { it.toString() },
                    beforeState = before.joinToString("\u001e"),
                    afterState = after.joinToString("\u001e"),
                    modelConfiguration = "local-deterministic-v1",
                    approvalStatus = "APPROVED",
                    undoneAt = null,
                ),
            )
        }

        rebuildAllLinks()
        ApplyResult(changeSet.id, affected.distinct())
    }

    override suspend fun undoLatest(): UndoResult? = withContext(dispatchers.io) {
        val changeSet = dao.latestUndoableChangeSet() ?: return@withContext null
        val restored = mutableListOf<String>()
        database.withTransaction {
            parseState(changeSet.beforeState).forEach { state ->
                storage.write(state.path, state.markdown)
                val entity = noteEntity(
                    id = state.id,
                    path = state.path,
                    title = state.title,
                    markdown = state.markdown,
                    sourceInboxIds = emptyList(),
                    now = System.currentTimeMillis(),
                    revision = (dao.noteById(state.id)?.revision ?: 0L) + 1,
                )
                dao.upsertNote(entity)
                indexNote(entity, state.markdown)
                restored += state.id
            }
            parseState(changeSet.afterState)
                .filter { after -> parseState(changeSet.beforeState).none { it.id == after.id } }
                .forEach { created ->
                    dao.deleteNote(created.id)
                    dao.deleteFts(created.id)
                    dao.replaceLinks(created.id, emptyList())
                    storage.delete(created.path)
                }
            dao.markChangeSetUndone(changeSet.id, System.currentTimeMillis())
        }
        rebuildAllLinks()
        UndoResult(changeSet.id, restored)
    }

    override suspend fun getNote(noteId: String): VaultNote? = withContext(dispatchers.io) {
        val entity = dao.noteById(noteId) ?: return@withContext null
        entity.toDomain(storage.read(entity.path).orEmpty())
    }

    override suspend fun search(query: String, limit: Int): List<VaultSearchResult> = withContext(dispatchers.io) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext dao.recentNotes(limit).map { it.searchResult("", 1) }
        val exact = dao.notesByTitle(clean).map { it.id }
        val ftsQuery = clean.split(Regex("""\s+""")).joinToString(" ") { "$it*" }
        val fts = runCatching { dao.searchFtsIds(ftsQuery, limit * 3) }.getOrDefault(emptyList())
        val notes = dao.notesByIds((exact + fts).distinct()).associateBy { it.id }
        (exact + fts).distinct().mapNotNull { id ->
            notes[id]?.let { note ->
                val markdown = storage.read(note.path).orEmpty()
                val score = score(note, markdown, clean, exact.contains(id))
                note.searchResult(snippet(markdown, clean), score)
            }
        }.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun answerVaultQuestion(conversationId: String, question: String): String =
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            dao.upsertKnowledgeChat(KnowledgeChatEntity(conversationId, now, now))
            dao.upsertKnowledgeMessage(
                KnowledgeMessageEntity(UUID.randomUUID().toString(), conversationId, "USER", question, now, "", true),
            )
            val results = search(question, limit = 5)
            val answer = if (results.isEmpty()) {
                "I could not find this in your vault."
            } else {
                buildString {
                    append("According to your vault, the closest relevant notes are:\n\n")
                    results.forEach { result ->
                        append("- ${result.snippet.ifBlank { result.title }} [[${result.title}]]\n")
                    }
                }
            }
            dao.upsertKnowledgeMessage(
                KnowledgeMessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "ASSISTANT",
                    content = answer,
                    createdAt = System.currentTimeMillis(),
                    citationNoteIds = results.joinToString(",") { it.noteId },
                    fullyGrounded = results.isNotEmpty(),
                ),
            )
            answer
        }

    private suspend fun validate(operations: List<VaultOperation>): List<String> {
        val errors = mutableListOf<String>()
        val proposedPaths = mutableSetOf<String>()
        operations.forEach { operation ->
            when (operation) {
                is VaultOperation.CreateNote -> {
                    if (!MarkdownVaultStorage.isSafePath(operation.proposedPath)) errors += "Invalid path: ${operation.proposedPath}"
                    if (!proposedPaths.add(operation.proposedPath)) errors += "Duplicate destination path: ${operation.proposedPath}"
                    if (dao.noteByPath(operation.proposedPath) != null) errors += "Path already exists: ${operation.proposedPath}"
                    if (operation.title.isBlank()) errors += "Create note is missing a title"
                    if (operation.markdown.isBlank()) errors += "Create note is missing Markdown"
                    if (operation.sourceInboxIds.isEmpty()) errors += "Create note is missing source inbox IDs"
                }

                is VaultOperation.UpdateNote -> validateExisting(operation.noteId, operation.expectedRevision, errors).also {
                    if (operation.replacementMarkdown.isBlank()) errors += "Update note has empty Markdown"
                }

                is VaultOperation.MoveNote -> {
                    validateExisting(operation.noteId, operation.expectedRevision, errors)
                    if (!MarkdownVaultStorage.isSafePath(operation.destinationPath)) errors += "Invalid destination path: ${operation.destinationPath}"
                    if (dao.noteByPath(operation.destinationPath) != null) errors += "Destination already exists: ${operation.destinationPath}"
                }

                is VaultOperation.RenameNote -> {
                    validateExisting(operation.noteId, operation.expectedRevision, errors)
                    if (operation.newTitle.isBlank()) errors += "Rename note is missing a title"
                }

                is VaultOperation.DeleteNote -> validateExisting(operation.noteId, operation.expectedRevision, errors)

                is VaultOperation.MergeNotes -> {
                    if (operation.sourceNoteIds.distinct().size != operation.sourceNoteIds.size) errors += "Merge has duplicate sources"
                    operation.sourceNoteIds.forEach { if (dao.noteById(it) == null) errors += "Unknown merge source note: $it" }
                    if (operation.mergedMarkdown.isBlank()) errors += "Merge result is empty"
                }
            }
        }
        return errors
    }

    private suspend fun validateExisting(noteId: String, expectedRevision: Long, errors: MutableList<String>) {
        val note = dao.noteById(noteId)
        when {
            note == null -> errors += "Unknown note: $noteId"
            note.revision != expectedRevision -> errors += "Revision conflict for ${note.title}"
        }
    }

    private suspend fun indexNote(entity: VaultNoteEntity, markdown: String) {
        dao.upsertFts(
            VaultNoteFtsEntity(
                noteId = entity.id,
                title = entity.title,
                path = entity.path,
                tags = entity.tags,
                aliases = entity.aliases,
                body = markdown,
            ),
        )
        dao.replaceLinks(entity.id, resolveLinks(entity.id, markdown))
    }

    private suspend fun rebuildLinksFor(noteIds: List<String>) {
        noteIds.forEach { id ->
            val note = dao.noteById(id) ?: return@forEach
            indexNote(note, storage.read(note.path).orEmpty())
        }
    }

    private suspend fun rebuildAllLinks() {
        rebuildLinksFor(dao.allActiveNotes().map { it.id })
    }

    private fun buildGraph(notes: List<VaultNoteEntity>, links: List<VaultLinkEntity>): VaultGraph {
        val activeIds = notes.map { it.id }.toSet()
        val resolvedLinks = links.filter { link ->
            link.status == LinkResolutionStatus.RESOLVED.name &&
                link.sourceNoteId in activeIds &&
                link.targetNoteId?.let { it in activeIds } == true
        }
        val degreeById = activeIds.associateWith { 0 }.toMutableMap()
        resolvedLinks.forEach { link ->
            degreeById[link.sourceNoteId] = degreeById.getValue(link.sourceNoteId) + 1
            link.targetNoteId?.let { target ->
                degreeById[target] = degreeById.getValue(target) + 1
            }
        }
        val nodes = notes
            .sortedBy { it.title.lowercase() }
            .map { note ->
                VaultGraphNode(
                    noteId = note.id,
                    title = note.title,
                    path = note.path,
                    cluster = graphCluster(note),
                    degree = degreeById[note.id] ?: 0,
                )
            }
        val edges = resolvedLinks.mapNotNull { link ->
            val target = link.targetNoteId ?: return@mapNotNull null
            VaultGraphEdge(
                id = link.id,
                sourceNoteId = link.sourceNoteId,
                targetNoteId = target,
                label = link.label ?: link.rawTarget,
            )
        }
        return VaultGraph(
            nodes = nodes,
            edges = edges,
            unresolvedLinkCount = links.count { link ->
                link.sourceNoteId in activeIds && link.status != LinkResolutionStatus.RESOLVED.name
            },
        )
    }

    private fun graphCluster(note: VaultNoteEntity): String =
        split(note.tags).firstOrNull()?.let { "#$it" }
            ?: note.path.substringBefore('/').takeIf { it != note.path }
            ?: "Vault"

    private suspend fun resolveLinks(sourceNoteId: String, markdown: String): List<VaultLinkEntity> {
        val notes = dao.allActiveNotes()
        return WikiLinkParser.parse(markdown).map { token ->
            val matches = notes.filter { note ->
                note.title.equals(token.rawTarget, ignoreCase = true) ||
                    split(note.aliases).any { it.equals(token.rawTarget, ignoreCase = true) }
            }
            val status = when (matches.size) {
                0 -> LinkResolutionStatus.UNRESOLVED
                1 -> LinkResolutionStatus.RESOLVED
                else -> LinkResolutionStatus.AMBIGUOUS
            }
            VaultLinkEntity(
                id = UUID.randomUUID().toString(),
                sourceNoteId = sourceNoteId,
                targetNoteId = matches.singleOrNull()?.id,
                rawTarget = token.rawTarget,
                label = token.label,
                startOffset = token.start,
                endOffset = token.end,
                status = status.name,
            )
        }
    }

    private suspend fun storeRevision(entity: VaultNoteEntity, markdown: String, changeSetId: String) {
        dao.insertRevision(
            VaultRevisionEntity(
                id = UUID.randomUUID().toString(),
                noteId = entity.id,
                path = entity.path,
                title = entity.title,
                markdown = markdown,
                revision = entity.revision,
                createdAt = System.currentTimeMillis(),
                changeSetId = changeSetId,
            ),
        )
    }

    private fun noteEntity(
        id: String,
        path: String,
        title: String,
        markdown: String,
        sourceInboxIds: List<String>,
        now: Long,
        revision: Long,
    ): VaultNoteEntity {
        val parsed = MarkdownFrontmatterParser.parse(markdown)
        return VaultNoteEntity(
            id = id,
            path = path,
            title = titleFromMarkdown(markdown) ?: title,
            createdAt = now,
            updatedAt = now,
            revision = revision,
            contentHash = sha256(markdown),
            archived = false,
            tags = parsed.fields["tags"].orEmpty().joinToString(","),
            aliases = parsed.fields["aliases"].orEmpty().joinToString(","),
            sourceInboxIds = sourceInboxIds.ifEmpty { parsed.fields["sources"].orEmpty().mapNotNull { it.removePrefix("inbox:").takeIf(String::isNotBlank) } }
                .joinToString(","),
        )
    }

    private fun buildMarkdown(id: String, title: String, sourceInboxIds: List<String>, body: String): String =
        """
        ---
        id: "$id"
        title: "$title"
        tags:
        aliases:
        sources:
        ${sourceInboxIds.joinToString("\n") { "  - inbox:$it" }}
        ---

        # $title

        $body

        ## Source notes

        ${sourceInboxIds.joinToString("\n") { "- inbox:$it" }}
        """.trimIndent()

    private fun structureBody(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        return buildString {
            append("## Notes\n\n")
            lines.forEach { append("- $it\n") }
            append("\n## Open questions\n\n")
            lines.filter { it.endsWith("?") }.forEach { append("- $it\n") }
        }.trim()
    }

    private fun deriveTitle(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }
            ?.replace(Regex("""^[#\-\*\d\.\s]+"""), "")
            ?.take(64)
            ?.trim()
            ?.ifBlank { null }
            ?: "Untitled note"

    private fun titleFromMarkdown(markdown: String): String? =
        markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()

    private fun slug(title: String): String {
        val normalized = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
            .lowercase()
            .replace(Regex("""[^a-z0-9]+"""), "-")
            .trim('-')
            .ifBlank { "untitled" }
        return normalized.take(72)
    }

    private fun score(note: VaultNoteEntity, markdown: String, query: String, exact: Boolean): Int {
        val q = query.lowercase()
        return (if (exact) 1000 else 0) +
            (if (note.title.lowercase().startsWith(q)) 500 else 0) +
            (if (split(note.aliases).any { it.equals(query, ignoreCase = true) }) 400 else 0) +
            (if (split(note.tags).any { it.contains(q, ignoreCase = true) }) 250 else 0) +
            (if (markdown.contains(query, ignoreCase = true)) 100 else 0) +
            (System.currentTimeMillis() - note.updatedAt).let { age -> if (age < 86_400_000L) 20 else 0 }
    }

    private fun snippet(markdown: String, query: String): String {
        val line = markdown.lines().firstOrNull { it.contains(query, ignoreCase = true) }
            ?: markdown.lines().firstOrNull { it.isNotBlank() }
            ?: ""
        return line.removePrefix("- ").take(180)
    }

    private fun VaultNoteEntity.searchResult(snippet: String, score: Int) =
        VaultSearchResult(id, title, path, snippet, score)

    private fun VaultNoteEntity.toDomain(markdown: String): VaultNote =
        VaultNote(id, path, title, markdown, createdAt, updatedAt, revision, contentHash, archived, split(tags), split(aliases), split(sourceInboxIds))

    private fun InboxEntryEntity.toDomain(): InboxEntry =
        InboxEntry(id, text, InboxEntryStatus.valueOf(status), createdAt, updatedAt, processedAt, lastError, split(resultNoteIds))

    private fun VaultLinkEntity.toDomain(): VaultLink =
        VaultLink(id, sourceNoteId, targetNoteId, rawTarget, label, LinkResolutionStatus.valueOf(status))

    private data class StoredState(val id: String, val path: String, val title: String, val markdown: String)

    private fun parseState(value: String): List<StoredState> =
        if (value.isBlank()) {
            emptyList()
        } else {
            value.split("\u001e").mapNotNull { row ->
                val parts = row.split("|", limit = 4)
                if (parts.size == 4) StoredState(parts[0], parts[1], parts[2], parts[3]) else null
            }
        }

    private fun split(value: String): List<String> =
        value.split(",").map { it.trim() }.filter { it.isNotBlank() }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val editableInboxStatuses = setOf(InboxEntryStatus.DRAFT.name, InboxEntryStatus.READY.name, InboxEntryStatus.FAILED.name)
    }
}
