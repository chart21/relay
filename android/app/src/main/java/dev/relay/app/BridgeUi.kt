package dev.relay.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Pending requests to send something: the exact text and recipient, and the only way to let it go out. */
@Composable
fun SendApprovals() {
    val ctx = LocalContext.current
    val reqs by Bridge.store.sendRequests.collectAsState()
    val scope = rememberCoroutineScope()
    reqs.forEach { r ->
        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(SendRules.title(r, remember(r.app) { Bridge.appLabel(ctx, r.app) }), style = MaterialTheme.typography.titleSmall)
                Text(SendRules.body(r))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ scope.launch { Bridge.decide(r.id, true) } }) { Text(if (r.byPhone) "Send" else "Approve") }
                    OutlinedButton({ scope.launch { Bridge.decide(r.id, false) } }) { Text(if (r.byPhone) "Cancel" else "Deny") }
                }
            }
        }
    }
}

private fun time(ms: Long) = if (ms == 0L) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneBridgeSection() {
    val ctx = LocalContext.current
    val store = Bridge.store
    val scope = rememberCoroutineScope()
    val features by store.features.collectAsState()
    val overrides by store.overrides.collectAsState()
    val known by store.known.collectAsState()
    val lastUpload by store.lastUpload.collectAsState()
    val status by store.status.collectAsState()
    val queued by store.queue.size().collectAsState(0)
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    var healthGranted by remember { mutableStateOf(emptySet<String>()) }
    var healthBg by remember { mutableStateOf(false) }
    LaunchedEffect(tick) {
        healthGranted = HealthReader.grantedPermissions(ctx)
        healthBg = HealthReader.bgAvailable(ctx)
    }
    val listener = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val sms = ctx.granted(Manifest.permission.READ_SMS)
    val fine = ctx.granted(Manifest.permission.ACCESS_FINE_LOCATION) || ctx.granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    val bgLoc = ctx.granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val healthOk = HealthReader.readPermissions.all { it in healthGranted }

    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++; Bridge.syncNow() }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { tick++; Bridge.syncNow() }

    Text("Phone bridge", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Text("Forwards what the phone receives to the Desktop (the gateway's inbox folder). Everything is off until you switch it on and grant the permission. Replies and mails are only sent after you tap Send or Approve.",
        style = MaterialTheme.typography.bodySmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Queue: $queued waiting · last upload: ${time(lastUpload)}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton({ Bridge.syncNow() }) { Text("Sync now") }
    }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

    // Notifications
    BridgeSwitch("Notifications of chosen apps (messages, payment pushes, parcels, calendar)", Bridge.NOTIFICATIONS in features) { store.setFeature(Bridge.NOTIFICATIONS, it); if (it) Bridge.syncNow() }
    if (!listener) Text("Notification access is not granted. A sideloaded app may first need \"Allow restricted settings\" in its app info (HyperOS: ⋮ menu there).", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!listener) {
            OutlinedButton({ ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }) { Text("App info") }
        }
        OutlinedButton({ ctx.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text(if (listener) "Notification access: granted" else "Grant notification access") }
    }
    var showApps by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val apps = remember(known, overrides, tick) {
        val recent = known.filterValues { now - it.second < 14 * 86_400_000L }.map { it.key to it.value.first }
        val defaults = Allowlist.packages.filter { p -> Bridge.appLabel(ctx, p) != p }.map { it to Bridge.appLabel(ctx, it) }
        (recent + defaults).distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    TextButton({ showApps = !showApps }) { Text(if (showApps) "Hide app list" else "Apps to forward (${apps.count { Allowlist.allowed(it.first, it.second, overrides) }} of ${apps.size})") }
    if (showApps) {
        apps.forEach { (pkg, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(Allowlist.allowed(pkg, label, overrides), { store.setAllowed(pkg, it) })
                Column(Modifier.padding(start = 8.dp)) { Text(label, style = MaterialTheme.typography.bodyMedium); Text(pkg, style = MaterialTheme.typography.labelSmall) }
            }
        }
        Text("Apps appear here after they posted a notification.", style = MaterialTheme.typography.bodySmall)
    }

    // SMS
    BridgeSwitch("SMS (inbox and sent)", Bridge.SMS in features) { store.setFeature(Bridge.SMS, it); if (it) Bridge.syncNow() }
    OutlinedButton({ smsLauncher.launch(Manifest.permission.READ_SMS) }, enabled = !sms) { Text(if (sms) "SMS permission: granted" else "Grant SMS permission") }

    // Health Connect
    BridgeSwitch("Health Connect (steps, sleep, heart rate, weight, nutrition)", Bridge.HEALTH in features) { store.setFeature(Bridge.HEALTH, it); if (it) Bridge.syncNow() }
    if (!HealthReader.available(ctx)) Text("Health Connect is not available on this phone (not installed or needs an update).", style = MaterialTheme.typography.bodySmall)
    else OutlinedButton({
        scope.launch { healthLauncher.launch(HealthReader.readPermissions + if (HealthReader.bgAvailable(ctx)) setOf(HealthReader.BACKGROUND) else emptySet()) }
    }, enabled = !healthOk || (healthBg && HealthReader.BACKGROUND !in healthGranted)) {
        Text(if (healthOk) "Health permissions: granted" + if (healthBg && HealthReader.BACKGROUND !in healthGranted) " (background missing)" else "" else "Grant Health Connect permissions")
    }

    // Location
    BridgeSwitch("Location, one fix when the Desktop asks (never continuous)", Bridge.LOCATION in features) { store.setFeature(Bridge.LOCATION, it); if (it) Bridge.syncNow() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ locLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, enabled = !fine) {
            Text(if (fine) "Location: granted" else "Grant location")
        }
        OutlinedButton({ bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }, enabled = fine && !bgLoc) {
            Text(if (bgLoc) "Background location: granted" else "Allow in background")
        }
    }
    if (fine && !bgLoc) Text("Without background location a request is answered only while the app is open. If the dialog does not appear, choose \"Allow all the time\" in the app's location settings.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BridgeSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(value, onChange)
        Text("  $label", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

/** What Health Connect shows for "privacy policy" and for the permission usage of Relay. */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RelayTheme {
                Surface {
                    Column(Modifier.systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Relay and your health data", style = MaterialTheme.typography.titleLarge)
                        Text("If you switch it on, Relay reads steps, sleep (with stages), heart rate, weight and nutrition (daily totals) from Health Connect and sends them over your own SSH connection to your own computer. " +
                            "Nothing goes to any other server or account, and nothing is written to Health Connect.")
                        Text("Reading starts with the last 7 days and then continues incrementally, every 30 minutes at most while the app is connected. You can turn it off in Relay's settings or revoke the permissions in Health Connect at any time.")
                        Button({ finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
