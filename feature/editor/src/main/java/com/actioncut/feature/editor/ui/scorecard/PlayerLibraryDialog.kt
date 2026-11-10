package com.actioncut.feature.editor.ui.scorecard

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.actioncut.core.designsystem.component.SelectableChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val KnownCountries = listOf("IND", "AUS", "ENG", "PAK", "SA", "NZ", "SL")

/**
 * "PLAYER" - the saved-player register, same controls as the web editor's Player sheet:
 * BAT/BOWL tabs, name search, country filter chips (ALL + countries + OTHERS), Import / Export of
 * the players backup file and Delete All. Players saved without a country count as IND (as on
 * the web). If [onPick] is given, tapping a player selects it.
 */
@Composable
fun PlayerLibraryDialog(
    library: PlayerLibrary,
    onDismiss: () -> Unit,
    onPick: ((Player) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var players by remember { mutableStateOf(library.all()) }
    var roleFilter by remember { mutableStateOf("bat") }
    var countryFilter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showRegister by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val count = withContext(Dispatchers.IO) { runCatching { library.importBackup(uri) }.getOrNull() }
                busy = false
                if (count == null) {
                    Toast.makeText(context, "Backup file could not be read", Toast.LENGTH_LONG).show()
                } else {
                    players = library.all()
                    Toast.makeText(context, "$count players imported", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument()) { uri ->
        if (uri != null) {
            busy = true
            scope.launch {
                val count = withContext(Dispatchers.IO) { runCatching { library.exportBackup(uri) }.getOrNull() }
                busy = false
                Toast.makeText(
                    context,
                    if (count == null) "Backup export failed" else "Backup saved ($count players)",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = maxOf(navInset, 56.dp)
    val white = TextStyle(color = Color.White)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(12.dp)
                    .padding(bottom = bottomSpace),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Players", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SelectableChip(label = "BAT", selected = roleFilter == "bat", onClick = { roleFilter = "bat" })
                    SelectableChip(label = "BOWL", selected = roleFilter == "bowl", onClick = { roleFilter = "bowl" })
                    TextButton(onClick = { showRegister = true }) { Text("+ New") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(enabled = !busy, onClick = { importLauncher.launch("*/*") }) { Text(if (busy) "Working..." else "Import") }
                    TextButton(enabled = !busy, onClick = { exportLauncher.launch("players-backup-${System.currentTimeMillis()}.json") }) { Text("Export") }
                    TextButton(enabled = players.isNotEmpty(), onClick = { confirmDeleteAll = true }) { Text("Delete All") }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search player...") },
                    singleLine = true,
                    textStyle = white,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (listOf("ALL") + PlayerCountries).forEach { code ->
                        SelectableChip(label = code, selected = countryFilter == code, onClick = { countryFilter = code })
                    }
                }
                val q = query.trim().lowercase()
                val shown = players.filter { p ->
                    if (p.role != roleFilter) return@filter false
                    val pc = p.country.ifBlank { "IND" }
                    if (countryFilter != "ALL") {
                        if (countryFilter == "OTHERS") {
                            if (pc in KnownCountries) return@filter false
                        } else if (pc != countryFilter) return@filter false
                    }
                    q.isEmpty() || p.name.lowercase().contains(q)
                }
                if (shown.isEmpty()) {
                    Text(
                        if (players.isEmpty()) "No players yet. Use + New, or Import a players backup." else "No players match this filter.",
                        color = Color(0xFFBBBBBB),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(shown, key = { it.id }) { pl ->
                            val thumb = remember(pl.id) { library.photo(pl, 4) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = onPick != null) { onPick?.invoke(pl) }
                                    .padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (thumb != null) {
                                    Image(
                                        bitmap = thumb.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                                    )
                                } else {
                                    Spacer(modifier = Modifier.size(48.dp))
                                }
                                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(pl.name, color = Color.White)
                                    Text("${pl.role.uppercase()}  ${pl.country.ifBlank { "IND" }}", color = Color(0xFFBBBBBB))
                                }
                                TextButton(onClick = {
                                    library.delete(pl.id)
                                    players = library.all()
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }

    if (showRegister) {
        PlayerRegisterDialog(
            library = library,
            onDismiss = { showRegister = false },
            onSaved = { players = library.all() },
        )
    }

    if (confirmDeleteAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Delete all players?") },
            text = { Text("All registered players and their photos will be removed. Export a backup first if you need them.") },
            confirmButton = {
                TextButton(onClick = {
                    library.deleteAll()
                    players = library.all()
                    confirmDeleteAll = false
                }) { Text("Delete All") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
        )
    }
}
