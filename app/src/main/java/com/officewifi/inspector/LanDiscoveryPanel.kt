package com.officewifi.inspector

import android.content.Context
import android.net.LinkProperties
import android.net.Network
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

internal data class DeviceFound(val ip: String, val services: String, val latency: Long)
internal data class UdpSeen(val sender: String, val protocol: String, val bytes: Int)

/** Activity-owned state: changing navigation tabs never discards results or stops an active scan. */
internal class DiscoverySession(private val context: Context) {
    var busy by mutableStateOf(false)
    var udpBusy by mutableStateOf(false)
    var message by mutableStateOf("Scan only networks you own or administer.")
    var udpMessage by mutableStateOf("Passive discovery sees only packets delivered to this phone.")
    var scanned by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var seconds by mutableLongStateOf(0L)
    var devices by mutableStateOf<List<DeviceFound>>(emptyList())
    var observed by mutableStateOf<List<UdpSeen>>(emptyList())
    var permission by mutableStateOf(false)
    var mode by mutableStateOf("IP Only")
    var resultMode by mutableStateOf("IP Only")
    val stop = AtomicBoolean(false)
    val udpStop = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("network_discovery_sessions", Context.MODE_PRIVATE)
    init {
        try {
            devices = JSONArray(prefs.getString("devices", "[]")).let { arr ->
                (0 until arr.length()).map { i -> arr.getJSONObject(i).let { DeviceFound(it.getString("ip"), it.getString("services"), it.getLong("latency")) } }
            }
            observed = JSONArray(prefs.getString("udp", "[]")).let { arr ->
                (0 until arr.length()).map { i -> arr.getJSONObject(i).let { UdpSeen(it.getString("sender"), it.getString("protocol"), it.getInt("bytes")) } }
            }
            scanned = prefs.getInt("scanned", 0); total = prefs.getInt("total", 0)
            seconds = prefs.getLong("seconds", 0L)
            message = prefs.getString("message", message) ?: message
            udpMessage = prefs.getString("udpMessage", udpMessage) ?: udpMessage
            mode = (prefs.getString("mode", mode) ?: mode).takeIf { it == "IP Only" || it == "Thorough" } ?: "IP Only"
            resultMode = prefs.getString("resultMode", resultMode) ?: resultMode
        } catch (_: Exception) { message = "Previous results could not be restored." }
    }
    fun save() {
        val d = JSONArray().also { arr -> devices.forEach { arr.put(JSONObject().put("ip",it.ip).put("services",it.services).put("latency",it.latency)) } }
        val u = JSONArray().also { arr -> observed.forEach { arr.put(JSONObject().put("sender",it.sender).put("protocol",it.protocol).put("bytes",it.bytes)) } }
        prefs.edit().putString("devices",d.toString()).putString("udp",u.toString())
            .putInt("scanned",scanned).putInt("total",total).putLong("seconds",seconds)
            .putString("message",message).putString("udpMessage",udpMessage).putString("mode",mode).putString("resultMode",resultMode).apply()
    }
    fun clear() {
        if (busy || udpBusy) return
        devices = emptyList(); observed = emptyList(); scanned = 0; total = 0; seconds = 0
        message = "Results cleared."; udpMessage = "No saved UDP observations."; save()
    }
}
private val ports = listOf(22 to "SSH", 53 to "DNS/TCP", 80 to "HTTP", 139 to "NetBIOS", 443 to "HTTPS", 445 to "SMB", 554 to "RTSP", 631 to "IPP", 3389 to "RDP", 5900 to "VNC", 8000 to "HTTP-alt", 8080 to "HTTP-alt", 9100 to "Printer")

@Composable
internal fun LanDiscoveryPanel(network: Network?, link: LinkProperties?, session: DiscoverySession, scope: CoroutineScope) {
    var busy by session::busy
    var udpBusy by session::udpBusy
    var message by session::message
    var udpMessage by session::udpMessage
    var scanned by session::scanned
    var total by session::total
    var seconds by session::seconds
    var devices by session::devices
    var observed by session::observed
    var permission by session::permission
    var mode by session::mode
    var resultMode by session::resultMode
    var selectedDevice by remember { mutableStateOf<DeviceFound?>(null) }
    var detailText by remember { mutableStateOf("") }
    var detailBusy by remember { mutableStateOf(false) }
    val mustard = androidx.compose.ui.graphics.Color(0xFFD9B65B)
    val stop = session.stop
    val udpStop = session.udpStop
    val ipv4 = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }
    val prefix = ipv4?.prefixLength ?: -1
    val ip = ipv4?.address?.hostAddress ?: "Unavailable"
    val candidates = remember(ip, prefix) {
        if (ipv4 == null || prefix !in 16..30) emptyList() else {
            val bytes = ipv4.address.address
            val addr = bytes.fold(0L) { a,b -> (a shl 8) or (b.toLong() and 255) }
            val mask = (0xffffffffL shl (32-prefix)) and 0xffffffffL
            val base = if (prefix < 22) addr and 0xffffff00L else addr and mask
            val count = if (prefix < 22) 256 else min(1L shl (32-prefix), 1024L).toInt()
            (1 until count-1).map { n ->
                val v = base + n
                "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
            }.filter { it != ip }
        }
    }
    selectedDevice?.let { device ->
        AlertDialog(onDismissRequest = { selectedDevice = null },
            title = { Text("Device investigation · ${device.ip}") },
            text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detailBusy) LinearProgressIndicator(color = mustard)
                Text(detailText, style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { TextButton(onClick = { selectedDevice = null }) { Text("Close") } })
    }
    HorizontalDivider()
    Text("LAN Device Scanner", style = MaterialTheme.typography.titleLarge)
    Text("IP discovery and TCP services. Wi-Fi SSIDs and channels remain in Wi-Fi Scan.", style = MaterialTheme.typography.bodySmall)
    Text("Connected address: $ip / $prefix · Scan cap: 1,022 hosts", style = MaterialTheme.typography.bodySmall)
    Row { Checkbox(checked = permission, onCheckedChange = { permission = it }); Text("I have permission to scan this network", modifier = Modifier.padding(top=12.dp), style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("IP Only", "Thorough").forEach { label -> FilterChip(selected = mode == label, onClick = { mode = label }, label = { Text(label) }) }
    }
    Text(if (mode == "IP Only") "Fast discovery: checks a few common TCP ports and lists responding IP addresses only. Silent or filtered devices may not appear." else "Service discovery: probes selected TCP ports and displays detected services.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(colors = ButtonDefaults.buttonColors(containerColor = mustard, contentColor = androidx.compose.ui.graphics.Color(0xFF263B22)), enabled = !busy && network != null && candidates.isNotEmpty() && permission, onClick = {
            busy = true; stop.set(false); scanned = 0; total = candidates.size; devices = emptyList(); seconds = 0; resultMode = mode
            val snapshot = candidates.toList(); val pace = mode
            scope.launch {
                val started = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    for ((index, address) in snapshot.withIndex()) {
                        if (stop.get()) break
                        val found = mutableListOf<String>()
                        val began = System.currentTimeMillis()
                        val selectedPorts = if (pace == "IP Only") listOf(80 to "HTTP", 443 to "HTTPS", 445 to "SMB") else ports
                        for ((port, name) in selectedPorts) {
                            if (stop.get()) break
                            try {
                                Socket().use { sock ->
                                    network!!.bindSocket(sock)
                                    sock.connect(InetSocketAddress(address, port), if (pace == "IP Only") 45 else 180)
                                    found.add("$port $name")
                                }
                            } catch (_: Exception) { }
                            
                        }
                        if (found.isNotEmpty()) {
                            val d = DeviceFound(address, found.joinToString(", "), System.currentTimeMillis()-began)
                            withContext(Dispatchers.Main) { devices = devices + d; session.save() }
                        }
                        withContext(Dispatchers.Main) { scanned = index+1; seconds = (System.currentTimeMillis()-started)/1000; if (index % 20 == 0) session.save() }
                    }
                }
                session.save()
                message = if (stop.get()) "Stopped: ${devices.size} responsive devices" else "Completed: ${devices.size} responsive devices. Devices without open selected TCP ports may be missed."
                busy = false
                session.save()
            }
        }) { Text(if (mode == "IP Only") "Quick IP Scan" else "Scan LAN") }
        if (busy) OutlinedButton(onClick = { stop.set(true) }) { Text("Stop") }
    }
    if (busy) LinearProgressIndicator(progress = { if (total > 0) scanned.toFloat()/total else 0f }, modifier = Modifier.fillMaxWidth())
    Text("$message · $scanned/$total hosts · ${seconds}s", style = MaterialTheme.typography.bodySmall)
    Text("Discovered: ${devices.size} · Saved scan: $resultMode", style = MaterialTheme.typography.titleMedium)
    devices.sortedBy { it.ip.split('.').lastOrNull()?.toIntOrNull() ?: 0 }.forEach { d ->
        Card(onClick = {
            selectedDevice = d
            detailBusy = true
            detailText = "Investigating ${d.ip}… This runs additional probes only against the selected IP."
            scope.launch {
                detailText = withContext(Dispatchers.IO) { investigateDevice(network, d) }
                detailBusy = false
            }
        }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) {
            Text("${d.ip}  ·  Tap for full report", style = MaterialTheme.typography.titleSmall)
            if (resultMode != "IP Only") {
                Text("TCP services: ${d.services}", style = MaterialTheme.typography.bodySmall)
                Text("Probe elapsed: ${d.latency} ms (not ping RTT)", style = MaterialTheme.typography.labelSmall)
            }
        } }
    }
    if (!busy && devices.isNotEmpty()) {
        OutlinedButton(onClick = { session.clear() }) { Text("Clear saved results") }
    }
}

/** On-demand, bounded investigation. Only probes the selected address; no claim of OS certainty. */
private fun investigateDevice(network: Network?, device: DeviceFound): String {
    val host = device.ip
    val start = System.currentTimeMillis()
    val hostname = try { InetAddress.getByName(host).canonicalHostName.takeIf { it != host } ?: "Not resolved" } catch (_: Exception) { "Unavailable" }
    val open = mutableListOf<String>()
    val checked = ports.size
    for ((port, label) in ports) {
        try {
            Socket().use { socket ->
                network?.bindSocket(socket)
                socket.connect(InetSocketAddress(host, port), 250)
                open.add("$port/tcp · $label")
            }
        } catch (_: Exception) { }
    }
    val elapsed = System.currentTimeMillis() - start
    return buildString {
        appendLine("IP address: $host")
        appendLine("Hostname: $hostname")
        appendLine("Discovery method: TCP connect probes")
        appendLine("Initial scan response: ${device.latency} ms (not ICMP ping)")
        appendLine("Selected ports tested: $checked")
        appendLine("Responding TCP services: ${open.size}")
        if (open.isEmpty()) appendLine("No selected TCP services responded; this does not prove the host is offline.")
        else open.forEach { appendLine("• $it") }
        appendLine("Investigation duration: ${elapsed} ms")
        appendLine("MAC address: ${lookupMac(host)}")
        appendLine("Manufacturer: Unavailable without a reliable vendor database")
        appendLine("Device type / operating system: Not confirmed")
        appendLine("Nonresponsive ports may be closed, filtered, or blocked by firewall.")
    }
}

/** Best-effort read of an existing kernel neighbor entry. Recent Android versions often deny access. */
private fun lookupMac(ip: String): String = try {
    val entry = java.io.File("/proc/net/arp").useLines { lines ->
        lines.drop(1).firstOrNull { it.trim().split(Regex("\\s+")).firstOrNull() == ip }
    }
    val parts = entry?.trim()?.split(Regex("\\s+"))
    val mac = parts?.getOrNull(3)
    if (mac != null && Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(mac) && mac != "00:00:00:00:00:00") mac
    else "Unavailable (Android may restrict neighbor-table access)"
} catch (_: Exception) { "Unavailable (Android may restrict neighbor-table access)" }
