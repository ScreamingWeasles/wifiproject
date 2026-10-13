package com.officewifi.inspector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Bundle
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.URL
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

private data class Reading(val time: Long, val ssid: String, val bssid: String, val rssi: Int, val freq: Int, val speed: Int, val gateway: String, val localIp: String)
private data class Event(val time: Long, val from: String, val to: String, val previousRssi: Int, val newRssi: Int)
private data class UdpResult(val sent: Int, val received: Int, val lossPct: Double, val avgRttMs: Double?, val jitterMs: Double?, val bssidChanges: Int, val detail: String)
private data class PingSummary(val sent: Int, val received: Int, val min: Double?, val max: Double?, val avg: Double?, val jitter: Double?, val elapsedMs: Long, val message: String)
private data class SaltSample(val second: Int, val mbps: Double, val bssid: String, val rssi: Int)
private data class SaltResult(val samples: List<SaltSample>, val bytes: Long, val elapsedMs: Long, val failures: Int, val stalls: Int, val roams: Int, val status: String) {
    val avgMbps get() = if (elapsedMs > 0) bytes * 8000.0 / elapsedMs else 0.0
}
private data class SpeedResult(val downloadMbps: Double?, val uploadMbps: Double?, val dnsMs: Long?, val detail: String)
private data class Probe(val time: Long, val target: String, val status: String, val ms: Long?)
private data class Alert(val time: Long, val message: String)
internal data class AP(val ssid: String, val bssid: String, val rssi: Int, val freq: Int)
private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
private fun time(t: Long) = synchronized(fmt) { fmt.format(Date(t)) }
private fun channel(mhz: Int): String = when {
    mhz == 2484 -> "2.4 GHz ch 14"
    mhz in 2412..2472 -> "2.4 GHz ch ${(mhz - 2407) / 5}"
    mhz in 5000..5895 -> "5 GHz ch ${(mhz - 5000) / 5}"
    mhz == 5935 -> "6 GHz ch 2"
    mhz in 5955..7115 -> "6 GHz ch ${(mhz - 5950) / 5}"
    else -> "$mhz MHz"
}
private fun quality(dbm: Int) = when { dbm >= -50 -> "Excellent"; dbm >= -60 -> "Good"; dbm >= -70 -> "Fair"; dbm >= -80 -> "Weak"; else -> "Very weak" }
private fun signalColor(dbm: Int): Color = when {
    dbm >= -60 -> Color(0xFF16834B)
    dbm >= -70 -> Color(0xFFE6A400)
    else -> Color(0xFFD84343)
}
private fun jitterGrade(value: Double?): String = when {
    value == null -> "UNAVAILABLE"
    value <= 10.0 -> "GOOD"
    value <= 30.0 -> "FAIR"
    else -> "POOR"
}
private fun jitterColor(value: Double?): Color = when (jitterGrade(value)) {
    "GOOD" -> Color(0xFF16834B)
    "FAIR" -> Color(0xFFB88600)
    "POOR" -> Color(0xFFC83D3D)
    else -> Color.Gray
}
private val apNameYellow = Color(0xFFB88600)
private fun surveyDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0L) / 1000L)
    return "%02d:%02d:%02d".format(Locale.US, seconds / 3600, (seconds / 60) % 60, seconds % 60)
}
private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

class MainActivity : ComponentActivity() {
    private val wifi by lazy { applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val cm by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var current by mutableStateOf<Reading?>(null)
    private var recording by mutableStateOf(false)
    private var surveyStartedAt by mutableLongStateOf(0L)
    private var surveyEndedAt by mutableLongStateOf(0L)
    private var surveyClockNow by mutableLongStateOf(System.currentTimeMillis())
    private var keepAwake by mutableStateOf(true)
    private var status by mutableStateOf("Ready. Start survey to log readings.")
    private val history = mutableStateListOf<Reading>()
    private val liveHistory = mutableStateListOf<Reading>()
    private val liveEvents = mutableStateListOf<Event>()
    private var livePrevious: Reading? = null
    private val events = mutableStateListOf<Event>()
    private val probes = mutableStateListOf<Probe>()
    private val aps = mutableStateListOf<AP>()
    private val knownAps = mutableStateMapOf<String, AP>()
    private val baselineBssids = mutableStateMapOf<String, String>()
    private val newApEvents = mutableStateListOf<String>()
    private val apDecisions = mutableStateMapOf<String, String>()
    private var baselineDate by mutableStateOf("No baseline saved")
    private var baselineStatus by mutableStateOf("Create a baseline from a completed scan.")
    private fun loadApBaseline() {
        val prefs = getSharedPreferences("signal_hunter_baseline", Context.MODE_PRIVATE)
        baselineDate = prefs.getString("date", "No baseline saved") ?: "No baseline saved"
        baselineBssids.clear()
        prefs.getStringSet("aps", emptySet())?.forEach { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2) baselineBssids[parts[0]] = parts[1]
        }
        apDecisions.clear()
        getSharedPreferences("signal_hunter_decisions", Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (v is String) apDecisions[k] = v
        }
    }
    private fun clearApBaseline() {
        getSharedPreferences("signal_hunter_baseline", Context.MODE_PRIVATE).edit().clear().apply()
        baselineBssids.clear()
        newApEvents.clear()
        baselineDate = "No baseline saved"
        baselineStatus = "Baseline cleared. Scan and save a fresh audit."
    }
    private fun saveApBaseline() {
        if (aps.isEmpty()) { baselineStatus = "Scan first; no APs available."; return }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val rows = aps.map { "${it.bssid.lowercase(Locale.ROOT)}|${it.ssid}" }.toSet()
        getSharedPreferences("signal_hunter_baseline", Context.MODE_PRIVATE).edit()
            .putStringSet("aps", rows).putString("date", stamp).apply()
        baselineBssids.clear()
        aps.forEach { baselineBssids[it.bssid.lowercase(Locale.ROOT)] = it.ssid }
        baselineDate = stamp
        newApEvents.clear()
        baselineStatus = "Saved ${rows.size} access points. Future scans will be compared."
    }
    private fun setApDecision(bssid: String, decision: String) {
        val key = bssid.lowercase(Locale.ROOT)
        apDecisions[key] = decision
        getSharedPreferences("signal_hunter_decisions", Context.MODE_PRIVATE).edit().putString(key, decision).apply()
    }

    private val alerts = mutableStateListOf<Alert>()
    private var monitoring by mutableStateOf(false)
    private var stickySince = 0L
    private var lastStickyAlert = 0L
    private var lastPingPongAlert = 0L
    private var saltBusy by mutableStateOf(false)
    private var saltDuration by mutableStateOf("60")
    private var saltStatus by mutableStateOf("Ready: sustained internet transfers, not LAN-only.")
    private var saltResult by mutableStateOf<SaltResult?>(null)
    private val saltSamples = mutableStateListOf<SaltSample>()
    private val saltStop = AtomicBoolean(false)
    private var saltBytes by mutableLongStateOf(0L)
    private var saltStartedAt by mutableLongStateOf(0L)
    private var saltFinishedAt by mutableLongStateOf(0L)
    private var speedBusy by mutableStateOf(false)
    private var udpBusy by mutableStateOf(false)
    private var udpResult by mutableStateOf<UdpResult?>(null)
    private var udpStatus by mutableStateOf("Requires a consenting UDP echo server; 8.8.8.8 is not one.")
    private var testDestination by mutableStateOf("8.8.8.8")
    private var testPort by mutableStateOf("50000")
    private var udpAuthorized by mutableStateOf(false)
    private var udpElapsed by mutableIntStateOf(0)
    private val udpStop = AtomicBoolean(false)
    private var speedResult by mutableStateOf<SpeedResult?>(null)
    private var speedStatus by mutableStateOf("Ready. Uses Cloudflare HTTPS test endpoints; 8.8.8.8 for DNS timing.")
    private val apNames = mutableStateMapOf<String, String>()
    private val mustard = Color(0xFFD9B65B)
    private var selectedAlert by mutableStateOf<Alert?>(null)
    private var selectedAp by mutableStateOf<AP?>(null)
    private var showMonitorHelp by mutableStateOf(false)
    private var editApBssid by mutableStateOf<String?>(null)
    private var editApName by mutableStateOf("")
    private var pingBusy by mutableStateOf(false)
    private var pingSize by mutableFloatStateOf(256f)
    private var pingDuration by mutableStateOf("60")
    private var pingElapsed by mutableLongStateOf(0L)
    private var pingSummary by mutableStateOf<PingSummary?>(null)
    private val pingStop = AtomicBoolean(false)
    private var pingLive by mutableStateOf("Ready")
    private var pingTarget by mutableStateOf("8.8.8.8")
    private var pingFinishedAt by mutableLongStateOf(0L)
    private var speedFinishedAt by mutableLongStateOf(0L)
    private var pingReportTarget by mutableStateOf("8.8.8.8")
    private var pingReportPayload by mutableIntStateOf(256)
    private var pingReportDuration by mutableStateOf("60")
    private var lastRoamAlert by mutableStateOf("No AP change observed yet")
    private var previousInternetSuccess: Long? = null
    private val outages = mutableStateListOf<String>()
    private var scanSortByName by mutableStateOf(false)
    private var scanNote by mutableStateOf("Tap Scan to refresh nearby APs (Android may throttle scans).")
    private var sessionName by mutableStateOf("Office walkthrough")
    private var roomName by mutableStateOf("Entrance")
    private val discoverySession by lazy { DiscoverySession(applicationContext) }
    private var lastSample: Reading? = null
    private var previousBssid: String? = null
    private var lastProbeTime = 0L
    private val probing = AtomicBoolean(false)

    private fun openWifiChooser() {
        try {
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            catch (e: Exception) { scanNote = "Could not open Wi-Fi settings: ${e.message}" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadApBaseline()
        val namePrefs = getSharedPreferences("ap_friendly_names", Context.MODE_PRIVATE)
        namePrefs.all.forEach { (bssid, value) -> if (value is String) apNames[bssid] = value }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        setContent {
            var permission by remember { mutableStateOf(granted) }
            var selectedTab by remember { mutableIntStateOf(7) }
            var hunterBssid by remember { mutableStateOf("") }
            var hunterPreviousRssi by remember { mutableIntStateOf(-100) }
            var hunterRunning by remember { mutableStateOf(false) }
            var hunterHelp by remember { mutableStateOf(false) }
            var consoleHelp by remember { mutableStateOf(false) }
            var showAbout by remember { mutableStateOf(false) }
            var showSurveyHelp by remember { mutableStateOf(false) }
            var showSecurityScan by remember { mutableStateOf(false) }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it }
            val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                if (uri != null) try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(makeCsv()) }
                    status = "CSV exported."
                } catch (e: Exception) { status = "Export error: ${e.message}" }
            }
            val diagnosticsCsvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                if (uri != null) try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(makeDiagnosticsCsv()) }
                    speedStatus = "Diagnostic CSV report saved."
                } catch (e: Exception) { speedStatus = "CSV export failed: ${e.message}" }
            }
            val diagnosticsPdfExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
                if (uri != null) try {
                    contentResolver.openOutputStream(uri)?.use { writeDiagnosticsPdf(it) }
                    speedStatus = "Diagnostic PDF report saved."
                } catch (e: Exception) { speedStatus = "PDF export failed: ${e.message}" }
            }
            val scope = rememberCoroutineScope()
            LaunchedEffect(permission) {
                while (true) {
                    if (permission) {
                        try { sample() } catch (e: Exception) { status = "Read error: ${e.message}" }
                    }
                    delay(1000)
                }
            }
            LaunchedEffect(recording) {
                while (recording) {
                    surveyClockNow = System.currentTimeMillis()
                    delay(250)
                }
            }
            LaunchedEffect(keepAwake) {
                if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val grassColors = lightColorScheme(
                primary = mustard, onPrimary = Color(0xFF263B22),
                primaryContainer = Color(0xFFD2E5C5), onPrimaryContainer = Color(0xFF263B22),
                background = Color(0xFFE4F0D7), onBackground = Color(0xFF263B22),
                surface = Color(0xFFF7FBF3), onSurface = Color(0xFF263B22),
                surfaceVariant = Color(0xFFDCEBD0), onSurfaceVariant = Color(0xFF41563B),
                secondary = Color(0xFF4A6543), onSecondary = Color.White
            )
            MaterialTheme(colorScheme = grassColors) {
                editApBssid?.let { bssid ->
                    AlertDialog(onDismissRequest = { editApBssid = null }, title = { Text("Name access point") },
                        text = { Column { Text("BSSID: $bssid"); OutlinedTextField(value = editApName, onValueChange = { editApName = it }, label = { Text("Friendly name") }) } },
                        confirmButton = { TextButton(onClick = { saveApName(bssid, editApName); editApBssid = null }) { Text("Save") } },
                        dismissButton = { TextButton(onClick = { editApBssid = null }) { Text("Cancel") } })
                }
                selectedAp?.let { ap ->
                    val r = current
                    val connected = r?.bssid.equals(ap.bssid, ignoreCase = true)
                    val readings = (liveHistory + history).filter { it.bssid.equals(ap.bssid, ignoreCase = true) }
                    AlertDialog(onDismissRequest = { selectedAp = null },
                        title = { Text("Access point details") },
                        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            apNames[ap.bssid.lowercase(Locale.ROOT)]?.let { Text(it, color = apNameYellow, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) }
                            Text("SSID: ${ap.ssid}")
                            Text("BSSID: ${ap.bssid}")
                            Text("Signal: ${ap.rssi} dBm (${quality(ap.rssi)})")
                            Text("Band/channel: ${channel(ap.freq)}")
                            Text("Frequency: ${ap.freq} MHz")
                            Text("Connected to this AP: ${if (connected) "Yes" else "No"}")
                            if (connected) {
                                Text("Link speed: ${r?.speed ?: 0} Mbps")
                                Text("Local IP: ${r?.localIp ?: "Unavailable"}")
                                Text("Gateway: ${r?.gateway ?: "Unavailable"}")
                            }
                            Text("Recorded samples: ${readings.size}")
                            if (readings.isNotEmpty()) {
                                Text("Strongest recorded signal: ${readings.maxOf { it.rssi }} dBm")
                                Text("Weakest recorded signal: ${readings.minOf { it.rssi }} dBm")
                                Text("First observed: ${Date(readings.minOf { it.time })}")
                                Text("Last observed: ${Date(readings.maxOf { it.time })}")
                            }
                            Text("Roaming events involving this AP: ${(liveEvents + events).count { it.from.equals(ap.bssid, true) || it.to.equals(ap.bssid, true) }}")
                            Text("Security, radio width, manufacturer, AP client count and hardware health: unavailable from the current scan data. Android may limit access to additional radio details.")
                        } },
                        confirmButton = { TextButton(onClick = { selectedAp = null }) { Text("Close") } },
                        dismissButton = { TextButton(onClick = { editApBssid = ap.bssid; editApName = apNames[ap.bssid.lowercase(Locale.ROOT)] ?: ""; selectedAp = null }) { Text("Name AP") } })
                }
                if (hunterHelp) AlertDialog(onDismissRequest = { hunterHelp = false }, title = { Text("Signal Hunter instructions") },
                    text = { Text("1. Grant Wi-Fi location permission and scan for nearby APs.\n2. Choose the AP you want to follow.\n3. Press Start to periodically request fresh Wi-Fi scans while the screen is open. The moving red eye indicates monitoring is active, not direction.\n4. Compare RSSI as you move; less-negative dBm generally means stronger signal.\n5. Scan & Compare requests a Wi-Fi scan and compares observed BSSIDs with your saved baseline. Save Baseline stores the current AP list; Clear List deletes that baseline.\nAndroid may throttle or cache scans. This does not measure physical distance or bearing.") },
                    confirmButton = { TextButton(onClick = { hunterHelp = false }) { Text("Close") } })
                if (consoleHelp) AlertDialog(onDismissRequest = { consoleHelp = false }, title = { Text("Console: planned capabilities") },
                    text = { Text("This release includes a Console configuration preview only. It does not open SSH, Telnet, TCP, Bluetooth or USB serial sessions yet. Future versions will add real transports, hardware profiles, saved sessions, logging, SSH key security, file transfer and configuration backups. Never enter live passwords into this preview.") },
                    confirmButton = { TextButton(onClick = { consoleHelp = false }) { Text("Close") } })
                if (showAbout) AlertDialog(onDismissRequest = { showAbout = false }, title = { Text("About Screaming Weasels") },
                    text = { Text("Office Wi-Fi Inspector\nVersion 0.9.3 · Console Preview\nMake Your Wi-Fi Squeal.") },
                    confirmButton = { TextButton(onClick = { showAbout = false }) { Text("Close") } })
                if (showSurveyHelp) AlertDialog(onDismissRequest = { showSurveyHelp = false }, title = { Text("How to use Site Survey") },
                    text = { Text("1. Connect to the Wi-Fi you want to survey and grant location permission.\n2. Enter a survey name and a room/checkpoint.\n3. Start Survey, then walk slowly through the area.\n4. Change checkpoint names as you move.\n5. Watch the signal timeline and AP roaming events.\n6. Stop Survey and export the results before closing the app.\nAndroid may throttle Wi-Fi scans; the graph is observational, not a calibrated RF measurement.") },
                    confirmButton = { TextButton(onClick = { showSurveyHelp = false }) { Text("Close") } })
                if (showMonitorHelp) AlertDialog(onDismissRequest = { showMonitorHelp = false },
                    title = { Text("What does Monitor do?") },
                    text = { Text("While this app is open, Monitor observes the connected Wi-Fi signal, AP/BSSID changes, roaming, possible sticky-client behavior, and periodic gateway and internet connectivity probes. It displays health alerts and recent observations. It does not log into AP hardware, inspect other clients, or automatically save a site survey. Start Survey to record a walkthrough.") },
                    confirmButton = { TextButton(onClick = { showMonitorHelp = false }) { Text("Close") } })
                selectedAlert?.let { alert ->
                    AlertDialog(onDismissRequest = { selectedAlert = null }, title = { Text("AP Health alert") },
                        text = { Text("Time: ${time(alert.time)}\n\n${alert.message}\n\nReview signal strength, AP roaming and gateway/Internet probe history to investigate this event. A failed TCP port probe alone does not prove the gateway is offline.") },
                        confirmButton = { TextButton(onClick = { selectedAlert = null }) { Text("Close") } })
                }
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = { Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) { Text("Screaming Weasels", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge) } },
                    bottomBar = {
                        NavigationBar {
                            listOf("Home", "Wi-Fi", "Device Scan", "Speed Test", "Signal Hunter").forEachIndexed { index, label ->
                                val destination = when (index) { 0 -> 7; 1 -> 0; 2 -> 2; 3 -> 4; else -> 8 }
                                NavigationBarItem(selected = selectedTab == destination, onClick = { selectedTab = destination },
                                    icon = { Text(when (index) { 0 -> "◉"; 1 -> "⌕"; 2 -> "▣"; 3 -> "▤"; 4 -> "↕"; 5 -> "▥"; else -> "▦" }) }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 9.sp) })
                            }
                        }
                    }
                ) { pad ->
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                        if (!permission) item { Button(colors = ButtonDefaults.buttonColors(containerColor = mustard, contentColor = Color(0xFF263B22)), onClick = { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Grant precise location permission") } }
                        when (selectedTab) {
                            9 -> {
                                item { ConsolePreview(onHelp = { consoleHelp = true }) }
                            }
                            8 -> {
                                item {
                                    Text("Signal Hunter · New AP Watch", style = MaterialTheme.typography.headlineSmall)
                                    Text("Select an AP to track its signal. Stronger readings suggest proximity, not a measured bearing or distance.", style = MaterialTheme.typography.bodySmall)
                                    val targetAp = aps.firstOrNull { it.bssid == hunterBssid }
                                    if (aps.isEmpty()) Text("Scan for nearby APs to start Signal Hunter.")
                                    else {
                                        var apExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            OutlinedButton(onClick = { apExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                                Text(targetAp?.let { "${it.ssid} (${it.rssi} dBm)" } ?: "Choose an AP", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            DropdownMenu(expanded = apExpanded, onDismissRequest = { apExpanded = false }) {
                                                aps.take(50).forEach { ap ->
                                                    DropdownMenuItem(text = { Text("${ap.ssid} · ${ap.rssi} dBm", maxLines = 1) }, onClick = {
                                                        hunterBssid = ap.bssid; hunterPreviousRssi = ap.rssi; apExpanded = false
                                                    })
                                                }
                                            }
                                        }
                                    }
                                    LaunchedEffect(hunterRunning, permission) {
                                        while (hunterRunning && permission) { scan(); delay(5000) }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(onClick = { hunterRunning = true }, enabled = permission && !hunterRunning) { Text("Start", maxLines = 1) }
                                        Button(onClick = { hunterRunning = false }, enabled = hunterRunning) { Text("Stop", maxLines = 1) }
                                        TextButton(onClick = { hunterHelp = true }) { Text("How to Use", maxLines = 1) }
                                    }
                                    // A blank bar when idle. Start the infinite eye animation only while scanning.
                                    if (hunterRunning) {
                                        val sweep = rememberInfiniteTransition(label = "hunterEye")
                                        val eyePosition by sweep.animateFloat(initialValue = -1f, targetValue = 1f,
                                            animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Reverse), label = "eyePosition")
                                        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF080809), RoundedCornerShape(22.dp))) {
                                            val cx = size.width / 2f
                                            val cy = size.height / 2f
                                            val half = size.width * .39f
                                            val eyeX = cx + half * eyePosition
                                            // No red guide line or trail; only the bouncing red eye.
                                            drawCircle(Color(0xFF9D0B12).copy(alpha=.20f), 32.dp.toPx(), Offset(eyeX,cy))
                                            drawCircle(Color(0xFFFF3434), 13.dp.toPx(), Offset(eyeX,cy))
                                            drawCircle(Color(0xFFFFC5C5), 4.dp.toPx(), Offset(eyeX,cy))
                                        }
                                    } else {
                                        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp).background(Color(0xFF080809), RoundedCornerShape(22.dp))) { }
                                    }
                                    Text("Signal Hunter eye is decorative, not a directional radar. Track real RSSI readings below.", style = MaterialTheme.typography.bodySmall)
                                    if (targetAp != null) {
                                        Text("${targetAp.ssid} · ${targetAp.rssi} dBm · ${channel(targetAp.freq)}", style = MaterialTheme.typography.titleMedium)
                                        Text("Signal: ${quality(targetAp.rssi)} · BSSID ${targetAp.bssid}")
                                        val delta = targetAp.rssi - hunterPreviousRssi
                                        Text(when { delta >= 4 -> "Signal getting stronger"; delta <= -4 -> "Signal getting weaker"; else -> "Signal approximately steady" })
                                        Button(onClick = { hunterPreviousRssi = targetAp.rssi; scan() }, enabled = permission,
                                            colors = ButtonDefaults.buttonColors(containerColor = mustard)) { Text("Take another reading") }
                                    }
                                    Text("Baseline saved: $baselineDate")
                                    Text(baselineStatus)
                                    Text("A newly observed Wi-Fi signal is not proof of a portable hotspot or surveillance device. Android may reuse cached scans or throttle scans.")
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { scan() }, enabled = permission, modifier = Modifier.fillMaxWidth()) { Text("Scan & Compare", maxLines = 1) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Button(onClick = { saveApBaseline() }, enabled = aps.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Save Baseline", maxLines = 1, fontSize = 12.sp) }
                                            Button(onClick = { clearApBaseline() }, enabled = baselineBssids.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Clear List", maxLines = 1, fontSize = 12.sp) }
                                        }
                                        TextButton(onClick = { hunterHelp = true }) { Text("About Scan & Compare", maxLines = 1) }
                                    }
                                    Text("Newly observed APs", style = MaterialTheme.typography.titleMedium)
                                    if (newApEvents.isEmpty()) Text("No new APs recorded in this session.")
                                    newApEvents.takeLast(50).asReversed().forEach { event -> Text(event, style = MaterialTheme.typography.bodySmall) }
                                    Text("Current AP classifications", style = MaterialTheme.typography.titleMedium)
                                    aps.filter { baselineBssids.isNotEmpty() && !baselineBssids.containsKey(it.bssid.lowercase(Locale.ROOT)) }.forEach { ap ->
                                        Text("${ap.ssid} · ${ap.bssid} · ${ap.rssi} dBm · ${channel(ap.freq)}")
                                        Text("Status: ${apDecisions[ap.bssid.lowercase(Locale.ROOT)] ?: "Investigate"}")
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            listOf("Trusted", "Investigate", "Ignore").forEach { choice ->
                                                TextButton(onClick = { setApDecision(ap.bssid, choice) }) { Text(choice) }
                                            }
                                        }
                                    }
                                }
                            }
                            7 -> {
                                item {
                                    Text("The Screaming Weasels WI-FI Tools", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                    Text("making your signal squeal", fontStyle = FontStyle.Italic)
                                    Text("Current Wi-Fi Connection", style = MaterialTheme.typography.titleLarge)
                                    val r = current
                                    if (r == null) Text("Not connected or waiting for permission")
                                    else Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                        Text(r.ssid, style = MaterialTheme.typography.headlineSmall)
                                        Text("${r.rssi} dBm · ${quality(r.rssi)}")
                                        Text("${channel(r.freq)} · ${r.freq} MHz · Link ${r.speed} Mbps")
                                        Text("IP ${r.localIp}")
                                    } }
                                    Button(onClick = { selectedTab = 0 }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = mustard)) { Text("Full Wi-Fi Details", maxLines = 1) }
                                    val tiles = listOf(Triple("Wi-Fi Explorer", "📶", 0), Triple("Speed Test", "↕", 4), Triple("Device Scan", "⌕", 2), Triple("Signal Hunter", "◎", 8), Triple("Console", ">_", 9))
                                    tiles.chunked(2).forEach { pair ->
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            pair.forEach { (name, symbol, destination) ->
                                                Button(onClick = { selectedTab = destination }, modifier = Modifier.weight(1f).height(116.dp).padding(vertical = 5.dp),
                                                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(6.dp),
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244B57), contentColor = Color.White)) {
                                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                                        Text(symbol, fontSize = 21.sp, color = mustard)
                                                        Spacer(Modifier.height(8.dp))
                                                        Text(name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    TextButton(onClick = { showAbout = true }) { Text("About") }
                                }
                            }
                            0 -> {
                                item {
                                    Text("Connected Wi-Fi", style = MaterialTheme.typography.titleMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { selectedTab = 1 }) { Text("AP Discovery", maxLines = 1) }
                                        OutlinedButton(onClick = { selectedTab = 5 }) { Text("AP Health", maxLines = 1) }
                                    }
                                    Text("Make Your Wi-Fi Squeal.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    val r = current
                                    if (r == null) Text("Waiting for Wi-Fi connection / location permission") else {
                                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE9F5EE))) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(r.ssid, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                                Text("${r.rssi} dBm · ${quality(r.rssi)}", style = MaterialTheme.typography.headlineSmall, color = signalColor(r.rssi), fontWeight = FontWeight.Bold)
                                                LinearProgressIndicator(progress = { ((r.rssi + 100) / 70f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(9.dp), color = signalColor(r.rssi))
                                                apNames[r.bssid.lowercase(Locale.ROOT)]?.let { Text(it, color = apNameYellow, fontWeight = FontWeight.Bold) }
                                                Text("Connected AP: ${r.bssid}")
                                                Text("Last roam: $lastRoamAlert", style = MaterialTheme.typography.bodySmall)
                                                Text("${channel(r.freq)} · ${r.freq} MHz · Link ${r.speed} Mbps")
                                                Text("IP ${r.localIp} · Gateway ${r.gateway}")
                                                Spacer(Modifier.height(6.dp))
                                                Text("Live signal strength · last 120 seconds", fontWeight = FontWeight.SemiBold)
                                                if (liveHistory.size >= 2) SignalGraph(liveHistory.toList(), liveEvents.toList())
                                                else Text("Collecting live readings…")
                                            }
                                        }
                                    }
                                }
                                item {
                                    Text("Nearby access points", style = MaterialTheme.typography.titleMedium)
                                    Text("Connected BSSID is pinned above. Other APs are sorted by signal strength.", style = MaterialTheme.typography.bodySmall)
                                    Text("${aps.count { !it.bssid.equals(current?.bssid, true) }} other BSSIDs from latest scan", style = MaterialTheme.typography.bodySmall)
                                }
                                items(aps.filterNot { it.bssid.equals(current?.bssid, true) }.sortedByDescending { it.rssi }) { ap ->
                                    AccessPointRow(ap, apNames[ap.bssid.lowercase(Locale.ROOT)]) { selectedAp = ap }
                                }
                                item { Text("Use Scan to refresh nearby networks. Use Survey to record a walkthrough.", style = MaterialTheme.typography.bodySmall) }
                            }
                            1 -> {
                                item {
                                    Text("Wi-Fi / AP Discovery", style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = { selectedTab = 0 }) { Text("← Back to Wi-Fi") }
                                    Button(colors = ButtonDefaults.buttonColors(containerColor = mustard, contentColor = Color(0xFF263B22)), enabled = permission, onClick = { scan() }) { Text("Scan for APs") }
                                    Text(scanNote, style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(selected = !scanSortByName, onClick = { scanSortByName = false }, label = { Text("By strength") })
                                        FilterChip(selected = scanSortByName, onClick = { scanSortByName = true }, label = { Text("By name A–Z") })
                                    }
                                    Text("Discovered Access Points · ${aps.size} access point radios · ${if (scanSortByName) "SSID A–Z" else "strongest first"}", style = MaterialTheme.typography.bodySmall)
                                    Text("Join opens Android's Wi-Fi chooser; select the network there.", style = MaterialTheme.typography.bodySmall)
                                }
                                items(if (scanSortByName) aps.sortedWith(compareBy<AP> { it.ssid.lowercase(Locale.ROOT) }.thenByDescending { it.rssi }) else aps.sortedByDescending { it.rssi }) { ap ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f).clickable { selectedAp = ap }) {
                                            Text("${if (ap.bssid.equals(current?.bssid, true)) "● " else ""}${ap.ssid}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            apNames[ap.bssid.lowercase(Locale.ROOT)]?.let { Text(it, color = apNameYellow, fontWeight = FontWeight.Bold) }
                                            Text("${ap.rssi} dBm · ${channel(ap.freq)}", color = signalColor(ap.rssi), style = MaterialTheme.typography.bodySmall)
                                            Text(ap.bssid, style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (ap.bssid.equals(current?.bssid, true)) {
                                            Text("Connected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                        } else {
                                            OutlinedButton(onClick = { openWifiChooser() }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Join") }
                                        }
                                    }
                                    HorizontalDivider()
                                }
                                item {
                                    Text("Channel analysis (visible AP signals, not airtime)", style = MaterialTheme.typography.titleMedium)
                                    Text("2.4 GHz", style = MaterialTheme.typography.titleSmall)
                                    ChannelGraph(aps.filter { it.freq in 2400..2500 }, 1..13)
                                    Text("5 GHz", style = MaterialTheme.typography.titleSmall)
                                    ChannelGraph(aps.filter { it.freq in 5000..5900 }, 36..165)
                                    Text("6 GHz", style = MaterialTheme.typography.titleSmall)
                                    ChannelGraph(aps.filter { it.freq in 5925..7125 }, 1..233)
                                    Text("Scan results can be cached or throttled by Android. Bars show strongest observed RSSI on each channel.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            2 -> {
                                item {
                                    Text("Device Scan", style = MaterialTheme.typography.titleMedium)
                                    Text("Ping diagnostics", style = MaterialTheme.typography.titleSmall)
                                    Text("Ping payload size changes ICMP request size; it does not simulate sustained file-transfer throughput.", style = MaterialTheme.typography.bodySmall)
                                    Text("Advanced Ping Test", style = MaterialTheme.typography.titleMedium)
                                    OutlinedTextField(value = pingTarget, onValueChange = { pingTarget = it.trim() }, label = { Text("Ping destination IP / hostname") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Text("Payload: ${pingSize.roundToInt()} bytes")
                                    Slider(value = pingSize, onValueChange = { pingSize = it }, valueRange = 32f..1472f, steps = 44)
                                    Text("Duration")
                                    Text(if (pingDuration == "∞") "Until stopped" else if (pingDuration == "1") "Single ping" else "$pingDuration seconds")
                                    val pingOptions = listOf("1", "5", "30", "60", "300", "∞")
                                    Slider(value = pingOptions.indexOf(pingDuration).coerceAtLeast(0).toFloat(),
                                        onValueChange = { pingDuration = pingOptions[it.roundToInt().coerceIn(0, 5)] },
                                        valueRange = 0f..5f, steps = 4)
                                    Button(colors = ButtonDefaults.buttonColors(containerColor = mustard, contentColor = Color(0xFF263B22)), enabled = !pingBusy && !speedBusy && current != null && pingTarget.isNotBlank(), onClick = {
                                        pingBusy = true; pingStop.set(false); pingSummary = null; pingElapsed = 0L; pingLive = "Starting ICMP echo test…"
                                        pingReportTarget = pingTarget; pingReportPayload = pingSize.roundToInt(); pingReportDuration = pingDuration
                                        val target = pingTarget; val size = pingSize.roundToInt(); val seconds = pingDuration.toIntOrNull()
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runPingTest(target, size, seconds) { elapsed, message ->
                                                    runOnUiThread { pingElapsed = elapsed; pingLive = message }
                                                }
                                            }
                                            pingSummary = result; pingFinishedAt = System.currentTimeMillis(); pingBusy = false; pingLive = result.message
                                        }
                                    }) { Text(if (pingBusy) "Ping running…" else "Start Ping Test") }
                                    if (pingBusy) {
                                        if (pingDuration == "∞") LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = mustard)
                                        else LinearProgressIndicator(progress = { (pingElapsed.toFloat() / ((pingDuration.toLongOrNull() ?: 60L)*1000L)).coerceIn(0f,1f) }, modifier = Modifier.fillMaxWidth(), color = mustard)
                                        OutlinedButton(onClick = { pingStop.set(true) }) { Text("Stop Ping") }
                                    }
                                    Text("Elapsed: ${surveyDuration(pingElapsed)} · $pingLive", style = MaterialTheme.typography.bodySmall)
                                    pingSummary?.let { r ->
                                        Text("Packets: ${r.received}/${r.sent} · Loss: ${if (r.sent > 0) "%.1f".format(Locale.US, 100.0*(r.sent-r.received)/r.sent) else "—"}%")
                                        Text("Latency min / avg / max: ${r.min?.let { "%.1f".format(Locale.US,it) } ?: "—"} / ${r.avg?.let { "%.1f".format(Locale.US,it) } ?: "—"} / ${r.max?.let { "%.1f".format(Locale.US,it) } ?: "—"} ms")
                                        Text("Jitter: ${r.jitter?.let { "%.1f ms".format(Locale.US,it) } ?: "Unavailable"} · ${jitterGrade(r.jitter)}", color = jitterColor(r.jitter), fontWeight = FontWeight.Bold)
                                        Text("Total duration: ${surveyDuration(r.elapsedMs)} · ${r.message}", style = MaterialTheme.typography.bodySmall)
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                                    Text("LAN IP discovery and TCP service scanning. Wi-Fi AP Discovery lists nearby wireless radios.", style = MaterialTheme.typography.bodySmall)
                                }
                                item {
                                    Button(colors = ButtonDefaults.buttonColors(containerColor = mustard), onClick = { showSecurityScan = !showSecurityScan }) { Text(if (showSecurityScan) "Hide Security Scan" else "Security Scan") }
                                    if (showSecurityScan) {
                                        Text("Nearby wireless signals (possible surveillance devices)")
                                        Text("Wi-Fi scanning cannot prove that a device is a camera. Offline, wired, and non-Wi-Fi devices cannot be detected.")
                                        Button(colors = ButtonDefaults.buttonColors(containerColor = mustard), onClick = { scan() }, enabled = permission) { Text("Refresh nearby signals", maxLines = 1) }
                                        aps.forEach { ap -> Text("${ap.ssid} · ${ap.bssid} · ${ap.rssi} dBm") }
                                        Text("Connected-network devices are listed below; inspect unknown devices with authorization.")
                                    }
                                }
                                item { LanDiscoveryPanel(cm.activeNetwork, cm.getLinkProperties(cm.activeNetwork), discoverySession, scope) }
                            }
                            3 -> {
                                item {
                                    Text("AP Health / Site survey", style = MaterialTheme.typography.titleMedium)
                                    TextButton(onClick = { showSurveyHelp = true }) { Text("How to use Survey") }
                                    OutlinedTextField(value = sessionName, onValueChange = { sessionName = it }, label = { Text("Survey name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    OutlinedTextField(value = roomName, onValueChange = { roomName = it }, label = { Text("Current room / checkpoint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(enabled = permission, onClick = {
                                            if (!recording) {
                                                monitoring = true
                                                history.clear(); events.clear(); probes.clear(); outages.clear(); previousInternetSuccess = null; previousBssid = current?.bssid
                                                lastSample = current; lastProbeTime = 0L
                                                surveyStartedAt = System.currentTimeMillis()
                                                surveyEndedAt = 0L
                                                surveyClockNow = surveyStartedAt
                                                recording = true; status = "Recording survey. Walk between APs."
                                            } else {
                                                surveyEndedAt = System.currentTimeMillis()
                                                recording = false; status = "Stopped. Export CSV to save results."
                                            }
                                        }, colors = ButtonDefaults.buttonColors(
                                            containerColor = if (recording) Color(0xFFD9B65B) else MaterialTheme.colorScheme.primary,
                                            contentColor = if (recording) Color(0xFF26331F) else MaterialTheme.colorScheme.onPrimary
                                        )) { Text(if (recording) "Stop survey" else "Start survey") }
                                        OutlinedButton(onClick = { export.launch("wifi-survey-${System.currentTimeMillis()}.csv") }, enabled = history.isNotEmpty()) { Text("Export CSV") }
                                    }
                                    Text("Status: $status", style = MaterialTheme.typography.bodySmall)
                                    if (surveyStartedAt > 0L) {
                                        val duration = (if (recording) surveyClockNow else surveyEndedAt) - surveyStartedAt
                                        Text("Survey elapsed: ${surveyDuration(duration)}", style = MaterialTheme.typography.titleMedium)
                                        if (recording) {
                                            LinearProgressIndicator(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = Color(0xFFD9B65B),
                                                trackColor = Color(0xFFD2E5C5)
                                            )
                                            Text("Recording in progress · no time limit", style = MaterialTheme.typography.bodySmall)
                                        } else {
                                            Text("Survey complete · duration ${surveyDuration(duration)}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    Row { Checkbox(checked = keepAwake, onCheckedChange = { keepAwake = it }); Text("Keep screen awake", modifier = Modifier.padding(top = 12.dp)) }
                                    Text("Samples: ${history.size} · Roams: ${events.size}")
                                    Text("Latest AP change: $lastRoamAlert", color = MaterialTheme.colorScheme.primary)
                                    Text("Observed probe interruptions: ${outages.size}")
                                    Text("AP labels: enter a BSSID and name below, then save")
                                    var editBssid by remember { mutableStateOf("") }
                                    var editName by remember { mutableStateOf("") }
                                    OutlinedTextField(value = editBssid, onValueChange = { editBssid = it }, label = { Text("AP BSSID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    OutlinedTextField(value = editName, onValueChange = { editName = it }, label = { Text("Friendly AP name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                    Button(colors = ButtonDefaults.buttonColors(containerColor = mustard, contentColor = Color(0xFF263B22)), onClick = { if (editBssid.isNotBlank() && editName.isNotBlank()) { saveApName(editBssid, editName); status = "Saved AP label" } }) { Text("Save AP name") }
                                }
                                item {
                                    Text("Recorded signal history", style = MaterialTheme.typography.titleMedium)
                                    if (history.size >= 2) SignalGraph(history.takeLast(120), events.toList()) else Text("Start a survey to collect readings.")
                                }
                                item { Text("Roaming events", style = MaterialTheme.typography.titleMedium) }
                                if (events.isEmpty()) item { Text("No AP transitions recorded in this survey.") }
                                items(events.takeLast(30).reversed()) { ev ->
                                    Text("${time(ev.time)}  ${apLabel(ev.from)} → ${apLabel(ev.to)}\nRSSI ${ev.previousRssi} → ${ev.newRssi} dBm")
                                    HorizontalDivider()
                                }
                                item {
                                    Text("Connectivity probes (every 2 s during survey or monitoring)", style = MaterialTheme.typography.titleMedium)
                                    Text("Gateway: ${probes.lastOrNull { it.target == "Gateway" }?.let { "${it.status} ${it.ms ?: "—"} ms" } ?: "Not tested"}")
                                    Text("Internet HTTPS: ${probes.lastOrNull { it.target == "Internet" }?.let { "${it.status} ${it.ms ?: "—"} ms" } ?: "Not tested"}")
                                    Text("Last interruption: ${outages.lastOrNull() ?: "None recorded"}")
                                    Text("Gateway uses TCP ports 80/443/53; closed ports do not prove gateway failure. Internet uses HTTPS, not ICMP.", style = MaterialTheme.typography.bodySmall)
                                    Text("Survey data is in memory; export before closing. Monitoring works while the app is in the foreground.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            5 -> {
                                item {
                                    Text("AP Health Dashboard", style = MaterialTheme.typography.headlineSmall)
                                    Button(onClick = { selectedTab = 3 }) { Text("Open Site Survey") }
                                    Text("Make Your Wi-Fi Squeal.", color = MaterialTheme.colorScheme.primary)
                                    Text("No fixed AP limit. Shows BSSIDs observed in recent scans, filtered to your connected SSID.", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(colors = ButtonDefaults.buttonColors(containerColor = mustard, contentColor = Color(0xFF263B22)), onClick = { scan() }, enabled = permission) { Text("Refresh APs") }
                                        val transition = rememberInfiniteTransition(label = "monitorPulse")
                                        val pulse by transition.animateFloat(initialValue = 0.70f, targetValue = 1f,
                                            animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "pulse")
                                        Button(onClick = { monitoring = !monitoring },
                                            colors = ButtonDefaults.buttonColors(containerColor = if (monitoring) mustard.copy(alpha = pulse) else MaterialTheme.colorScheme.primary,
                                                contentColor = if (monitoring) Color(0xFF263B22) else Color.White)) {
                                            Text(if (monitoring) "Monitoring…" else "Monitor")
                                        }
                                        TextButton(onClick = { showMonitorHelp = true }) { Text("What is Monitor?") }
                                    }
                                    val r = current
                                    val same = knownAps.values.filter { it.ssid == r?.ssid }.sortedByDescending { it.rssi }
                                    Text("${same.size} observed BSSIDs for ${r?.ssid ?: "no connection"}")
                                    if (same.isEmpty()) Text("Scan while connected to discover APs.")
                                }
                                items(knownAps.values.filter { it.ssid == current?.ssid }.sortedByDescending { it.rssi }) { ap ->
                                    Column {
                                        AccessPointRow(ap, apNames[ap.bssid.lowercase(Locale.ROOT)]) { selectedAp = ap }
                                        Text("${quality(ap.rssi)} · ${channel(ap.freq)}", style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = { editApBssid = ap.bssid; editApName = apNames[ap.bssid.lowercase()] ?: "" }) { Text("Name / rename AP") }
                                    }
                                }
                                item {
                                    Text("Roaming & connection alerts", style = MaterialTheme.typography.titleMedium)
                                    Text("Monitoring: ${if (monitoring || recording) "ON" else "OFF"} · ${alerts.size} alerts", style = MaterialTheme.typography.bodySmall)
                                }
                                items(alerts.takeLast(20).reversed()) { alert ->
                                    TextButton(onClick = { selectedAlert = alert }) { Text("${time(alert.time)}  ${alert.message}") }
                                }
                                item {
                                    val recent = probes.filter { it.target == "Internet" }.takeLast(30)
                                    val success = recent.mapNotNull { it.ms }
                                    val loss = if (recent.isNotEmpty()) 100.0 * (recent.size - success.size) / recent.size else null
                                    val avg = if (success.isNotEmpty()) success.average() else null
                                    val jitter = if (success.size >= 2) success.zipWithNext { a, b -> kotlin.math.abs(a-b).toDouble() }.average() else null
                                    Text("Real-time traffic readiness (estimate)", style = MaterialTheme.typography.titleMedium)
                                    Text("Internet HTTPS probes: ${recent.size} · Loss: ${loss?.let { "%.1f%%".format(Locale.US,it) } ?: "—"}")
                                    Text("Average response: ${avg?.let { "%.0f ms".format(Locale.US,it) } ?: "—"} · Variation: ${jitter?.let { "%.0f ms".format(Locale.US,it) } ?: "—"}")
                                    val verdict = when {
                                        recent.size < 10 -> "Collect at least 10 probes"
                                        loss != null && loss > 2 -> "Caution: probe failures"
                                        avg != null && avg > 150 -> "Caution: high response time"
                                        jitter != null && jitter > 30 -> "Caution: variable response time"
                                        else -> "Good on sampled HTTPS probes"
                                    }
                                    Text(verdict, fontWeight = FontWeight.Bold)
                                    Text("HTTPS request timing is not RTP jitter or a certified real-time media assessment. Router port tests can fail even with a healthy gateway.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            4 -> {
                                item {
                                    Text("Internet Speed Test", style = MaterialTheme.typography.headlineSmall)
                                    Text("Runs on your active Wi-Fi connection. Download/upload use Cloudflare test endpoints; Google Public DNS is tested over HTTPS for response latency, not bandwidth.", style = MaterialTheme.typography.bodySmall)
                                    Button(colors = ButtonDefaults.buttonColors(containerColor = mustard, contentColor = Color(0xFF263B22)), enabled = !speedBusy && !pingBusy && current != null, onClick = {
                                        val network = cm.activeNetwork
                                        if (network != null) {
                                            speedBusy = true
                                            speedResult = null
                                            speedStatus = "Testing DNS, download and upload…"
                                            scope.launch {
                                                val outcome = withContext(Dispatchers.IO) { runSpeedTest(network) }
                                                speedResult = outcome
                                                speedFinishedAt = System.currentTimeMillis()
                                                speedStatus = outcome.detail
                                                speedBusy = false
                                            }
                                        }
                                    }) { Text(if (speedBusy) "Testing…" else "Start Speed Test") }
                                    if (speedBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = mustard)
                                    Text(speedStatus, style = MaterialTheme.typography.bodySmall)
                                    speedResult?.let { result ->
                                        Text("Download: ${result.downloadMbps?.let { "%.1f Mbps".format(Locale.US,it) } ?: "Unavailable"}", style = MaterialTheme.typography.titleLarge)
                                        Text("Upload: ${result.uploadMbps?.let { "%.1f Mbps".format(Locale.US,it) } ?: "Unavailable"}", style = MaterialTheme.typography.titleLarge)
                                        Text("Google DNS HTTPS: ${result.dnsMs?.let { "$it ms" } ?: "Unavailable"}")
                                    }
                                    Text("Speed testing transfers data. Results depend on internet service, test server, and device performance. HTTPS/DNS timing is not ICMP jitter; run the Ping Test for latency and jitter statistics.", style = MaterialTheme.typography.bodySmall)
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                                    Text("SALT Test — Sustained LAN Throughput", style = MaterialTheme.typography.titleLarge)
                                    Text("Standalone continuous HTTPS download to stress-test Wi-Fi. Requires internet and uses data. Measures Wi-Fi + LAN + internet, NOT LAN-only performance.", style = MaterialTheme.typography.bodySmall)
                                    Text("Duration")
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("30", "60", "300", "∞").forEach { choice ->
                                            FilterChip(selected = saltDuration == choice, enabled = !saltBusy, onClick = { saltDuration = choice }, label = { Text(when(choice) { "30" -> "30s"; "60" -> "1m"; "300" -> "5m"; else -> "Until stop" }) })
                                        }
                                    }
                                    Button(colors = ButtonDefaults.buttonColors(containerColor = mustard, contentColor = Color(0xFF263B22)), enabled = !saltBusy && !speedBusy && !pingBusy && current != null, onClick = {
                                        val net = cm.activeNetwork ?: return@Button
                                        saltBusy = true; saltStop.set(false); saltSamples.clear(); saltBytes = 0L
                                        saltStartedAt = System.currentTimeMillis(); saltResult = null
                                        saltStatus = "SALT transfer running…"
                                        val duration = saltDuration.toIntOrNull()
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                runSaltTest(net, duration) { sample, bytes ->
                                                    runOnUiThread { saltSamples.add(sample); saltBytes = bytes }
                                                }
                                            }
                                            saltResult = result; saltFinishedAt = System.currentTimeMillis()
                                            saltStatus = result.status; saltBusy = false
                                        }
                                    }) { Text(if (saltBusy) "SALT running…" else "Start SALT Test") }
                                    if (saltBusy) {
                                        Text("Adding Salt to the Squeal", style = MaterialTheme.typography.titleLarge, color = mustard, fontWeight = FontWeight.Bold)
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = mustard)
                                        OutlinedButton(onClick = { saltStop.set(true) }) { Text("Stop SALT") }
                                    }
                                    Text(saltStatus, style = MaterialTheme.typography.bodySmall)
                                    val saltShown = saltSamples.toList()
                                    if (saltShown.isNotEmpty()) {
                                        Text("Current: ${"%.1f".format(Locale.US, saltShown.last().mbps)} Mbps · Mean interval: ${"%.1f".format(Locale.US, saltShown.map { it.mbps }.average())} Mbps")
                                        Text("Min / Max: ${"%.1f".format(Locale.US, saltShown.minOf { it.mbps })} / ${"%.1f".format(Locale.US, saltShown.maxOf { it.mbps })} Mbps")
                                        Text("Transferred: ${"%.1f".format(Locale.US, saltBytes / 1_000_000.0)} MB")
                                        Canvas(modifier = Modifier.fillMaxWidth().height(110.dp)) {
                                            val points = saltShown.takeLast(120)
                                            if (points.size >= 2) {
                                                val top = points.maxOf { it.mbps }.coerceAtLeast(1.0)
                                                val dx = size.width / (points.size - 1)
                                                for (i in 1 until points.size) drawLine(Color(0xFF326B37), Offset((i-1)*dx, size.height*(1f-(points[i-1].mbps/top).toFloat())), Offset(i*dx, size.height*(1f-(points[i].mbps/top).toFloat())), strokeWidth=3.dp.toPx())
                                            }
                                        }
                                    }
                                    saltResult?.let { r ->
                                        val mean = r.samples.map { it.mbps }.average().takeIf { !it.isNaN() } ?: 0.0
                                        val lowFraction = if (r.samples.isEmpty()) 0.0 else r.samples.count { it.mbps < mean * 0.5 }.toDouble() / r.samples.size
                                        val grade = when { r.failures > 2 || r.stalls > 3 || lowFraction > .20 -> "POOR"; r.failures > 0 || r.stalls > 0 || lowFraction > .05 -> "FAIR"; else -> "STABLE" }
                                        Text("Test duration: ${surveyDuration(r.elapsedMs)}")
                                        Text("Stability: $grade (heuristic)", color = when(grade) { "STABLE" -> Color(0xFF16834B); "FAIR" -> Color(0xFFB88600); else -> Color(0xFFC83D3D) }, fontWeight = FontWeight.Bold)
                                        Text("Average: ${"%.1f".format(Locale.US, r.avgMbps)} Mbps · Stalls: ${r.stalls} · Errors: ${r.failures} · AP roams: ${r.roams}")
                                    }
                                    if (pingSummary != null || speedResult != null || saltResult != null) {
                                        Text("Export network diagnostics", style = MaterialTheme.typography.titleMedium)
                                        Text("Exports the latest completed Ping and Speed tests. Metrics not measured are marked unavailable.", style = MaterialTheme.typography.bodySmall)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = { diagnosticsCsvExport.launch("screaming-weasels-diagnostics-${System.currentTimeMillis()}.csv") }) { Text("Export CSV") }
                                            OutlinedButton(onClick = { diagnosticsPdfExport.launch("screaming-weasels-diagnostics-${System.currentTimeMillis()}.pdf") }) { Text("Export PDF") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
    }

    private fun reportNumber(n: Double?): String = n?.let { "%.2f".format(Locale.US, it) } ?: "Unavailable"

    private fun diagnosticsLines(): List<String> = buildList {
        add("Screaming Weasels Wi-Fi Teaser | Network Diagnostics | v0.9.1")
        add("Report generated: ${Date()}")
        val p = pingSummary
        if (p == null) add("Ping Test: No completed result") else {
            add("PING TEST")
            add("Completed: ${Date(pingFinishedAt)}")
            add("Target: $pingReportTarget")
            add("ICMP payload: $pingReportPayload bytes")
            add("Selected duration: ${if (pingReportDuration == "∞") "Until stopped" else "$pingReportDuration seconds"}")
            add("Actual elapsed: ${surveyDuration(p.elapsedMs)}")
            add("Sent: ${p.sent}; Received: ${p.received}")
            add("Packet loss: ${if (p.sent > 0) reportNumber(100.0 * (p.sent - p.received) / p.sent) + "%" else "Unavailable"}")
            add("Minimum latency: ${reportNumber(p.min)} ms")
            add("Overall average latency: ${reportNumber(p.avg)} ms")
            add("Maximum latency: ${reportNumber(p.max)} ms")
            add("Jitter (average successive RTT variation): ${reportNumber(p.jitter)} ms | Grade: ${jitterGrade(p.jitter)}")
            add("Status: ${p.message}")
        }
        add("")
        val r = speedResult
        if (r == null) add("SPEED TEST: No completed result") else {
            add("SPEED TEST")
            add("Completed: ${Date(speedFinishedAt)}")
            add("Download: ${reportNumber(r.downloadMbps)} Mbps")
            add("Upload: ${reportNumber(r.uploadMbps)} Mbps")
            add("DNS HTTPS timing: ${r.dnsMs?.let { "$it ms" } ?: "Unavailable"}")
            add("Average ICMP latency: Not measured in speed test (see separate Ping Test)")
            add("Jitter: Not measured in speed test (see separate Ping Test)")
            add("Status: ${r.detail}")
        }
        add("")
        add("SALT TEST")
        saltResult?.let { r ->
            add("Completed: ${Date(saltFinishedAt)}")
            add("Test duration: ${surveyDuration(r.elapsedMs)}")
            add("Transferred bytes: ${r.bytes}")
            add("Average Mbps: ${reportNumber(r.avgMbps)}")
            add("Failures: ${r.failures}; Stalls: ${r.stalls}; AP roams: ${r.roams}")
            r.samples.forEach { x -> add("SALT sample ${x.second}s: ${reportNumber(x.mbps)} Mbps | BSSID ${x.bssid} | RSSI ${x.rssi} dBm") }
            add("Status: ${r.status}")
        } ?: add("No completed SALT test")
        add("SALT measures Wi-Fi + LAN + internet, not LAN-only; stability is heuristic.")
        add("")
        add("Notes: ICMP ping and HTTPS throughput are different measurements. A missing response is not proof of a device outage.")
    }

    private fun makeDiagnosticsCsv(): String = buildString {
        append("Field,Value\n")
        diagnosticsLines().forEach { line ->
            val parts = line.split(": ", limit = 2)
            append(csv(parts.first()))
            append(",")
            append(csv(if (parts.size == 2) parts[1] else ""))
            append("\n")
        }
    }

    private fun writeDiagnosticsPdf(out: java.io.OutputStream) {
        val document = PdfDocument()
        try {
            val lines = diagnosticsLines()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(38, 59, 34); textSize = 11f }
            var pageIndex = 0
            for (chunk in lines.chunked(43)) {
                pageIndex++
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex).create())
                var y = 48f
                chunk.forEachIndexed { index, line ->
                    paint.isFakeBoldText = index == 0 || line == "PING TEST" || line == "SPEED TEST"
                    paint.color = when {
                        line.contains("Grade: GOOD") -> android.graphics.Color.rgb(22, 131, 75)
                        line.contains("Grade: FAIR") -> android.graphics.Color.rgb(184, 134, 0)
                        line.contains("Grade: POOR") -> android.graphics.Color.rgb(200, 61, 61)
                        else -> android.graphics.Color.rgb(38, 59, 34)
                    }
                    // Limit width for PDF page. Long details wrap into two rows.
                    line.chunked(85).forEach { part -> page.canvas.drawText(part, 36f, y, paint); y += 17f }
                }
                document.finishPage(page)
            }
            document.writeTo(out)
        } finally { document.close() }
    }

    private fun saveApName(bssid: String, name: String) {
        val key = bssid.trim().lowercase()
        if (key.isBlank()) return
        if (name.isBlank()) { apNames.remove(key); getSharedPreferences("ap_friendly_names", Context.MODE_PRIVATE).edit().remove(key).apply() }
        else { apNames[key] = name.trim(); getSharedPreferences("ap_friendly_names", Context.MODE_PRIVATE).edit().putString(key, name.trim()).apply() }
    }

    private fun runPingTest(target: String, payload: Int, durationSeconds: Int?, update: (Long, String) -> Unit): PingSummary {
        // Android system ping utility sends ICMP echo packets; device permissions and binary availability vary.
        // Parse only actual received replies. No TCP approximation is reported as ICMP RTT.
        val start = android.os.SystemClock.elapsedRealtime()
        val times = mutableListOf<Double>()
        var sent = 0
        var errors = 0
        while (!pingStop.get() && (durationSeconds == null || android.os.SystemClock.elapsedRealtime() - start < durationSeconds*1000L)) {
            val began = android.os.SystemClock.elapsedRealtime()
            try {
                val process = ProcessBuilder("/system/bin/ping", "-n", "-c", "1", "-W", "1", "-s", payload.toString(), target).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                val value = Regex("time[=<]([0-9]+(?:\\.[0-9]+)?)").find(output)?.groupValues?.get(1)?.toDoubleOrNull()
                if (value != null) times.add(value) else errors++
            } catch (_: Exception) { errors++ }
            sent++
            val elapsed = android.os.SystemClock.elapsedRealtime() - start
            update(elapsed, "Sent $sent · Replies ${times.size}")
            val wait = (1000L - (android.os.SystemClock.elapsedRealtime() - began)).coerceAtLeast(0L)
            if (wait > 0 && !pingStop.get()) Thread.sleep(wait)
        }
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        val jitter = if (times.size > 1) times.zipWithNext { a,b -> abs(b-a) }.average() else null
        return PingSummary(sent, times.size, times.minOrNull(), times.maxOrNull(), times.takeIf { it.isNotEmpty() }?.average(), jitter, elapsed,
            if (pingStop.get()) "Stopped by user" else if (times.isEmpty()) "No ICMP replies; network or Android may block ping" else "Completed")
    }

    private fun apLabel(bssid: String): String = apNames[bssid.lowercase()] ?: bssid

    // Sends ~640 kbps of paced UDP payload (1000 bytes / 12.5 ms) to an authorized echo server.
    // A separate echo response is required for each packet; this is not a Microsoft Teams protocol emulator.
    private fun runUdpVideoTest(net: Network, host: String, port: Int, progress: (Int) -> Unit): UdpResult {
        var sent = 0; var received = 0; var changes = 0
        var previousBssid = current?.bssid
        val rtts = mutableListOf<Double>()
        val started = System.nanoTime()
        var detail = "Completed 60-second UDP video-like traffic simulation"
        try {
            val address = InetAddress.getByName(host)
            DatagramSocket().use { socket ->
                net.bindSocket(socket)
                socket.soTimeout = 180
                val payload = ByteArray(1000)
                val reply = ByteArray(1500)
                var lastProgress = -1
                while (!udpStop.get()) {
                    val elapsedNs = System.nanoTime() - started
                    val sec = (elapsedNs / 1_000_000_000L).toInt()
                    if (sec >= 60) break
                    if (sec != lastProgress) { progress(sec); lastProgress = sec }
                    val bssid = current?.bssid
                    if (previousBssid != null && bssid != null && !previousBssid.equals(bssid, true)) changes++
                    previousBssid = bssid
                    val tick = System.nanoTime()
                    val seq = sent + 1
                    for (i in 0..3) payload[i] = (seq ushr (i * 8)).toByte()
                    socket.send(DatagramPacket(payload, payload.size, address, port)); sent++
                    try {
                        val response = DatagramPacket(reply, reply.size)
                        socket.receive(response)
                        if (response.address == address && response.port == port && response.length >= 4 &&
                            (0..3).all { reply[it] == payload[it] }) {
                            received++
                            rtts.add((System.nanoTime() - tick) / 1_000_000.0)
                        }
                    } catch (_: java.net.SocketTimeoutException) { /* missing echo */ }
                    val waitNs = 12_500_000L - (System.nanoTime() - tick)
                    if (waitNs > 0) java.util.concurrent.locks.LockSupport.parkNanos(waitNs)
                }
                if (udpStop.get()) detail = "Stopped by user"
            }
        } catch (e: Exception) { detail = "UDP test failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}" }
        progress(((System.nanoTime() - started) / 1_000_000_000L).toInt().coerceAtMost(60))
        val avg = if (rtts.isNotEmpty()) rtts.average() else null
        val jitter = if (rtts.size > 1) rtts.zipWithNext { a,b -> abs(b-a) }.average() else null
        return UdpResult(sent, received, if (sent > 0) 100.0 * (sent-received) / sent else 0.0, avg, jitter, changes, detail)
    }

    private fun runSaltTest(net: Network, durationSeconds: Int?, onSample: (SaltSample, Long) -> Unit): SaltResult {
        val started = System.nanoTime()
        val startMs = System.currentTimeMillis()
        val endNs = durationSeconds?.let { started + it * 1_000_000_000L }
        var bytes = 0L
        var failures = 0
        var lastError = ""
        var stalls = 0
        var lastProgressNs = started
        var intervalNs = started
        var intervalBytes = 0L
        var previousBssid = ""
        var roams = 0
        val samples = mutableListOf<SaltSample>()
        val buffer = ByteArray(64 * 1024)
        while (!saltStop.get() && (endNs == null || System.nanoTime() < endNs)) {
            var connection: HttpURLConnection? = null
            try {
                connection = net.openConnection(URL("https://speed.cloudflare.com/__down?bytes=10000000")) as HttpURLConnection
                connection.connectTimeout = 6000; connection.readTimeout = 3000; connection.useCaches = false
                connection.setRequestProperty("Cache-Control", "no-store")
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                connection.inputStream.use { stream ->
                    while (!saltStop.get() && (endNs == null || System.nanoTime() < endNs)) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        bytes += n; intervalBytes += n; lastProgressNs = System.nanoTime()
                        val now = System.nanoTime()
                        if (now - intervalNs >= 1_000_000_000L) {
                            val sec = ((now - started) / 1_000_000_000L).toInt()
                            val mbps = intervalBytes * 8.0 / ((now - intervalNs) / 1e9) / 1_000_000
                            @Suppress("DEPRECATION") val info = wifi.connectionInfo
                            val bssid = info.bssid ?: "unknown"
                            if (previousBssid.isNotEmpty() && !previousBssid.equals(bssid, true)) roams++
                            previousBssid = bssid
                            val point = SaltSample(sec, mbps, bssid, info.rssi)
                            samples.add(point); onSample(point, bytes)
                            intervalNs = now; intervalBytes = 0
                        }
                    }
                }
            } catch (e: Exception) {
                if (!saltStop.get()) {
                    failures++
                    lastError = "${e.javaClass.simpleName}: ${e.message ?: "unknown network error"}"
                    if (System.nanoTime() - lastProgressNs > 3_000_000_000L) stalls++
                }
                if (failures >= 5) break
                try { Thread.sleep(750) } catch (_: InterruptedException) { break }
            } finally { connection?.disconnect() }
        }
        val elapsed = (System.nanoTime() - started) / 1_000_000L
        val status = when { saltStop.get() -> "Stopped by tester"; failures >= 5 -> "Stopped after repeated transfer errors ($failures). Last error: $lastError"; else -> "SALT test completed" }
        return SaltResult(samples, bytes, elapsed, failures, stalls, roams, status)
    }

    private fun runSpeedTest(net: Network): SpeedResult {
        val dns = try {
            val start = System.nanoTime()
            // DNS-over-HTTPS query to Google Public DNS; does not force destination IP 8.8.8.8.
            val conn = net.openConnection(URL("https://dns.google/resolve?name=example.com&type=A")) as HttpURLConnection
            conn.connectTimeout = 3500; conn.readTimeout = 3500
            conn.setRequestProperty("Accept", "application/dns-json")
            try { if (conn.responseCode !in 200..299) error("DNS HTTP ${conn.responseCode}"); conn.inputStream.use { it.readBytes() } }
            finally { conn.disconnect() }
            (System.nanoTime() - start) / 1_000_000
        } catch (_: Exception) { null }
        var download: Double? = null
        var upload: Double? = null
        val errors = mutableListOf<String>()
        try {
            val conn = net.openConnection(URL("https://speed.cloudflare.com/__down?bytes=5000000")) as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 12000
            conn.useCaches = false
            val bytes = ByteArray(32 * 1024)
            val start = System.nanoTime(); var total = 0L
            try {
                if (conn.responseCode !in 200..299) error("Download HTTP ${conn.responseCode}")
                conn.inputStream.use { stream ->
                    while (true) { val n = stream.read(bytes); if (n < 0) break; total += n }
                }
            } finally { conn.disconnect() }
            val elapsed = (System.nanoTime() - start) / 1e9
            if (elapsed > 0 && total > 0) download = total * 8.0 / elapsed / 1_000_000
        } catch (e: Exception) { errors.add("Download: ${e.javaClass.simpleName}") }
        try {
            val data = ByteArray(2_000_000) { (it % 251).toByte() }
            val conn = net.openConnection(URL("https://speed.cloudflare.com/__up")) as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true; conn.useCaches = false
            conn.connectTimeout = 5000; conn.readTimeout = 12000
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(data.size)
            val start = System.nanoTime()
            try {
                conn.outputStream.use { it.write(data) }
                if (conn.responseCode !in 200..299) error("Upload HTTP ${conn.responseCode}")
                val elapsed = (System.nanoTime() - start) / 1e9
                if (elapsed > 0) upload = data.size * 8.0 / elapsed / 1_000_000
            } finally { conn.disconnect() }
        } catch (e: Exception) { errors.add("Upload: ${e.javaClass.simpleName}") }
        return SpeedResult(download, upload, dns, if (errors.isEmpty()) "Test finished" else errors.joinToString("; "))
    }

    private fun sample() {
        val net = cm.activeNetwork ?: run { current = null; return }
        val caps = cm.getNetworkCapabilities(net)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) { current = null; return }
        @Suppress("DEPRECATION") val info = wifi.connectionInfo
        val props = cm.getLinkProperties(net)
        val gateway = props?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress ?: "unknown"
        val ip = props?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress ?: "unknown"
        val bssid = info.bssid ?: "unknown"
        val r = Reading(System.currentTimeMillis(), info.ssid?.trim('"') ?: "unknown", bssid, info.rssi, info.frequency, info.linkSpeed, gateway, ip)
        current = r
        if (knownAps[bssid.lowercase()] == null) knownAps[bssid.lowercase()] = AP(r.ssid, bssid, r.rssi, r.freq)
        liveHistory.add(r)
        while (liveHistory.size > 120) liveHistory.removeAt(0)
        val oldLive = livePrevious
        if (oldLive != null && oldLive.bssid != bssid && bssid != "02:00:00:00:00:00" && oldLive.bssid != "02:00:00:00:00:00") {
            liveEvents.add(Event(r.time, oldLive.bssid, bssid, oldLive.rssi, r.rssi))
            alerts.add(Alert(r.time, "Roamed ${apLabel(oldLive.bssid)} → ${apLabel(bssid)}"))
            val last = liveEvents.dropLast(1).lastOrNull()
            if (last != null && last.from.equals(bssid, true) && last.to.equals(oldLive.bssid, true) && r.time - last.time < 60000 && r.time - lastPingPongAlert > 60000) {
                alerts.add(Alert(r.time, "Ping-pong detected: ${apLabel(bssid)} ↔ ${apLabel(oldLive.bssid)}"))
                lastPingPongAlert = r.time
            }
            while (alerts.size > 200) alerts.removeAt(0)
            lastRoamAlert = "${time(r.time)} ${apLabel(oldLive.bssid)} → ${apLabel(bssid)} (${oldLive.rssi} → ${r.rssi} dBm)"
        }
        livePrevious = r
        val cutoff = r.time - 120_000L
        while (liveEvents.isNotEmpty() && liveEvents.first().time < cutoff) liveEvents.removeAt(0)
        if (!recording && !monitoring) return
        if (recording) history.add(r)
        val stronger = knownAps.values.filter { it.ssid == r.ssid && !it.bssid.equals(r.bssid, true) && it.rssi >= r.rssi + 15 }.maxByOrNull { it.rssi }
        if (r.rssi <= -70 && stronger != null) {
            if (stickySince == 0L) stickySince = r.time
            if (r.time - stickySince > 10000 && r.time - lastStickyAlert > 60000) {
                alerts.add(Alert(r.time, "Possible sticky client: ${r.bssid} ${r.rssi} dBm vs ${stronger.bssid} ${stronger.rssi} dBm (scan may be stale)"))
                lastStickyAlert = r.time
            }
        } else stickySince = 0L
        val before = lastSample
        if (previousBssid != null && previousBssid != bssid && bssid != "02:00:00:00:00:00") {
            events.add(Event(r.time, previousBssid!!, bssid, before?.rssi ?: r.rssi, r.rssi))
        }
        previousBssid = bssid
        lastSample = r
        if (r.time - lastProbeTime >= 2000 && probing.compareAndSet(false, true)) {
            lastProbeTime = r.time
            Thread {
                try {
                    val local = tcpGateway(net, gateway)
                    val remote = internetProbe(net)
                    runOnUiThread {
                        if (recording || monitoring) {
                            probes.add(local); probes.add(remote)
                            while (probes.size > 3000) probes.removeAt(0)
                            if (remote.ms != null && remote.status.startsWith("HTTP")) {
                                if (previousInternetSuccess != null && remote.time - previousInternetSuccess!! > 6000L) outages.add("${time(remote.time)} probe gap ${(remote.time - previousInternetSuccess!!)} ms")
                                previousInternetSuccess = remote.time
                            } else outages.add("${time(remote.time)} internet probe failed: ${remote.status}")
                        }
                    }
                } finally { probing.set(false) }
            }.start()
        }
    }

    private fun tcpGateway(net: Network, gateway: String): Probe {
        val now = System.currentTimeMillis()
        if (gateway == "unknown") return Probe(now, "Gateway", "No gateway", null)
        for (port in listOf(80, 443, 53)) {
            try {
                val start = System.nanoTime()
                net.socketFactory.createSocket().use { it.connect(InetSocketAddress(gateway, port), 700) }
                return Probe(now, "Gateway", "TCP $port reachable", (System.nanoTime() - start) / 1_000_000)
            } catch (_: Exception) { }
        }
        return Probe(now, "Gateway", "TCP ports unavailable", null)
    }
    private fun internetProbe(net: Network): Probe {
        val now = System.currentTimeMillis()
        return try {
            val start = System.nanoTime()
            val conn = net.openConnection(URL("https://www.google.com/generate_204")) as HttpURLConnection
            conn.connectTimeout = 1500; conn.readTimeout = 1500; conn.instanceFollowRedirects = false
            val code = try { conn.responseCode } finally { conn.disconnect() }
            Probe(now, "Internet", "HTTP $code", (System.nanoTime() - start) / 1_000_000)
        } catch (e: Exception) { Probe(now, "Internet", "Failed: ${e.javaClass.simpleName}", null) }
    }
    private fun scan() {
        try {
            @Suppress("DEPRECATION") val started = wifi.startScan()
            @Suppress("DEPRECATION") val result = wifi.scanResults
            aps.clear()
            aps.addAll(result.sortedByDescending { it.level }.map { AP(it.SSID.ifBlank { "(hidden)" }, it.BSSID, it.level, it.frequency) })
            aps.forEach { knownAps[it.bssid.lowercase()] = it }
            if (baselineBssids.isNotEmpty()) {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                aps.filter { !baselineBssids.containsKey(it.bssid.lowercase(Locale.ROOT)) }.forEach { ap ->
                    val key = ap.bssid.lowercase(Locale.ROOT)
                    if (newApEvents.none { it.contains("BSSID=$key ") }) {
                        newApEvents.add("$stamp BSSID=$key SSID=${ap.ssid} RSSI=${ap.rssi}dBm ${channel(ap.freq)}")
                    }
                }
                baselineStatus = "${aps.count { !baselineBssids.containsKey(it.bssid.lowercase(Locale.ROOT)) }} newly observed APs in latest scan"
            }
            scanNote = if (started) "Scan requested; results may be cached. Tap again after several seconds." else "Scan throttled/denied; showing cached results."
        } catch (e: Exception) { scanNote = "Scan error: ${e.message}" }
    }
    private fun makeCsv(): String = buildString {
        appendLine("record_type,time,session,room,ssid,bssid,rssi_dbm,frequency_mhz,channel,link_mbps,gateway,local_ip,detail,latency_ms")
        if (surveyStartedAt > 0L) {
            val finish = if (recording) System.currentTimeMillis() else surveyEndedAt
            val metadata = "Survey started: ${time(surveyStartedAt)}; ended: ${if (recording) "still recording" else time(finish)}; duration: ${surveyDuration(finish - surveyStartedAt)}"
            appendLine(listOf("survey_summary",time(surveyStartedAt),csv(sessionName),"","","","","","","","","",csv(metadata),"").joinToString(","))
        }
        history.forEach { r -> appendLine(listOf("reading",time(r.time),csv(sessionName),csv(roomName),csv(r.ssid),csv(r.bssid),r.rssi,r.freq,csv(channel(r.freq)),r.speed,csv(r.gateway),csv(r.localIp),"","").joinToString(",")) }
        events.forEach { e -> appendLine(listOf("roam",time(e.time),csv(sessionName),"","","","","","","","","",csv("${e.from} -> ${e.to}; RSSI ${e.previousRssi} -> ${e.newRssi}"),"").joinToString(",")) }
        outages.forEach { o -> appendLine(listOf("interruption","",csv(sessionName),"","","","","","","","","",csv(o),"").joinToString(",")) }
        probes.forEach { p -> appendLine(listOf("probe",time(p.time),csv(sessionName),"","","","","","","","","",csv("${p.target}: ${p.status}"),p.ms ?: "").joinToString(",")) }
    }
}

@Composable private fun SignalGraph(values: List<Reading>, transitions: List<Event>) {
    val min = -100f; val max = -20f
    Column(Modifier.fillMaxWidth().background(Color(0xFFF6F8FA), RoundedCornerShape(12.dp)).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−20 dBm", style = MaterialTheme.typography.labelSmall)
            Text("AP transitions: ${transitions.count { it.time in values.first().time..values.last().time }}", style = MaterialTheme.typography.labelSmall)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(190.dp)) {
            val bands = listOf(Triple(-20f,-60f,Color(0xFFE2F5E9)),Triple(-60f,-70f,Color(0xFFFFF1CF)),Triple(-70f,-100f,Color(0xFFFBE0E0)))
            bands.forEach { (upper,lower,color) ->
                val y1 = (max-upper)/(max-min)*size.height; val y2 = (max-lower)/(max-min)*size.height
                drawRect(color, Offset(0f,y1), androidx.compose.ui.geometry.Size(size.width,y2-y1))
            }
            listOf(-40,-60,-70,-80).forEach { level ->
                val y=(max-level)/(max-min)*size.height
                drawLine(Color(0xFFB8C0C8), Offset(0f,y),Offset(size.width,y),1f)
            }
            val first=values.first().time.toDouble(); val span=(values.last().time-first).coerceAtLeast(1.0)
            val points=values.map { v -> Offset(((v.time-first)/span*size.width).toFloat(),(max-v.rssi.coerceIn(-100,-20))/(max-min)*size.height) }
            points.zipWithNext().forEach { (a,b) -> drawLine(Color(0xFF6750A4),a,b,4f) }
            transitions.filter { it.time in values.first().time..values.last().time }.forEach { ev ->
                val x=((ev.time-first)/span*size.width).toFloat()
                drawLine(Color(0xFF1D6FD8),Offset(x,0f),Offset(x,size.height),2f)
                drawCircle(Color(0xFF1D6FD8),5f,Offset(x,8f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("−100 dBm · ${time(values.first().time)}", style = MaterialTheme.typography.labelSmall)
            Text(time(values.last().time), style = MaterialTheme.typography.labelSmall)
        }
        Text("Purple: RSSI   Blue marker: observed BSSID change", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun ChannelGraph(networks: List<AP>, range: IntRange) {
    val grouped=networks.groupBy { channelNumber(it.freq) }.filterKeys { it in range }
    if (grouped.isEmpty()) { Text("No scan results for this band", style = MaterialTheme.typography.bodySmall); return }
    val keys=grouped.keys.sorted()
    Column(Modifier.fillMaxWidth().background(Color(0xFFF6F8FA), RoundedCornerShape(12.dp)).padding(12.dp)) {
        keys.forEach { ch ->
            val strongest=grouped.getValue(ch).maxOf { it.rssi }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Ch $ch", modifier = Modifier.width(62.dp), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { ((strongest + 100) / 70f).coerceIn(0f,1f) }, modifier = Modifier.weight(1f).height(10.dp), color = signalColor(strongest))
                Text(" $strongest dBm", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
private fun channelNumber(mhz: Int): Int = when {
    mhz == 2484 -> 14
    mhz in 2412..2472 -> (mhz-2407)/5
    mhz in 5000..5895 -> (mhz-5000)/5
    mhz == 5935 -> 2
    mhz in 5955..7115 -> (mhz-5950)/5
    else -> -1
}

@Composable private fun AccessPointRow(ap: AP, friendlyName: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(ap.ssid, fontWeight = FontWeight.SemiBold)
            friendlyName?.let { Text(it, color = apNameYellow, fontWeight = FontWeight.Bold) }
            Text("${channel(ap.freq)} · ${ap.bssid}", style = MaterialTheme.typography.bodySmall)
        }
        Text("${ap.rssi} dBm", color = signalColor(ap.rssi), fontWeight = FontWeight.Bold)
    }
    HorizontalDivider()
}
