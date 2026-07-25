package com.gemmory.vault.presentation

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gemmory.vault.domain.LinkResolutionStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

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
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Vault graph")
                        Text(
                            "${state.nodes.size} notes, ${state.edges.count { it.status == LinkResolutionStatus.RESOLVED }} links",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close graph window")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.nodes.isEmpty()) {
                    Text("No vault notes yet", style = MaterialTheme.typography.titleMedium)
                } else {
                    VaultGraphCanvas(state = state, modifier = Modifier.fillMaxSize())
                }
            }

            val unresolved = state.edges.filter { it.status != LinkResolutionStatus.RESOLVED }
            if (unresolved.isNotEmpty()) {
                HorizontalDivider()
                UnresolvedLinksList(
                    edges = unresolved,
                    nodesById = state.nodes.associateBy { it.id },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun UnresolvedLinksList(
    edges: List<VaultGraphEdge>,
    nodesById: Map<String, VaultGraphNode>,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(edges) { edge ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        edge.status.name.lowercase(),
                        modifier = Modifier.width(92.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Column {
                        Text(
                            nodesById[edge.sourceNoteId]?.title.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            edge.targetLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultGraphCanvas(
    state: VaultGraphUiState,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Canvas(modifier = modifier) {
        val nodes = state.nodes
        if (nodes.isEmpty()) return@Canvas

        val center = Offset(size.width / 2f, size.height / 2f)
        val nodeRadius = (min(size.width, size.height) / when {
            nodes.size <= 6 -> 15f
            nodes.size <= 16 -> 23f
            else -> 34f
        }).coerceIn(13f, 34f)
        val graphRadius = (min(size.width, size.height) / 2f - nodeRadius - 58f).coerceAtLeast(0f)
        val positions = nodes.mapIndexed { index, node ->
            val position = if (nodes.size == 1) {
                center
            } else {
                val angle = -PI / 2.0 + (2.0 * PI * index / nodes.size)
                Offset(
                    x = center.x + (cos(angle) * graphRadius).toFloat(),
                    y = center.y + (sin(angle) * graphRadius).toFloat(),
                )
            }
            node.id to position
        }.toMap()

        val edgeColor = colorScheme.outline.copy(alpha = 0.62f)
        state.edges
            .filter { it.status == LinkResolutionStatus.RESOLVED && it.targetNoteId != null }
            .forEach { edge ->
                val source = positions[edge.sourceNoteId] ?: return@forEach
                val target = positions[edge.targetNoteId] ?: return@forEach
                if (source == target) {
                    drawCircle(
                        color = edgeColor,
                        radius = nodeRadius * 1.42f,
                        center = source,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                } else {
                    drawLine(
                        color = edgeColor,
                        start = source,
                        end = target,
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawCircle(
                        color = colorScheme.primary.copy(alpha = 0.82f),
                        radius = 3.dp.toPx(),
                        center = target,
                    )
                }
            }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorScheme.onSurface.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 12.dp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorScheme.onSurfaceVariant.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 10.dp.toPx()
        }

        nodes.forEachIndexed { index, node ->
            val position = positions.getValue(node.id)
            val fill = graphColors[index % graphColors.size]
            drawCircle(
                color = fill.copy(alpha = 0.92f),
                radius = nodeRadius,
                center = position,
            )
            drawCircle(
                color = colorScheme.onSurface.copy(alpha = 0.56f),
                radius = nodeRadius,
                center = position,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawContext.canvas.nativeCanvas.drawText(
                node.title.compactLabel(18),
                position.x,
                position.y + nodeRadius + 18.dp.toPx(),
                labelPaint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                node.path.compactLabel(22),
                position.x,
                position.y + nodeRadius + 32.dp.toPx(),
                pathPaint,
            )
        }
    }
}

private fun String.compactLabel(maxLength: Int): String =
    if (length <= maxLength) this else take(maxLength - 1) + "."

private val graphColors = listOf(
    Color(0xFF8AB4F8),
    Color(0xFF81C995),
    Color(0xFFFDD663),
    Color(0xFFF28B82),
    Color(0xFFC58AF9),
    Color(0xFF78D9EC),
)
