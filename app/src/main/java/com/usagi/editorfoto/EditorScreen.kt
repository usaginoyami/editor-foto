@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.usagi.editorfoto

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private val TABS = listOf("Luz", "Cor", "HSL", "Curva", "Presets", "Cortar")

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val params by vm.params.collectAsState()
    val rendered by vm.rendered.collectAsState()
    val loading by vm.loading.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val message by vm.message.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }

    BackHandler { vm.close() }
    LaunchedEffect(message) {
        message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding()) {
        // barra superior
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { vm.close() }) { Text("Fechar") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.undo() }, enabled = canUndo) { Text("Desfazer") }
                TextButton(onClick = { vm.resetAll() }) { Text("Resetar") }
                Button(onClick = { vm.export() }, enabled = !loading) { Text("Salvar") }
            }
        }

        // pré-visualização
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(8.dp)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        showOriginal = true
                        tryAwaitRelease()
                        showOriginal = false
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            rendered?.let {
                Image(
                    bitmap = (if (showOriginal) it.original else it.edited).asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (loading) CircularProgressIndicator()
            if (showOriginal) {
                Text(
                    "ORIGINAL",
                    modifier = Modifier.align(Alignment.TopCenter).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    fontSize = 12.sp, color = Color.White
                )
            }
        }

        // abas
        ScrollableTabRow(
            selectedTabIndex = tab,
            edgePadding = 8.dp,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            TABS.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }

        Box(
            Modifier.fillMaxWidth().height(250.dp).background(MaterialTheme.colorScheme.surface)
        ) {
            when (tab) {
                0 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AdjustSlider("Exposição", params.exposure, -2f..2f, vm, { "%+.2f EV".format(it) }) { v, p -> p.copy(exposure = v) }
                    AdjustSlider("Contraste", params.contrast, -1f..1f, vm) { v, p -> p.copy(contrast = v) }
                    AdjustSlider("Realces", params.highlights, -1f..1f, vm) { v, p -> p.copy(highlights = v) }
                    AdjustSlider("Sombras", params.shadows, -1f..1f, vm) { v, p -> p.copy(shadows = v) }
                }
                1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AdjustSlider("Temperatura", params.temperature, -1f..1f, vm) { v, p -> p.copy(temperature = v) }
                    AdjustSlider("Matiz", params.tint, -1f..1f, vm) { v, p -> p.copy(tint = v) }
                    AdjustSlider("Vibração", params.vibrance, -1f..1f, vm) { v, p -> p.copy(vibrance = v) }
                    AdjustSlider("Saturação", params.saturation, -1f..1f, vm) { v, p -> p.copy(saturation = v) }
                }
                2 -> HslTab(vm, params)
                3 -> CurveTab(vm, params)
                4 -> PresetsTab(vm)
                5 -> CropTab(vm, params)
            }
        }
    }
}

// ---------- slider ----------

@Composable
fun AdjustSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    vm: EditorViewModel,
    format: (Float) -> String = { "%+d".format((it * 100).roundToInt()) },
    onChange: (Float, EditParams) -> EditParams
) {
    var started by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    vm.pushUndo()
                    vm.update { p -> onChange(0f, p) }
                })
            },
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(format(value), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = value,
            onValueChange = { v ->
                if (!started) { started = true; vm.pushUndo() }
                val snapped = if (abs(v) < (range.endInclusive - range.start) * 0.01f) 0f else v
                vm.update { p -> onChange(snapped, p) }
            },
            onValueChangeFinished = { started = false },
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary)
        )
    }
}

// ---------- HSL ----------

@Composable
fun HslTab(vm: EditorViewModel, params: EditParams) {
    var sel by remember { mutableIntStateOf(0) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HSL_COLORS.forEachIndexed { i, c ->
                Box(
                    Modifier.size(if (sel == i) 34.dp else 28.dp).clip(CircleShape)
                        .background(Color(c))
                        .then(if (sel == i) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                        .pointerInput(i) { detectTapGestures { sel = i } }
                )
            }
        }
        Text(HSL_NAMES[sel], fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        val adj = params.hsl[sel]
        fun upd(f: (HslAdj) -> HslAdj) = { p: EditParams -> p.copy(hsl = p.hsl.mapIndexed { i, a -> if (i == sel) f(a) else a }) }
        AdjustSlider("Tom", adj.hue, -1f..1f, vm) { v, p -> upd { it.copy(hue = v) }(p) }
        AdjustSlider("Saturação", adj.sat, -1f..1f, vm) { v, p -> upd { it.copy(sat = v) }(p) }
        AdjustSlider("Luminância", adj.lum, -1f..1f, vm) { v, p -> upd { it.copy(lum = v) }(p) }
    }
}

// ---------- curva ----------

@Composable
fun CurveTab(vm: EditorViewModel, params: EditParams) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableIntStateOf(-1) }
    val primary = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier.size(220.dp).onSizeChanged { size = it }
                .background(Color(0xFF0E0E12), RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { o ->
                            vm.pushUndo()
                            val x = o.x / size.width
                            active = (x / 0.25f).roundToInt().coerceIn(0, 4)
                        },
                        onDragEnd = { active = -1 },
                        onDragCancel = { active = -1 },
                        onDrag = { change, _ ->
                            change.consume()
                            val i = active
                            if (i >= 0) {
                                val y = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                                vm.update { p -> p.copy(curve = p.curve.mapIndexed { k, v -> if (k == i) y else v }) }
                            }
                        }
                    )
                }
        ) {
            val w = this.size.width
            val h = this.size.height
            for (k in 1..3) {
                drawLine(Color(0xFF2A2A33), Offset(w * k / 4, 0f), Offset(w * k / 4, h))
                drawLine(Color(0xFF2A2A33), Offset(0f, h * k / 4), Offset(w, h * k / 4))
            }
            drawLine(Color(0xFF3A3A44), Offset(0f, h), Offset(w, 0f))
            val lut = ImageProcessor.buildCurveLut(params.curve)
            val path = Path()
            for (i in 0..255) {
                val px = w * i / 255f
                val py = h * (1f - lut[i])
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, Color.White, style = Stroke(width = 3f))
            params.curve.forEachIndexed { k, v ->
                drawCircle(primary, 9f, Offset(w * k / 4f, h * (1f - v)))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Arraste os pontos para ajustar a curva de tons.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                vm.pushUndo(); vm.update { it.copy(curve = IDENTITY_CURVE) }
            }) { Text("Resetar curva") }
        }
    }
}

// ---------- presets ----------

@Composable
fun PresetsTab(vm: EditorViewModel) {
    val custom by vm.customPresets.collectAsState()
    val thumbs by vm.thumbs.collectAsState()
    var showSave by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Preset?>(null) }

    Column(Modifier.padding(vertical = 12.dp)) {
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(BUILTIN_PRESETS + custom) { preset ->
                Column(
                    Modifier.width(84.dp).combinedClickable(
                        onClick = { vm.applyPreset(preset) },
                        onLongClick = { if (preset.custom) toDelete = preset }
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val t = thumbs[preset.name]
                    Box(Modifier.size(84.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        if (t != null) Image(t.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(preset.name, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { name = ""; showSave = true }) { Text("Salvar ajustes como preset") }
        }
        Text("Segure um preset seu para apagar.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Novo preset") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nome") }) },
            confirmButton = { TextButton(onClick = { vm.saveCurrentAsPreset(name); showSave = false }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancelar") } }
        )
    }
    toDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Apagar \"${p.name}\"?") },
            confirmButton = { TextButton(onClick = { vm.deletePreset(p); toDelete = null }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } }
        )
    }
}

// ---------- corte ----------

@Composable
fun CropTab(vm: EditorViewModel, params: EditParams) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Aspect.values().forEach { a ->
                FilterChip(
                    selected = params.aspect == a,
                    onClick = { vm.pushUndo(); vm.update { it.copy(aspect = a) } },
                    label = { Text(a.label, fontSize = 12.sp) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.pushUndo(); vm.update { it.copy(rotation = (it.rotation + 3) % 4) } }) { Text("⟲ 90°") }
            OutlinedButton(onClick = { vm.pushUndo(); vm.update { it.copy(rotation = (it.rotation + 1) % 4) } }) { Text("⟳ 90°") }
            OutlinedButton(onClick = { vm.pushUndo(); vm.update { it.copy(flipH = !it.flipH) } }) { Text("↔") }
            OutlinedButton(onClick = { vm.pushUndo(); vm.update { it.copy(flipV = !it.flipV) } }) { Text("↕") }
        }
        AdjustSlider("Endireitar", params.straighten, -45f..45f, vm, { "%+.1f°".format(it) }) { v, p -> p.copy(straighten = v) }
    }
}
