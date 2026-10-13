package com.officewifi.inspector

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Settings-only preview. No transport implementation or credential capture. */
@Composable
fun ConsolePreview(onHelp: () -> Unit) {
    var transport by remember { mutableStateOf("USB Serial") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var baud by remember { mutableStateOf("9600") }
    var dataBits by remember { mutableStateOf("8") }
    var parity by remember { mutableStateOf("None") }
    var stopBits by remember { mutableStateOf("1") }
    var flow by remember { mutableStateOf("None") }
    var encoding by remember { mutableStateOf("UTF-8") }
    var terminal by remember { mutableStateOf("xterm") }
    var localEcho by remember { mutableStateOf("Auto") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Console", style = MaterialTheme.typography.headlineMedium)
        Text("Configuration preview — connections are not enabled in v0.9.3.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onHelp) { Text("About / How to Use", maxLines = 1) }
        Text("Connection Type", style = MaterialTheme.typography.titleMedium)
        listOf("SSH", "USB Serial", "Bluetooth SPP", "BLE UART", "Telnet", "Raw TCP").chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { option ->
                    FilterChip(selected = transport == option, onClick = { transport = option }, label = { Text(option, maxLines = 1) }, modifier = Modifier.weight(1f))
                }
            }
        }
        if (transport == "SSH" || transport == "Telnet" || transport == "Raw TCP") {
            OutlinedTextField(host, { host = it }, label = { Text("Host or IP") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(port, { port = it }, label = { Text("TCP port") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        } else {
            Text("Physical adapter selection, permissions and pairing will be available when transport drivers are implemented.")
            OutlinedTextField(baud, { baud = it }, label = { Text("Baud rate (custom allowed)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            ConfigChoice("Data bits", listOf("5","6","7","8"), dataBits) { dataBits = it }
            ConfigChoice("Parity", listOf("None","Even","Odd","Mark","Space"), parity) { parity = it }
            ConfigChoice("Stop bits", listOf("1","1.5","2"), stopBits) { stopBits = it }
            ConfigChoice("Flow control", listOf("None","RTS/CTS","XON/XOFF","DTR/DSR"), flow) { flow = it }
        }
        Text("Terminal Options", style = MaterialTheme.typography.titleMedium)
        ConfigChoice("Emulation", listOf("xterm","VT100","VT220","ANSI","Dumb"), terminal) { terminal = it }
        ConfigChoice("Encoding", listOf("UTF-8","US-ASCII","ISO-8859-1"), encoding) { encoding = it }
        ConfigChoice("Local echo", listOf("Auto","On","Off"), localEcho) { localEcho = it }
        HorizontalDivider()
        Text("Planned: multi-session tabs, profiles, editable vendor presets, secure SSH keys, Bluetooth keyboards, technician keys, command library, configuration backup/diff, logging, SFTP/SCP, jump hosts, forwarding, and connection diagnostics.")
        Text("No credentials are collected. Connect and Save Profile will appear when implemented.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConfigChoice(title: String, options: List<String>, value: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(value, maxLines = 1) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { onSelect(choice); expanded = false }) }
            }
        }
    }
}
