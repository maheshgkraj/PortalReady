package com.maheshgkraj.portalready.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
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
import androidx.compose.material.icons.filled.*
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
import com.maheshgkraj.portalready.model.PortalPreset
import com.maheshgkraj.portalready.model.PresetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortalHomeScreen() {
    var currentTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("PortalReady", fontWeight = FontWeight.Bold)
                        Text(
                            "100% Offline • Exact KB, 300 DPI & Exam Specs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Default.Badge, contentDescription = null) },
                    label = { Text("Photo & Sign") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                    label = { Text("PDF Merger") }
                )
            }
        }
    ) { padding ->
        if (currentTab == 0) {
            PhotoAndSignatureTab(modifier = Modifier.padding(padding))
        } else {
            PdfMergerScreen(modifier = Modifier.padding(padding))
        }
    }
}

@Composable
private fun PhotoAndSignatureTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedPreset by remember { mutableStateOf(PresetCatalog.allPresets.first()) }
    var isCustomMode by remember { mutableStateOf(false) }

    // Custom spec inputs
    var customWidth by remember { mutableStateOf("300") }
    var customHeight by remember { mutableStateOf("400") }
    var customMinKb by remember { mutableStateOf("15") }
    var customMaxKb by remember { mutableStateOf("50") }
    var customShowDate by remember { mutableStateOf(false) }

    // Framing controls
    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var verticalOffset by remember { mutableFloatStateOf(0.0f) }

    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var result by remember { mutableStateOf<ProcessedResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var enableWhiteBg by remember { mutableStateOf(true) }
    var enableAutoFaceCenter by remember { mutableStateOf(true) }
    var enableSigClean by remember { mutableStateOf(true) }
    var candidateName by remember { mutableStateOf("") }
    var photoDate by remember { mutableStateOf("") }

    val activeSpec = remember(
        selectedPreset, isCustomMode, customWidth, customHeight, customMinKb, customMaxKb, customShowDate
    ) {
        if (!isCustomMode) {
            selectedPreset
        } else {
            val w = (customWidth.toIntOrNull() ?: 300).coerceIn(50, 2000)
            val h = (customHeight.toIntOrNull() ?: 400).coerceIn(50, 2000)
            val minK = (customMinKb.toIntOrNull() ?: 10).coerceIn(2, 2000)
            val maxK = (customMaxKb.toIntOrNull() ?: 50).coerceIn(minK + 1, 5000)
            PortalPreset(
                id = "custom_${w}x${h}",
                title = "Custom Specification",
                subtitle = "${w}x${h} px • $minK KB - $maxK KB • 300 DPI",
                category = "Custom",
                docType = DocType.PHOTO,
                widthPx = w,
                heightPx = h,
                minKb = minK,
                maxKb = maxK,
                requiresWhiteBg = true,
                supportsDateStamp = customShowDate
            )
        }
    }

    fun reprocess() {
        val src = rawBitmap ?: return
        scope.launch {
            isProcessing = true
            val processed = withContext(Dispatchers.Default) {
                var working = src
                var faceFound = false

                if (activeSpec.docType == DocType.PHOTO && enableAutoFaceCenter) {
                    val (centered, detected) = ExactImageProcessor.autoCenterFace(working)
                    working = centered
                    faceFound = detected
                }
                if (activeSpec.docType == DocType.PHOTO && enableWhiteBg) {
                    working = runCatching {
                        ExactImageProcessor.applyStudioWhiteBackground(working)
                    }.getOrDefault(working)
                }
                if (activeSpec.docType == DocType.SIGNATURE && enableSigClean) {
                    working = ExactImageProcessor.cleanSignature(working)
                }
                working = ExactImageProcessor.framedCropAndScale(
                    source = working,
                    targetWidth = activeSpec.widthPx,
                    targetHeight = activeSpec.heightPx,
                    zoom = zoomLevel,
                    verticalBias = verticalOffset
                )
                if (activeSpec.supportsDateStamp) {
                    working = ExactImageProcessor.stampNameAndDate(working, candidateName, photoDate)
                }
                ExactImageProcessor.compressToExactRange(
                    bitmap = working,
                    minKb = activeSpec.minKb,
                    maxKb = activeSpec.maxKb,
                    faceDetected = faceFound
                )
            }
            result = processed
            isProcessing = false
        }
    }

    fun loadUriAndProcess(uri: Uri) {
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ ->
                decoder.isMutableRequired = true
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
        }
        rawBitmap = bmp
        zoomLevel = 1.0f
        verticalOffset = 0.0f
        reprocess()
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) loadUriAndProcess(uri)
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraUri
        if (success && capturedUri != null) {
            loadUriAndProcess(capturedUri)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Live Output Card
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
                        text = activeSpec.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = activeSpec.subtitle,
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
                                    "VERIFIED: %dx%d px • %.1f KB • 300 DPI",
                                    res.width,
                                    res.height,
                                    res.finalSizeKb
                                ),
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1B873F),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (res.faceDetected && activeSpec.docType == DocType.PHOTO) {
                            Text(
                                text = "Face Biometric Auto-Centered ✅",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF1B873F)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // Camera + Gallery Action Buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val uri = ExactImageProcessor.createCameraCaptureUri(context)
                                pendingCameraUri = uri
                                cameraLauncher.launch(uri)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Camera")
                        }

                        OutlinedButton(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Gallery")
                        }
                    }

                    if (result != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    val res = result ?: return@FilledTonalButton
                                    val name = "${activeSpec.id.uppercase()}_${res.finalSizeKb.toInt()}KB.jpg"
                                    ExactImageProcessor.saveToGallery(context, res.bytes, name)
                                    Toast.makeText(context, "Saved to Pictures/PortalReady/$name", Toast.LENGTH_LONG).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Save JPG")
                            }

                            FilledTonalButton(
                                onClick = {
                                    val res = result ?: return@FilledTonalButton
                                    val name = "${activeSpec.id.uppercase()}_${res.finalSizeKb.toInt()}KB.jpg"
                                    ExactImageProcessor.shareImage(context, res.bytes, name)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }
                        }
                    }
                }
            }
        }

        // 2. AI Automation & Framing Controls
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (activeSpec.docType == DocType.PHOTO) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto Face-Centering (75% Passport)")
                                Text("Detects face & aligns head automatically", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = enableAutoFaceCenter,
                                onCheckedChange = { enableAutoFaceCenter = it; reprocess() }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
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
                        if (activeSpec.supportsDateStamp) {
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = candidateName,
                                onValueChange = { candidateName = it; reprocess() },
                                label = { Text("Candidate Name") },
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

                    if (rawBitmap != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(
                            "Fine-Tune Framing",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Zoom", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = zoomLevel,
                                onValueChange = { zoomLevel = it },
                                onValueChangeFinished = { reprocess() },
                                valueRange = 1.0f..2.2f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Position", modifier = Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = verticalOffset,
                                onValueChange = { verticalOffset = it },
                                onValueChangeFinished = { reprocess() },
                                valueRange = -1.0f..1.0f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 3. Custom Mode Toggle & Inputs
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCustomMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Custom Size & KB Mode", fontWeight = FontWeight.Bold)
                            Text("For any college, bank, or private form", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = isCustomMode,
                            onCheckedChange = { isCustomMode = it; reprocess() }
                        )
                    }

                    if (isCustomMode) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customWidth,
                                onValueChange = { customWidth = it.filter(Char::isDigit); reprocess() },
                                label = { Text("Width (px)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = customHeight,
                                onValueChange = { customHeight = it.filter(Char::isDigit); reprocess() },
                                label = { Text("Height (px)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = customMinKb,
                                onValueChange = { customMinKb = it.filter(Char::isDigit); reprocess() },
                                label = { Text("Min KB") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = customMaxKb,
                                onValueChange = { customMaxKb = it.filter(Char::isDigit); reprocess() },
                                label = { Text("Max KB") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Add Name & Date Bar")
                            Switch(
                                checked = customShowDate,
                                onCheckedChange = { customShowDate = it; reprocess() }
                            )
                        }
                    }
                }
            }
        }

        // 4. Preset Selector List
        item {
            Text(
                "Exam & Portal Presets",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }

        items(PresetCatalog.allPresets) { preset ->
            val isSelected = !isCustomMode && preset.id == selectedPreset.id
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        isCustomMode = false
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