package com.actioncut.feature.editor.ui.newsalert

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.io.FileOutputStream

/**
 * ஸ்கிரீன்ஷாட் 1: News Alert Templates பாட்டம் ஷீட்
 */
@Composable
fun NewsAlertTemplatesDialog(
    onDismiss: () -> Unit,
    onSelectTemplate: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121212),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Header with Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "News Alert Templates",
                        style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Template 1 Preview Card: FLASH NEWS
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF1E1E1E))
                        .clickable { onSelectTemplate() }
                        .padding(14.dp),
                ) {
                    ComposeCanvas(modifier = Modifier.fillMaxSize()) {
                        val path = ComposePath().apply {
                            moveTo(0f, 0f)
                            lineTo(size.width * 0.65f, 0f)
                            lineTo(size.width * 0.76f, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        drawPath(path, color = Color(0xFF900C18))
                    }
                    Text(
                        text = "o FLASH NEWS",
                        style = TextStyle(color = Color(0xFFFFD700), fontSize = 16.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp),
                    )
                }

                // Template 2 Action Card: NEWS BANNER CARD (மஞ்சள் பார்டர் கொண்ட கார்டு)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141416))
                        .border(1.5.dp, Color(0xFFFFC107), RoundedCornerShape(12.dp))
                        .clickable { onSelectTemplate() }
                        .padding(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF242426)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Image, contentDescription = null, tint = Color(0xFFFFC107))
                        }
                        Column {
                            Text(
                                text = "NEWS BANNER CARD",
                                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Full 16:9 · Main Track-ல் Add ஆகும்",
                                style = TextStyle(color = Color(0xFFFFC107), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ஸ்கிரீன்ஷாட் 2: Flash News Text உள்ளீட்டு டயலாக்
 */
@Composable
fun FlashNewsTextDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            initialText.ifBlank {
                "அன்பு மற்றும் இரக்கம் மற்றும் பிற சமயத்தினர் ஆவர் இவர்கள் பெரும்பாலும் தங்கள் விரைவான முடிவுகளையும் பல பவுண்டுகள்.\nஎப்ள மற்றும் பிற சமயத்தினர் ஆவர் இவர்கள் பெரும்பாலும் தங்கள் விரைவான"
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF121212),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Flash News Text",
                        style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                // Subtitle Instruction (படத்தில் உள்ள அதே தமிழ் உரை)
                Text(
                    text = "Text-ஐ இங்கே Paste செய்யவும். ஒவ்வொரு (.) புள்ளிக்குப் பிறகும் அடுத்த வரியில், வேறு நிறத்தில் வரும். KF +/- மூலம் ஒவ்வொரு வாக்கியமாக வரவைக்கலாம்.",
                    style = TextStyle(color = Color(0xFFB0B0B0), fontSize = 12.5.sp, lineHeight = 18.sp),
                )

                // Big Text Area
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp, lineHeight = 22.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E20)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF00E5FF),
                        unfocusedBorderColor = Color(0xFF333333),
                        cursorColor = Color(0xFF00E5FF),
                    ),
                )

                // Buttons: Cancel & Apply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262626)),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Text("Cancel", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { onApply(text) },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Apply", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * ஸ்கிரீன்ஷாட் 3: Fty Video Editor-ல் உள்ளது போல இடதுபுறம் சாய்வான சிகப்பு நிற Flash News Banner
 * மற்றும் மஞ்சள்/வெள்ளை நிற வாக்கியங்களை உருவாக்கும் Renderer
 */
object NewsBannerRenderer {

    fun createBannerBitmap(context: Context, rawText: String): String {
        val width = 1920
        val height = 1080
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bannerWidth = width * 0.52f
        val slantOffset = width * 0.08f

        // 1. இடதுபுற சாய்வான சிகப்பு நிற Polygon Banner
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(bannerWidth, 0f)
            lineTo(bannerWidth - slantOffset, height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }

        val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, bannerWidth, height.toFloat(),
                AndroidColor.parseColor("#8E0614"),
                AndroidColor.parseColor("#4A0208"),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawPath(path, bannerPaint)

        // சாய்வான கோட்டின் மீது மெல்லிய பார்டர்
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = AndroidColor.parseColor("#33FFFFFF")
        }
        canvas.drawLine(bannerWidth, 0f, bannerWidth - slantOffset, height.toFloat(), borderPaint)

        // 2. வாக்கியங்களை புள்ளி (.) அடிப்படையில் பிரித்தல்
        val sentences = rawText.split(".")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = AndroidColor.parseColor("#FFE600")
        }

        val startX = 60f
        var startY = 160f
        val maxTextWidth = bannerWidth - slantOffset - 80f
        val lineHeight = 48f

        sentences.forEachIndexed { index, sentence ->
            // Fty வடிவில்: முதல் வாக்கியம் மஞ்சள் (#FFE600), அடுத்த வாக்கியம் வெள்ளை (#FFFFFF)
            val isYellow = index % 2 == 0
            textPaint.color = if (isYellow) AndroidColor.parseColor("#FFE600") else AndroidColor.WHITE
            bulletPaint.color = if (isYellow) AndroidColor.parseColor("#FFE600") else AndroidColor.WHITE

            // புல்லட் வட்டம் 'o'
            canvas.drawCircle(startX, startY - 10f, 9f, bulletPaint)

            // நீண்ட வரிகளை மடித்து (Wrap) எழுதுதல்
            val words = sentence.split(" ")
            var line = ""
            words.forEach { word ->
                val testLine = if (line.isEmpty()) word else "$line $word"
                if (textPaint.measureText(testLine) > maxTextWidth) {
                    canvas.drawText(line, startX + 30f, startY, textPaint)
                    startY += lineHeight
                    line = word
                } else {
                    line = testLine
                }
            }
            if (line.isNotEmpty()) {
                canvas.drawText(line, startX + 30f, startY, textPaint)
                startY += lineHeight + 28f // வாக்கியங்களுக்கு இடையே உள்ள இடைவெளி
            }
        }

        // Cache-ல் சேமித்து URI உருவாக்குதல்
        val file = File(context.cacheDir, "news_banner_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Uri.fromFile(file).toString()
    }
}
