package fieldlog.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fieldlog.core.BOREHOLE
import fieldlog.core.FLAGS
import fieldlog.core.HoleView
import fieldlog.core.OUTCROP
import fieldlog.core.SiteView
import fieldlog.core.Strata
import kotlinx.coroutines.launch

private val Earth = Color(0xFF5B4636)
private val Ok = Color(0xFF1A7F37)
private val Bad = Color(0xFFB42318)
private val Muted = Color(0xFF5F6B76)

@Composable
fun FieldlogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Earth,
            secondary = Color(0xFF8D6E4F),
            primaryContainer = Color(0xFFEFE6DA),
            surface = Color(0xFFFFFBF7),
            background = Color(0xFFFFFBF7),
        ),
        content = content,
    )
}

@Composable
fun App(vm: FieldViewModel, share: (String, String) -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // Owned here, not by a screen, so "Removed BH07 · Undo" outlives the hole screen it came from.
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removed: (HoleView) -> Unit = { h ->
        vm.edit { it.removeHole(h.id) }
        open = null
        scope.launch {
            if (snack.showSnackbar("Removed ${h.name.ifEmpty { "hole" }}", "Undo") == SnackbarResult.ActionPerformed) {
                vm.edit { it.restoreHole(h.id) }
            }
        }
    }
    // Reading version makes every screen recompose when the notebook changes, locally or by sync.
    @Suppress("UNUSED_VARIABLE") val v = vm.version
    val view = vm.view
    val hole = open?.let { view.hole(it) }
    if (open != null && hole != null) {
        BackHandler { open = null }
        HoleScreen(vm, view, hole, back = { open = null }, remove = { removed(hole) })
    } else {
        SiteScreen(vm, view, open = { open = it }, share = share, snack = snack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Bar(title: String, vm: FieldViewModel, navigation: @Composable () -> Unit = {}, actions: @Composable () -> Unit = {}) {
    val focus = LocalFocusManager.current
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${vm.store.device} · ${vm.status}",
                    style = MaterialTheme.typography.labelSmall, color = Muted,
                    modifier = Modifier.testTag("status"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = navigation,
        actions = {
            actions()
            Text("sync", style = MaterialTheme.typography.labelSmall, color = Muted)
            Switch(
                checked = vm.online, onCheckedChange = { focus.clearFocus(); vm.switchOnline(it) },
                modifier = Modifier.padding(horizontal = 6.dp).testTag("online"),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
fun SiteScreen(vm: FieldViewModel, view: SiteView, open: (String) -> Unit, share: (String, String) -> Unit, snack: SnackbarHostState) {
    var exporting by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            Bar(view.name.ifEmpty { "fieldlog" }, vm, actions = {
                IconButton(onClick = { exporting = true }, modifier = Modifier.testTag("export")) {
                    Icon(Icons.Default.Share, "Export for strata")
                }
                IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { open(vm.edit { it.addHole(BOREHOLE).first }) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Borehole") },
                modifier = Modifier.testTag("addHole"),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 96.dp)) {
            item {
                OutlinedTextField(
                    value = view.name, onValueChange = { s -> vm.edit { it.siteName(s) } },
                    label = { Text("Site") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("siteName"),
                )
                Spacer(Modifier.height(6.dp))
                ColumnField(vm, view)
                Spacer(Modifier.height(10.dp))
                val check = Strata.check(view)
                Text(
                    "${view.holes.size} holes · ${check.ready.size} ready for strata" +
                        if (check.problems.isNotEmpty()) " · ${check.problems.size} to fix" else "",
                    style = MaterialTheme.typography.labelLarge, color = Muted,
                    modifier = Modifier.padding(vertical = 4.dp).testTag("summary"),
                )
            }
            items(view.holes, key = { it.id }) { h -> HoleCard(view, h) { open(h.id) } }
            if (view.holes.isEmpty()) item {
                Text(
                    "No holes yet. Add a borehole, or turn on sync to get the site from the relay.",
                    color = Muted, modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
    }
    if (exporting) ExportDialog(view, onDismiss = { exporting = false }, share = share)
    if (settings) SettingsDialog(vm, onDismiss = { settings = false })
}

/** The stratigraphic column is one comma-separated field; kept as typed until it parses. */
@Composable
private fun ColumnField(vm: FieldViewModel, view: SiteView) {
    var text by remember(view.units) { mutableStateOf(view.units.joinToString(", ")) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s
            vm.edit { it.units(s.split(',').map { u -> u.trim() }.filter { u -> u.isNotEmpty() }) }
        },
        label = { Text("Column, top to basement") },
        placeholder = { Text("topsoil, clay, sand, gravel, chalk") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("units"),
    )
}

@Composable
private fun HoleCard(site: SiteView, h: HoleView, onClick: () -> Unit) {
    val problems = if (h.kind == BOREHOLE && h.intervals.isNotEmpty()) Strata.holeProblems(site, h) else emptyList()
    val duplicate = h.name.isNotEmpty() && site.holes.count { it.name == h.name && it.kind == BOREHOLE && it.intervals.isNotEmpty() } > 1 &&
        h.kind == BOREHOLE && h.intervals.isNotEmpty()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("hole:${h.name}"),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniColumn(site, h)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name.ifEmpty { "unnamed" }, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.testTag("holeid:${h.id}"))
                val where = if (h.x.isEmpty() && h.y.isEmpty()) "no location" else "(${h.x}, ${h.y})"
                val depth = h.intervals.lastOrNull()?.to?.let { " · to $it m" } ?: ""
                Text("${h.kind} · $where$depth", style = MaterialTheme.typography.bodySmall, color = Muted)
                if (h.flags.isNotEmpty()) Text(h.flags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Earth)
            }
            val (label, color) = when {
                h.kind == OUTCROP -> "outcrop" to Muted
                h.intervals.isEmpty() -> "not logged" to Muted
                duplicate -> "name clash" to Bad
                problems.isNotEmpty() -> "${problems.size} to fix" to Bad
                else -> "ready" to Ok
            }
            Text(label, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("state:${h.name}"))
        }
    }
}

private val UNIT_COLORS = listOf(0xFF8D6E4F, 0xFFC9A66B, 0xFFE4D3A8, 0xFF9BA3AB, 0xFFF3F0E6, 0xFF6B8E9E, 0xFFB5838D)

private fun unitColor(site: SiteView, unit: String): Color {
    val i = site.units.indexOf(unit)
    return if (i < 0) Color(0xFFDDDDDD) else Color(UNIT_COLORS[i % UNIT_COLORS.size])
}

/** A little drawn log: interval thicknesses to scale, coloured by unit. */
@Composable
private fun MiniColumn(site: SiteView, h: HoleView) {
    val depths = h.intervals.mapNotNull { iv -> fieldlog.core.parseNumber(iv.to)?.let { iv to it } }
    val total = depths.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Column(Modifier.width(14.dp).height(48.dp).background(Color(0xFFEFEFEF), RoundedCornerShape(3.dp))) {
        var prev = 0.0
        for ((iv, to) in depths.sortedBy { it.second }) {
            val f = ((to - prev) / total).toFloat()
            if (f > 0f) Box(Modifier.fillMaxWidth().weight(f).background(unitColor(site, iv.unit)))
            prev = to
        }
        if (prev < total || depths.isEmpty()) Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoleScreen(vm: FieldViewModel, site: SiteView, h: HoleView, back: () -> Unit, remove: () -> Unit) {
    Scaffold(
        topBar = {
            Bar(h.name.ifEmpty { "New hole" }, vm, navigation = {
                IconButton(onClick = back, modifier = Modifier.testTag("back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                IconButton(onClick = remove, modifier = Modifier.testTag("removeHole")) { Icon(Icons.Default.Delete, "Remove hole") }
            })
        },
    ) { pad ->
        Column(
            Modifier.padding(top = pad.calculateTopPadding()).padding(horizontal = 12.dp).fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = h.name, onValueChange = { s -> vm.edit { it.setHole(h.id, "name", s) } },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f).testTag("holeName"),
                )
                Spacer(Modifier.width(8.dp))
                SingleChoiceSegmentedButtonRow {
                    listOf(BOREHOLE, OUTCROP).forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = h.kind == k, onClick = { vm.edit { it.setHole(h.id, "kind", k) } },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(k, fontSize = 12.sp) }
                    }
                }
            }
            Row {
                NumberField("x (m)", h.x, Modifier.weight(1f).testTag("x")) { s -> vm.edit { it.setHole(h.id, "x", s) } }
                Spacer(Modifier.width(6.dp))
                NumberField("y (m)", h.y, Modifier.weight(1f).testTag("y")) { s -> vm.edit { it.setHole(h.id, "y", s) } }
                Spacer(Modifier.width(6.dp))
                NumberField("collar (m)", h.collar, Modifier.weight(1f).testTag("collar")) { s -> vm.edit { it.setHole(h.id, "collar", s) } }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                FLAGS.take(3).forEach { f ->
                    FilterChip(
                        selected = f in h.flags, onClick = { vm.edit { it.flag(h.id, f, f !in h.flags) } },
                        label = { Text(f, fontSize = 12.sp) }, modifier = Modifier.testTag("flag:$f"),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FLAGS.drop(3).forEach { f ->
                    FilterChip(
                        selected = f in h.flags, onClick = { vm.edit { it.flag(h.id, f, f !in h.flags) } },
                        label = { Text(f, fontSize = 12.sp) }, modifier = Modifier.testTag("flag:$f"),
                    )
                }
            }
            if (h.kind == BOREHOLE) {
                Spacer(Modifier.height(8.dp))
                Text("Log", style = MaterialTheme.typography.titleSmall)
                h.intervals.forEachIndexed { i, iv -> IntervalRow(vm, site, h, i, iv) }
                OutlinedButton(
                    onClick = {
                        val last = h.intervals.lastOrNull()
                        val next = last?.let { site.units.getOrNull(site.units.indexOf(it.unit) + 1) } ?: site.units.firstOrNull() ?: ""
                        vm.edit { it.addInterval(h.id, next, last?.to ?: "0", "") }
                    },
                    modifier = Modifier.padding(vertical = 4.dp).testTag("addInterval"),
                ) { Icon(Icons.Default.Add, null); Text("Interval") }
                val problems = Strata.holeProblems(site, h).filter { h.intervals.isNotEmpty() }
                val clash = site.holes.count { it.name == h.name && h.name.isNotEmpty() && it.kind == BOREHOLE && it.intervals.isNotEmpty() } > 1
                if (clash) Problem("another borehole is also called ${h.name}; rename one")
                problems.forEach { Problem(it) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = h.notes, onValueChange = { s -> vm.edit { it.editNotes(h.id, s) } },
                label = { Text("Notes") }, minLines = 4,
                modifier = Modifier.fillMaxWidth().testTag("notes"),
            )
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun Problem(text: String) =
    Text("• $text", color = Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 1.dp).testTag("problem"))

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        isError = value.isNotEmpty() && fieldlog.core.parseNumber(value) == null,
        modifier = modifier,
    )
}

@Composable
private fun IntervalRow(vm: FieldViewModel, site: SiteView, h: HoleView, i: Int, iv: fieldlog.core.IntervalView) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(110.dp)) {
            Row(
                Modifier.clickable { menu = true }.background(unitColor(site, iv.unit).copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 14.dp).testTag("unit:$i"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(iv.unit.ifEmpty { "unit" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                site.units.forEach { u ->
                    DropdownMenuItem(text = { Text(u) }, onClick = { menu = false; vm.edit { it.setInterval(iv.id, "unit", u) } })
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        NumberField("from", iv.from, Modifier.weight(1f).testTag("from:$i")) { s -> vm.edit { it.setInterval(iv.id, "from", s) } }
        Text("–", Modifier.padding(horizontal = 3.dp))
        NumberField("to", iv.to, Modifier.weight(1f).testTag("to:$i")) { s -> vm.edit { it.setInterval(iv.id, "to", s) } }
        IconButton(onClick = { vm.edit { it.removeInterval(h.id, iv.id) } }) { Icon(Icons.Default.Close, "Remove interval") }
    }
}

@Composable
private fun ExportDialog(view: SiteView, onDismiss: () -> Unit, share: (String, String) -> Unit) {
    val (csv, report) = remember(view) { Strata.export(view) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (csv != null) "Ready for strata" else "strata would refuse this") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${report.ready.size} boreholes ready, ${report.skipped.size} left out")
                report.skipped.forEach { Text("left out: $it", color = Muted, style = MaterialTheme.typography.bodySmall) }
                if (report.problems.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider()
                    report.problems.forEach { Problem(it.toString()) }
                }
                if (csv != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        csv.lines().take(6).joinToString("\n") + "\n…", fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        modifier = Modifier.testTag("csvPreview"),
                    )
                }
            }
        },
        confirmButton = {
            if (csv != null) TextButton(onClick = { share(csv, view.name); onDismiss() }, modifier = Modifier.testTag("shareCsv")) { Text("Share CSV") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SettingsDialog(vm: FieldViewModel, onDismiss: () -> Unit) {
    var relay by remember { mutableStateOf(vm.store.relayUrl) }
    var name by remember { mutableStateOf(vm.store.device) }
    val nameOk = name.isNotEmpty() && name.none { it == '@' || it == ',' || it.isWhitespace() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column {
                OutlinedTextField(relay, { relay = it }, label = { Text("Relay URL") }, singleLine = true, modifier = Modifier.testTag("relayUrl"))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    name, { name = it }, label = { Text("Device name") }, singleLine = true,
                    enabled = vm.store.canRename, isError = !nameOk, modifier = Modifier.testTag("deviceName"),
                )
                Text(
                    if (vm.store.canRename) "Part of every change this device makes; fixed after the first edit."
                    else "Fixed: this device has already made changes as ${vm.store.device}.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = nameOk, onClick = {
                vm.setRelay(relay)
                if (vm.store.canRename && name != vm.store.device) vm.rename(name)
                onDismiss()
            }, modifier = Modifier.testTag("saveSettings")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
