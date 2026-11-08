package com.example.kidsafe.child

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.kidsafe.ui.KsTheme

class MainActivity : ComponentActivity() {

    /** Set when the child tapped a "message from your parent" notification. */
    private var openChatRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openChatRequest = intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true
        setContent {
            KsTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                        ChildApp(
                            openChat = openChatRequest,
                            onChatOpened = { openChatRequest = false },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_CHAT, false)) openChatRequest = true
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat"
    }
}

private enum class Step { PAIR, DISCLOSURE, PERMISSIONS, HOME }

/** The child app is four steps in a row: link -> read the disclosure -> allow permissions -> home. */
@Composable
fun ChildApp(openChat: Boolean, onChatOpened: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { PairingRepository(ctx.applicationContext) }
    var step by remember {
        mutableStateOf(
            when {
                !repo.isPaired -> Step.PAIR
                !Permissions.allGranted(ctx) -> Step.PERMISSIONS
                else -> Step.HOME
            },
        )
    }
    when (step) {
        Step.PAIR -> PairScreen(repo) { step = Step.DISCLOSURE }
        Step.DISCLOSURE -> DisclosureScreen { step = Step.PERMISSIONS }
        Step.PERMISSIONS -> PermissionScreen(repo) { step = Step.HOME }
        Step.HOME -> HomeScreen(repo, openChat = openChat, onChatOpened = onChatOpened)
    }
}
