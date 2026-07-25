package com.gemmory.vault.presentation

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmory.vault.domain.LinkResolutionStatus
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val GraphMinScale = 0.48f
private const val GraphMaxScale = 4.2f
private const val GoldenAngle = 2.3999632f

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
    val layout = remember(visibleNodes, visibleEdges, canvasSize) { graphLayout(visibleNodes, visibleEdges, canvasSize) }
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
    fun viewportCenter(): Offset = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    fun updateTransform(zoomChange: Float, panChange: Offset, anchor: Offset) {
        val oldScale = scale
        val newScale = (oldScale * zoomChange).coerceIn(GraphMinScale, GraphMaxScale)
        pan = anchoredPanForScale(pan, anchor, oldScale, newScale) + panChange
        scale = newScale
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
                        onClick = { updateTransform(1.25f, Offset.Zero, viewportCenter()) },
                        icon = Icons.Filled.Add,
                        label = "Zoom in",
                    )
                    GraphControl(
                        onClick = { updateTransform(1f / 1.25f, Offset.Zero, viewportCenter()) },
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
                    onTransformGesture = ::updateTransform,
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
    onTransformGesture: (zoomChange: Float, panChange: Offset, anchor: Offset) -> Unit,
    onCanvasSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = graphColors(nodes)
    val colorScheme = MaterialTheme.colorScheme
    Canvas(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged(onCanvasSizeChanged)
            .pointerInput(layout) {
                detectTransformGestures { centroid, panChange, zoomChange, _ ->
                    onTransformGesture(zoomChange, panChange, centroid)
                }
            }
            .pointerInput(layout, scale, pan) {
                detectTapGestures { tap ->
                    val graphTap = (tap - pan) / scale
                    layout.nearestNode(graphTap)?.let { onNodeSelected(it.id) }
                }
            },
    ) {
        drawGraphBackdrop(colorScheme, scale, pan)
        drawClusterFields(layout, colors, scale, pan)
        drawGraphLinks(
            edges = edges,
            layout = layout,
            nodeColors = colors,
            colorScheme = colorScheme,
            scale = scale,
            pan = pan,
            selectedNodeId = selectedNodeId,
        )

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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraphBackdrop(
    colorScheme: ColorScheme,
    scale: Float,
    pan: Offset,
) {
    drawRect(
        brush = Brush.verticalGradient(
            listOf(
                colorScheme.surface,
                colorScheme.surfaceVariant.copy(alpha = 0.72f),
                colorScheme.surface,
            ),
        ),
    )

    val spacing = (72f * scale).coerceIn(34f, 128f)
    val startX = pan.x % spacing - spacing
    val startY = pan.y % spacing - spacing
    var x = startX
    while (x < size.width + spacing) {
        drawLine(
            color = colorScheme.outlineVariant.copy(alpha = 0.10f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
        )
        x += spacing
    }
    var y = startY
    while (y < size.height + spacing) {
        drawLine(
            color = colorScheme.outlineVariant.copy(alpha = 0.10f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        y += spacing
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraphLinks(
    edges: List<VaultGraphWindowEdge>,
    layout: GraphLayout,
    nodeColors: Map<String, Color>,
    colorScheme: ColorScheme,
    scale: Float,
    pan: Offset,
    selectedNodeId: String?,
) {
    val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorScheme.onSurface.toArgb()
        textSize = 10.dp.toPx()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorScheme.surface.copy(alpha = 0.82f).toArgb()
    }

    edges.forEach { edge ->
        val sourceNode = layout.nodesById[edge.sourceNoteId] ?: return@forEach
        val targetId = edge.targetNoteId ?: return@forEach
        val targetNode = layout.nodesById[targetId] ?: return@forEach
        val rawSource = layout.positions[edge.sourceNoteId]?.transformed(scale, pan) ?: return@forEach
        val rawTarget = layout.positions[targetId]?.transformed(scale, pan) ?: return@forEach
        val vector = rawTarget - rawSource
        val distance = max(1f, vector.getDistance())
        val direction = vector / distance
        val sourceRadius = layout.radius(sourceNode) * scale
        val targetRadius = layout.radius(targetNode) * scale
        val start = rawSource + direction * (sourceRadius + 8f)
        val end = rawTarget - direction * (targetRadius + 10f)
        val normal = Offset(-direction.y, direction.x)
        val curveDirection = if ((edge.sourceNoteId.hashCode() xor targetId.hashCode()) % 2 == 0) 1f else -1f
        val curve = (18f + min(54f, distance * 0.12f)) * curveDirection
        val control = midpoint(start, end) + normal * curve
        val highlighted = selectedNodeId != null &&
            (edge.sourceNoteId == selectedNodeId || edge.targetNoteId == selectedNodeId)
        val dimmed = selectedNodeId != null && !highlighted
        val sourceColor = nodeColors.getValue(sourceNode.clusterKey())
        val targetColor = nodeColors.getValue(targetNode.clusterKey())
        val path = Path().apply {
            moveTo(start.x, start.y)
            quadraticTo(control.x, control.y, end.x, end.y)
        }
        val alpha = if (dimmed) 0.10f else if (highlighted) 0.96f else 0.50f
        val linkColor = if (highlighted) colorScheme.primary else blend(sourceColor, targetColor, 0.48f)

        if (highlighted) {
            drawPath(
                path = path,
                color = colorScheme.primary.copy(alpha = 0.20f),
                style = Stroke(width = 13f, cap = StrokeCap.Round),
            )
            drawPath(
                path = path,
                color = colorScheme.secondary.copy(alpha = 0.22f),
                style = Stroke(width = 7f, cap = StrokeCap.Round),
            )
        }
        drawPath(
            path = path,
            color = Color.Black.copy(alpha = if (dimmed) 0.04f else 0.24f),
            style = Stroke(width = if (highlighted) 5.5f else 3.5f, cap = StrokeCap.Round),
        )
        drawPath(
            path = path,
            color = linkColor,
            alpha = alpha,
            style = Stroke(width = if (highlighted) 3.4f else 2.1f, cap = StrokeCap.Round),
        )
        drawArrowHead(
            tip = end,
            control = control,
            color = linkColor,
            alpha = if (dimmed) 0.08f else if (highlighted) 0.92f else 0.42f,
            size = if (highlighted) 10.5f else 8f,
        )

        if (highlighted && edge.targetLabel.isNotBlank() && distance > 112f) {
            drawIntoCanvas { canvas ->
                val label = edge.targetLabel.compactLabel(20)
                val labelWidth = labelTextPaint.measureText(label)
                val labelX = control.x - labelWidth / 2f
                val labelY = control.y - 8f
                canvas.nativeCanvas.drawRoundRect(
                    RectF(labelX - 8f, labelY - 18f, labelX + labelWidth + 8f, labelY + 6f),
                    12f,
                    12f,
                    labelBackgroundPaint,
                )
                canvas.nativeCanvas.drawText(label, labelX, labelY, labelTextPaint)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(
    tip: Offset,
    control: Offset,
    color: Color,
    alpha: Float,
    size: Float,
) {
    val angle = atan2(tip.y - control.y, tip.x - control.x)
    val left = Offset(
        tip.x - cos(angle - PI.toFloat() / 7f) * size,
        tip.y - sin(angle - PI.toFloat() / 7f) * size,
    )
    val right = Offset(
        tip.x - cos(angle + PI.toFloat() / 7f) * size,
        tip.y - sin(angle + PI.toFloat() / 7f) * size,
    )
    drawPath(
        path = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(left.x, left.y)
            lineTo(right.x, right.y)
            close()
        },
        color = color,
        alpha = alpha,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClusterFields(
    layout: GraphLayout,
    colors: Map<String, Color>,
    scale: Float,
    pan: Offset,
) {
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.White.copy(alpha = 0.58f).toArgb()
        textSize = 10.dp.toPx()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    layout.clusterCenters.forEach { (cluster, center) ->
        val radius = layout.clusterRadius(cluster) * scale
        val transformedCenter = center.transformed(scale, pan)
        val clusterColor = colors[layout.clusterPrimaryKey(cluster)] ?: Color.White
        drawCircle(clusterColor.copy(alpha = 0.075f), radius * 1.12f, transformedCenter)
        drawCircle(clusterColor.copy(alpha = 0.12f), radius * 0.82f, transformedCenter)
        drawCircle(
            color = clusterColor.copy(alpha = 0.36f),
            radius = radius,
            center = transformedCenter,
            style = Stroke(width = 1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(13f, 18f))),
        )
        layout.clusterLabels[cluster]?.takeIf { scale > 0.62f }?.let { label ->
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawText(label.compactLabel(22), transformedCenter.x - radius + 18f, transformedCenter.y - radius + 24f, labelPaint)
            }
        }
    }
}

private data class GraphLayout(
    val positions: Map<String, Offset>,
    val nodesById: Map<String, VaultGraphWindowNode>,
    val clusterCenters: Map<String, Offset>,
    val clusterLabels: Map<String, String>,
    val nodeClusterIds: Map<String, String>,
    val clusterPrimaryKeys: Map<String, String>,
) {
    fun radius(node: VaultGraphWindowNode): Float = 11f + min(8f, node.degree * 1.7f)

    fun nearestNode(point: Offset): VaultGraphWindowNode? = positions.entries
        .filter { (nodeId, position) ->
            val node = nodesById[nodeId] ?: return@filter false
            hypot(point.x - position.x, point.y - position.y) <= radius(node) + 20f
        }
        .minByOrNull { (_, position) -> hypot(point.x - position.x, point.y - position.y) }
        ?.key
        ?.let(nodesById::get)

    fun clusterRadius(cluster: String): Float {
        val center = clusterCenters[cluster] ?: return 0f
        return positions.filterKeys { nodeClusterIds[it] == cluster }.values
            .maxOfOrNull { hypot(it.x - center.x, it.y - center.y) + 36f }
            ?.plus(18f)
            ?: 42f
    }

    fun clusterPrimaryKey(cluster: String): String = clusterPrimaryKeys[cluster] ?: cluster
}

private data class GraphCluster(
    val id: String,
    val label: String,
    val primaryKey: String,
    val members: List<VaultGraphWindowNode>,
)

private fun graphLayout(
    nodes: List<VaultGraphWindowNode>,
    edges: List<VaultGraphWindowEdge>,
    size: IntSize,
): GraphLayout {
    if (nodes.isEmpty() || size == IntSize.Zero) {
        return GraphLayout(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
    }
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    val center = Offset(width / 2f, height / 2f)
    val grouped = graphClusters(nodes, edges)
    val clusterRing = min(width, height) * 0.28f
    val clusterCenters = grouped.mapIndexed { index, cluster ->
        val angle = if (grouped.size == 1) 0.0 else 2.0 * PI * index / grouped.size - PI / 2.0
        cluster.id to if (grouped.size == 1) center else Offset(
            center.x + cos(angle).toFloat() * clusterRing,
            center.y + sin(angle).toFloat() * clusterRing,
        )
    }.toMap()
    val nodeClusterIds = grouped.flatMap { cluster -> cluster.members.map { it.id to cluster.id } }.toMap()
    val positions = grouped.flatMapIndexed { groupIndex, cluster ->
        val clusterCenter = clusterCenters.getValue(cluster.id)
        val members = cluster.members.sortedWith(compareByDescending<VaultGraphWindowNode> { it.degree }.thenBy { it.title.lowercase() })
        val spread = max(42f, min(172f, 31f * sqrt(members.size.toFloat())))
        members.mapIndexed { index, node ->
            val progress = if (members.size <= 1) 0f else sqrt((index + 1f) / members.size)
            val radius = spread * (0.18f + progress * 0.78f)
            val angle = groupIndex * 0.7f + index * GoldenAngle
            node.id to if (members.size == 1) clusterCenter else Offset(
                clusterCenter.x + cos(angle) * radius,
                clusterCenter.y + sin(angle) * radius,
            )
        }
    }.toMap()
    return GraphLayout(
        positions = relaxLinkedNodes(positions, edges, nodeClusterIds),
        nodesById = nodes.associateBy { it.id },
        clusterCenters = clusterCenters,
        clusterLabels = grouped.associate { it.id to it.label },
        nodeClusterIds = nodeClusterIds,
        clusterPrimaryKeys = grouped.associate { it.id to it.primaryKey },
    )
}

private fun VaultGraphWindowNode.degree(edges: List<VaultGraphWindowEdge>): Int = edges.count {
    it.status == LinkResolutionStatus.RESOLVED && (it.sourceNoteId == id || it.targetNoteId == id)
}

private fun graphClusters(
    nodes: List<VaultGraphWindowNode>,
    edges: List<VaultGraphWindowEdge>,
): List<GraphCluster> {
    val nodesById = nodes.associateBy { it.id }
    val adjacency = nodes.associate { it.id to mutableSetOf<String>() }.toMutableMap()
    edges.forEach { edge ->
        val targetId = edge.targetNoteId ?: return@forEach
        if (edge.sourceNoteId in nodesById && targetId in nodesById) {
            adjacency.getValue(edge.sourceNoteId) += targetId
            adjacency.getValue(targetId) += edge.sourceNoteId
        }
    }

    val visited = mutableSetOf<String>()
    val linkedClusters = mutableListOf<List<VaultGraphWindowNode>>()
    val isolated = mutableListOf<VaultGraphWindowNode>()
    nodes.sortedBy { it.title.lowercase() }.forEach { node ->
        if (!visited.add(node.id)) return@forEach
        val stack = mutableListOf(node.id)
        val componentIds = mutableListOf<String>()
        while (stack.isNotEmpty()) {
            val id = stack.removeAt(stack.lastIndex)
            componentIds += id
            adjacency.getValue(id).forEach { next ->
                if (visited.add(next)) stack += next
            }
        }
        val component = componentIds.mapNotNull(nodesById::get)
        if (component.size > 1) linkedClusters += component else isolated += node
    }

    val clusters = linkedClusters
        .sortedWith(compareByDescending<List<VaultGraphWindowNode>> { it.size }.thenBy { it.first().title.lowercase() })
        .mapIndexed { index, members ->
            val primaryKey = members.primaryClusterKey()
            GraphCluster(
                id = "linked:$index",
                label = clusterLabel(primaryKey, members.size),
                primaryKey = primaryKey,
                members = members,
            )
        }
        .toMutableList()

    isolated
        .groupBy { it.clusterKey() }
        .toSortedMap()
        .forEach { (key, members) ->
            clusters += GraphCluster(
                id = "folder:$key",
                label = clusterLabel(key, members.size),
                primaryKey = key,
                members = members,
            )
        }

    return clusters.sortedWith(compareByDescending<GraphCluster> { it.members.size }.thenBy { it.label.lowercase() })
}

private fun relaxLinkedNodes(
    initial: Map<String, Offset>,
    edges: List<VaultGraphWindowEdge>,
    nodeClusterIds: Map<String, String>,
): Map<String, Offset> {
    val positions = initial.toMutableMap()
    repeat(14) {
        edges.forEach { edge ->
            val targetId = edge.targetNoteId ?: return@forEach
            if (nodeClusterIds[edge.sourceNoteId] != nodeClusterIds[targetId]) return@forEach
            val source = positions[edge.sourceNoteId] ?: return@forEach
            val target = positions[targetId] ?: return@forEach
            val delta = target - source
            positions[edge.sourceNoteId] = source + delta * 0.018f
            positions[targetId] = target - delta * 0.018f
        }
    }
    return positions
}

private fun List<VaultGraphWindowNode>.primaryClusterKey(): String =
    groupBy { it.clusterKey() }
        .maxWithOrNull(compareBy<Map.Entry<String, List<VaultGraphWindowNode>>> { it.value.size }.thenBy { it.key })
        ?.key
        ?: "Vault"

private fun clusterLabel(primaryKey: String, size: Int): String =
    if (size == 1) primaryKey else "$primaryKey / $size notes"

private fun VaultGraphWindowNode.clusterKey(): String = path.substringBefore('/', missingDelimiterValue = "Vault").ifBlank { "Vault" }

private fun Offset.transformed(scale: Float, pan: Offset): Offset = Offset(x * scale + pan.x, y * scale + pan.y)

private fun anchoredPanForScale(pan: Offset, anchor: Offset, oldScale: Float, newScale: Float): Offset {
    if (oldScale == 0f) return pan
    val graphAnchor = (anchor - pan) / oldScale
    return anchor - graphAnchor * newScale
}

private fun midpoint(start: Offset, end: Offset): Offset = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)

private fun blend(start: Color, end: Color, amount: Float): Color =
    Color(
        red = start.red + (end.red - start.red) * amount,
        green = start.green + (end.green - start.green) * amount,
        blue = start.blue + (end.blue - start.blue) * amount,
        alpha = start.alpha + (end.alpha - start.alpha) * amount,
    )

private fun graphColors(nodes: List<VaultGraphWindowNode>): Map<String, Color> {
    val palette = listOf(
        Color(0xFF64B5F6),
        Color(0xFF4DB6AC),
        Color(0xFFFFD54F),
        Color(0xFFF06292),
        Color(0xFFB39DDB),
        Color(0xFFAED581),
        Color(0xFFFF8A65),
        Color(0xFF4DD0E1),
    )
    return nodes.map { it.clusterKey() }.distinct().sorted().mapIndexed { index, cluster -> cluster to palette[index % palette.size] }.toMap()
}

private fun String.compactLabel(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength - 1) + "."
