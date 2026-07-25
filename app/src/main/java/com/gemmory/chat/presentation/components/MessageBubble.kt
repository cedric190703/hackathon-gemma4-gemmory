package com.gemmory.chat.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gemmory.chat.domain.ChatMessage
import com.gemmory.chat.domain.MessageRole
import com.gemmory.chat.domain.MessageStatus

/**
 * One chat bubble.
 *
 * [textProvider] is a lambda rather than a value so a streaming update only
 * invalidates this bubble's text layout instead of the whole list.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    textProvider: () -> String,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val background = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(background)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics {
                    contentDescription = if (isUser) "Your message" else "Assistant message"
                },
        ) {
            val text = textProvider()
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = foreground,
                )
            } else if (message.status == MessageStatus.GENERATING) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = foreground,
                )
            }

            val label = message.statusLabel()
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun ContextTrimmedNotice(droppedMessages: Int, modifier: Modifier = Modifier) {
    Text(
        text = "$droppedMessages older message${if (droppedMessages == 1) "" else "s"} " +
            "are no longer part of the model's context.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .semantics { contentDescription = "Context trimmed notice" },
    )
}

@Composable
private fun ChatMessage.statusLabel(): String? = when (status) {
    MessageStatus.CANCELLED -> errorText ?: "Stopped"
    MessageStatus.FAILED -> errorText ?: "Failed"
    else -> null
}

@Composable
fun EmptyConversationHint(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Everything runs on this device",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Ask Gemma 4 E2B anything. Your prompts never leave the phone.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
