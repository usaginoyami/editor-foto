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
import kotlinx.coroutines.CancellationException
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

    private var sourceUri: Uri? = null       // a exportação relê o original em resolução total
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
                val (prev, thumb) = withContext(Dispatchers.IO) {
                    val p = decode(uri, 1280)
                    p to scaleTo(p, 160)
                }
                sourceUri = uri
                previewSource = prev
                thumbSource = thumb
                _opened.value = true
                undoStack.clear(); _canUndo.value = false
                geoKey = null
                _params.value = EditParams()
                _rendered.value = null
                renderTrigger.value++
                buildThumbs()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {   // inclui OutOfMemoryError
                _message.value = "Não consegui abrir essa foto."
            } finally {
                _loading.value = false
            }
        }
    }

    fun close() {
        _opened.value = false
        sourceUri = null; previewSource = null; thumbSource = null
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
                map[it.id] = ImageProcessor.applyColor(t, it.look)
            }
            _thumbs.value = map
        }
    }

    // ---------- exportar ----------

    fun export() {
        val src = sourceUri ?: return
        val p = _params.value
        viewModelScope.launch {
            _loading.value = true
            val ok = withContext(Dispatchers.IO) {
                try {
                    saveJpeg(renderFullRes(src, p), src)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) { false }
            }
            _loading.value = false
            _message.value = if (ok) "Salva em Imagens/EditorFoto" else "Falha ao salvar a foto."
        }
    }

    /** Relê o original sem reduzir; se faltar memória, tenta de novo com lados máximos menores. */
    private fun renderFullRes(uri: Uri, p: EditParams): Bitmap {
        var failure: OutOfMemoryError? = null
        for (side in intArrayOf(Int.MAX_VALUE, 8000, 6000, 4000)) {
            try {
                return ImageProcessor.render(decode(uri, side), p)
            } catch (e: OutOfMemoryError) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("render falhou")
    }

    private fun saveJpeg(bmp: Bitmap, source: Uri) {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "EditorFoto_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EditorFoto")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            resolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            copyExif(source, uri)
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)   // não deixa arquivo pela metade na galeria
            throw e
        }
    }

    /** Copia data, câmera e exposição do original (a localização só vem se o sistema a fornecer). */
    private fun copyExif(from: Uri, to: Uri) {
        val cr = getApplication<Application>().contentResolver
        try {
            val src = cr.openInputStream(from)?.use { ExifInterface(it) } ?: return
            cr.openFileDescriptor(to, "rw")?.use { pfd ->
                val dst = ExifInterface(pfd.fileDescriptor)
                for (tag in EXIF_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
                // a rotação do EXIF já foi aplicada nos pixels
                dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Editor Foto")
                dst.saveAttributes()
            }
        } catch (_: Exception) {
            // sem EXIF a foto continua válida; não vale falhar a exportação por isso
        }
    }

    private companion object {
        val EXIF_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, "LensModel",
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP
        )
    }

    fun clearMessage() { _message.value = null }
}
