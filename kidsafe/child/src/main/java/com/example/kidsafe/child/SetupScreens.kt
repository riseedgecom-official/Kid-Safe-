package com.example.kidsafe.child

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.kidsafe.ui.Banner
import com.example.kidsafe.ui.HeroCard
import com.example.kidsafe.ui.KsCard
import com.example.kidsafe.ui.Muted
import com.example.kidsafe.ui.PrimaryButton
import com.example.kidsafe.ui.Tone
import kotlinx.coroutines.launch

/** Step 1: the child types their name and the code the parent shows. */
@Composable
fun PairScreen(repo: PairingRepository, onPaired: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        HeroCard {
            Text("🛡️ KidSafe", style = MaterialTheme.typography.headlineLarge)
            Text("Your safety, your family's peace of mind.", style = MaterialTheme.typography.bodyLarge)
            Text("CHILD APP  •  SECURE CONNECTION", style = MaterialTheme.typography.labelMedium)
        }
        Text("Connect to your parent", style = MaterialTheme.typography.headlineSmall)
        Muted("Ask your parent to open the Parent app, tap Add child, and share the pairing code.")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Code") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton(
            text = if (busy) "Linking…" else "Link",
            enabled = !busy && name.isNotBlank() && code.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    repo.pair(code, name)
                        .onSuccess { onPaired() }
                        .onFailure {
                            error = it.message ?: "Could not link. Check your internet and try again."
                            busy = false
                        }
                }
            },
        )
        error?.let { Banner(it, tone = Tone.Danger) }
    }
}

/** Step 2. Prominent disclosure: shown BEFORE any system permission dialog (required by Google Play). */
@Composable
fun DisclosureScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Before you continue", style = MaterialTheme.typography.headlineSmall)
        KsCard {
            Text(
                "This app collects your phone’s location in the background — even when the app is closed " +
                    "or not in use — and shares it with the parent you just linked with. " +
                    "It also shares your battery level.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Why: so your parent can find you if something goes wrong, even if your phone is locked.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "A notification is always shown while this is on. You will only be asked to allow this once.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        KsCard {
            Text(
                "এই অ্যাপ ব্যাকগ্রাউন্ডে (অ্যাপ বন্ধ থাকলেও) তোমার ফোনের লোকেশন ও ব্যাটারি লেভেল " +
                    "তোমার লিঙ্ক করা অভিভাবকের সাথে শেয়ার করবে, যাতে বিপদে তোমাকে খুঁজে পাওয়া যায়। " +
                    "চালু থাকলে সবসময় একটি নোটিফিকেশন দেখা যাবে। পারমিশন শুধু একবারই দিতে হবে।",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        PrimaryButton(text = "I understand · বুঝেছি", onClick = onContinue, modifier = Modifier.fillMaxWidth())
    }
}

/** One numbered permission step: a title, an optional hint and one button. */
@Composable
private fun PermissionStep(
    title: String,
    done: Boolean,
    buttonText: String,
    enabled: Boolean,
    hint: String? = null,
    onClick: () -> Unit,
) {
    KsCard {
        Text(
            if (done) "✅ $title" else title,
            style = MaterialTheme.typography.titleMedium,
        )
        if (hint != null) Muted(hint)
        PrimaryButton(
            text = buttonText,
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Step 3: the three Android permissions, one at a time. */
@Composable
fun PermissionScreen(repo: PairingRepository, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var fg by remember { mutableStateOf(Permissions.hasForegroundLocation(ctx)) }
    var bg by remember { mutableStateOf(Permissions.hasBackgroundLocation(ctx)) }
    var notif by remember { mutableStateOf(Permissions.hasNotifications(ctx)) }

    // Re-check when the user comes back from the system settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        fg = Permissions.hasForegroundLocation(ctx)
        bg = Permissions.hasBackgroundLocation(ctx)
        notif = Permissions.hasNotifications(ctx)
    }

    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        fg = Permissions.hasForegroundLocation(ctx)
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        bg = Permissions.hasBackgroundLocation(ctx)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notif = Permissions.hasNotifications(ctx)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Allow permissions (one time)", style = MaterialTheme.typography.headlineSmall)

        PermissionStep(
            title = "1. Location",
            done = fg,
            buttonText = "Allow location",
            enabled = !fg,
            onClick = {
                fgLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            },
        )

        PermissionStep(
            title = "2. Location — all the time",
            done = bg,
            hint = "On the next screen choose “Allow all the time”.",
            buttonText = "Allow all the time",
            enabled = fg && !bg,
            onClick = { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionStep(
                title = "3. Notifications",
                done = notif,
                buttonText = "Allow notifications",
                enabled = !notif,
                onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }

        val ready = fg && bg && notif
        val scope = rememberCoroutineScope()
        PrimaryButton(
            text = "Finish",
            enabled = ready,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    repo.recordConsent()
                    onDone()
                }
            },
        )
    }
}
