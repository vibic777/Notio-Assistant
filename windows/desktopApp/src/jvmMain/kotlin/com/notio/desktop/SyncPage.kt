package com.notio.desktop

import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notio.shared.sync.FolderInfo
import com.notio.shared.sync.FoundDevice
import com.notio.shared.sync.Pairing
import com.notio.shared.sync.SyncCheck
import com.notio.shared.sync.SyncDirection
import com.notio.shared.sync.SyncEngine
import com.notio.shared.sync.SyncException
import com.notio.shared.sync.SyncFolders
import com.notio.shared.sync.SyncOutcome
import com.notio.shared.sync.SyncPresets
import com.notio.shared.sync.SyncPrompt
import com.notio.shared.sync.SyncStatus
import com.notio.shared.transfer.ImportMode
import kotlinx.coroutines.launch

/**
 * Settings -> Sync (D226): this device, a new connection (Wait with a code / Find and type the code), the kept connections with
 * Sync…, Test and Remove, and the log. The device listens and announces itself ONLY while this page is on the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncOptions(controller: NotioController) {
    val engine = controller.syncEngine
    val store = controller.syncStore
    val res = LocalRes.current
    val scope = rememberCoroutineScope()
    // Listening only while the page is on the screen (Android: lifecycle START / STOP): left, the port and the announcement close.
    DisposableEffect(engine) {
        engine.startListening()
        // "Always ready for a sync" (D104, Android D323): Notio listens all the time it runs, leaving the page does not end it.
        onDispose { if (!controller.syncAlways) engine.stopListening() }
    }

    val listening by engine.listening.collectAsState()
    val code by engine.pairingCode.collectAsState()
    val prompt by engine.prompt.collectAsState()
    val status by engine.status.collectAsState()
    val nearby by engine.nearby.collectAsState()
    val pairings by store.pairings.collectAsState()
    val log by store.log.collectAsState()
    val pairedEvent by engine.pairedEvent.collectAsState()
    var deviceName by remember { mutableStateOf(store.deviceName) }
    val portBusy by engine.portBusy.collectAsState()
    var listenPort by remember { mutableStateOf(store.listenPort) }
    var choosingPort by remember { mutableStateOf(false) }

    var renaming by remember { mutableStateOf(false) }
    var finding by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf<Pairing?>(null) }
    var working by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<Pair<String, SyncOutcome>?>(null) }
    var checks by remember { mutableStateOf<Pair<String, List<SyncCheck>>?>(null) }
    var removing by remember { mutableStateOf<Pairing?>(null) }
    var addressing by remember { mutableStateOf<Pairing?>(null) }
    var choosingFolders by remember { mutableStateOf<Pairing?>(null) }
    var showLog by remember { mutableStateOf(false) }

    fun toast(text: String) = controller.say(text)
    fun reason(e: Throwable): String = reasonText(res, e)
    fun startSync(p: Pairing, direction: SyncDirection, mode: ImportMode, folders: Set<String>?, reminders: Boolean, unattended: Boolean = false) {
        working = res.str("sync_running", p.peerName)
        scope.launch {
            engine.sync(p.id, direction, mode, folders, reminders, unattended)
                .onSuccess { outcome = p.peerName to it }
                .onFailure { toast(res.str("sync_failed", reason(it))) }
            working = null
        }
    }

    Text(stringResource("settings_sync_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // ---- this device ----
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource("sync_this_device", deviceName), style = MaterialTheme.typography.titleSmall)
            val l = listening
            Text(
                if (l == null) stringResource("sync_not_listening")
                else if (l.addresses.isEmpty()) stringResource("sync_no_wifi")
                else stringResource(if (controller.syncAlways) "sync_listening_always" else "sync_listening", l.addresses.joinToString(", ") { "$it:${l.port}" }),
                style = MaterialTheme.typography.bodySmall,
                color = if (l == null || l.addresses.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Always ready for a sync (D104, Android D323): off by default.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val alwaysLabel = stringResource("sync_always")
                Text(alwaysLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Switch(
                    checked = controller.syncAlways,
                    onCheckedChange = { controller.chooseSyncAlways(it) },
                    modifier = Modifier.semantics { contentDescription = alwaysLabel },
                )
            }
            Text(stringResource("sync_always_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The port of this device (D135, Android D357): by itself, or the one typed by the person; a taken one is said.
            val busy = portBusy
            Text(
                if (busy != null) stringResource("sync_port_busy", busy.toString())
                else if (listenPort != 0) stringResource("sync_port_manual", listenPort.toString())
                else if (l != null && l.port != SyncEngine.DEFAULT_PORT) stringResource("sync_port_auto_other", SyncEngine.DEFAULT_PORT.toString(), l.port.toString())
                else stringResource("sync_port_auto"),
                style = MaterialTheme.typography.bodySmall,
                color = if (busy != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { renaming = true }) { Text(stringResource("sync_rename_device")) }
                TextButton(onClick = { choosingPort = true }) { Text(stringResource("sync_port_edit")) }
            }
        }
    }

    // ---- a new connection ----
    Text(stringResource("sync_new_connection"), style = MaterialTheme.typography.titleSmall)
    val c = code
    if (c == null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { engine.startPairing() }, enabled = listening != null) { Text(stringResource("sync_wait")) }
            OutlinedButton(onClick = { engine.recheckNetworkIfChanged(); finding = true }) { Text(stringResource("sync_find")) }
            // The network changed since the page began to listen (D123): the address, the announcement and the search anew.
            OutlinedButton(onClick = { engine.recheckNetwork() }, enabled = listening != null) { Text(stringResource("sync_recheck")) }
        }
        Text(stringResource("sync_roles_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource("sync_code_title"), style = MaterialTheme.typography.bodyMedium)
                // The code beside its Copy (D123): it can be sent to the other device by another way than reading it aloud.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer { Text(c, fontFamily = FontFamily.Monospace, fontSize = 28.sp, style = MaterialTheme.typography.headlineMedium) }
                    TextButton(onClick = { copyToClipboard(c) }) { Text(stringResource("action_copy")) }
                }
                Text(stringResource("sync_code_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { engine.stopPairing() }) { Text(stringResource("action_cancel")) }
            }
        }
    }
    pairedEvent?.let { name ->
        LaunchedEffect(name) { toast(res.str("sync_paired_with", name)); engine.clearPairedEvent() }
    }

    // ---- kept connections ----
    Text(stringResource("sync_connections"), style = MaterialTheme.typography.titleSmall)
    if (pairings.isNotEmpty()) {
        Text(stringResource("sync_preset_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource("sync_preset_unattended_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pairings.isEmpty()) {
        Text(stringResource("sync_no_connections"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    pairings.forEach { p ->
        val online = nearby.any { it.deviceId == p.peerDeviceId }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.peerName, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (online) "sync_online" else "sync_offline"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (p.lastSyncAt > 0) stringResource("sync_last", Format.dateTime(p.lastSyncAt)) else stringResource("sync_never"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Android D280: where this device knocks when the other is not found by itself; it can be corrected by hand.
                Text(
                    p.lastHost?.let { stringResource("sync_address_line", it + ":" + (p.lastPort.takeIf { port -> port > 0 } ?: SyncEngine.DEFAULT_PORT)) }
                        ?: stringResource("sync_address_unknown"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // What a sync of this connection does without asking (D316).
                PresetRow(p, enabled = working == null, change = { how -> store.update(p.id, how) }, onChooseFolders = { choosingFolders = p })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Everything set beforehand: it starts at once; otherwise the dialog asks what is left to choose.
                    Button(onClick = {
                        if (SyncPresets.startsAtOnce(p)) startSync(p, p.direction, SyncPresets.modeOf(p), SyncPresets.foldersOf(p), p.reminders, unattended = true) else syncing = p
                    }, enabled = working == null) { Text(stringResource("sync_sync")) }
                    OutlinedButton(onClick = {
                        working = res.str("sync_testing")
                        scope.launch { checks = p.peerName to engine.test(p.id); working = null }
                    }, enabled = working == null) { Text(stringResource("sync_test")) }
                    TextButton(onClick = { addressing = p }, enabled = working == null) { Text(stringResource("sync_address_edit")) }
                    TextButton(onClick = { removing = p }, enabled = working == null) { Text(stringResource("sync_remove")) }
                }
            }
        }
    }

    // ---- log ----
    TextButton(onClick = { showLog = true }) { Text(stringResource("sync_log") + " (" + log.size + ")") }

    // ---- dialogs ----
    if (renaming) {
        var name by remember { mutableStateOf(deviceName) }
        com.notio.desktop.EditorDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource("sync_rename_device")) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text(stringResource("sync_device_name")) }) },
            confirmButton = {
                TextButton(onClick = {
                    store.deviceName = name
                    deviceName = store.deviceName
                    renaming = false
                    if (listening != null) { engine.stopListening(); engine.startListening() }
                }, enabled = name.isNotBlank()) { Text(stringResource("action_save")) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource("action_cancel")) } },
        )
    }

    if (choosingPort) {
        PortDialog(
            current = listenPort,
            isFree = { engine.isPortFree(it) },
            onDismiss = { choosingPort = false },
            onSave = { port ->
                choosingPort = false
                store.listenPort = port
                listenPort = store.listenPort
                store.log(if (port == 0) "the port to listen on: by itself" else "the port to listen on set by hand: $port")
                // The page is on the screen, so the device listens: on the new port from now.
                engine.stopListening(); engine.startListening()
            },
        )
    }

    if (finding) {
        FindDeviceDialog(
            nearby = nearby,
            onRecheck = { engine.recheckNetwork() },
            onDismiss = { finding = false },
            onPair = { host, port, typed ->
                finding = false
                working = res.str("sync_pairing_progress")
                scope.launch {
                    engine.pair(host, port, typed)
                        .onSuccess { toast(res.str("sync_paired_with", it.peerName)) }
                        .onFailure { toast(res.str("sync_failed", reason(it))) }
                    working = null
                }
            },
        )
    }

    syncing?.let { p ->
        SyncDialog(
            controller = controller,
            pairing = p,
            onDismiss = { syncing = null },
            onRefreshFolders = { scope.launch { engine.fetchFolders(p.id).onFailure { toast(res.str("sync_failed", reason(it))) } } },
            onStart = { direction, mode, folders, reminders ->
                syncing = null
                // Shown here because it is a replacement, with everything set beforehand: this dialog was the confirmation of
                // the device that starts, and the other device is not asked (D333). Anything asked here: it is asked there too.
                startSync(p, direction, mode, folders, reminders, unattended = SyncPresets.nothingAsked(p))
            },
        )
    }

    choosingFolders?.let { p ->
        val live = pairings.firstOrNull { it.id == p.id } ?: p
        val local = controller.state.collectAsState().value.folders.map { FolderInfo(it.folder.id, it.folder.name) }
        PresetFoldersDialog(
            pairing = live,
            folders = SyncFolders.choices(local, live.peerFolders),
            known = local + live.peerFolders,
            onRefresh = { scope.launch { engine.fetchFolders(p.id).onFailure { toast(res.str("sync_failed", reason(it))) } } },
            onDismiss = { choosingFolders = null },
            onSave = { chosen -> store.update(p.id) { SyncPresets.withFolders(it, false, chosen) }; choosingFolders = null },
        )
    }

    addressing?.let { p ->
        AddressDialog(
            pairing = p,
            onDismiss = { addressing = null },
            onSave = { host, port ->
                addressing = null
                // The file of the connections is written off the window's thread.
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        store.update(p.id) { it.copy(lastHost = host, lastPort = port) }
                        store.log("address of ${p.peerName} set by hand: $host:$port")
                    }
                    toast(res.str("sync_address_saved", "$host:$port"))
                }
            },
        )
    }

    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource("sync_remove_title", p.peerName)) },
            text = { Text(stringResource("sync_remove_message")) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    scope.launch {
                        val both = engine.unpair(p.id)
                        toast(res.str(if (both) "sync_removed_both" else "sync_removed_here"))
                    }
                }) { Text(stringResource("sync_remove")) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource("action_cancel")) } },
        )
    }

    working?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(text) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }

    // What an incoming connection is doing on this device.
    if (working == null && prompt == null) status?.let { s ->
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(stringResource(when (s) { SyncStatus.RECEIVING -> "sync_status_receiving"; SyncStatus.SENDING -> "sync_status_sending"; else -> "sync_pairing_progress" }))
            },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }

    outcome?.let { (name, o) ->
        AlertDialog(
            onDismissRequest = { outcome = null },
            title = { Text(stringResource("sync_done_title", name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.received?.let { Text(stringResource("sync_received") + " " + statsText(res, it)) }
                    o.sent?.let { Text(stringResource("sync_sent") + " " + statsText(res, it)) }
                    // "Done" with nothing carried is said in words (D324): no new folder, no new or updated note either way.
                    if (listOfNotNull(o.received, o.sent).all { s -> s.getOrElse(0) { 0 } + s.getOrElse(2) { 0 } + s.getOrElse(3) { 0 } == 0 }) {
                        Text(stringResource("sync_nothing_changed"), color = MaterialTheme.colorScheme.error)
                    }
                    if (kotlin.math.abs(o.clockOffset) >= SyncEngine.CLOCK_TOLERANCE) {
                        Text(stringResource("sync_clock_warning", (o.clockOffset / 60_000).toString()), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { outcome = null }) { Text(stringResource("action_close")) } },
        )
    }

    checks?.let { (name, list) ->
        val lines = list.map { (if (it.ok) "✓ " else "✗ ") + stepText(res, it.step) + ": " + it.detail }
        val report = "Notio sync, $name\n" + lines.joinToString("\n")
        AlertDialog(
            onDismissRequest = { checks = null },
            title = { Text(stringResource("sync_test") + " — " + name) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    list.forEachIndexed { i, check ->
                        Text(lines[i], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = if (check.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                    Text(stringResource(if (list.all { it.ok }) "sync_test_ok" else "sync_test_failed"), style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { copy(controller, report) }) { Text(stringResource("sync_copy_report")) } },
            dismissButton = { TextButton(onClick = { checks = null }) { Text(stringResource("action_close")) } },
        )
    }

    if (showLog) {
        AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text(stringResource("sync_log")) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (log.isEmpty()) Text(stringResource("sync_log_empty"))
                    SelectionContainer { Text(log.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { copy(controller, log.joinToString("\n")) }) { Text(stringResource("sync_copy_report")) } },
            dismissButton = { TextButton(onClick = { store.clearLog() }) { Text(stringResource("sync_log_clear")) } },
        )
    }

    // A question from another device: pairing or a sync that changes or sends this device's data.
    prompt?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(when (p) {
                    is SyncPrompt.Pair -> stringResource("sync_prompt_pair", p.peerName)
                    is SyncPrompt.Request -> stringResource("sync_prompt_sync", p.peerName)
                })
            },
            text = {
                if (p is SyncPrompt.Request) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(when (p.direction) {
                            SyncDirection.SEND -> "sync_prompt_send"
                            SyncDirection.RECEIVE -> "sync_prompt_receive"
                            SyncDirection.BOTH -> "sync_prompt_both"
                        }))
                        Text(stringResource("sync_mode_label") + " " + stringResource(modeTitle(p.mode)))
                        Text(
                            stringResource("sync_folders_label") + " " + (p.folderNames?.joinToString(", ") ?: stringResource("sync_all_folders")) +
                                (if (p.reminders) "" else ", " + stringResource("sync_without_reminders")),
                        )
                        if (p.mode == ImportMode.REPLACE && p.direction != SyncDirection.SEND) {
                            // Of everything, or of the folders named above only (D325).
                            Text(stringResource(if (p.folderNames == null) "sync_replace_warning" else "sync_replace_folders_hint"), color = MaterialTheme.colorScheme.error)
                        }
                        if (kotlin.math.abs(p.clockOffset) >= SyncEngine.CLOCK_TOLERANCE) {
                            Text(stringResource("sync_clock_warning", (p.clockOffset / 60_000).toString()), color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    Text(stringResource("sync_prompt_pair_hint"))
                }
            },
            confirmButton = { TextButton(onClick = { p.answer.complete(true) }) { Text(stringResource("sync_allow")) } },
            dismissButton = { TextButton(onClick = { p.answer.complete(false) }) { Text(stringResource("sync_deny")) } },
        )
    }
}

/** Client role of pairing: a device found on the network (or an address typed), then the code shown there. */
@Composable
private fun FindDeviceDialog(nearby: List<FoundDevice>, onRecheck: () -> Unit, onDismiss: () -> Unit, onPair: (host: String, port: Int, code: String) -> Unit) {
    var target by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var targetName by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("sync_find")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val t = target
                if (t == null) {
                    // Every Notio device seen, the ones waiting with a code first (D229); one not waiting answers "not waiting".
                    val devices = nearby.sortedByDescending { it.waitingToPair }
                    if (devices.none { it.waitingToPair }) {
                        Text(stringResource("sync_searching_all"))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    OutlinedButton(onClick = onRecheck) { Text(stringResource("sync_recheck")) }
                    devices.forEach { d ->
                        OutlinedButton(onClick = { target = d.host to d.port; targetName = d.name }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                // The row shows a name and an address, so it keeps its words (D123): its texts are drawn
                                // past the button, which would else draw the icon of "waits" in place of the whole row.
                                androidx.compose.material3.Text(d.name + "  (" + d.host + ":" + d.port + ")")
                                androidx.compose.material3.Text(
                                    if (d.waitingToPair) stringResource("sync_found_waiting")
                                    else stringResource("sync_found_not_waiting", stringResource("sync_wait")),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (d.waitingToPair) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Text(stringResource("sync_manual_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        address, { address = it.trim() }, singleLine = true,
                        label = { Text(stringResource("sync_address")) },
                        placeholder = { Text("192.168.1.20:47821") },
                    )
                    TextButton(onClick = {
                        val host = address.substringBefore(':')
                        val port = address.substringAfter(':', "").toIntOrNull() ?: SyncEngine.DEFAULT_PORT
                        target = host to port; targetName = host
                    }, enabled = address.isNotBlank()) { Text(stringResource("sync_use_address")) }
                } else {
                    Text(stringResource("sync_enter_code", targetName))
                    OutlinedTextField(
                        code, { code = it.uppercase().take(16) }, singleLine = true,
                        label = { Text(stringResource("sync_code")) },
                        placeholder = { Text("XXXX-XXXX-XXXX") },
                    )
                }
            }
        },
        confirmButton = {
            val t = target
            if (t != null) TextButton(
                onClick = { onPair(t.first, t.second, code) },
                enabled = com.notio.shared.sync.SyncCrypto.normalizeCode(code).length == com.notio.shared.sync.SyncCrypto.CODE_LENGTH,
            ) { Text(stringResource("sync_connect")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

/** Sync…: direction, mode (as in an import) and the folders; the choices of last time come back. */
/**
 * The address of a paired device, typed by hand (Android D280): IP (or a name) and port, as the other device shows them at the top of
 * its Sync page. The pairing key is not touched: a device at that address without the key is refused.
 */
@Composable
private fun AddressDialog(pairing: Pairing, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var host by remember { mutableStateOf(pairing.lastHost.orEmpty()) }
    var port by remember { mutableStateOf((pairing.lastPort.takeIf { it > 0 } ?: SyncEngine.DEFAULT_PORT).toString()) }
    val goodHost = com.notio.shared.sync.SyncAddress.host(host)
    val goodPort = com.notio.shared.sync.SyncAddress.port(port)
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("sync_address_title", pairing.peerName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    host, { host = it.take(253) }, singleLine = true,
                    label = { Text(stringResource("sync_address_host")) },
                    isError = host.isNotBlank() && goodHost == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                    label = { Text(stringResource("sync_address_port")) },
                    isError = goodPort == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (host.isNotBlank() && (goodHost == null || goodPort == null)) {
                    Text(stringResource("sync_address_invalid"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource("sync_address_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (goodHost != null && goodPort != null) onSave(goodHost, goodPort) }, enabled = goodHost != null && goodPort != null) {
                Text(stringResource("action_save"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

@Composable
private fun SyncDialog(
    controller: NotioController,
    pairing: Pairing,
    onDismiss: () -> Unit,
    onRefreshFolders: () -> Unit,
    onStart: (SyncDirection, ImportMode, Set<String>?, Boolean) -> Unit,
) {
    val store = controller.syncStore
    val live by store.pairings.collectAsState()
    val current = live.firstOrNull { it.id == pairing.id } ?: pairing
    var direction by remember { mutableStateOf(pairing.direction) }
    var mode by remember { mutableStateOf(runCatching { ImportMode.valueOf(pairing.mode) }.getOrDefault(ImportMode.MERGE)) }
    var all by remember { mutableStateOf(pairing.allFolders) }
    var chosen by remember { mutableStateOf(pairing.folderIds.toSet()) }
    var withReminders by remember { mutableStateOf(pairing.reminders) }
    val local = controller.state.collectAsState().value.folders.map { FolderInfo(it.folder.id, it.folder.name) }
    // A folder is chosen by its name (D324): the same folder has another id on the other device.
    val known = local + current.peerFolders
    val folders = SyncFolders.choices(local, current.peerFolders)
    val replaceAllowed = SyncEngine.allowed(direction, ImportMode.REPLACE, if (all) null else chosen.toList())
    // A replacement that the chosen direction does not allow: chosen here, it goes back to a merge; set beforehand
    // for the connection (D316), it is not changed behind the person's back — Start waits until it is allowed.
    if (mode == ImportMode.REPLACE && !replaceAllowed && pairing.askMode) mode = ImportMode.MERGE
    val modeAllowed = mode != ImportMode.REPLACE || replaceAllowed

    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("sync_with", pairing.peerName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // What is set beforehand for this connection is told in a line, what is asked is chosen here (D316).
                if (pairing.askDirection) {
                    Text(stringResource("sync_direction"), style = MaterialTheme.typography.titleSmall)
                    SyncDirection.values().forEach { d -> Choice(directionText(d, pairing.peerName), direction == d) { direction = d } }
                } else {
                    Text(stringResource("sync_direction") + ": " + directionText(direction, pairing.peerName), style = MaterialTheme.typography.bodyMedium)
                }
                if (pairing.askMode) {
                    Text(stringResource("sync_mode"), style = MaterialTheme.typography.titleSmall)
                    listOf(ImportMode.MERGE, ImportMode.NEW_IDS, ImportMode.REPLACE).forEach { m ->
                        Choice(stringResource(modeTitle(m)), mode == m, enabled = m != ImportMode.REPLACE || replaceAllowed) { mode = m }
                    }
                } else {
                    Text(stringResource("sync_mode") + ": " + stringResource(modeTitle(mode)), style = MaterialTheme.typography.bodyMedium)
                }
                // A replacement of chosen folders (D325) says what it does to them, not to the whole device.
                Text(stringResource(if (mode == ImportMode.REPLACE && !all) "sync_replace_folders_hint" else modeHint(mode)), style = MaterialTheme.typography.bodySmall, color = if (mode == ImportMode.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (!replaceAllowed) Text(stringResource("sync_replace_rule"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pairing.askFolders) {
                    Text(stringResource("sync_folders"), style = MaterialTheme.typography.titleSmall)
                    Check(stringResource("sync_all_folders"), all) { all = it }
                    if (!all) {
                        folders.forEach { f -> Check(f.name, SyncFolders.isChosen(chosen, f, known)) { on -> chosen = SyncFolders.toggled(chosen, f, on, known) } }
                        TextButton(onClick = onRefreshFolders) { Text(stringResource("sync_refresh_folders", pairing.peerName)) }
                    }
                    // The reminders travel with their notes or stay where they are, all of them at once (D319).
                    Check(stringResource("reminders_title"), withReminders) { withReminders = it }
                } else {
                    Text(
                        stringResource("sync_folders") + ": " + (if (all) stringResource("sync_all_folders") else stringResource("sync_preset_folders_chosen", SyncFolders.count(chosen, known))) +
                            (if (withReminders) "" else ", " + stringResource("sync_without_reminders")),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStart(direction, mode, if (all) null else chosen, withReminders) }, enabled = (all || chosen.isNotEmpty()) && modeAllowed) { Text(stringResource("sync_start")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

/**
 * The port this device listens on (D135, Android D357): by itself — the usual one, or any free one when it is taken — or one typed by the
 * person, for a rule of a firewall or a router. A typed port is tried at once and said to be free or taken; a taken one can
 * still be kept (the program that has it may be closed later), and the page says it while it is so.
 */
@Composable
private fun PortDialog(current: Int, isFree: (Int) -> Boolean, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var auto by remember { mutableStateOf(current == 0) }
    var port by remember { mutableStateOf((current.takeIf { it > 0 } ?: SyncEngine.DEFAULT_PORT).toString()) }
    val good = com.notio.shared.sync.SyncAddress.listenPort(port)
    // Opening a port for the check is not for the UI thread.
    val free by produceState<Boolean?>(null, good, auto) {
        value = null
        if (!auto && good != null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { isFree(good) }
    }
    com.notio.desktop.EditorDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("sync_port_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(stringResource("sync_port_automatic"), selected = auto) { auto = true }
                Choice(stringResource("sync_port_own"), selected = !auto) { auto = false }
                if (!auto) {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit).take(5) }, singleLine = true,
                        label = { Text(stringResource("sync_address_port")) },
                        isError = good == null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when {
                        good == null -> Text(stringResource("sync_port_range"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        free == true -> Text(stringResource("sync_port_free", good.toString()), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        free == false -> Text(stringResource("sync_port_taken", good.toString()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource("sync_port_hint", SyncEngine.DEFAULT_PORT.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (auto) onSave(0) else good?.let(onSave) }, enabled = auto || good != null) { Text(stringResource("action_save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun modeTitle(mode: ImportMode) = when (mode) {
    ImportMode.MERGE -> "sync_mode_merge"
    ImportMode.NEW_IDS -> "sync_mode_copies"
    ImportMode.REPLACE -> "sync_mode_replace"
}

private fun modeHint(mode: ImportMode) = when (mode) {
    ImportMode.MERGE -> "sync_mode_merge_hint"
    ImportMode.NEW_IDS -> "sync_mode_copies_hint"
    ImportMode.REPLACE -> "sync_mode_replace_hint"
}

private fun statsText(res: AndroidRes, s: List<Int>): String =
    res.str("sync_stats", s.getOrElse(0) { 0 } + s.getOrElse(1) { 0 }, s.getOrElse(2) { 0 }, s.getOrElse(3) { 0 }, s.getOrElse(4) { 0 }, s.getOrElse(5) { 0 })

private fun stepText(res: AndroidRes, step: String): String = res.str(
    when (step) {
        "network" -> "sync_step_network"
        "listening" -> "sync_step_listening"
        "find" -> "sync_step_find"
        "connect" -> "sync_step_connect"
        "handshake" -> "sync_step_handshake"
        "clock" -> "sync_step_clock"
        "reply" -> "sync_step_reply"
        else -> "sync_step_pairing"
    },
)

private fun reasonText(res: AndroidRes, e: Throwable): String {
    val reason = (e as? SyncException)?.reason
    val id = when (reason) {
        SyncEngine.NOT_FOUND -> "sync_err_not_found"
        com.notio.shared.sync.SyncReasons.NOT_WAITING -> "sync_err_not_waiting"
        com.notio.shared.sync.SyncReasons.UNKNOWN_PAIRING -> "sync_err_unknown_pairing"
        com.notio.shared.sync.SyncReasons.VERSION -> "sync_err_version"
        com.notio.shared.sync.SyncReasons.WRONG_CODE -> "sync_err_wrong_code"
        com.notio.shared.sync.SyncReasons.DENIED -> "sync_err_denied"
        com.notio.shared.sync.SyncReasons.NO_SPACE -> "sync_err_no_space"
        else -> null
    }
    return id?.let { res.str(it) } ?: SyncEngine.describe(e)
}

private fun copy(controller: NotioController, text: String) {
    copyToClipboard(text)
    controller.say(controller.res.str("sync_copied"))
}

/** Settings -> Sync as its own page (the phone shows the same options inside Settings). */
@Composable
fun SyncScreen(controller: NotioController) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val side = ((maxWidth - 1000.dp) / 2).coerceAtLeast(0.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp + side, end = 16.dp + side, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { SyncOptions(controller) }
    }
}

/**
 * What a sync of this connection does without asking (D316): the direction, the way and the folders — each asked every time
 * or set here once. The buttons show a value, so they stay words (the rule of icons, D307), and their menus are lists of
 * values, without icons (D314): the Material button and menu are used as they are.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetRow(p: Pairing, enabled: Boolean, change: ((Pairing) -> Pairing) -> Unit, onChooseFolders: () -> Unit) {
    val ask = stringResource("sync_preset_ask")
    val mode = SyncPresets.modeOf(p)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PresetButton(stringResource("sync_direction") + ": " + (if (p.askDirection) ask else directionText(p.direction, p.peerName)), enabled) { close ->
            PresetItem(ask, p.askDirection) { change { SyncPresets.withDirection(it, null) }; close() }
            SyncDirection.values().forEach { d ->
                PresetItem(directionText(d, p.peerName), !p.askDirection && p.direction == d) { change { SyncPresets.withDirection(it, d) }; close() }
            }
        }
        PresetButton(stringResource("sync_mode") + ": " + (if (p.askMode) ask else stringResource(modeTitle(mode))), enabled) { close ->
            PresetItem(ask, p.askMode) { change { SyncPresets.withMode(it, null) }; close() }
            listOf(ImportMode.MERGE, ImportMode.NEW_IDS, ImportMode.REPLACE).forEach { m ->
                PresetItem(stringResource(modeTitle(m)), !p.askMode && mode == m, enabled = m != ImportMode.REPLACE || SyncPresets.replaceAllowed(p)) {
                    change { SyncPresets.withMode(it, m) }; close()
                }
            }
        }
        val foldersText = when {
            p.askFolders -> ask
            p.allFolders -> stringResource("sync_all_folders")
            else -> stringResource("sync_preset_folders_chosen", SyncFolders.count(p.folderIds, p.peerFolders))
        } + (if (p.askFolders || p.reminders) "" else ", " + stringResource("sync_without_reminders"))
        PresetButton(stringResource("sync_folders") + ": " + foldersText, enabled) { close ->
            PresetItem(ask, p.askFolders) { change { SyncPresets.withFolders(it, true, null) }; close() }
            PresetItem(stringResource("sync_all_folders"), !p.askFolders && p.allFolders) { change { SyncPresets.withFolders(it, false, null) }; close() }
            PresetItem(stringResource("sync_preset_choose_folders"), !p.askFolders && !p.allFolders) { close(); onChooseFolders() }
            // With or without the reminders (D319): set with the folders.
            if (!p.askFolders) PresetItem(stringResource("reminders_title"), p.reminders) { change { SyncPresets.withReminders(it, !p.reminders) }; close() }
        }
    }
}

@Composable
private fun directionText(direction: SyncDirection, peerName: String): String = when (direction) {
    SyncDirection.SEND -> stringResource("sync_dir_send", peerName)
    SyncDirection.RECEIVE -> stringResource("sync_dir_receive", peerName)
    SyncDirection.BOTH -> stringResource("sync_dir_both")
}

@Composable
private fun PresetButton(label: String, enabled: Boolean, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.OutlinedButton(
            onClick = { open = true }, enabled = enabled,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { androidx.compose.material3.Text(label, style = MaterialTheme.typography.labelLarge) }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) { items { open = false } }
    }
}

@Composable
private fun PresetItem(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { androidx.compose.material3.Text(label) },
        onClick = onClick,
        enabled = enabled,
        trailingIcon = {
            if (selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = stringResource("cd_selected"))
        },
    )
}

/**
 * The folders a connection always syncs (D316): the ones of this device and the ones the other device told last time, a name
 * once (D324, [known] is all of them with their ids).
 */
@Composable
private fun PresetFoldersDialog(pairing: Pairing, folders: List<FolderInfo>, known: List<FolderInfo>, onRefresh: () -> Unit, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(if (pairing.askFolders || pairing.allFolders) emptySet() else pairing.folderIds.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource("sync_folders") + ": " + pairing.peerName) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                folders.forEach { f -> Check(f.name, SyncFolders.isChosen(chosen, f, known)) { on -> chosen = SyncFolders.toggled(chosen, f, on, known) } }
                TextButton(onClick = onRefresh) { Text(stringResource("sync_refresh_folders", pairing.peerName)) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(chosen) }, enabled = chosen.isNotEmpty()) { Text(stringResource("action_save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource("action_cancel")) } },
    )
}
