package com.actioncut.feature.editor.ui.scorecard

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/** Country codes offered by the web editor's "New Player Register" form (index.html: pr-country-row). */
val PlayerCountries = listOf("IND", "AUS", "ENG", "PAK", "SA", "NZ", "SL", "OTHERS")

/**
 * "CARD ADD" - New Player Register, same fields as the web editor (openPlayerRegisterMenu):
 * player photo, name, BAT/BOWL role and country (IND default). The new player is saved FIRST in
 * the register.
 */
@Composable
fun PlayerRegisterDialog(
    library: PlayerLibrary,
    onDismiss: () -> Unit,
    onSaved: (Player) -> Unit = {},
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("bat") }
    var country by remember { mutableStateOf("IND") }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pickedUri = uri
    }
    LaunchedEffect(pickedUri) {
        val u = pickedUri
        preview = if (u == null) null else runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            context.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
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
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("New Player Register", color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val pv = preview
                    if (pv != null) {
                        Image(
                            bitmap = pv.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        Spacer(modifier = Modifier.size(64.dp))
                    }
                    Button(onClick = { launcher.launch("image/*") }) { Text("Player Photo") }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Player Name (English, as on the scoreboard)") },
                    placeholder = { Text("Devdutt Padikkal") },
                    singleLine = true,
                    textStyle = white,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Player Role", color = Color(0xFFBBBBBB))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableChip(label = "BAT", selected = role == "bat", onClick = { role = "bat" })
                    SelectableChip(label = "BOWL", selected = role == "bowl", onClick = { role = "bowl" })
                }
                Text("Country", color = Color(0xFFBBBBBB))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerCountries.forEach { code ->
                        SelectableChip(label = code, selected = country == code, onClick = { country = code })
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
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
                                Toast.makeText(context, "Player saved", Toast.LENGTH_SHORT).show()
                                onSaved(added)
                                name = ""
                                pickedUri = null
                            }
                        },
                    ) { Text("Save Player") }
                }
            }
        }
    }
}
