package com.actioncut.feature.editor.ui.scorecard

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Player register: list (tap a player to use it), add new (name, BAT/BOWL, country, photo), delete. */
@Composable
fun PlayerLibraryDialog(
    library: PlayerLibrary,
    onDismiss: () -> Unit,
    onPick: (Player) -> Unit,
) {
    val context = LocalContext.current
    var players by remember { mutableStateOf(library.all()) }
    var filter by remember { mutableStateOf("all") }
    var name by remember { mutableStateOf("") }
    var country by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("bat") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pickedUri = uri
    }
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = if (navInset > 0.dp) navInset else 48.dp
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableChip(label = "ALL", selected = filter == "all", onClick = { filter = "all" })
                    SelectableChip(label = "BAT", selected = filter == "bat", onClick = { filter = "bat" })
                    SelectableChip(label = "BOWL", selected = filter == "bowl", onClick = { filter = "bowl" })
                }
                val shown = players.filter { filter == "all" || it.role == filter }
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.id }) { pl ->
                        val thumb = remember(pl.id) { library.photo(pl, 4) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pl) }
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
                                Text("${pl.role.uppercase()}  ${pl.country}", color = Color(0xFFBBBBBB))
                            }
                            TextButton(onClick = {
                                library.delete(pl.id)
                                players = library.all()
                            }) { Text("Delete") }
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (English, as on the scoreboard)") },
                    singleLine = true,
                    textStyle = white,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = country,
                    onValueChange = { country = it },
                    label = { Text("Country") },
                    singleLine = true,
                    textStyle = white,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableChip(label = "BAT", selected = role == "bat", onClick = { role = "bat" })
                    SelectableChip(label = "BOWL", selected = role == "bowl", onClick = { role = "bowl" })
                    TextButton(onClick = { launcher.launch("image/*") }) {
                        Text(if (pickedUri == null) "Choose photo" else "Photo chosen")
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        enabled = name.isNotBlank() && pickedUri != null,
                        onClick = {
                            val uri = pickedUri ?: return@Button
                            val added = library.add(name, role, country, uri)
                            if (added == null) {
                                Toast.makeText(context, "Could not read that photo", Toast.LENGTH_LONG).show()
                            } else {
                                players = library.all()
                                name = ""
                                country = ""
                                pickedUri = null
                            }
                        },
                    ) { Text("Save player") }
                }
            }
        }
    }
}
