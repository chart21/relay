package dev.relay.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

const val ACT_NEW = "dev.relay.app.NEW_CHAT"
const val ACT_JARVIS = "dev.relay.app.JARVIS"

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        Relay.startConnection()
        startService(this)
        setContent { RelayTheme { RelayNav() } }
    }

    override fun onStart() { super.onStart(); Relay.foreground = true }
    override fun onStop() { Relay.foreground = false; super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent) {
        when (i.action) { // launcher shortcuts (long press the app icon)
            ACT_NEW -> Relay.openRequest.value = "route:new"
            ACT_JARVIS -> { Relay.openRequest.value = "route:assistant"; if (Assistant.conv.value == ConvState.Off) Assistant.startConversation(this) }
        }
        if (i.action == ACT_NEW || i.action == ACT_JARVIS) i.action = Intent.ACTION_MAIN // handled once, also after a re-creation
        i.getStringExtra(Notifier.EXTRA_SESSION)?.let { Relay.openRequest.value = it }
        if (i.getBooleanExtra(Notifier.EXTRA_UPDATE, false)) Relay.scope.launch { Updater.run(applicationContext) }
        i.getStringExtra("tab")?.let { Relay.openRequest.value = "route:" + if (it == "chat") "assistant" else it }
        // First-run provisioning (adb / setup script): only honoured until the settings were saved once.
        val host = i.getStringExtra("host") ?: return
        Relay.scope.launch {
            if (!Relay.settings.flow.first().configured) {
                val d = Prefs()
                Relay.settings.update { it.copy(host = host, port = i.getIntExtra("port", d.port), user = i.getStringExtra("user") ?: d.user, configured = true) }
            }
        }
    }
}

fun startService(ctx: Context) {
    runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, RelayService::class.java)) }
}

@Composable
fun RelayNav() {
    val nav = rememberNavController()
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startService(Relay.app) }
    LaunchedEffect(Unit) { perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) }
    val open by Relay.openRequest.collectAsState()
    LaunchedEffect(open) {
        val r = open ?: return@LaunchedEffect
        Relay.openRequest.value = null
        if (r.startsWith("route:")) nav.navigate(r.removePrefix("route:")) { launchSingleTop = true }
        else nav.navigate("session/${Uri.encode(r)}") { launchSingleTop = true }
    }
    fun openSession(ref: String) = nav.navigate("session/${Uri.encode(ref)}") { launchSingleTop = true }
    NavHost(nav, "sessions") {
        composable("sessions") { SessionsScreen(::openSession, { nav.navigate("new") }, { nav.navigate("assistant") }, { nav.navigate("settings") }, { nav.navigate("accounts") }, { nav.navigate("memory") }) }
        composable("memory") { MemoryScreen({ nav.popBackStack() }, ::openSession) }
        composable("accounts") { AccountsScreen { nav.popBackStack() } }
        composable("session/{ref}") { e ->
            SessionScreen(Uri.decode(e.arguments?.getString("ref").orEmpty())) { nav.popBackStack() }
        }
        composable("new") {
            NewSessionScreen({ nav.popBackStack() }) { target ->
                nav.navigate("session/${Uri.encode(target)}") { popUpTo("new") { inclusive = true } }
            }
        }
        composable("assistant") { AssistantScreen { nav.popBackStack() } }
        composable("settings") { SettingsScreen { nav.popBackStack() } }
    }
}
