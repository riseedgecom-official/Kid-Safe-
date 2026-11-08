package com.example.kidsafe.child

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.kidsafe.core.Fmt
import com.example.kidsafe.core.ScreenTimeGoal
import com.example.kidsafe.ui.Banner
import com.example.kidsafe.ui.ChoiceChips
import com.example.kidsafe.ui.HeroCard
import com.example.kidsafe.ui.KsCard
import com.example.kidsafe.ui.Muted
import com.example.kidsafe.ui.OutlineButton
import com.example.kidsafe.ui.Pill
import com.example.kidsafe.ui.PrimaryButton
import com.example.kidsafe.ui.SectionTitle
import com.example.kidsafe.ui.TonalButton
import com.example.kidsafe.ui.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SHARE_CHOICES = listOf(5, 15, 30, 60, 180, 360, 720, 1440, ScreenShareService.ALL_TIME)

private fun shareLabel(m: Int): String = when {
    m == ScreenShareService.ALL_TIME -> "All time"
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} hr"
    else -> "$m min"
}

/** The child's home screen. Everything the parent can see is explained here, in plain words. */
@Composable
fun HomeScreen(repo: PairingRepository, openChat: Boolean, onChatOpened: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var chatOpen by remember { mutableStateOf(false) }
    var childName by remember { mutableStateOf(repo.childName) }

    var sosMessage by remember { mutableStateOf<String?>(null) }
    var sosFailed by remember { mutableStateOf(false) }
    var talkMessage by remember { mutableStateOf<String?>(null) }
    var checkInMessage by remember { mutableStateOf<String?>(null) }
    var checkInFailed by remember { mutableStateOf(false) }
    var shareMinutes by remember { mutableStateOf(ScreenShareService.DEFAULT_MINUTES) }
    var shareMessage by remember { mutableStateOf<String?>(null) }

    var usageOk by remember { mutableStateOf(Permissions.hasUsageAccess(ctx)) }
    var usedMs by remember { mutableLongStateOf(0L) }
    var goalMinutes by remember { mutableStateOf(ScreenTimeConfig.goalMinutes(ctx)) }

    var webEnabled by remember { mutableStateOf(WebFilterConfig.isEnabled(ctx)) }
    var webNeedsConsent by remember { mutableStateOf(WebFilterConfig.needsConsent(ctx)) }

    val projectionManager = remember { ctx.getSystemService(MediaProjectionManager::class.java) }

    val shareLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(ctx, ScreenShareService::class.java).apply {
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenShareService.EXTRA_MINUTES, shareMinutes)
            }
            ContextCompat.startForegroundService(ctx, intent)
            shareMessage = if (shareMinutes == ScreenShareService.ALL_TIME) {
                "Screen sharing started — it will continue until you stop it. " +
                    "A notification stays on screen the whole time."
            } else {
                "Sharing started — ends by itself in $shareMinutes min. " +
                    "A notification stays on screen the whole time."
            }
        } else {
            shareMessage = "Screen sharing was not started."
        }
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        WebFilterConfig.apply(ctx)
        webNeedsConsent = WebFilterConfig.needsConsent(ctx)
    }

    // Tapping "Message from your parent" in the notification shade lands here.
    LaunchedEffect(openChat) {
        if (openChat) {
            chatOpen = true
            onChatOpened()
        }
    }

    // Phones paired with an older version don't have the name saved locally yet.
    LaunchedEffect(Unit) {
        repo.loadNameIfMissing()
        childName = repo.childName
    }

    // The parent can switch web protection on at any time, so keep checking while this screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            webEnabled = WebFilterConfig.isEnabled(ctx)
            webNeedsConsent = WebFilterConfig.needsConsent(ctx)
            delay(2_000)
        }
    }

    // Re-check when the child comes back from the Settings screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        usageOk = Permissions.hasUsageAccess(ctx)
        goalMinutes = ScreenTimeConfig.goalMinutes(ctx)
    }

    LaunchedEffect(Unit) {
        ContextCompat.startForegroundService(ctx, Intent(ctx, LocationService::class.java))
    }

    LaunchedEffect(usageOk) {
        if (usageOk) {
            repo.recordUsageConsentOnce()
            UsageScheduler.schedule(ctx)
            UsageScheduler.runNow(ctx)
        }
    }

    // Today's screen time and the parent's goal, refreshed every 30 seconds while the screen is open.
    LaunchedEffect(usageOk) {
        while (true) {
            goalMinutes = ScreenTimeConfig.goalMinutes(ctx)
            if (usageOk) {
                usedMs = runCatching { withContext(Dispatchers.IO) { TodayUsage.totalMsToday(ctx) } }
                    .getOrDefault(usedMs)
            }
            delay(30_000)
        }
    }

    if (chatOpen) {
        ChatScreen(onBack = { chatOpen = false })
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCard {
            Text("KidSafe", style = MaterialTheme.typography.labelLarge)
            Text(
                if (childName != null) "Hi ${childName}! 👋" else "You're connected!",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text("Your safety dashboard", style = MaterialTheme.typography.bodyMedium)
        }

        // ---- What your parent can see ----
        KsCard {
            Pill("ON", Tone.Success)
            Text("📍 Location sharing is on", style = MaterialTheme.typography.titleMedium)
            Muted(
                "Your parent can see your last location and battery, even if your phone is locked. " +
                    "লোকেশন শেয়ারিং চালু আছে।",
            )
        }

        if (usageOk) {
            KsCard {
                Pill("ON", Tone.Success)
                Text("📊 App usage reporting is on", style = MaterialTheme.typography.titleMedium)
                Muted("Your parent can see which apps you used today and for how long.")
                if (goalMinutes > 0) {
                    val reached = ScreenTimeGoal.isExceeded(usedMs, goalMinutes)
                    Text(
                        "Today: ${Fmt.duration(usedMs)} of ${Fmt.goal(goalMinutes)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LinearProgressIndicator(
                        progress = { ScreenTimeGoal.progress(usedMs, goalMinutes) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (reached) {
                        Banner(
                            "You've reached today's screen-time goal. Nothing is blocked — " +
                                "it's just a reminder from your parent.",
                            tone = Tone.Warning,
                        )
                    } else {
                        Muted(
                            "Your parent set a daily goal. Nothing is blocked — " +
                                "you'll just get a reminder when you reach it.",
                        )
                    }
                } else {
                    Text("Today: ${Fmt.duration(usedMs)}", style = MaterialTheme.typography.titleSmall)
                }
            }
        } else {
            // Disclosure shown BEFORE sending the child to the system Settings screen.
            KsCard {
                Text("📊 Allow app usage reporting", style = MaterialTheme.typography.titleMedium)
                Muted(
                    "So your parent can see which apps you used today and for how long. " +
                        "Only app names and time are shared — not what you do inside an app. " +
                        "On the next screen, find “KidSafe (Child)” in the list and turn it on. " +
                        "(একবারই করতে হবে। কোন অ্যাপ কতক্ষণ চলেছে শুধু সেটাই অভিভাবক দেখবে। " +
                        "পরের স্ক্রিনে “KidSafe (Child)” খুঁজে চালু করো।)",
                )
                PrimaryButton(
                    text = "Open settings",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                )
            }
        }

        if (webEnabled && webNeedsConsent) {
            // Disclosure shown BEFORE the Android VPN prompt.
            KsCard {
                Text("Allow web protection", style = MaterialTheme.typography.titleMedium)
                Muted(
                    "Your parent turned on web protection. It blocks adult and unsafe websites. " +
                        "Android will ask you to allow a “VPN connection”. It is only used to check website " +
                        "names — your passwords, messages and what you read are not seen. " +
                        "If you try to open a blocked site, your parent can see the site name. " +
                        "(অভিভাবক ওয়েব প্রোটেকশন চালু করেছেন। এটি শুধু ওয়েবসাইটের নাম দেখে খারাপ সাইট বন্ধ করে — " +
                        "পাসওয়ার্ড বা মেসেজ দেখে না।)",
                )
                PrimaryButton(
                    text = "Turn on web protection",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val intent = VpnService.prepare(ctx)
                        if (intent != null) {
                            vpnLauncher.launch(intent)
                        } else {
                            WebFilterConfig.apply(ctx)
                            webNeedsConsent = false
                        }
                    },
                )
            }
        } else if (webEnabled) {
            KsCard {
                Pill("ON", Tone.Success)
                Text("Web protection is on ✅", style = MaterialTheme.typography.titleMedium)
                Muted("Adult and unsafe websites are blocked.")
            }
        }

        // ---- Talk to your parent ----
        PrimaryButton(
            text = "💬 Chat with your parent",
            modifier = Modifier.fillMaxWidth(),
            onClick = { chatOpen = true },
        )

        SectionTitle("Need help right now?")

        TonalButton(
            text = "✅ I'm safe",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                checkInMessage = null
                scope.launch {
                    repo.sendCheckIn()
                        .onSuccess {
                            checkInFailed = false
                            checkInMessage = "Sent. Your parent knows you're okay."
                        }
                        .onFailure {
                            checkInFailed = true
                            checkInMessage = it.message ?: "Could not send. Try again."
                        }
                }
            },
        )
        checkInMessage?.let {
            Banner(it, tone = if (checkInFailed) Tone.Danger else Tone.Success)
        }

        OutlineButton(
            text = "💬 I need to talk",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                repo.sendNeedTalk()
                talkMessage = "Sent. Your parent will reach out to you."
            },
        )
        talkMessage?.let { Banner(it, tone = Tone.Success) }

        // ---- Screen sharing: only the child can start it ----
        SectionTitle("🖥️ Share your screen")
        Muted(
            "Only you can start this — your parent cannot turn it on from their side. " +
                "A notification stays on screen the whole time you're sharing, and you can stop " +
                "anytime. It always ends by itself (max 24 hours). " +
                "(শুধু তুমিই এটা চালু করতে পারবে, অভিভাবক দূর থেকে চালু করতে পারবেন না। " +
                "চালু থাকলে সবসময় নোটিফিকেশন দেখা যাবে, এবং যেকোনো সময় বন্ধ করতে পারবে।)",
        )
        ChoiceChips(
            options = SHARE_CHOICES,
            selected = shareMinutes,
            label = { shareLabel(it) },
            onSelect = { shareMinutes = it },
        )
        OutlineButton(
            text = "🖥️ Share my screen",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val intent = projectionManager?.createScreenCaptureIntent()
                if (intent != null) shareLauncher.launch(intent)
            },
        )
        shareMessage?.let { Banner(it, tone = Tone.Info) }

        // ---- SOS ----
        PrimaryButton(
            text = "🚨 SOS — I need help",
            danger = true,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    repo.sendSos()
                        .onSuccess {
                            sosFailed = false
                            sosMessage = "SOS sent to your parent."
                        }
                        .onFailure {
                            sosFailed = true
                            sosMessage = it.message ?: "Could not send SOS."
                        }
                }
            },
        )
        sosMessage?.let { Banner(it, tone = if (sosFailed) Tone.Danger else Tone.Success) }
    }
}
