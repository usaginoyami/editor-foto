package com.usagi.editorfoto

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

class Rendered(val edited: Bitmap, val original: Bitmap)

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _params = MutableStateFlow(EditParams())
    val params: StateFlow<EditParams> = _params.asStateFlow()

    private val _rendered = MutableStateFlow<Rendered?>(null)
    val rendered: StateFlow<Rendered?> = _rendered.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _customPresets = MutableStateFlow<List<Preset>>(emptyList())
    val customPresets: StateFlow<List<Preset>> = _customPresets.asStateFlow()

    private val _thumbs = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbs: StateFlow<Map<String, Bitmap>> = _thumbs.asStateFlow()

    private val renderTrigger = MutableStateFlow(0)

    private var fullSource: Bitmap? = null   // para exportar
    private var previewSource: Bitmap? = null
    private var thumbSource: Bitmap? = null

    private val undoStack = ArrayDeque<EditParams>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _opened = MutableStateFlow(false)
    val opened: StateFlow<Boolean> = _opened.asStateFlow()

    private val prefs = app.getSharedPreferences("presets", 0)

    // cache da geometria
    private var geoKey: List<Any>? = null
    private var geoBitmap: Bitmap? = null

    init {
        loadPresets()
        viewModelScope.launch {
            combine(_params, renderTrigger) { p, _ -> p }.collectLatest { p ->
                val src = previewSource ?: return@collectLatest
                val r = withContext(Dispatchers.Default) {
                    val key = listOf(p.rotation, p.flipH, p.flipV, p.straighten, p.aspect, src)
                    val geo = if (key == geoKey && geoBitmap != null) geoBitmap!! else {
                        ImageProcessor.applyGeometry(src, p).also { geoBitmap = it; geoKey = key }
                    }
                    Rendered(ImageProcessor.applyColor(geo, p), geo)
                }
                _rendered.value = r
            }
        }
    }

    // ---------- carregar ----------

    fun open(uri: Uri) {
        viewModelScope.launch {
            _loading.value = true
            try {
                val (full, prev, thumb) = withContext(Dispatchers.IO) {
                    val f = decode(uri, 4000)
                    Triple(f, scaleTo(f, 1280), scaleTo(f, 160))
                }
                fullSource = full
                previewSource = prev
                thumbSource = thumb
                _opened.value = true
                undoStack.clear(); _canUndo.value = false
                geoKey = null
                _params.value = EditParams()
                _rendered.value = null
                renderTrigger.value++
                buildThumbs()
            } catch (e: Exception) {
                _message.value = "Não consegui abrir essa foto."
            } finally {
                _loading.value = false
            }
        }
    }

    fun close() {
        _opened.value = false
        fullSource = null; previewSource = null; thumbSource = null
        geoBitmap = null; geoKey = null
        _rendered.value = null
        _thumbs.value = emptyMap()
        undoStack.clear(); _canUndo.value = false
        _params.value = EditParams()
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val cr = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
        val orientation = cr.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return scaleTo(bmp, maxSide)
    }

    private fun scaleTo(b: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide / max(b.width, b.height).toFloat()
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    // ---------- edição ----------

    fun pushUndo() {
        undoStack.addLast(_params.value)
        if (undoStack.size > 50) undoStack.removeFirst()
        _canUndo.value = true
    }

    fun update(transform: (EditParams) -> EditParams) {
        _params.value = transform(_params.value)
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        _params.value = undoStack.removeLast()
        _canUndo.value = undoStack.isNotEmpty()
    }

    fun resetAll() {
        pushUndo()
        _params.value = EditParams()
    }

    fun applyPreset(p: Preset) {
        pushUndo()
        _params.value = _params.value.withLook(p.look)
    }

    // ---------- presets ----------

    private fun loadPresets() {
        val raw = prefs.getString("custom", null) ?: return
        try {
            val arr = JSONArray(raw)
            _customPresets.value = List(arr.length()) {
                val o = arr.getJSONObject(it)
                Preset(o.getString("name"), EditParams.lookFromJson(o.getJSONObject("look")), custom = true)
            }
        } catch (_: Exception) { }
    }

    private fun savePresets() {
        val arr = JSONArray()
        _customPresets.value.forEach {
            arr.put(JSONObject().put("name", it.name).put("look", it.look.lookToJson()))
        }
        prefs.edit().putString("custom", arr.toString()).apply()
    }

    fun saveCurrentAsPreset(name: String) {
        val n = name.trim().ifEmpty { "Meu preset" }
        val look = _params.value
        _customPresets.value = _customPresets.value.filter { it.name != n } + Preset(n, look, custom = true)
        savePresets()
        buildThumbs()
    }

    fun deletePreset(p: Preset) {
        _customPresets.value = _customPresets.value.filter { it.name != p.name }
        savePresets()
        buildThumbs()
    }

    private fun buildThumbs() {
        val t = thumbSource ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val map = HashMap<String, Bitmap>()
            (BUILTIN_PRESETS + _customPresets.value).forEach {
                map[it.name] = ImageProcessor.applyColor(t, it.look)
            }
            _thumbs.value = map
        }
    }

    // ---------- exportar ----------

    fun export() {
        val src = fullSource ?: return
        val p = _params.value
        viewModelScope.launch {
            _loading.value = true
            val ok = withContext(Dispatchers.IO) {
                try {
                    val out = ImageProcessor.render(src, p)
                    val resolver = getApplication<Application>().contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "EditorFoto_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EditorFoto")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    resolver.openOutputStream(uri)!!.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (e: Throwable) { false }
            }
            _loading.value = false
            _message.value = if (ok) "Salva em Imagens/EditorFoto" else "Falha ao salvar a foto."
        }
    }

    fun clearMessage() { _message.value = null }
}
