package com.maheshgkraj.portalready.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maheshgkraj.portalready.engine.ExactImageProcessor
import com.maheshgkraj.portalready.engine.ProcessedResult
import com.maheshgkraj.portalready.model.DocType
import com.maheshgkraj.portalready.model.PresetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalHomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedPreset by remember { mutableStateOf(PresetCatalog.allPresets.first()) }
    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var result by remember { mutableStateOf<ProcessedResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var enableWhiteBg by remember { mutableStateOf(true) }
    var enableSigClean by remember { mutableStateOf(true) }
    var candidateName by remember { mutableStateOf("") }
    var photoDate by remember { mutableStateOf("") }

    fun reprocess() {
        val src = rawBitmap ?: return
        scope.launch {
            isProcessing = true
            val processed = withContext(Dispatchers.Default) {
                var working = src
                if (selectedPreset.docType == DocType.PHOTO && enableWhiteBg) {
                    working = runCatching {
                        ExactImageProcessor.applyStudioWhiteBackground(working)
                    }.getOrDefault(working)
                }
                if (selectedPreset.docType == DocType.SIGNATURE && enableSigClean) {
                    working = ExactImageProcessor.cleanSignature(working)
                }
                working = ExactImageProcessor.centerCropAndScale(
                    working,
                    selectedPreset.widthPx,
                    selectedPreset.heightPx
                )
                if (selectedPreset.supportsDateStamp) {
                    working = ExactImageProcessor.stampNameAndDate(working, candidateName, photoDate)
                }
                ExactImageProcessor.compressToExactRange(
                    working,
                    selectedPreset.minKb,
                    selectedPreset.maxKb
                )
            }
            result = processed
            isProcessing = false
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
            rawBitmap = bmp
            reprocess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PortalReady", fontWeight = FontWeight.Bold)
                        Text(
                            "100% Offline • Exact KB & Exam Specs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = selectedPreset.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = selectedPreset.subtitle,
                            style = MaterialTheme.typography.bodySmall
                        )

                        Spacer(Modifier.height(12.dp))

                        if (isProcessing) {
                            CircularProgressIndicator()
                        } else if (result != null) {
                            val res = result!!
                            Image(
                                bitmap = res.previewBitmap.asImageBitmap(),
                                contentDescription = "Processed Preview",
                                modifier = Modifier
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color.White)
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF1B873F))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "VERIFIED: %dx%d px • %.1f KB",
                                        res.width,
                                        res.height,
                                        res.finalSizeKb
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1B873F)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (rawBitmap == null) "Select Photo" else "Change")
                            }

                            if (result != null) {
                                FilledTonalButton(
                                    onClick = {
                                        val res = result ?: return@FilledTonalButton
                                        val name = "${selectedPreset.id}_${res.finalSizeKb.toInt()}KB.jpg"
                                        ExactImageProcessor.saveToGallery(context, res.bytes, name)
                                        Toast.makeText(context, "Saved to Pictures/PortalReady/$name", Toast.LENGTH_LONG).show()
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save JPG")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        if (selectedPreset.docType == DocType.PHOTO) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("AI Studio White Background")
                                Switch(
                                    checked = enableWhiteBg,
                                    onCheckedChange = { enableWhiteBg = it; reprocess() }
                                )
                            }
                            if (selectedPreset.supportsDateStamp) {
                                OutlinedTextField(
                                    value = candidateName,
                                    onValueChange = { candidateName = it; reprocess() },
                                    label = { Text("Candidate Name (Optional)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = photoDate,
                                    onValueChange = { photoDate = it; reprocess() },
                                    label = { Text("Date of Photo (DD-MM-YYYY)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Remove Paper Shadow (Pure Black Ink)")
                                Switch(
                                    checked = enableSigClean,
                                    onCheckedChange = { enableSigClean = it; reprocess() }
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Select Exam / Portal Specification",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            items(PresetCatalog.allPresets) { preset ->
                val isSelected = preset.id == selectedPreset.id
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedPreset = preset
                            reprocess()
                        },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(preset.title, fontWeight = FontWeight.SemiBold)
                            Text(preset.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                        AssistChip(onClick = {}, label = { Text(preset.category) })
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}