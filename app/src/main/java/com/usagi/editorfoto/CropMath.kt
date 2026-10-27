package com.usagi.editorfoto

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Área de corte em coordenadas normalizadas (0..1) da imagem já girada/endireitada. */
data class CropRect(val l: Float = 0f, val t: Float = 0f, val r: Float = 1f, val b: Float = 1f) {
    val w: Float get() = r - l
    val h: Float get() = b - t
    val isFull: Boolean get() = l <= 0f && t <= 0f && r >= 1f && b >= 1f
}

val FULL_CROP = CropRect()

enum class CropHandle { TL, T, TR, R, BR, B, BL, L, MOVE }

/**
 * Contas do corte, sem nada de Android. Todas as larguras são tratadas numa unidade em que a
 * altura da imagem vale 1 e a largura vale [imgAspect] (largura/altura da imagem).
 */
object CropMath {
    const val MIN = 0.08f   // menor lado permitido (fração da imagem)

    fun move(c: CropRect, dx: Float, dy: Float): CropRect {
        val mx = dx.coerceIn(-c.l, 1f - c.r)
        val my = dy.coerceIn(-c.t, 1f - c.b)
        return CropRect(c.l + mx, c.t + my, c.r + mx, c.b + my)
    }

    /**
     * Arrasta uma alça. [dx] e [dy] são frações da largura e da altura da imagem.
     * [ratio] = largura/altura travada da área cortada (null = livre).
     */
    fun drag(c: CropRect, hd: CropHandle, dx: Float, dy: Float, imgAspect: Float, ratio: Float?): CropRect {
        if (hd == CropHandle.MOVE) return move(c, dx, dy)
        if (ratio == null) return dragFree(c, hd, dx, dy)
        return when (hd) {
            CropHandle.TL -> dragLocked(c, c.r, c.b, c.l + dx, c.t + dy, imgAspect, ratio)
            CropHandle.TR -> dragLocked(c, c.l, c.b, c.r + dx, c.t + dy, imgAspect, ratio)
            CropHandle.BL -> dragLocked(c, c.r, c.t, c.l + dx, c.b + dy, imgAspect, ratio)
            CropHandle.BR -> dragLocked(c, c.l, c.t, c.r + dx, c.b + dy, imgAspect, ratio)
            else -> move(c, dx, dy)   // com proporção travada só os cantos redimensionam
        }
    }

    private fun dragFree(c: CropRect, hd: CropHandle, dx: Float, dy: Float): CropRect {
        var l = c.l
        var t = c.t
        var r = c.r
        var b = c.b
        if (hd == CropHandle.TL || hd == CropHandle.L || hd == CropHandle.BL) l = (c.l + dx).coerceIn(0f, c.r - MIN)
        if (hd == CropHandle.TR || hd == CropHandle.R || hd == CropHandle.BR) r = (c.r + dx).coerceIn(c.l + MIN, 1f)
        if (hd == CropHandle.TL || hd == CropHandle.T || hd == CropHandle.TR) t = (c.t + dy).coerceIn(0f, c.b - MIN)
        if (hd == CropHandle.BL || hd == CropHandle.B || hd == CropHandle.BR) b = (c.b + dy).coerceIn(c.t + MIN, 1f)
        return CropRect(l, t, r, b)
    }

    /** Canto oposto fixo em (ax, ay); o canto arrastado quer ir para (cx, cy). */
    private fun dragLocked(
        c: CropRect, ax: Float, ay: Float, cx: Float, cy: Float, imgAspect: Float, ratio: Float
    ): CropRect {
        val sx = if (cx >= ax) 1f else -1f
        val sy = if (cy >= ay) 1f else -1f
        var pw = abs(cx - ax) * imgAspect
        var ph = pw / ratio
        // tamanho mínimo (nos dois lados)
        val minPh = max(MIN, MIN * imgAspect / ratio)
        if (ph < minPh) { ph = minPh; pw = ph * ratio }
        // não pode passar das bordas da imagem
        val maxW = (if (sx > 0f) 1f - ax else ax) * imgAspect
        val maxH = if (sy > 0f) 1f - ay else ay
        if (pw > maxW) { pw = maxW; ph = pw / ratio }
        if (ph > maxH) { ph = maxH; pw = ph * ratio }
        if (ph < minPh - 1e-6f) return c   // sem espaço nesse sentido: mantém o corte atual
        val nx = ax + sx * pw / imgAspect
        val ny = ay + sy * ph
        return CropRect(min(ax, nx), min(ay, ny), max(ax, nx), max(ay, ny))
    }

    /** Maior área com a proporção [ratio] dentro de [c], com o mesmo centro. */
    fun fit(c: CropRect, ratio: Float, imgAspect: Float): CropRect {
        val pw = c.w * imgAspect
        val ph = c.h
        var nw = pw
        var nh = pw / ratio
        if (nh > ph) { nh = ph; nw = ph * ratio }
        val hw = nw / imgAspect / 2f
        val hh = nh / 2f
        val cx = ((c.l + c.r) / 2f).coerceIn(hw, 1f - hw)
        val cy = ((c.t + c.b) / 2f).coerceIn(hh, 1f - hh)
        return CropRect(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    // Acompanham o que o usuário vê quando gira/espelha a foto
    fun rotateCw(c: CropRect) = CropRect(1f - c.b, c.l, 1f - c.t, c.r)
    fun rotateCcw(c: CropRect) = CropRect(c.t, 1f - c.r, c.b, 1f - c.l)
    fun flipH(c: CropRect) = CropRect(1f - c.r, c.t, 1f - c.l, c.b)
    fun flipV(c: CropRect) = CropRect(c.l, 1f - c.b, c.r, 1f - c.t)
}
