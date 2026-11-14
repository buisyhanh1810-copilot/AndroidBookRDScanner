package com.bookscan.app

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Nắn phẳng trang cong theo MÉP GIẤY: mép trên và mép dưới được dò thành đường cong (đa thức bậc 3),
 * hai cạnh bên là đường thẳng. Mỗi điểm ảnh của trang phẳng được lấy từ ảnh gốc qua lưới (Coons đơn giản):
 * x nội suy giữa hai cạnh bên, y nội suy giữa đường cong trên và dưới.
 * Nếu không dò được mép tin cậy thì trả null để gọi lại cách homography cũ.
 */
object EdgeWarp {

    class Poly(val c: DoubleArray, val xm: Double, val sc: Double) {
        fun eval(x: Double): Double {
            val t = (x - xm) / sc
            var r = 0.0
            for (i in c.indices.reversed()) r = r * t + c[i]
            return r
        }
    }

    class Curves(val top: Poly?, val bottom: Poly?) {
        fun any(): Boolean = top != null || bottom != null
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (i in 0 until n) {
            var piv = i
            for (r in i + 1 until n) if (abs(a[r][i]) > abs(a[piv][i])) piv = r
            if (abs(a[piv][i]) < 1e-12) return null
            val tmp = a[i]; a[i] = a[piv]; a[piv] = tmp
            val tb = b[i]; b[i] = b[piv]; b[piv] = tb
            for (r in i + 1 until n) {
                val f = a[r][i] / a[i][i]
                for (c in i until n) a[r][c] -= f * a[i][c]
                b[r] -= f * b[i]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = b[i]
            for (c in i + 1 until n) s -= a[i][c] * x[c]
            x[i] = s / a[i][i]
        }
        return x
    }

    private fun fit(xs: DoubleArray, ys: DoubleArray, use: BooleanArray, deg: Int, xm: Double, sc: Double): DoubleArray? {
        val n = deg + 1
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (k in xs.indices) {
            if (!use[k]) continue
            val t = (xs[k] - xm) / sc
            val pw = DoubleArray(2 * n)
            pw[0] = 1.0
            for (i in 1 until 2 * n) pw[i] = pw[i - 1] * t
            for (i in 0 until n) {
                for (j in 0 until n) a[i][j] += pw[i + j]
                b[i] += pw[i] * ys[k]
            }
        }
        return solve(a, b)
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[s.size / 2]
    }

    private fun robustFit(xs: DoubleArray, ys: DoubleArray, deg: Int): Poly? {
        var xm = 0.0
        for (x in xs) xm += x
        xm /= xs.size
        var sc = 1.0
        for (x in xs) sc = max(sc, abs(x - xm))
        val use = BooleanArray(xs.size) { true }
        var poly: Poly? = null
        for (iter in 0 until 5) {
            var cnt = 0
            for (u in use) if (u) cnt++
            if (cnt < deg + 2) return null
            val c = fit(xs, ys, use, deg, xm, sc) ?: return null
            poly = Poly(c, xm, sc)
            val res = DoubleArray(xs.size) { ys[it] - poly!!.eval(xs[it]) }
            val inl = ArrayList<Double>()
            for (i in xs.indices) if (use[i]) inl.add(res[i])
            val med = median(inl)
            val s = max(1.0, 1.4826 * median(inl.map { abs(it - med) }))
            for (i in xs.indices) use[i] = abs(res[i]) < 2.5 * s + 1.0
        }
        return poly
    }

    private fun detectEdge(data: ByteArray, w: Int, hh: Int, q: Array<Point>, ppc: Double, top: Boolean): Poly? {
        val x0 = max(q[0].x, q[3].x)
        val x1 = min(q[1].x, q[2].x)
        val band = max(3, (0.5 * ppc).toInt())
        val search = (1.0 * ppc).toInt()
        val xsL = ArrayList<Double>()
        val ysL = ArrayList<Double>()
        var cols = 0
        var xc = x0 + 0.4 * ppc
        while (xc < x1 - 0.4 * ppc) {
            cols++
            val t = if (top) (xc - q[0].x) / (q[1].x - q[0].x) else (xc - q[3].x) / (q[2].x - q[3].x)
            val yref = if (top) q[0].y + t * (q[1].y - q[0].y) else q[3].y + t * (q[2].y - q[3].y)
            val a = max(0, (xc - band / 2.0).toInt())
            val b = min(w, (xc + band / 2.0).toInt())
            val lo = max(4, (yref - search).toInt())
            val hi = min(hh - 4, (yref + search).toInt())
            if (b > a && hi - lo >= 10) {
                val n = hi - lo + 6
                val p = DoubleArray(n)
                for (k in 0 until n) {
                    val y = lo - 3 + k
                    var s = 0
                    for (x in a until b) s += data[y * w + x].toInt() and 0xFF
                    p[k] = s.toDouble() / (b - a)
                }
                var bestK = -1
                var bestV = 0.0
                for (k in 0 until hi - lo) {
                    val d = p[k + 6] - p[k]
                    val v = if (top) d else -d
                    if (v > bestV) { bestV = v; bestK = k }
                }
                if (bestK >= 0 && bestV >= 10.0) {
                    xsL.add(xc)
                    ysL.add((lo + bestK).toDouble())
                }
            }
            xc += band * 0.6
        }
        if (xsL.size < max(6, (0.35 * cols).toInt())) return null
        return robustFit(xsL.toDoubleArray(), ysL.toDoubleArray(), 3)
    }

    private fun lineY(a: Point, b: Point, x: Double): Double = a.y + (x - a.x) * (b.y - a.y) / (b.x - a.x + 1e-9)

    /** q: TL,TR,BR,BL của trang trong ảnh cắt thô [crop]; h: chiều cao trang (px). */
    fun detect(crop: Mat, q: Array<Point>, ppc: Double, h: Int): Curves {
        val gray = Mat()
        Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(0.0, 0.0), 1.5)
        val w = gray.cols()
        val hh = gray.rows()
        val data = ByteArray(w * hh)
        gray.get(0, 0, data)
        gray.release()
        var top = detectEdge(data, w, hh, q, ppc, true)
        var bot = detectEdge(data, w, hh, q, ppc, false)
        val xmin = min(min(q[0].x, q[3].x), min(q[1].x, q[2].x))
        val xmax = max(max(q[0].x, q[3].x), max(q[1].x, q[2].x))
        // chỉ nhận đường cong nếu không lệch quá 0,8 cm so với mép thẳng đã dò
        if (top != null) {
            var bad = false
            for (i in 0..19) {
                val x = q[0].x + (q[1].x - q[0].x) * i / 19.0
                if (abs(top.eval(x.coerceIn(xmin, xmax)) - lineY(q[0], q[1], x)) > 0.8 * ppc) bad = true
            }
            if (bad) top = null
        }
        if (bot != null) {
            var bad = false
            for (i in 0..19) {
                val x = q[3].x + (q[2].x - q[3].x) * i / 19.0
                if (abs(bot.eval(x.coerceIn(xmin, xmax)) - lineY(q[3], q[2], x)) > 0.8 * ppc) bad = true
            }
            if (bad) bot = null
        }
        // khoảng cách trên-dưới phải còn xấp xỉ chiều cao trang
        val c = Curves(top, bot)
        for (i in 0..19) {
            val x = q[0].x + (q[1].x - q[0].x) * i / 19.0
            val yt = if (top != null) top.eval(x.coerceIn(xmin, xmax)) else lineY(q[0], q[1], x)
            val yb = if (bot != null) bot.eval(x.coerceIn(xmin, xmax)) else lineY(q[3], q[2], x)
            if (yb - yt < 0.8 * h || yb - yt > 1.2 * h) return Curves(null, null)
        }
        return c
    }

    /** Nắn trang pw x h từ ảnh gốc [img]; [cropToImg] đổi toạ độ crop -> ảnh gốc (3x3). */
    fun warp(img: Mat, cropToImg: Mat, q: Array<Point>, pw: Int, h: Int, cv: Curves): Mat {
        val xmin = min(min(q[0].x, q[3].x), min(q[1].x, q[2].x))
        val xmax = max(max(q[0].x, q[3].x), max(q[1].x, q[2].x))
        val out = Mat(h, pw, img.type())
        val chunk = 192
        var r0 = 0
        while (r0 < h) {
            val r1 = min(h, r0 + chunk)
            val rows = r1 - r0
            val buf = FloatArray(rows * pw * 2)
            var idx = 0
            for (v in r0 until r1) {
                val s = v / (h - 1.0)
                val xl = q[0].x + (q[3].x - q[0].x) * s
                val xr = q[1].x + (q[2].x - q[1].x) * s
                for (u in 0 until pw) {
                    val t = u / (pw - 1.0)
                    val xs = xl + t * (xr - xl)
                    val xc = xs.coerceIn(xmin, xmax)
                    val yt = if (cv.top != null) cv.top.eval(xc) else lineY(q[0], q[1], xs)
                    val yb = if (cv.bottom != null) cv.bottom.eval(xc) else lineY(q[3], q[2], xs)
                    buf[idx++] = xs.toFloat()
                    buf[idx++] = (yt + s * (yb - yt)).toFloat()
                }
            }
            val pts = Mat(rows, pw, CvType.CV_32FC2)
            pts.put(0, 0, buf)
            val mp = Mat()
            Core.perspectiveTransform(pts, mp, cropToImg)
            val ch = ArrayList<Mat>()
            Core.split(mp, ch)
            val piece = Mat()
            Imgproc.remap(img, piece, ch[0], ch[1], Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            piece.copyTo(out.rowRange(r0, r1))
            piece.release(); pts.release(); mp.release()
            for (m in ch) m.release()
            r0 = r1
        }
        return out
    }
}
