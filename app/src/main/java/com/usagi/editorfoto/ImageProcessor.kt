package com.usagi.editorfoto

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object ImageProcessor {

    // ---------- Geometria ----------

    fun applyGeometry(src: Bitmap, p: EditParams): Bitmap {
        var bmp = src
        val turns = ((p.rotation % 4) + 4) % 4
        if (turns != 0 || p.flipH || p.flipV) {
            // Gira primeiro e espelha depois: assim ↔ e ↕ sempre espelham o que se vê na tela.
            val m = Matrix()
            if (turns != 0) m.postRotate(90f * turns)
            if (p.flipH) m.postScale(-1f, 1f)
            if (p.flipV) m.postScale(1f, -1f)
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        if (abs(p.straighten) > 0.05f) {
            val w = bmp.width
            val h = bmp.height
            val rad = Math.toRadians(abs(p.straighten).toDouble())
            val s = (1.0 / (cos(rad) + sin(rad) * max(w / h.toDouble(), h / w.toDouble()))).toFloat()
            val ow = (w * s).roundToInt().coerceAtLeast(1)
            val oh = (h * s).roundToInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.translate(ow / 2f, oh / 2f)
            c.rotate(p.straighten)
            c.translate(-w / 2f, -h / 2f)
            c.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            bmp = out
        }
        return bmp
    }

    /** Corte manual: [c] em coordenadas normalizadas da imagem já girada/endireitada. */
    fun applyCrop(src: Bitmap, c: CropRect): Bitmap {
        if (c.isFull) return src
        val w = src.width
        val h = src.height
        val x = (c.l * w).roundToInt().coerceIn(0, w - 1)
        val y = (c.t * h).roundToInt().coerceIn(0, h - 1)
        val cw = ((c.r * w).roundToInt() - x).coerceIn(1, w - x)
        val ch = ((c.b * h).roundToInt() - y).coerceIn(1, h - y)
        if (x == 0 && y == 0 && cw == w && ch == h) return src
        return Bitmap.createBitmap(src, x, y, cw, ch)
    }

    // ---------- Nitidez (máscara de nitidez) ----------

    /**
     * Realça detalhes somando a diferença entre a foto e uma versão borrada.
     * O raio cresce com a resolução, então a prévia e a foto exportada ficam parecidas.
     */
    fun applySharpen(src: Bitmap, amount: Float): Bitmap {
        if (amount <= 0f) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val k = max(1, (max(w, h) / 1280f).roundToInt())
        val amt = amount * 1.6f

        IntStream.range(0, h).parallel().forEach { y ->
            val r0 = (y - k).coerceAtLeast(0) * w
            val r1 = y * w
            val r2 = (y + k).coerceAtMost(h - 1) * w
            val t = IntArray(9)
            for (x in 0 until w) {
                val x0 = (x - k).coerceAtLeast(0)
                val x2 = (x + k).coerceAtMost(w - 1)
                t[0] = px[r0 + x0]; t[1] = px[r0 + x]; t[2] = px[r0 + x2]
                t[3] = px[r1 + x0]; t[4] = px[r1 + x]; t[5] = px[r1 + x2]
                t[6] = px[r2 + x0]; t[7] = px[r2 + x]; t[8] = px[r2 + x2]
                val a = t[4] ushr 24
                out[r1 + x] = (a shl 24) or (sharpCh(t, 16, amt) shl 16) or
                        (sharpCh(t, 8, amt) shl 8) or sharpCh(t, 0, amt)
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun sharpCh(t: IntArray, sh: Int, amt: Float): Int {
        val blur = (((t[0] shr sh) and 255) + 2 * ((t[1] shr sh) and 255) + ((t[2] shr sh) and 255) +
                2 * ((t[3] shr sh) and 255) + 4 * ((t[4] shr sh) and 255) + 2 * ((t[5] shr sh) and 255) +
                ((t[6] shr sh) and 255) + 2 * ((t[7] shr sh) and 255) + ((t[8] shr sh) and 255)) / 16f
        val c = (t[4] shr sh) and 255
        return (c + (c - blur) * amt).roundToInt().coerceIn(0, 255)
    }

    // ---------- Histograma ----------

    class Histogram(val r: IntArray, val g: IntArray, val b: IntArray, val l: IntArray)

    /** Histograma por amostragem (barato o bastante para recalcular a cada ajuste). */
    fun histogram(bmp: Bitmap): Histogram {
        val w = bmp.width
        val h = bmp.height
        val hr = IntArray(256)
        val hg = IntArray(256)
        val hb = IntArray(256)
        val hl = IntArray(256)
        val stepX = max(1, w / 256)
        val stepY = max(1, h / 160)
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                hr[r]++; hg[g]++; hb[b]++
                hl[(0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)]++
                x += stepX
            }
            y += stepY
        }
        return Histogram(hr, hg, hb, hl)
    }

    // ---------- Curva (spline monotônica) ----------

    fun buildCurveLut(ys: List<Float>): FloatArray {
        val n = 5
        val h = 0.25f
        val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / h }
        val m = FloatArray(n)
        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (k in 1 until n - 1) m[k] = if (d[k - 1] * d[k] <= 0f) 0f else (d[k - 1] + d[k]) / 2f
        for (k in 0 until n - 1) {
            if (d[k] == 0f) { m[k] = 0f; m[k + 1] = 0f } else {
                val a = m[k] / d[k]
                val b = m[k + 1] / d[k]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / sqrt(s)
                    m[k] = t * a * d[k]
                    m[k + 1] = t * b * d[k]
                }
            }
        }
        return FloatArray(256) { i ->
            val x = i / 255f
            val k = (x / h).toInt().coerceIn(0, n - 2)
            val t = (x - k * h) / h
            val t2 = t * t
            val t3 = t2 * t
            val y = (2 * t3 - 3 * t2 + 1) * ys[k] + (t3 - 2 * t2 + t) * h * m[k] +
                    (-2 * t3 + 3 * t2) * ys[k + 1] + (t3 - t2) * h * m[k + 1]
            y.coerceIn(0f, 1f)
        }
    }

    // ---------- Cor ----------

    private val HUE_CENTERS = floatArrayOf(0f, 30f, 60f, 120f, 180f, 240f, 270f, 320f, 360f)

    // Tabelas sRGB <-> luz linear (exposição e balanço de branco ficam fisicamente corretos)
    private const val LIN_STEPS = 4096
    private val SRGB_TO_LIN = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    private val LIN_TO_SRGB = FloatArray(LIN_STEPS + 1) { i ->
        val l = i / LIN_STEPS.toFloat()
        if (l <= 0.0031308f) l * 12.92f else 1.055f * l.pow(1f / 2.4f) - 0.055f
    }

    fun applyColor(src: Bitmap, p: EditParams): Bitmap {
        if (!p.hasColorEdits) return src
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Multiplicadores aplicados em luz linear. O expoente 2,2 mantém a força que
        // temperatura/matiz tinham antes (quando eram aplicados direto no valor sRGB).
        val useLin = p.exposure != 0f || p.temperature != 0f || p.tint != 0f
        val expMul = 2f.pow(p.exposure)
        val rMul = expMul * (1f + p.temperature * 0.25f).pow(2.2f)
        val bMul = expMul * (1f - p.temperature * 0.25f).pow(2.2f)
        val gMul = expMul * (1f - p.tint * 0.15f).pow(2.2f)
        val cf = 1f + p.contrast
        val sh = p.shadows * 0.35f
        val hi = p.highlights * 0.35f
        val lut = buildCurveLut(p.curve)
        val useCurve = p.curve != IDENTITY_CURVE
        val gSat = max(p.saturation, 0f)                              // aumento de saturação (HSL)
        val desat = if (p.saturation < 0f) 1f + p.saturation else 1f  // redução: mistura com a luma
        val useHsl = gSat != 0f || p.vibrance != 0f || p.hsl.any { !it.isZero }
        val hueAdj = FloatArray(8) { p.hsl[it].hue * 30f }
        val satAdj = FloatArray(8) { p.hsl[it].sat }
        val lumAdj = FloatArray(8) { p.hsl[it].lum * 0.25f }
        val vib = p.vibrance

        // vinheta (elíptica, acompanha a proporção da foto) e granulação
        val vig = p.vignette
        val useVig = vig != 0f
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val invCx = 1f / max(cx, 1f)
        val invCy = 1f / max(cy, 1f)
        val grainAmt = p.grain * 0.36f
        val useGrain = p.grain > 0f
        val cell = max(1, (max(w, h) / 1280f).roundToInt())   // grão com o mesmo tamanho aparente na prévia e na exportação

        IntStream.range(0, h).parallel().forEach { y ->
            val row = y * w
            var i = row
            val end = i + w
            val dy = (y - cy) * invCy
            val dy2 = dy * dy
            while (i < end) {
                val c = px[i]
                val a = c ushr 24
                val r8 = (c shr 16) and 255
                val g8 = (c shr 8) and 255
                val b8 = c and 255
                var r: Float
                var g: Float
                var b: Float
                if (useLin) {
                    r = LIN_TO_SRGB[((SRGB_TO_LIN[r8] * rMul).coerceIn(0f, 1f) * LIN_STEPS + 0.5f).toInt()]
                    g = LIN_TO_SRGB[((SRGB_TO_LIN[g8] * gMul).coerceIn(0f, 1f) * LIN_STEPS + 0.5f).toInt()]
                    b = LIN_TO_SRGB[((SRGB_TO_LIN[b8] * bMul).coerceIn(0f, 1f) * LIN_STEPS + 0.5f).toInt()]
                } else {
                    r = r8 / 255f
                    g = g8 / 255f
                    b = b8 / 255f
                }

                // contraste
                r = ((r - 0.5f) * cf + 0.5f).coerceIn(0f, 1f)
                g = ((g - 0.5f) * cf + 0.5f).coerceIn(0f, 1f)
                b = ((b - 0.5f) * cf + 0.5f).coerceIn(0f, 1f)

                // sombras / realces
                if (sh != 0f || hi != 0f) {
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val d = sh * (1f - l) * (1f - l) + hi * l * l
                    r = (r + d).coerceIn(0f, 1f)
                    g = (g + d).coerceIn(0f, 1f)
                    b = (b + d).coerceIn(0f, 1f)
                }

                // curva
                if (useCurve) {
                    r = lut[(r * 255f + 0.5f).toInt()]
                    g = lut[(g * 255f + 0.5f).toInt()]
                    b = lut[(b * 255f + 0.5f).toInt()]
                }

                // reduzir saturação: mistura com a luma (Rec. 709), não com a luminosidade HSL;
                // assim -100 vira um P&B com contraste correto entre as cores
                if (desat < 1f) {
                    val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    r = y + (r - y) * desat
                    g = y + (g - y) * desat
                    b = y + (b - y) * desat
                }

                // aumento de saturação / vibração / HSL
                if (useHsl) {
                    val mx = max(r, max(g, b))
                    val mn = minOf(r, minOf(g, b))
                    val dl = mx - mn
                    val l = (mx + mn) / 2f
                    if (dl > 1e-6f) {
                        var s = dl / (1f - abs(2f * l - 1f)).coerceAtLeast(1e-6f)
                        var hh = when (mx) {
                            r -> 60f * (((g - b) / dl) % 6f)
                            g -> 60f * ((b - r) / dl + 2f)
                            else -> 60f * ((r - g) / dl + 4f)
                        }
                        if (hh < 0f) hh += 360f

                        // peso entre canais vizinhos
                        var idx = 0
                        while (idx < 7 && hh >= HUE_CENTERS[idx + 1]) idx++
                        val t = (hh - HUE_CENTERS[idx]) / (HUE_CENTERS[idx + 1] - HUE_CENTERS[idx])
                        val j = (idx + 1) % 8
                        val dh = hueAdj[idx] * (1f - t) + hueAdj[j] * t
                        val ds = satAdj[idx] * (1f - t) + satAdj[j] * t
                        val dlum = lumAdj[idx] * (1f - t) + lumAdj[j] * t

                        hh = (hh + dh + 360f) % 360f
                        s = s * (1f + gSat) * (1f + ds)  // gSat >= 0 aqui
                        s += vib * (1f - s.coerceIn(0f, 1f)) * s
                        s = s.coerceIn(0f, 1f)
                        val l2 = (l + dlum * s).coerceIn(0f, 1f)

                        val cc = (1f - abs(2f * l2 - 1f)) * s
                        val hp = hh / 60f
                        val xx = cc * (1f - abs(hp % 2f - 1f))
                        val m0 = l2 - cc / 2f
                        when (hp.toInt()) {
                            0 -> { r = cc; g = xx; b = 0f }
                            1 -> { r = xx; g = cc; b = 0f }
                            2 -> { r = 0f; g = cc; b = xx }
                            3 -> { r = 0f; g = xx; b = cc }
                            4 -> { r = xx; g = 0f; b = cc }
                            else -> { r = cc; g = 0f; b = xx }
                        }
                        r += m0; g += m0; b += m0
                    }
                }

                if (useVig) {
                    val dx = (i - row - cx) * invCx
                    val d = sqrt((dx * dx + dy2) * 0.5f)
                    val tt = ((d - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    val mul = 1f + vig * 0.9f * (tt * tt * (3f - 2f * tt))
                    r *= mul; g *= mul; b *= mul
                }
                if (useGrain) {
                    val gx = (i - row) / cell
                    val gy = y / cell
                    val n = (hash01(gx, gy) + hash01(gy + 977, gx + 131)) - 1f   // -1..1, mais denso no meio
                    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
                    val wgt = 1f - 0.6f * (2f * l - 1f) * (2f * l - 1f)          // menos grão em preto/branco puros
                    val add = n * grainAmt * 0.5f * wgt
                    r += add; g += add; b += add
                }

                val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
                px[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
                i++
            }
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun hash01(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }

    /** Aplica nitidez (antes da cor) e depois os ajustes de cor, vinheta e grão. */
    fun finish(cropped: Bitmap, p: EditParams): Bitmap = applyColor(applySharpen(cropped, p.sharpen), p)

    fun render(src: Bitmap, p: EditParams): Bitmap =
        finish(applyCrop(applyGeometry(src, p), p.crop), p)
}
