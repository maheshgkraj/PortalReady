package com.maheshgkraj.portalready.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import com.maheshgkraj.portalready.engine.MergedPdfResult
import com.maheshgkraj.portalready.engine.PdfSourcePage
import com.maheshgkraj.portalready.engine.SizeCappedPdfMerger
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfMergerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pages by remember { mutableStateOf<List<PdfSourcePage>>(emptyList()) }
    var selectedCapKb by remember { mutableIntStateOf(2048) } // Default 2 MB
    var customCapText by remember { mutableStateOf("") }
    var enhanceText by remember { mutableStateOf(true) }
    var isBuilding by remember { mutableStateOf(false) }
    var mergedResult by remember { mutableStateOf<MergedPdfResult?>(null) }

    val capPresets = listOf(
        "500 KB" to 500,
        "1 MB" to 1024,
        "2 MB" to 2048,
        "5 MB" to 5120
    )

    val multiPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isBuilding = true
                val extracted = SizeCappedPdfMerger.extractPagesFromUris(context, uris)
                pages = pages + extracted
                mergedResult = null
                isBuilding = false
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Target Size Cap Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Target Maximum PDF Size",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Merge marks cards, certificates & PDFs strictly under portal limit",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(10.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        capPresets.forEach { (label, kb) ->
                            FilterChip(
                                selected = selectedCapKb == kb && customCapText.isBlank(),
                                onClick = {
                                    selectedCapKb = kb
                                    customCapText = ""
                                    mergedResult = null
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = customCapText,
                        onValueChange = { input ->
                            val digits = input.filter { it.isDigit() }
                            customCapText = digits
                            digits.toIntOrNull()?.let { if (it >= 50) selectedCapKb = it }
                            mergedResult = null
                        },
                        label = { Text("Or Custom Max Size (in KB, e.g. 1500)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sharpen Marks Card Text", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = enhanceText,
                            onCheckedChange = { enhanceText = it; mergedResult = null }
                        )
                    }
                }
            }
        }

        // 2. Add Files & Merge Action Buttons
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { multiPicker.launch(arrayOf("image/*", "application/pdf")) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add Images / PDFs")
                }

                if (pages.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                isBuilding = true
                                mergedResult = SizeCappedPdfMerger.buildCappedPdf(
                                    pages = pages,
                                    maxTargetKb = selectedCapKb,
                                    enhanceReadability = enhanceText
                                )
                                isBuilding = false
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Compress, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Merge (${pages.size})")
                    }
                }
            }
        }

        // 3. Verified Output Card
        if (isBuilding) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        } else if (mergedResult != null) {
            item {
                val res = mergedResult!!
                val sizeLabel = if (res.finalSizeKb >= 1024f) {
                    String.format(Locale.US, "%.2f MB", res.finalSizeKb / 1024f)
                } else {
                    String.format(Locale.US, "%.1f KB", res.finalSizeKb)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (res.withinCap) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (res.withinCap) Icons.Default.CheckCircle else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (res.withinCap) Color(0xFF1B873F) else Color(0xFFE65100)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "MERGED PDF: ${res.pageCount} Pages • $sizeLabel",
                                fontWeight = FontWeight.Bold,
                                color = if (res.withinCap) Color(0xFF1B873F) else Color(0xFFE65100)
                            )
                        }

                        Spacer(Modifier.height(10.dp))

                        Button(
                            onClick = {
                                val fileName = "PortalReady_Merged_${res.pageCount}p_${res.finalSizeKb.toInt()}KB.pdf"
                                SizeCappedPdfMerger.savePdfToDocuments(context, res.bytes, fileName)
                                Toast.makeText(
                                    context,
                                    "Saved to Documents/PortalReady/$fileName",
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Save PDF to Documents")
                        }
                    }
                }
            }
        }

        // 4. Page Queue List (Reorder / Remove)
        if (pages.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Page Order (${pages.size} Pages)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = { pages = emptyList(); mergedResult = null }) {
                        Text("Clear All")
                    }
                }
            }

            itemsIndexed(pages, key = { _, item -> item.id }) { index, page ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = page.bitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp, 64.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Page ${index + 1}", fontWeight = FontWeight.Bold)
                            Text(page.label, style = MaterialTheme.typography.bodySmall)
                        }

                        // Move Up
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                val mutable = pages.toMutableList()
                                val item = mutable.removeAt(index)
                                mutable.add(index - 1, item)
                                pages = mutable
                                mergedResult = null
                            }
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                        }

                        // Move Down
                        IconButton(
                            enabled = index < pages.lastIndex,
                            onClick = {
                                val mutable = pages.toMutableList()
                                val item = mutable.removeAt(index)
                                mutable.add(index + 1, item)
                                pages = mutable
                                mergedResult = null
                            }
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                        }

                        // Delete
                        IconButton(
                            onClick = {
                                pages = pages.filterIndexed { i, _ -> i != index }
                                mergedResult = null
                            }
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}