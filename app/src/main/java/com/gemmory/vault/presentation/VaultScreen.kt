package com.gemmory.vault.presentation

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gemmory.vault.domain.VaultGraph
import com.gemmory.vault.domain.VaultGraphNode
import com.gemmory.vault.domain.VaultNote
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultScreen(
    state: KnowledgeUiState,
    onSearch: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onDeleteNote: (String) -> Unit,
    onOpenGraph: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var notePendingRemoval by remember { mutableStateOf<VaultNote?>(null) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Vault") },
                actions = {
                    IconButton(onClick = onOpenGraph) {
                        Icon(Icons.Filled.AccountTree, contentDescription = "Open graph window")
                    }
                    IconButton(onClick = onUndo) {
                        Icon(Icons.Filled.History, contentDescription = "Undo latest change")
                    }
                },
            )
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(0.42f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearch,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search vault") },
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val entries = if (state.searchQuery.isBlank()) {
                        state.notes.map { NoteListItem(it.noteId, it.title, it.path) }
                    } else {
                        state.searchResults.map { NoteListItem(it.noteId, it.title, it.path) }
                    }
                    items(entries, key = { it.noteId }) { item ->
                        Card(Modifier.fillMaxWidth().clickable { onOpenNote(item.noteId) }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.path, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Column(Modifier.weight(0.58f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultGraphSection(
                    graph = state.graph,
                    selectedNoteId = state.selectedNote?.id,
                    onOpenNote = onOpenNote,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.banner?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                val note = state.selectedNote
                if (note == null) {
                    Text("Select a note", style = MaterialTheme.typography.titleMedium)
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(note.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        IconButton(
                            onClick = { notePendingRemoval = note },
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove note from vault")
                        }
                    }
                    Text("${note.path}  rev ${note.revision}", style = MaterialTheme.typography.labelMedium)
                    LazyColumn {
                        item {
                            Text(
                                text = note.markdown,
                                modifier = Modifier.fillMaxWidth(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    notePendingRemoval?.let { note ->
        AlertDialog(
            onDismissRequest = { notePendingRemoval = null },
            title = { Text("Remove note from vault?") },
            text = { Text("This permanently removes ${note.title} from the vault.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        notePendingRemoval = null
                        onDeleteNote(note.id)
                    },
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { notePendingRemoval = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun VaultGraphSection(
    graph: VaultGraph,
    selectedNoteId: String?,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val layout = remember(graph, canvasSize) { graph.layout(canvasSize) }
    val clusters = remember(graph) { graph.nodes.map { it.cluster }.distinct().sorted() }
    val clusterColors = graphClusterColors(clusters)
    val selectedNode = selectedNoteId?.let { id -> graph.nodes.firstOrNull { it.noteId == id } }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val mutedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val edgeColor = MaterialTheme.colorScheme.outline
    val selectedColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Graph Visualizer",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${graph.nodes.size} notes, ${graph.edges.size} edges, ${clusters.size} clusters",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .onSizeChanged { canvasSize = it },
            ) {
                if (graph.nodes.isEmpty()) {
                    Text(
                        "No notes yet",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(layout) {
                                detectTapGestures { tap ->
                                    layout.nearestNode(tap)?.let { onOpenNote(it.noteId) }
                                }
                            },
                    ) {
                        drawRect(surfaceVariant.copy(alpha = 0.28f))
                        layout.clusters.forEach { (cluster, center) ->
                            val color = clusterColors[cluster] ?: selectedColor
                            val radius = layout.clusterRadius(cluster)
                            drawCircle(color.copy(alpha = 0.08f), radius = radius, center = center)
                            drawCircle(color.copy(alpha = 0.18f), radius = radius, center = center, style = Stroke(width = 1.4f))
                        }
                        graph.edges.forEach { edge ->
                            val source = layout.nodes[edge.sourceNoteId] ?: return@forEach
                            val target = layout.nodes[edge.targetNoteId] ?: return@forEach
                            val emphasized = selectedNoteId == edge.sourceNoteId || selectedNoteId == edge.targetNoteId
                            drawLine(
                                color = if (emphasized) selectedColor else edgeColor,
                                start = source.offset,
                                end = target.offset,
                                strokeWidth = if (emphasized) 3f else 1.6f,
                                cap = StrokeCap.Round,
                                alpha = if (emphasized) 0.9f else 0.42f,
                            )
                        }
                        graph.nodes.forEach { node ->
                            val nodeLayout = layout.nodes[node.noteId] ?: return@forEach
                            val color = clusterColors[node.cluster] ?: selectedColor
                            val selected = selectedNoteId == node.noteId
                            drawCircle(
                                color = color.copy(alpha = if (node.degree == 0) 0.55f else 0.95f),
                                radius = nodeLayout.radius,
                                center = nodeLayout.offset,
                            )
                            drawCircle(
                                color = if (selected) selectedColor else Color.White.copy(alpha = 0.75f),
                                radius = nodeLayout.radius + if (selected) 5f else 2f,
                                center = nodeLayout.offset,
                                style = Stroke(width = if (selected) 3f else 1.2f),
                            )
                        }
                        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = textColor
                            textSize = 11.dp.toPx()
                        }
                        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = mutedTextColor
                            textSize = 10.dp.toPx()
                        }
                        drawIntoCanvas { canvas ->
                            layout.clusters.forEach { (cluster, center) ->
                                val label = cluster.ellipsize(18)
                                val labelWidth = mutedPaint.measureText(label)
                                canvas.nativeCanvas.drawText(
                                    label,
                                    labelX(center.x - labelWidth / 2f, labelWidth, size.width),
                                    (center.y + layout.clusterRadius(cluster) + 12f).coerceIn(12f, size.height - 4f),
                                    mutedPaint,
                                )
                            }
                            graph.nodes.forEach { node ->
                                if (graph.nodes.size <= 18 || node.degree >= 2 || selectedNoteId == node.noteId) {
                                    val nodeLayout = layout.nodes[node.noteId] ?: return@forEach
                                    val paint = if (node.degree == 0) mutedPaint else labelPaint
                                    val label = node.title.ellipsize(22)
                                    val labelWidth = paint.measureText(label)
                                    canvas.nativeCanvas.drawText(
                                        label,
                                        labelX(nodeLayout.offset.x + nodeLayout.radius + 5f, labelWidth, size.width),
                                        (nodeLayout.offset.y - nodeLayout.radius).coerceIn(12f, size.height - 4f),
                                        paint,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    selectedNode?.let { "${it.title} (${it.cluster})" } ?: "Tap a node to open it",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (graph.unresolvedLinkCount > 0) {
                    Text(
                        "${graph.unresolvedLinkCount} unresolved",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

private data class NoteListItem(
    val noteId: String,
    val title: String,
    val path: String,
)

private data class GraphLayout(
    val nodes: Map<String, GraphNodeLayout>,
    val clusters: Map<String, Offset>,
) {
    fun nearestNode(tap: Offset): VaultGraphNode? =
        nodes.values
            .filter { hypot(tap.x - it.offset.x, tap.y - it.offset.y) <= max(28f, it.radius + 12f) }
            .minByOrNull { hypot(tap.x - it.offset.x, tap.y - it.offset.y) }
            ?.node

    fun clusterRadius(cluster: String): Float {
        val members = nodes.values.filter { it.node.cluster == cluster }
        if (members.isEmpty()) return 0f
        val center = clusters[cluster] ?: return 0f
        val farthest = members.maxOf { hypot(it.offset.x - center.x, it.offset.y - center.y) + it.radius }
        return max(36f, farthest + 18f)
    }
}

private data class GraphNodeLayout(
    val node: VaultGraphNode,
    val offset: Offset,
    val radius: Float,
)

private fun VaultGraph.layout(size: IntSize): GraphLayout {
    if (size.width == 0 || size.height == 0 || nodes.isEmpty()) return GraphLayout(emptyMap(), emptyMap())
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val minDimension = min(width, height)
    val center = Offset(width / 2f, height / 2f)
    val grouped = nodes.groupBy { it.cluster }.toSortedMap()
    val clusterRing = minDimension * 0.28f
    val clusterCenters = grouped.keys.mapIndexed { index, cluster ->
        val angle = if (grouped.size == 1) 0.0 else (2.0 * PI * index / grouped.size) - PI / 2.0
        val offset = if (grouped.size == 1) {
            center
        } else {
            Offset(
                x = center.x + cos(angle).toFloat() * clusterRing,
                y = center.y + sin(angle).toFloat() * clusterRing,
            )
        }
        cluster to offset
    }.toMap()

    val padding = 30f
    val maxX = max(padding, width - padding)
    val maxY = max(padding, height - padding)
    val nodeLayouts = grouped.flatMap { (cluster, clusterNodes) ->
        val clusterCenter = clusterCenters.getValue(cluster)
        val spread = max(24f, min(86f, 18f * sqrt(clusterNodes.size.toFloat())))
        clusterNodes.sortedWith(compareByDescending<VaultGraphNode> { it.degree }.thenBy { it.title.lowercase() })
            .mapIndexed { index, node ->
                val angle = if (clusterNodes.size == 1) 0.0 else (2.0 * PI * index / clusterNodes.size) + PI / 4.0
                val rawOffset = if (clusterNodes.size == 1) {
                    clusterCenter
                } else {
                    Offset(
                        x = clusterCenter.x + cos(angle).toFloat() * spread,
                        y = clusterCenter.y + sin(angle).toFloat() * spread,
                    )
                }
                node.noteId to GraphNodeLayout(
                    node = node,
                    offset = Offset(
                        x = rawOffset.x.coerceIn(padding, maxX),
                        y = rawOffset.y.coerceIn(padding, maxY),
                    ),
                    radius = 8f + min(7f, node.degree * 1.5f),
                )
            }
    }.toMap()
    return GraphLayout(nodeLayouts, clusterCenters)
}

@Composable
private fun graphClusterColors(clusters: List<String>): Map<String, Color> {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(
        scheme.primary,
        scheme.tertiary,
        scheme.secondary,
        scheme.error,
        scheme.primaryContainer,
        scheme.tertiaryContainer,
        scheme.secondaryContainer,
    )
    return clusters.mapIndexed { index, cluster -> cluster to colors[index % colors.size] }.toMap()
}

private fun String.ellipsize(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength - 3) + "..."

private fun labelX(preferred: Float, labelWidth: Float, containerWidth: Float): Float =
    if (containerWidth <= labelWidth + 8f) 4f else preferred.coerceIn(4f, containerWidth - labelWidth - 4f)
