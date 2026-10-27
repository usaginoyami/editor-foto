@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.usagi.editorfoto

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val TABS = listOf("Luz", "Cor", "HSL", "Curva", "Efeitos", "Presets", "Cortar")
private const val TAB_CROP = 6

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val ctx = LocalContext.current
    val params by vm.params.collectAsState()
    val rendered by vm.rendered.collectAsState()
    val loading by vm.loading.collectAsState()
    val canUndo by vm.canUndo.collectAsState()
    val message by vm.message.collectAsState()
    val saved by vm.saved.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var showOriginal by remember { mutableStateOf(false) }
    var showHist by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    BackHandler { vm.close() }
    LaunchedEffect(message) {
        message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.clearMessage() }
    }

    val r = rendered
    val sv = saved
    val canShare = sv != null && sv.params == params
    val imgAspect = r?.let { it.geo.width / it.geo.height.toFloat() } ?: 1f

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
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            if (tab == TAB_CROP && r != null) {
                CropPreview(vm, params, r.geo)
            } else {
                Box(
                    Modifier.fillMaxSize().clipToBounds()
                        // pinça = zoom, arrastar = mover (a prévia tem até 1280 px)
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, panDelta, zoomDelta, _ ->
                                val s0 = zoom
                                val s1 = (s0 * zoomDelta).coerceIn(1f, 6f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                val rel = centroid - center
                                val o = rel + panDelta - (rel - pan) * (s1 / s0)
                                val mx = (s1 - 1f) * size.width / 2f
                                val my = (s1 - 1f) * size.height / 2f
                                zoom = s1
                                pan = if (s1 <= 1f) Offset.Zero else Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
                            }
                        }
                        // segurar (sem mexer) = ver o original; toque duplo = voltar ao zoom 1x
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { zoom = 1f; pan = Offset.Zero },
                                onPress = {
                                    coroutineScope {
                                        val job = launch { delay(250); showOriginal = true }
                                        tryAwaitRelease()
                                        job.cancel()
                                    }
                                    showOriginal = false
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    r?.let {
                        Image(
                            bitmap = (if (showOriginal) it.original else it.edited).asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = zoom; scaleY = zoom
                                translationX = pan.x; translationY = pan.y
                            }
                        )
                    }
                    if (showOriginal) {
                        Text(
                            "ORIGINAL",
                            modifier = Modifier.align(Alignment.TopCenter).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                            fontSize = 12.sp, color = Color.White
                        )
                    }
                }
                if (showHist) r?.let {
                    HistogramView(it.hist, Modifier.align(Alignment.TopStart).padding(6.dp).size(132.dp, 56.dp))
                }
                Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (canShare && sv != null) PreviewChip("COMPARTILHAR", false) { shareImage(ctx, sv.uri) }
                    PreviewChip("HIST", showHist) { showHist = !showHist }
                }
            }
            if (loading) CircularProgressIndicator()
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
                4 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    AdjustSlider("Nitidez", params.sharpen, 0f..1f, vm, { "%d".format((it * 100).roundToInt()) }) { v, p -> p.copy(sharpen = v) }
                    AdjustSlider("Vinheta", params.vignette, -1f..1f, vm) { v, p -> p.copy(vignette = v) }
                    AdjustSlider("Granulação", params.grain, 0f..1f, vm, { "%d".format((it * 100).roundToInt()) }) { v, p -> p.copy(grain = v) }
                }
                5 -> PresetsTab(vm)
                6 -> CropTab(vm, params, imgAspect)
            }
        }
    }
}

private fun shareImage(ctx: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Compartilhar foto"))
}

@Composable
private fun PreviewChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 11.sp,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) MaterialTheme.colorScheme.primary else Color(0x99000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun HistogramView(h: ImageProcessor.Histogram, modifier: Modifier) {
    Canvas(modifier.background(Color(0x99000000), RoundedCornerShape(6.dp))) {
        val w = size.width
        val hh = size.height
        var mx = 1
        for (i in 1..254) mx = maxOf(mx, h.l[i], h.r[i], h.g[i], h.b[i])   // ignora as pontas (corte)
        fun y(v: Int) = hh - hh * 0.95f * (v.coerceAtMost(mx).toFloat() / mx)
        val fill = Path().apply {
            moveTo(0f, hh)
            for (i in 0..255) lineTo(w * i / 255f, y(h.l[i]))
            lineTo(w, hh)
            close()
        }
        drawPath(fill, Color(0x55FFFFFF))
        for ((arr, c) in listOf(h.r to Color(0xCCFF5252), h.g to Color(0xCC69F0AE), h.b to Color(0xCC448AFF))) {
            val path = Path()
            for (i in 0..255) {
                if (i == 0) path.moveTo(0f, y(arr[0])) else path.lineTo(w * i / 255f, y(arr[i]))
            }
            drawPath(path, c, style = Stroke(width = 2f))
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
            items(BUILTIN_PRESETS + custom, key = { it.id }) { preset ->
                Column(
                    Modifier.width(84.dp).combinedClickable(
                        onClick = { vm.applyPreset(preset) },
                        onLongClick = { if (preset.custom) toDelete = preset }
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val t = thumbs[preset.id]
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
fun CropTab(vm: EditorViewModel, params: EditParams, imgAspect: Float) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Aspect.values().forEach { a ->
                FilterChip(
                    selected = params.aspect == a,
                    onClick = { vm.setAspect(a, imgAspect) },
                    label = { Text(a.label, fontSize = 12.sp) }
                )
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.rotate(-1) }) { Text("⟲ 90°") }
            OutlinedButton(onClick = { vm.rotate(1) }) { Text("⟳ 90°") }
            OutlinedButton(onClick = { vm.flip(true) }) { Text("↔") }
            OutlinedButton(onClick = { vm.flip(false) }) { Text("↕") }
            if (params.aspect.ratio != null) OutlinedButton(onClick = { vm.swapAspect(imgAspect) }) { Text("Inverter") }
            if (!params.crop.isFull) OutlinedButton(onClick = { vm.resetCrop() }) { Text("Resetar corte") }
        }
        AdjustSlider("Endireitar", params.straighten, -45f..45f, vm, { "%+.1f°".format(it) }) { v, p -> p.copy(straighten = v) }
        Text("Arraste os cantos e as bordas da foto para cortar.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Retângulo (em px da tela) onde uma imagem iw x ih aparece dentro de uma área cw x ch com ContentScale.Fit. */
private class FitBox(val ox: Float, val oy: Float, val w: Float, val h: Float)

private fun fitBox(cw: Float, ch: Float, iw: Float, ih: Float): FitBox {
    val s = minOf(cw / iw, ch / ih)
    val w = iw * s
    val h = ih * s
    return FitBox((cw - w) / 2f, (ch - h) / 2f, w, h)
}

private fun pickHandle(p: Offset, l: Float, t: Float, r: Float, b: Float, slop: Float, cornersOnly: Boolean): CropHandle? {
    fun near(x: Float, y: Float) = abs(p.x - x) <= slop && abs(p.y - y) <= slop
    if (near(l, t)) return CropHandle.TL
    if (near(r, t)) return CropHandle.TR
    if (near(l, b)) return CropHandle.BL
    if (near(r, b)) return CropHandle.BR
    val inX = p.x in l..r
    val inY = p.y in t..b
    if (!cornersOnly) {
        if (inX && abs(p.y - t) <= slop) return CropHandle.T
        if (inX && abs(p.y - b) <= slop) return CropHandle.B
        if (inY && abs(p.x - l) <= slop) return CropHandle.L
        if (inY && abs(p.x - r) <= slop) return CropHandle.R
    }
    return if (inX && inY) CropHandle.MOVE else null
}

/** Foto inteira (já girada/endireitada) com a área de corte por cima. */
@Composable
fun CropPreview(vm: EditorViewModel, params: EditParams, geo: Bitmap) {
    val img = remember(geo) { geo.asImageBitmap() }
    val ar = geo.width / geo.height.toFloat()
    val cropNow by rememberUpdatedState(params.crop)
    val ratioNow by rememberUpdatedState(params.aspect.lockFor(ar, params.aspectSwap))
    val arNow by rememberUpdatedState(ar)
    val slopPx = with(LocalDensity.current) { 36.dp.toPx() }

    Box(
        Modifier.fillMaxSize().pointerInput(geo) {
            var active: CropHandle? = null
            detectDragGestures(
                onDragStart = { pos ->
                    val g = fitBox(size.width.toFloat(), size.height.toFloat(), geo.width.toFloat(), geo.height.toFloat())
                    val c = cropNow
                    active = pickHandle(
                        pos,
                        g.ox + c.l * g.w, g.oy + c.t * g.h, g.ox + c.r * g.w, g.oy + c.b * g.h,
                        slopPx, ratioNow != null
                    )
                    if (active != null) vm.pushUndo()
                },
                onDragEnd = { active = null },
                onDragCancel = { active = null },
                onDrag = { change, drag ->
                    val h = active
                    if (h != null) {
                        change.consume()
                        val g = fitBox(size.width.toFloat(), size.height.toFloat(), geo.width.toFloat(), geo.height.toFloat())
                        val dx = drag.x / g.w
                        val dy = drag.y / g.h
                        vm.update { p -> p.copy(crop = CropMath.drag(p.crop, h, dx, dy, arNow, ratioNow)) }
                    }
                }
            )
        }
    ) {
        Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val g = fitBox(size.width, size.height, geo.width.toFloat(), geo.height.toFloat())
            val c = params.crop
            val l = g.ox + c.l * g.w
            val t = g.oy + c.t * g.h
            val r = g.ox + c.r * g.w
            val b = g.oy + c.b * g.h
            val dim = Color(0x99000000)
            // escurece o que vai ser cortado fora
            drawRect(dim, Offset(g.ox, g.oy), Size(g.w, (t - g.oy).coerceAtLeast(0f)))
            drawRect(dim, Offset(g.ox, b), Size(g.w, (g.oy + g.h - b).coerceAtLeast(0f)))
            drawRect(dim, Offset(g.ox, t), Size((l - g.ox).coerceAtLeast(0f), b - t))
            drawRect(dim, Offset(r, t), Size((g.ox + g.w - r).coerceAtLeast(0f), b - t))
            // grade de terços
            val grid = Color(0x66FFFFFF)
            for (k in 1..2) {
                drawLine(grid, Offset(l + (r - l) * k / 3f, t), Offset(l + (r - l) * k / 3f, b), 1f)
                drawLine(grid, Offset(l, t + (b - t) * k / 3f), Offset(r, t + (b - t) * k / 3f), 1f)
            }
            drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(width = 2f))
            // cantos
            val len = 26.dp.toPx()
            val th = 5f
            fun corner(x: Float, y: Float, sx: Float, sy: Float) {
                drawLine(Color.White, Offset(x - sx * th / 2f, y), Offset(x + sx * len, y), th)
                drawLine(Color.White, Offset(x, y - sy * th / 2f), Offset(x, y + sy * len), th)
            }
            corner(l, t, 1f, 1f); corner(r, t, -1f, 1f); corner(l, b, 1f, -1f); corner(r, b, -1f, -1f)
        }
    }
}
