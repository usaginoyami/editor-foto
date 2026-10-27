package com.usagi.editorfoto

import org.json.JSONArray
import org.json.JSONObject

data class HslAdj(val hue: Float = 0f, val sat: Float = 0f, val lum: Float = 0f) {
    val isZero get() = hue == 0f && sat == 0f && lum == 0f
}

enum class Aspect(val label: String, val ratio: Float?) {
    FREE("Livre", null),
    ORIGINAL("Original", null),
    SQUARE("1:1", 1f),
    R45("4:5", 0.8f),
    R32("3:2", 1.5f),
    R169("16:9", 16f / 9f);

    /**
     * Largura/altura travada para uma imagem de proporção [imgAspect]; null = corte livre.
     * Por padrão a proporção acompanha a orientação da foto; [swap] inverte (ex.: 4:5 em foto deitada).
     */
    fun lockFor(imgAspect: Float, swap: Boolean): Float? = when (this) {
        FREE -> null
        ORIGINAL -> imgAspect
        else -> {
            val r = ratio!!
            var t = r
            if (r != 1f && ((imgAspect < 1f) != (r < 1f))) t = 1f / r
            if (swap && r != 1f) t = 1f / t
            t
        }
    }
}

val HSL_NAMES = listOf("Vermelho", "Laranja", "Amarelo", "Verde", "Ciano", "Azul", "Roxo", "Magenta")
val HSL_COLORS = listOf(
    0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60
)
val IDENTITY_CURVE = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

data class EditParams(
    // Luz
    val exposure: Float = 0f,      // -2..2 EV
    val contrast: Float = 0f,      // -1..1
    val highlights: Float = 0f,    // -1..1
    val shadows: Float = 0f,       // -1..1
    // Cor
    val temperature: Float = 0f,   // -1..1
    val tint: Float = 0f,          // -1..1
    val saturation: Float = 0f,    // -1..1
    val vibrance: Float = 0f,      // -1..1
    // Curva (y em x = 0, .25, .5, .75, 1)
    val curve: List<Float> = IDENTITY_CURVE,
    // HSL (8 canais)
    val hsl: List<HslAdj> = List(8) { HslAdj() },
    // Efeitos
    val sharpen: Float = 0f,       // 0..1
    val vignette: Float = 0f,      // -1 (escurece) .. 1 (clareia)
    val grain: Float = 0f,         // 0..1
    // Geometria
    val rotation: Int = 0,         // quartos de volta
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val straighten: Float = 0f,    // -45..45 graus
    val aspect: Aspect = Aspect.FREE,
    val aspectSwap: Boolean = false,
    val crop: CropRect = FULL_CROP
) {
    val hasColorEdits: Boolean
        get() = exposure != 0f || contrast != 0f || highlights != 0f || shadows != 0f ||
                temperature != 0f || tint != 0f || saturation != 0f || vibrance != 0f ||
                curve != IDENTITY_CURVE || hsl.any { !it.isZero } ||
                vignette != 0f || grain != 0f

    /** Aplica o "look" (tom/cor/curva/HSL) de [look] mantendo a geometria atual. */
    fun withLook(look: EditParams) = look.copy(
        rotation = rotation, flipH = flipH, flipV = flipV,
        straighten = straighten, aspect = aspect, aspectSwap = aspectSwap, crop = crop
    )

    fun lookToJson(): JSONObject = JSONObject().apply {
        put("exposure", exposure.toDouble()); put("contrast", contrast.toDouble())
        put("highlights", highlights.toDouble()); put("shadows", shadows.toDouble())
        put("temperature", temperature.toDouble()); put("tint", tint.toDouble())
        put("saturation", saturation.toDouble()); put("vibrance", vibrance.toDouble())
        put("curve", JSONArray(curve.map { it.toDouble() }))
        put("hsl", JSONArray(hsl.map { JSONArray(listOf(it.hue.toDouble(), it.sat.toDouble(), it.lum.toDouble())) }))
        put("sharpen", sharpen.toDouble()); put("vignette", vignette.toDouble()); put("grain", grain.toDouble())
    }

    companion object {
        fun lookFromJson(o: JSONObject): EditParams {
            val c = o.optJSONArray("curve")
            val curve = if (c != null && c.length() == 5) List(5) { c.getDouble(it).toFloat() } else IDENTITY_CURVE
            val h = o.optJSONArray("hsl")
            val hsl = List(8) { i ->
                val a = h?.optJSONArray(i)
                if (a != null && a.length() == 3)
                    HslAdj(a.getDouble(0).toFloat(), a.getDouble(1).toFloat(), a.getDouble(2).toFloat())
                else HslAdj()
            }
            return EditParams(
                exposure = o.optDouble("exposure", 0.0).toFloat(),
                contrast = o.optDouble("contrast", 0.0).toFloat(),
                highlights = o.optDouble("highlights", 0.0).toFloat(),
                shadows = o.optDouble("shadows", 0.0).toFloat(),
                temperature = o.optDouble("temperature", 0.0).toFloat(),
                tint = o.optDouble("tint", 0.0).toFloat(),
                saturation = o.optDouble("saturation", 0.0).toFloat(),
                vibrance = o.optDouble("vibrance", 0.0).toFloat(),
                curve = curve, hsl = hsl,
                sharpen = o.optDouble("sharpen", 0.0).toFloat(),
                vignette = o.optDouble("vignette", 0.0).toFloat(),
                grain = o.optDouble("grain", 0.0).toFloat()
            )
        }
    }
}

data class Preset(val name: String, val look: EditParams, val custom: Boolean = false) {
    /** Chave única: um preset seu pode ter o mesmo nome de um nativo sem colidir. */
    val id: String get() = (if (custom) "c:" else "b:") + name
}

val BUILTIN_PRESETS = listOf(
    Preset("Original", EditParams()),
    Preset("Vívido", EditParams(contrast = 0.15f, saturation = 0.25f, vibrance = 0.3f, shadows = 0.15f)),
    Preset("Quente", EditParams(temperature = 0.35f, tint = 0.05f, contrast = 0.08f, saturation = 0.1f)),
    Preset("Frio", EditParams(temperature = -0.35f, tint = -0.05f, contrast = 0.08f)),
    Preset("Matte", EditParams(contrast = -0.15f, shadows = 0.3f, saturation = -0.1f,
        curve = listOf(0.08f, 0.27f, 0.5f, 0.75f, 0.94f))),
    Preset("Cinema", EditParams(contrast = 0.2f, temperature = 0.1f, saturation = -0.1f, shadows = 0.2f, highlights = -0.2f,
        vignette = -0.25f,
        hsl = List(8) { i -> when (i) { 1 -> HslAdj(0f, 0.2f, 0f); 4 -> HslAdj(-0.2f, 0.2f, -0.1f); 5 -> HslAdj(-0.2f, 0.2f, -0.1f); else -> HslAdj() } })),
    Preset("P&B", EditParams(saturation = -1f, contrast = 0.25f)),
    Preset("P&B Suave", EditParams(saturation = -1f, contrast = -0.1f, shadows = 0.2f,
        curve = listOf(0.06f, 0.27f, 0.5f, 0.73f, 0.94f))),
    Preset("Filme", EditParams(temperature = 0.15f, tint = 0.05f, contrast = 0.1f, saturation = -0.15f, grain = 0.3f,
        curve = listOf(0.05f, 0.24f, 0.5f, 0.77f, 0.96f))),
    Preset("Drama", EditParams(contrast = 0.4f, highlights = -0.4f, shadows = 0.3f, vibrance = 0.2f, saturation = -0.1f,
        vignette = -0.35f))
)
