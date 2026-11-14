package com.bookscan.app

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Kéo giãn chữ bị NÉN THEO CHIỀU NGANG gần gáy (sách dày mở chụp chính diện: mặt giấy nghiêng nên chữ hẹp lại ~cos θ).
 * Đã thử trong mô phỏng Python (có ground truth): sách dày giảm khoảng một nửa sai số tổng, giấy phẳng/cong nhẹ không đổi vì có "cổng".
 *
 * Cách làm (ảnh đã nắn về khổ giấy, biết cạnh gáy):
 *  1. Mặt nạ mực → dính chữ thành "từ" (giãn ngang vài px) → lấy bề rộng từ theo vị trí x.
 *  2. Hệ số nén c(x) theo hai cách rồi lấy trung bình:
 *     (a) bề rộng từ trung bình theo ô 1,5 cm / bề rộng ở vùng phẳng (4 cm xa gáy nhất), đa thức bậc 4;
 *     (b) mô hình c(x) = cos(θ·exp(−(d/σ)²)) với d = khoảng cách tới gáy, khớp (θ, σ) bình phương tối thiểu.
 *  3. Cổng: chỉ nắn khi c trung bình trong 3 cm sát gáy < 0,92 (nén rõ) và có ≥ 60 từ.
 *  4. Dãn cột theo 1/c rồi chuẩn hoá để tổng bề rộng trang không đổi (bề rộng trang vốn đã bằng khổ giấy).
 * Chưa kiểm thử trên điện thoại hay ảnh một trang thật.
 */
object HorizontalFlattener {
    class Result(val mat: Mat, val note: String)

    private const val WORK_PPC = 40.0
    private const val GATE = 0.92

    fun correct(page: Mat, ppc: Double, gutterSide: Int): Result? {
        if (gutterSide == SinglePageScanner.GUTTER_NONE) return null
        val gutterRight = gutterSide == SinglePageScanner.GUTTER_RIGHT
        val sc = min(1.0, WORK_PPC / ppc)
        val wp = ppc * sc
        val small = Mat()
        Imgproc.resize(page, small, Size(), sc, sc, Imgproc.INTER_AREA)
        val g = Mat()
        if (small.channels() == 1) small.copyTo(g) else Imgproc.cvtColor(small, g, Imgproc.COLOR_BGR2GRAY)
        small.release()
        val W = g.cols()

        // --- từ chữ ---
        val gap = max(2, Math.round(3 * wp / 40.0).toInt())
        val gf = Mat()
        g.convertTo(gf, CvType.CV_32F)
        g.release()
        val k1 = max(3, (0.8 * wp).toInt())
        val bg = Mat()
        Imgproc.dilate(gf, bg, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k1.toDouble(), k1.toDouble())))
        Imgproc.GaussianBlur(bg, bg, Size(), 0.8 * wp)
        Core.add(bg, Scalar(1e-3), bg)
        val ratio = Mat()
        Core.divide(gf, bg, ratio)
        gf.release(); bg.release()
        val ink = Mat()
        Core.compare(ratio, Scalar(0.78), ink, Core.CMP_LT)
        ratio.release()
        val m = (0.6 * wp).toInt()
        if (m > 0 && ink.rows() > 2 * m) {
            ink.submat(0, m, 0, ink.cols()).setTo(Scalar(0.0))
            ink.submat(ink.rows() - m, ink.rows(), 0, ink.cols()).setTo(Scalar(0.0))
        }
        val blob = Mat()
        Imgproc.dilate(ink, blob, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(gap.toDouble(), 1.0)))
        ink.release()
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        Imgproc.connectedComponentsWithStats(blob, labels, stats, cents, 8, CvType.CV_32S)
        blob.release(); labels.release(); cents.release()
        val n = stats.rows()
        val st = IntArray(n * 5)
        stats.get(0, 0, st)
        stats.release()
        val wx = ArrayList<Double>()
        val ww = ArrayList<Double>()
        for (i in 1 until n) {
            val x = st[i * 5]; val w = st[i * 5 + 2]; val h = st[i * 5 + 3]
            if (h >= 0.15 * wp && h <= 0.9 * wp && w >= 0.4 * wp && w <= 4 * wp) {
                wx.add((x + w / 2.0) / wp)                 // cm
                ww.add((w - (gap - 1)) / wp)               // cm
            }
        }
        if (wx.size < 60) return null
        val wcm = W / wp

        // --- (a) bề rộng từ theo ô 1,5 cm ---
        val binW = 1.5
        val cx = ArrayList<Double>()
        val cw = ArrayList<Double>()
        var a0 = 0.0
        while (a0 < wcm) {
            var s = 0.0; var cnt = 0
            for (i in wx.indices) if (wx[i] >= a0 && wx[i] < a0 + binW) { s += ww[i]; cnt++ }
            if (cnt >= 12) { cx.add(a0 + binW / 2); cw.add(s / cnt) }
            a0 += binW
        }
        if (cx.size < 5) return null
        val cxMin = cx.min(); val cxMax = cx.max()
        val outerIdx = cx.indices.filter { if (gutterRight) cx[it] <= cxMin + 4.0 else cx[it] >= cxMax - 4.0 }
        if (outerIdx.isEmpty()) return null
        val ref = outerIdx.map { cw[it] }.average()
        if (ref <= 0) return null
        val cb = cw.map { it / ref }.toDoubleArray()
        val coef = polyFit(cx.toDoubleArray(), cb, 4, wcm) ?: return null

        // --- (b) mô hình tham số ---
        var bestR = Double.MAX_VALUE
        var bestTh = 0.0
        var bestSg = 3.0
        var th = 0.0
        while (th <= 80.0) {
            var sg = 1.0
            while (sg <= 8.0) {
                var sw = 0.0; var scc = 0.0; var sww = 0.0
                val cs = DoubleArray(wx.size)
                for (i in wx.indices) {
                    val d = if (gutterRight) wcm - wx[i] else wx[i]
                    val c = cos(Math.toRadians(th) * exp(-(d / sg) * (d / sg))).coerceIn(0.2, 1.0)
                    cs[i] = c
                    sw += ww[i] * c; scc += c * c; sww += ww[i] * ww[i]
                }
                val w0 = sw / scc
                var r = 0.0
                for (i in wx.indices) { val e = ww[i] - w0 * cs[i]; r += e * e }
                if (r < bestR) { bestR = r; bestTh = th; bestSg = sg }
                sg += 0.5
            }
            th += 5.0
        }

        // --- hệ số nén theo cột ---
        val lo = cx.min(); val hi = cx.max()
        val cc = DoubleArray(W)
        for (x in 0 until W) {
            val xcm = x / wp
            val c1 = evalPoly(coef, xcm.coerceIn(lo, hi), wcm).coerceIn(0.3, 1.3)
            val d = if (gutterRight) wcm - xcm else xcm
            val c2 = cos(Math.toRadians(bestTh) * exp(-(d / bestSg) * (d / bestSg))).coerceIn(0.2, 1.0)
            cc[x] = 0.5 * (c1 + c2)
        }
        // cổng: nén rõ trong 3 cm sát gáy
        val zone = (0 until W).filter { if (gutterRight) it >= W - 3 * wp else it < 3 * wp }
        if (zone.isEmpty()) return null
        val zoneC = zone.map { cc[it] }.average()
        if (zoneC >= GATE) return null

        // --- bản đồ cột: dãn theo 1/c, chuẩn hoá giữ bề rộng ---
        val s = DoubleArray(W)
        for (i in 1 until W) s[i] = s[i - 1] + 0.5 * (1.0 / cc[i] + 1.0 / cc[i - 1])
        val tot = s[W - 1]
        if (tot <= 0) return null
        for (i in 0 until W) s[i] = s[i] / tot * (W - 1)
        val cols = DoubleArray(W)
        var j = 0
        for (x in 0 until W) {
            while (j < W - 2 && s[j + 1] < x) j++
            val d = s[j + 1] - s[j]
            val t = if (d > 1e-9) ((x - s[j]) / d).coerceIn(0.0, 1.0) else 0.0
            cols[x] = j + t
        }

        // --- remap toàn độ phân giải ---
        val PW = page.cols()
        val PH = page.rows()
        val rowX = FloatArray(PW)
        for (X in 0 until PW) {
            val xs = (X * sc).coerceIn(0.0, W - 1.0)
            val i0 = xs.toInt().coerceAtMost(W - 2)
            val f = xs - i0
            rowX[X] = (((1 - f) * cols[i0] + f * cols[i0 + 1]) / sc).toFloat()
        }
        val mapX = Mat(PH, PW, CvType.CV_32F)
        val mapY = Mat(PH, PW, CvType.CV_32F)
        val rowY = FloatArray(PW)
        for (Y in 0 until PH) {
            java.util.Arrays.fill(rowY, Y.toFloat())
            mapX.put(Y, 0, rowX)
            mapY.put(Y, 0, rowY)
        }
        val out = Mat()
        Imgproc.remap(page, out, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        mapX.release(); mapY.release()
        val edge = cc[if (gutterRight) W - 1 else 0]
        return Result(out, "Kéo giãn chữ gần gáy: nén ≈ %.0f%% sát gáy (θ≈%.0f°, σ≈%.1f cm).".format((1 - edge) * 100, bestTh, bestSg))
    }

    private fun polyFit(x: DoubleArray, y: DoubleArray, deg: Int, scale: Double): DoubleArray? {
        val m = Array(deg + 1) { DoubleArray(deg + 2) }
        for (i in x.indices) {
            val t = x[i] / scale * 2 - 1
            val pw = DoubleArray(deg + 1) { Math.pow(t, it.toDouble()) }
            for (r in 0..deg) {
                for (c in 0..deg) m[r][c] += pw[r] * pw[c]
                m[r][deg + 1] += pw[r] * y[i]
            }
        }
        for (r in 0..deg) m[r][r] += 1e-6
        for (c in 0..deg) {
            var piv = c
            for (r in c + 1..deg) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
            if (abs(m[piv][c]) < 1e-12) return null
            val tmp = m[c]; m[c] = m[piv]; m[piv] = tmp
            for (r in 0..deg) if (r != c) {
                val f = m[r][c] / m[c][c]
                for (cc in c..deg + 1) m[r][cc] -= f * m[c][cc]
            }
        }
        return DoubleArray(deg + 1) { m[it][deg + 1] / m[it][it] }
    }

    private fun evalPoly(c: DoubleArray, x: Double, scale: Double): Double {
        val t = x / scale * 2 - 1
        var r = 0.0
        for (i in c.indices.reversed()) r = r * t + c[i]
        return r
    }
}
