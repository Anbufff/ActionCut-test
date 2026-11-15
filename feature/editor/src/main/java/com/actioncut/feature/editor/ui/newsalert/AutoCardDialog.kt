package com.actioncut.feature.editor.ui.newsalert

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.actioncut.core.designsystem.component.SelectableChip

@Composable
fun AutoCardDialog(
    knownPlayerNames: List<String>,
    resolvePhotoUri: (String) -> String?,
    onDismiss: () -> Unit,
    onSaveQueue: (entries: List<AutoCardEntry>, durationMs: Long) -> Unit,
    onGenerateAndAdd: (entries: List<AutoCardEntry>, durationMs: Long) -> Unit,
) {
    val context = LocalContext.current
    var rawText by remember { mutableStateOf("") }
    var durationSec by remember { mutableIntStateOf(8) }
    var pickingForIndex by remember { mutableIntStateOf(-1) }

    var useCustomBg by remember { mutableStateOf(AutoCardRenderer.isCustomBackgroundEnabled(context)) }
    var customBgVersion by remember { mutableIntStateOf(0) }
    val hasSavedCustomBg = remember(customBgVersion) { AutoCardRenderer.getCustomBackgroundFile(context) != null }
    val customBgThumb = remember(customBgVersion) { AutoCardRenderer.loadCustomBackgroundThumb(context) }

    val customPhotoMap = remember { mutableStateMapOf<Int, String>() }
    val customLayoutMap = remember { mutableStateMapOf<Int, Int>() }

    val bgPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val saved = AutoCardRenderer.saveCustomBackground(context, uri)
            if (saved) {
                useCustomBg = true
                customBgVersion++
                Toast.makeText(context, "Background saved!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Could not read background image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && pickingForIndex >= 0) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            customPhotoMap[pickingForIndex] = uri.toString()
        }
        pickingForIndex = -1
    }

    val parsedEntries = remember(rawText, knownPlayerNames) {
        AutoCardRenderer.parseKeyPoints(rawText, knownPlayerNames)
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = maxOf(navInset, 64.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121214),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .padding(bottom = bottomSpace),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto Card Studio (Offline)",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Cards-ஐ Queue-வில் சேமித்து Voiceover இடத்தில் 1-Tap-ல் சேர்க்கலாம்",
                            color = Color(0xFF9E9E9E),
                            fontSize = 11.sp,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    placeholder = {
                        Text(
                            text = "1. Rohit Sharma: தொடக்க வீரராக 0 runs-ல் out...\n2. Ruturaj Gaikwad: 13 balls சந்தித்து 5 runs...",
                            color = Color(0xFF6F6F75),
                            fontSize = 13.sp,
                        )
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 13.sp, lineHeight = 18.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1C1C20)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF323238),
                        cursorColor = Color(0xFF00E5FF),
                    ),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Card நேரம்:",
                        color = Color(0xFFBBBBBB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    listOf(5, 8, 10, 12).forEach { sec ->
                        SelectableChip(
                            label = "${sec}s",
                            selected = durationSec == sec,
                            onClick = { durationSec = sec },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "BG:",
                        color = Color(0xFFBBBBBB),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    SelectableChip(
                        label = "Auto Templates",
                        selected = !useCustomBg,
                        onClick = {
                            AutoCardRenderer.setCustomBackgroundEnabled(context, false)
                            useCustomBg = false
                        },
                    )
                    if (hasSavedCustomBg) {
                        if (customBgThumb != null) {
                            Image(
                                bitmap = customBgThumb.asImageBitmap(),
                                contentDescription = "Custom BG",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        width = if (useCustomBg) 2.dp else 1.dp,
                                        color = if (useCustomBg) Color(0xFF00E5FF) else Color(0xFF44444C),
                                        shape = RoundedCornerShape(6.dp),
                                    ),
                            )
                        }
                        SelectableChip(
                            label = "Custom BG",
                            selected = useCustomBg,
                            onClick = {
                                AutoCardRenderer.setCustomBackgroundEnabled(context, true)
                                useCustomBg = true
                            },
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF262630))
                                .clickable { bgPhotoPicker.launch(arrayOf("image/*")) }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Image,
                                contentDescription = "Change BG",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Change",
                                color = Color(0xFF00E5FF),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF3A181D))
                                .clickable {
                                    AutoCardRenderer.deleteCustomBackground(context)
                                    useCustomBg = false
                                    customBgVersion++
                                    Toast.makeText(context, "Background deleted", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete BG",
                                tint = Color(0xFFFF6B7A),
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Delete",
                                color = Color(0xFFFF6B7A),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        SelectableChip(
                            label = "+ Upload BG",
                            selected = false,
                            onClick = { bgPhotoPicker.launch(arrayOf("image/*")) },
                        )
                    }
                }

                if (parsedEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF18181C)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Gemini-யின் 1..10 Player Key Points-ஐ இங்கே Paste செய்யுங்கள்.\nPhoto தானாக CARD ADD / CRIC CDN-லிருந்து இணையும்!",
                            color = Color(0xFF888890),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                } else {
                    Text(
                        text = "கண்டறியப்பட்ட Cards: ${parsedEntries.size} (Design மாற்ற Tap செய்யலாம்)",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(parsedEntries, key = { idx, item -> "${idx}_${item.playerName}" }) { idx, entry ->
                            val pickedUri = customPhotoMap[idx]
                            val autoUri = remember(entry.playerName) { resolvePhotoUri(entry.playerName) }
                            val hasPhoto = !pickedUri.isNullOrBlank() || !autoUri.isNullOrBlank()
                            val layoutIdx = customLayoutMap[idx] ?: entry.layoutIdx

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1B1B20))
                                    .border(
                                        width = 1.dp,
                                        color = if (hasPhoto) Color(0xFF2E4D3A) else Color(0xFF4D3E22),
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${entry.playerName}",
                                        color = Color(0xFFFFE600),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )

                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF282832))
                                            .clickable {
                                                customLayoutMap[idx] = (layoutIdx + 1) % AutoCardRenderer.LAYOUT_COUNT
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            Icons.Filled.Palette,
                                            contentDescription = "Change Layout",
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            text = AutoCardRenderer.layoutName(layoutIdx),
                                            color = Color(0xFF00E5FF),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }

                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (hasPhoto) Color(0xFF173D26) else Color(0xFF422F10))
                                            .clickable {
                                                pickingForIndex = idx
                                                photoPicker.launch(arrayOf("image/*"))
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Icon(
                                            if (hasPhoto) Icons.Filled.Check else Icons.Filled.Image,
                                            contentDescription = "Pick Photo",
                                            tint = if (hasPhoto) Color(0xFF4DFF88) else Color(0xFFFFC107),
                                            modifier = Modifier.size(13.dp),
                                        )
                                        Text(
                                            text = if (pickedUri != null) "Picked" else if (hasPhoto) "Photo" else "+ Photo",
                                            color = if (hasPhoto) Color(0xFF4DFF88) else Color(0xFFFFC107),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }

                                Text(
                                    text = entry.text,
                                    color = Color(0xFFDCDCE2),
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                val buildFinalEntries: () -> List<AutoCardEntry> = {
                    parsedEntries.mapIndexed { idx, e ->
                        e.copy(
                            customPhotoUri = customPhotoMap[idx],
                            layoutIdx = customLayoutMap[idx] ?: e.layoutIdx,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(46.dp),
                    ) {
                        Text("Cancel", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    OutlinedButton(
                        enabled = parsedEntries.isNotEmpty(),
                        onClick = { onGenerateAndAdd(buildFinalEntries(), durationSec * 1_000L) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(46.dp),
                    ) {
                        Text("All", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        enabled = parsedEntries.isNotEmpty(),
                        onClick = { onSaveQueue(buildFinalEntries(), durationSec * 1_000L) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFC107),
                            disabledContainerColor = Color(0xFF4A3D12),
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                    ) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "Save ${parsedEntries.size} Cards",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
