package com.gemmory.vault.presentation

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmory.vault.domain.LinkResolutionStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun VaultGraphScreen(
    viewModel: VaultGraphViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    VaultGraphScreen(state = state, onClose = onClose, modifier = modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultGraphScreen(
    state: VaultGraphUiState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedNodeId by remember { mutableStateOf<String?>(null) }
    var hideIsolated by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val visibleNodes = remember(state.nodes, hideIsolated) {
        state.nodes.filter { !hideIsolated || it.degree(state.edges) > 0 }
    }
    val visibleNodeIds = remember(visibleNodes) { visibleNodes.mapTo(mutableSetOf()) { it.id } }
    val visibleEdges = remember(state.edges, visibleNodeIds) {
        state.edges.filter {
            it.status == LinkResolutionStatus.RESOLVED &&
                it.targetNoteId != null &&
                it.sourceNoteId in visibleNodeIds &&
                it.targetNoteId in visibleNodeIds
        }
    }
    val layout = remember(visibleNodes, canvasSize) { graphLayout(visibleNodes, canvasSize) }
    val nodesById = remember(visibleNodes) { visibleNodes.associateBy { it.id } }
    val selectedNode = selectedNodeId?.let(nodesById::get)
    val connectedNodeIds = remember(selectedNodeId, visibleEdges) {
        selectedNodeId?.let { selected ->
            visibleEdges.flatMapTo(mutableSetOf()) { edge ->
                if (edge.sourceNoteId == selected) listOf(edge.targetNoteId.orEmpty())
                else if (edge.targetNoteId == selected) listOf(edge.sourceNoteId)
                else emptyList()
            }
        }.orEmpty()
    }
    val transformState: TransformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.55f, 3.2f)
        pan += panChange
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Vault graph")
                        Text(
                            "${visibleNodes.size} notes, ${visibleEdges.size} links",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    GraphControl(
                        onClick = { scale = (scale * 1.25f).coerceAtMost(3.2f) },
                        icon = Icons.Filled.Add,
                        label = "Zoom in",
                    )
                    GraphControl(
                        onClick = { scale = (scale / 1.25f).coerceAtLeast(0.55f) },
                        icon = Icons.Filled.Remove,
                        label = "Zoom out",
                    )
                    GraphControl(
                        onClick = { scale = 1f; pan = Offset.Zero },
                        icon = Icons.Filled.CenterFocusStrong,
                        label = "Reset view",
                    )
                    GraphOptionsButton(
                        showLabels = showLabels,
                        hideIsolated = hideIsolated,
                        onToggleLabels = { showLabels = !showLabels },
                        onToggleIsolated = {
                            hideIsolated = !hideIsolated
                            if (selectedNode?.degree == 0) selectedNodeId = null
                        },
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close graph window")
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (visibleNodes.isEmpty()) {
                Text(
                    if (state.nodes.isEmpty()) "No vault notes yet" else "No linked notes to show",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                VaultGraphCanvas(
                    nodes = visibleNodes,
                    edges = visibleEdges,
                    layout = layout,
                    scale = scale,
                    pan = pan,
                    selectedNodeId = selectedNodeId,
                    connectedNodeIds = connectedNodeIds,
                    showLabels = showLabels,
                    onNodeSelected = { selectedNodeId = it },
                    transformState = transformState,
                    onCanvasSizeChanged = { canvasSize = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            GraphLegend(
                unresolvedCount = state.edges.count { it.status != LinkResolutionStatus.RESOLVED },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
            )

            selectedNode?.let { node ->
                GraphSelectionPanel(
                    node = node,
                    connectedNodeIds = connectedNodeIds,
                    nodesById = nodesById,
                    onNodeSelected = { selectedNodeId = it },
                    onDismiss = { selectedNodeId = null },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun GraphControl(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun GraphOptionsButton(
    showLabels: Boolean,
    hideIsolated: Boolean,
    onToggleLabels: () -> Unit,
    onToggleIsolated: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Graph options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (showLabels) "Hide labels" else "Show labels") },
                onClick = { onToggleLabels(); expanded = false },
                leadingIcon = { Icon(Icons.Filled.TextFields, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(if (hideIsolated) "Show isolated notes" else "Hide isolated notes") },
                onClick = { onToggleIsolated(); expanded = false },
                leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun GraphLegend(unresolvedCount: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text("Explore", style = MaterialTheme.typography.labelLarge)
            Text("Drag to pan. Pinch to zoom. Tap a note to trace links.", style = MaterialTheme.typography.labelSmall)
            if (unresolvedCount > 0) {
                Text(
                    "$unresolvedCount unresolved link${if (unresolvedCount == 1) "" else "s"}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun GraphSelectionPanel(
    node: VaultGraphWindowNode,
    connectedNodeIds: Set<String>,
    nodesById: Map<String, VaultGraphWindowNode>,
    onNodeSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(node.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(node.path, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear selected note")
                }
            }
            HorizontalDivider()
            if (connectedNodeIds.isEmpty()) {
                Text("This note has no resolved links in the current view.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Connected notes", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    connectedNodeIds.sortedBy { nodesById[it]?.title.orEmpty() }.forEach { noteId ->
                        val linkedNode = nodesById[noteId] ?: return@forEach
                        AssistChip(
                            onClick = { onNodeSelected(noteId) },
                            label = { Text(linkedNode.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultGraphCanvas(
    nodes: List<VaultGraphWindowNode>,
    edges: List<VaultGraphWindowEdge>,
    layout: GraphLayout,
    scale: Float,
    pan: Offset,
    selectedNodeId: String?,
    connectedNodeIds: Set<String>,
    showLabels: Boolean,
    onNodeSelected: (String) -> Unit,
    transformState: TransformableState,
    onCanvasSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = graphColors(nodes)
    val colorScheme = MaterialTheme.colorScheme
    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged(onCanvasSizeChanged)
            .transformable(transformState)
            .pointerInput(layout, scale, pan) {
                detectTapGestures { tap ->
                    val graphTap = (tap - pan) / scale
                    layout.nearestNode(graphTap)?.let { onNodeSelected(it.id) }
                }
            },
    ) {
        drawRect(colorScheme.surface)
        drawClusterFields(layout, colors, scale, pan)
            edges.forEach { edge ->
                val source = layout.positions[edge.sourceNoteId]?.transformed(scale, pan) ?: return@forEach
                val target = edge.targetNoteId?.let(layout.positions::get)?.transformed(scale, pan) ?: return@forEach
                val highlighted = selectedNodeId != null &&
                    (edge.sourceNoteId == selectedNodeId || edge.targetNoteId == selectedNodeId)
                val dimmed = selectedNodeId != null && !highlighted
                drawLine(
                    color = if (highlighted) colorScheme.primary else colorScheme.outline,
                    start = source,
                    end = target,
                    strokeWidth = if (highlighted) 3.2f else 1.5f,
                    alpha = if (dimmed) 0.12f else if (highlighted) 0.94f else 0.42f,
                )
            }

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorScheme.onSurface.toArgb()
                textSize = 12.dp.toPx()
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            drawIntoCanvas { canvas ->
                nodes.forEach { node ->
                    val position = layout.positions[node.id]?.transformed(scale, pan) ?: return@forEach
                    val radius = layout.radius(node) * scale
                    val selected = node.id == selectedNodeId
                    val connected = node.id in connectedNodeIds
                    val dimmed = selectedNodeId != null && !selected && !connected
                    val fill = colors.getValue(node.clusterKey())
                    drawCircle(fill.copy(alpha = if (dimmed) 0.24f else 0.94f), radius, position)
                    drawCircle(
                        color = if (selected) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.38f),
                        radius = radius + if (selected) 5f else 1.5f,
                        center = position,
                        style = Stroke(width = if (selected) 3f else 1.2f),
                        alpha = if (dimmed) 0.18f else 1f,
                    )
                    if (showLabels && (!dimmed || selected || connected)) {
                        val label = node.title.compactLabel(24)
                        canvas.nativeCanvas.drawText(label, position.x + radius + 7f, position.y + 4f, labelPaint)
                    }
                }
            }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClusterFields(
    layout: GraphLayout,
    colors: Map<String, Color>,
    scale: Float,
    pan: Offset,
) {
    layout.clusterCenters.forEach { (cluster, center) ->
        val radius = layout.clusterRadius(cluster) * scale
        val transformedCenter = center.transformed(scale, pan)
        drawCircle(colors.getValue(cluster).copy(alpha = 0.06f), radius, transformedCenter)
        drawCircle(colors.getValue(cluster).copy(alpha = 0.28f), radius, transformedCenter, style = Stroke(width = 1.2f))
    }
}

private data class GraphLayout(
    val positions: Map<String, Offset>,
    val nodesById: Map<String, VaultGraphWindowNode>,
    val clusterCenters: Map<String, Offset>,
) {
    fun radius(node: VaultGraphWindowNode): Float = 11f + min(8f, node.degree * 1.7f)

    fun nearestNode(point: Offset): VaultGraphWindowNode? = positions.entries
        .filter { (_, position) -> hypot(point.x - position.x, point.y - position.y) <= 34f }
        .minByOrNull { (_, position) -> hypot(point.x - position.x, point.y - position.y) }
        ?.key
        ?.let(nodesById::get)

    fun clusterRadius(cluster: String): Float {
        val center = clusterCenters[cluster] ?: return 0f
        return positions.filterKeys { nodesById[it]?.clusterKey() == cluster }.values
            .maxOfOrNull { hypot(it.x - center.x, it.y - center.y) + 36f }
            ?.plus(18f)
            ?: 42f
    }
}

private fun graphLayout(nodes: List<VaultGraphWindowNode>, size: IntSize): GraphLayout {
    if (nodes.isEmpty() || size == IntSize.Zero) return GraphLayout(emptyMap(), emptyMap(), emptyMap())
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val center = Offset(width / 2f, height / 2f)
    val grouped = nodes.groupBy { it.clusterKey() }.toSortedMap()
    val clusterRing = min(width, height) * 0.28f
    val clusterCenters = grouped.keys.mapIndexed { index, cluster ->
        val angle = if (grouped.size == 1) 0.0 else 2.0 * PI * index / grouped.size - PI / 2.0
        cluster to if (grouped.size == 1) center else Offset(
            center.x + cos(angle).toFloat() * clusterRing,
            center.y + sin(angle).toFloat() * clusterRing,
        )
    }.toMap()
    val positions = grouped.flatMap { (cluster, members) ->
        val clusterCenter = clusterCenters.getValue(cluster)
        val spread = max(32f, min(118f, 24f * sqrt(members.size.toFloat())))
        members.sortedBy { it.title.lowercase() }.mapIndexed { index, node ->
            val angle = if (members.size == 1) 0.0 else 2.0 * PI * index / members.size + PI / 4.0
            node.id to if (members.size == 1) clusterCenter else Offset(
                clusterCenter.x + cos(angle).toFloat() * spread,
                clusterCenter.y + sin(angle).toFloat() * spread,
            )
        }
    }.toMap()
    return GraphLayout(positions, nodes.associateBy { it.id }, clusterCenters)
}

private fun VaultGraphWindowNode.degree(edges: List<VaultGraphWindowEdge>): Int = edges.count {
    it.status == LinkResolutionStatus.RESOLVED && (it.sourceNoteId == id || it.targetNoteId == id)
}

private fun VaultGraphWindowNode.clusterKey(): String = path.substringBefore('/', missingDelimiterValue = "Vault").ifBlank { "Vault" }

private fun Offset.transformed(scale: Float, pan: Offset): Offset = Offset(x * scale + pan.x, y * scale + pan.y)

private fun graphColors(nodes: List<VaultGraphWindowNode>): Map<String, Color> {
    val palette = listOf(
        Color(0xFF1E88E5),
        Color(0xFF00897B),
        Color(0xFFF9A825),
        Color(0xFFD81B60),
        Color(0xFF5E35B1),
        Color(0xFF546E7A),
    )
    return nodes.map { it.clusterKey() }.distinct().sorted().mapIndexed { index, cluster -> cluster to palette[index % palette.size] }.toMap()
}

private fun String.compactLabel(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength - 1) + "."
