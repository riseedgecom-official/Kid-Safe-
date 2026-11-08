package com.example.kidsafe.child

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.kidsafe.core.ChatMessage
import com.example.kidsafe.ui.ChatBubble
import com.example.kidsafe.ui.ChatInputBar
import com.example.kidsafe.ui.EmptyState
import com.example.kidsafe.ui.KsScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One-tap preset replies — shown as real buttons above the text field. */
private val QUICK_REPLIES = listOf(
    "👍 I'm okay",
    "📞 Call me",
    "🚫 Can't talk now",
    "🏠 Heading home",
)

/** Chat with the linked parent. */
@Composable
fun ChatScreen(onBack: () -> Unit) {
    val repo = remember { ChatRepository() }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    BackHandler(onBack = onBack)

    DisposableEffect(Unit) {
        val reg = repo.listen { messages = it }
        onDispose { reg?.remove() }
    }

    // While this screen is in front, a new message from the parent doesn't need a notification.
    DisposableEffect(Unit) {
        ChatVisibility.isOpen = true
        onDispose { ChatVisibility.isOpen = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ChatVisibility.isOpen = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { ChatVisibility.isOpen = false }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    KsScreen(
        title = "💬 Chat with your parent",
        onBack = onBack,
        bottomBar = { ChatInputBar(QUICK_REPLIES) { repo.send(it) } },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (messages.isEmpty()) {
                item { EmptyState("👋", "No messages yet", "Say hi to your parent.") }
            }
            items(messages, key = { it.id }) { m ->
                // On the child's own screen, a message the child sent sits on the right.
                ChatBubble(text = m.text, timeText = timeFormat.format(Date(m.time)), mine = m.fromChild)
            }
        }
    }
}
