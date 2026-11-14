package com.bookscan.app

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Bám biên chính xác cho MỘT TRANG sau khi đã có khung (từ dò biên giấy/khối chữ):
 * với mỗi cạnh cần bám, lấy 48 lát cắt vuông góc quanh cạnh hiện có, tín hiệu "có mép" = bước nhảy sáng nhiều tỉ lệ + sắc độ giống giấy,
 * rồi Viterbi tìm đường mép liền nhau (nhảy tối đa 3 px/lát). Đường mới được KẾT HỢP với cạnh cũ (trọng số cũ 0,3):
 * đường thẳng khớp bình phương tối thiểu đồng thời với cạnh cũ và các điểm bám tin cậy (mép giấy cong thì ra đường nằm giữa).
 * Cạnh nào bám không đủ tin cậy thì giữ nguyên cạnh cũ. CẠNH GÁY (skipSide) không bám và hai đầu gáy giữ nguyên.
 *
 * Mô phỏng Python (giấy phẳng, sai số khung hạt giống 6 mm/góc): sai số góc ngoài 7,2 → 4,1 mm; hai đầu gáy không dịch.
 * Chưa kiểm thử trên điện thoại.
 */
object EdgeTracker {
    private const val K = 48
    private val SCALES = intArrayOf(2, 4, 7, 11)
    private val GAM = doubleArrayOf(1.0, 0.97, 0.93, 0.88)

    private class V(val x: Double, val y: Double)

    private class SideData(
        val a: V, val b: V, val pts: Array<V>, val conf: DoubleArray, val keep: BooleanArray, val ok: Boolean
    )

    /**
     * [quad] = [tl, tr, br, bl] của trang trong ảnh gốc; [wCm] x [hCm] = khổ MỘT trang;
     * [skipSide] = cạnh gáy (0=trên,1=phải,2=dưới,3=trái) hoặc null nếu bám cả 4 cạnh.
     * Trả về 4 góc mới (ảnh gốc) hoặc null nếu không đáng tin (giữ khung cũ).
     */
    fun refine(img: Mat, quad: Array<Point>, wCm: Double, hCm: Double, skipSide: Int?, lam: Double = 0.3): Array<Point>? {
        val sc = 700.0 / max(img.cols(), img.rows())
        val small = Mat()
        Imgproc.resize(img, small, Size(), sc, sc, Imgproc.INTER_AREA)
        val w = small.cols()
        val h = small.rows()
        val long = max(w, h).toDouble()
        val lab = Mat()
        Imgproc.cvtColor(small, lab, Imgproc.COLOR_BGR2Lab)
        small.release()
        val chs = ArrayList<Mat>()
        Core.split(lab, chs)
        lab.release()
        val lf = Mat()
        chs[0].convertTo(lf, CvType.CV_32F)
        val kc = odd(max(9.0, 0.02 * long))
        val lc = Mat()
        Imgproc.morphologyEx(lf, lc, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kc.toDouble(), kc.toDouble())))
        Imgproc.GaussianBlur(lc, lc, Size(0.0, 0.0), 1.0)
        val ls = FloatArray(w * h); lc.get(0, 0, ls)
        val ta = Mat(); val tb = Mat()
        chs[1].convertTo(ta, CvType.CV_32F, 1.0, -128.0)
        chs[2].convertTo(tb, CvType.CV_32F, 1.0, -128.0)
        val aArr = FloatArray(w * h); val bArr = FloatArray(w * h)
        ta.get(0, 0, aArr); tb.get(0, 0, bArr)
        for (m in chs) m.release()
        lf.release(); lc.release(); ta.release(); tb.release()

        // màu giấy từ vùng giữa ảnh
        val cx0 = (0.3 * w).toInt(); val cx1 = (0.7 * w).toInt(); val cy0 = (0.3 * h).toInt(); val cy1 = (0.7 * h).toInt()
        val roi = ArrayList<Double>()
        for (y in cy0 until cy1) for (x in cx0 until cx1) roi.add(ls[y * w + x].toDouble())
        if (roi.size < 50) return null
        val sorted = roi.sorted()
        val p85 = sorted[(0.85 * (sorted.size - 1)).toInt()]
        val p60 = sorted[(0.60 * (sorted.size - 1)).toInt()]
        val sa = ArrayList<Double>(); val sb = ArrayList<Double>()
        for (y in cy0 until cy1) for (x in cx0 until cx1) if (ls[y * w + x] >= p60) { sa.add(aArr[y * w + x].toDouble()); sb.add(bArr[y * w + x].toDouble()) }
        sa.sort(); sb.sort()
        val ma = sa[sa.size / 2]; val mb = sb[sb.size / 2]
        val chromM = Mat(h, w, CvType.CV_32F)
        val cArr = FloatArray(w * h) { val d = hypot(aArr[it] - ma, bArr[it] - mb) / 12.0; exp(-d * d).toFloat() }
        chromM.put(0, 0, cArr)
        Imgproc.GaussianBlur(chromM, chromM, Size(0.0, 0.0), 2.0)
        chromM.get(0, 0, cArr)
        chromM.release()

        val Q = Array(4) { V(quad[it].x * sc, quad[it].y * sc) }
        val top = (dist(Q[1], Q[0]) + dist(Q[2], Q[3])) / 2
        val side = (dist(Q[3], Q[0]) + dist(Q[2], Q[1])) / 2
        val s = sqrt(top / wCm * side / hCm)
        if (!s.isFinite() || s < 1e-3) return null
        val cen = V(Q.sumOf { it.x } / 4, Q.sumOf { it.y } / 4)

        // đường mới cho từng cạnh: (điểm, hướng)
        val lines = ArrayList<Pair<V, V>>()
        for (i in 0 until 4) {
            val a = Q[i]; val b = Q[(i + 1) % 4]
            val len = dist(a, b)
            val d = V((b.x - a.x) / len, (b.y - a.y) / len)
            if (skipSide != null && i == skipSide) { lines.add(a to d); continue }
            val sd = try { sideData(ls, cArr, w, h, Q, i, s, cen, p85) } catch (e: Exception) { null }
            lines.add(if (sd == null || !sd.ok) (a to d) else combLine(sd, lam, s))
        }
        // giao 4 đường -> 4 góc; hai đầu gáy giữ nguyên
        val out = Array(4) { Q[it] }
        for (i in 0 until 4) {
            val l1 = lines[(i + 3) % 4]; val l2 = lines[i]
            val p = inter(l1.first, l1.second, l2.first, l2.second)
            if (p != null) out[i] = p
        }
        if (skipSide != null) {
            out[skipSide] = Q[skipSide]
            out[(skipSide + 1) % 4] = Q[(skipSide + 1) % 4]
        }
        // an toàn: lồi, diện tích đổi < 30%
        val a0 = area(Q); val a1 = area(out)
        if (a1 < 0.7 * a0 || a1 > 1.3 * a0 || !convex(out)) return null
        return Array(4) { Point(out[it].x / sc, out[it].y / sc) }
    }

    /**
     * Tiện ích cho luồng "cắt thô → refinePage": [q2] là khung trong toạ độ crop, [mm] là phép nắn ảnh gốc → crop,
     * [rough] là khung thô (ảnh gốc) có hai đầu gáy chính xác. Cạnh gáy [skipSide] giữ nguyên đúng như [rough].
     * Trả về khung mới trong toạ độ crop, hoặc null (giữ q2).
     */
    fun refineCrop(img: Mat, mm: Mat, q2: Array<Point>, rough: Array<Point>, wCm: Double, hCm: Double, skipSide: Int?): Array<Point>? {
        val inv = mm.inv()
        val back = MatOfPoint2f()
        Core.perspectiveTransform(MatOfPoint2f(*q2), back, inv)
        inv.release()
        val b = back.toArray()
        if (skipSide != null) {
            b[skipSide] = rough[skipSide]
            b[(skipSide + 1) % 4] = rough[(skipSide + 1) % 4]
        }
        val r = refine(img, b, wCm, hCm, skipSide) ?: return null
        val fwd = MatOfPoint2f()
        Core.perspectiveTransform(MatOfPoint2f(*r), fwd, mm)
        return fwd.toArray()
    }

    // ---------------------------------------------------------------- tín hiệu + Viterbi
    private fun sideData(ls: FloatArray, cArr: FloatArray, w: Int, h: Int, Q: Array<V>, i: Int, s: Double, cen: V, P: Double): SideData? {
        val a = Q[i]; val b = Q[(i + 1) % 4]
        val len = dist(a, b)
        val d = V((b.x - a.x) / len, (b.y - a.y) / len)
        var o = V(d.y, -d.x)
        if (o.x * ((a.x + b.x) / 2 - cen.x) + o.y * ((a.y + b.y) / 2 - cen.y) < 0) o = V(-o.x, -o.y)
        val band = max(16, (2.4 * s).toInt())
        val n = 2 * band + 1
        val Cx = DoubleArray(K); val Cy = DoubleArray(K)
        for (k in 0 until K) {
            val t = 0.06 + 0.88 * k / (K - 1)
            Cx[k] = a.x + (b.x - a.x) * t; Cy[k] = a.y + (b.y - a.y) * t
        }
        val cl = Array(K) { DoubleArray(n + 1) }
        val cc = Array(K) { DoubleArray(n + 1) }
        val inb = Array(K) { BooleanArray(n) }
        for (k in 0 until K) for (j in 0 until n) {
            val u = (j - band).toDouble()
            val X = Cx[k] + u * o.x; val Y = Cy[k] + u * o.y
            inb[k][j] = X >= 0 && X <= w - 1 && Y >= 0 && Y <= h - 1
            cl[k][j + 1] = cl[k][j] + bil(ls, w, h, X, Y)
            cc[k][j + 1] = cc[k][j] + bil(cArr, w, h, X, Y)
        }
        val E = Array(K) { DoubleArray(n) }
        val Pc = Array(K) { DoubleArray(n) }
        for ((si, sc_) in SCALES.withIndex()) {
            val g = GAM[si]
            for (k in 0 until K) for (j in sc_ until n - sc_) {
                val lin = (cl[k][j] - cl[k][j - sc_]) / sc_
                val lout = (cl[k][j + sc_ + 1] - cl[k][j + 1]) / sc_
                val dl = lin - lout
                E[k][j] = max(E[k][j], g * (max(dl, 0.0) + 0.6 * max(-dl, 0.0)))
                val cin = (cc[k][j] - cc[k][j - sc_]) / sc_
                val cout = (cc[k][j + sc_ + 1] - cc[k][j + 1]) / sc_
                Pc[k][j] = max(Pc[k][j], g * (cin - cout).coerceIn(0.0, 1.0))
            }
        }
        val all = ArrayList<Double>(K * n)
        for (k in 0 until K) for (j in 0 until n) all.add(E[k][j])
        all.sort()
        val p97 = all[(0.97 * (all.size - 1)).toInt()]
        val den = max(p97, 0.05 * P)
        val S = Array(K) { k -> DoubleArray(n) { j ->
            if (!inb[k][j] || j < 6 || j >= n - 6) 0.0
            else 0.65 * (E[k][j] / den).coerceIn(0.0, 1.0) + 0.35 * Pc[k][j]
        } }
        val path = viterbi(S, K, n)
        val pts = Array(K) { k -> V(Cx[k] + (path[k] - band) * o.x, Cy[k] + (path[k] - band) * o.y) }
        val conf = DoubleArray(K) { S[it][path[it]] }
        val cs = conf.sorted()
        val keepThr = max(0.15, cs[(0.35 * (K - 1)).toInt()])
        val keep = BooleanArray(K) { conf[it] >= keepThr }

        // đường thẳng bền vững qua điểm bám -> kiểm tra tin cậy
        val dd = V(Cx[K - 1] - Cx[0], Cy[K - 1] - Cy[0]); val dl2 = hypot(dd.x, dd.y)
        val dir = V(dd.x / dl2, dd.y / dl2)
        val t = DoubleArray(K) { (pts[it].x - Cx[0]) * dir.x + (pts[it].y - Cy[0]) * dir.y }
        val u = DoubleArray(K) { (pts[it].x - Cx[0]) * o.x + (pts[it].y - Cy[0]) * o.y }
        var idx = (0 until K).filter { keep[it] }
        var c0 = 0.0; var c1 = 0.0
        for (iter in 0 until 5) {
            if (idx.size < 3) break
            var sn = 0.0; var st = 0.0; var su = 0.0; var stt = 0.0; var stu = 0.0
            for (q in idx) { sn++; st += t[q]; su += u[q]; stt += t[q] * t[q]; stu += t[q] * u[q] }
            val den2 = sn * stt - st * st
            if (abs(den2) < 1e-9) break
            c0 = (sn * stu - st * su) / den2; c1 = (su - c0 * st) / sn
            val res = DoubleArray(K) { abs(u[it] - (c0 * t[it] + c1)) }
            val medRes = idx.map { res[it] }.sorted().let { it[it.size / 2] }
            val thr = max(1.5, 2.5 * medRes * 1.48)
            idx = (0 until K).filter { keep[it] && res[it] < thr }
            if (idx.size < 6) break
        }
        val inl = idx.size.toDouble() / K
        val mc = conf.sorted()[K / 2]
        val sh0 = abs(c1)
        val sh1 = abs(c0 * dl2 + c1)
        val ok = inl > 0.6 && mc > 0.3 && max(sh0, sh1) < 1.2 * s
        return SideData(a, b, pts, conf, keep, ok)
    }

    private fun viterbi(S: Array<DoubleArray>, K: Int, n: Int, lam: Double = 0.04, maxJump: Int = 3): IntArray {
        val dp = Array(K) { DoubleArray(n) { -1e9 } }
        val bp = Array(K) { IntArray(n) }
        for (j in 0 until n) dp[0][j] = S[0][j]
        for (k in 1 until K) for (j in 0 until n) {
            var best = -1e18; var arg = j
            for (dj in -maxJump..maxJump) {
                val jp = j - dj
                if (jp < 0 || jp >= n) continue
                val v = dp[k - 1][jp] - lam * dj * dj
                if (v > best) { best = v; arg = jp }
            }
            dp[k][j] = best + S[k][j]; bp[k][j] = arg
        }
        var j = 0
        for (q in 1 until n) if (dp[K - 1][q] > dp[K - 1][j]) j = q
        val path = IntArray(K)
        path[K - 1] = j
        for (k in K - 1 downTo 1) { j = bp[k][j]; path[k - 1] = j }
        return path
    }

    // ---------------------------------------------------------------- kết hợp cũ + mới
    private fun combLine(sd: SideData, lam: Double, s: Double): Pair<V, V> {
        val ops = Array(K) { val t = 0.06 + 0.88 * it / (K - 1); V(sd.a.x + (sd.b.x - sd.a.x) * t, sd.a.y + (sd.b.y - sd.a.y) * t) }
        var idx = (0 until K).filter { sd.keep[it] }
        var mu = V(0.0, 0.0)
        var d = V(1.0, 0.0)
        for (iter in 0 until 3) {
            if (idx.isEmpty()) break
            var cm = 0.0
            for (q in idx) cm += sd.conf[q]
            cm = max(cm / idx.size, 1e-6)
            val px = DoubleArray(K + idx.size); val py = DoubleArray(K + idx.size); val w = DoubleArray(K + idx.size)
            for (q in 0 until K) { px[q] = ops[q].x; py[q] = ops[q].y; w[q] = lam / K }
            for ((m, q) in idx.withIndex()) {
                px[K + m] = sd.pts[q].x; py[K + m] = sd.pts[q].y
                w[K + m] = (1 - lam) / idx.size * (sd.conf[q] / cm)
            }
            val sw = w.sum()
            var mx = 0.0; var my = 0.0
            for (q in w.indices) { mx += px[q] * w[q]; my += py[q] * w[q] }
            mx /= sw; my /= sw
            var sxx = 0.0; var sxy = 0.0; var syy = 0.0
            for (q in w.indices) {
                val dx = px[q] - mx; val dy = py[q] - my
                sxx += w[q] * dx * dx; sxy += w[q] * dx * dy; syy += w[q] * dy * dy
            }
            val th = 0.5 * atan2(2 * sxy, sxx - syy)
            mu = V(mx, my); d = V(cos(th), sin(th))
            val n = V(d.y, -d.x)
            val r = DoubleArray(K) { abs((sd.pts[it].x - mu.x) * n.x + (sd.pts[it].y - mu.y) * n.y) }
            val medR = idx.map { r[it] }.sorted().let { it[it.size / 2] }
            val thr = max(0.4 * s, 2.5 * 1.48 * medR)
            val nid = (0 until K).filter { sd.keep[it] && r[it] < thr }
            if (nid.size < 6) break
            idx = nid
        }
        return mu to d
    }

    // ---------------------------------------------------------------- tiện ích
    private fun odd(v: Double): Int { var k = v.toInt(); if (k % 2 == 0) k++; return k }
    private fun dist(a: V, b: V) = hypot(a.x - b.x, a.y - b.y)

    private fun bil(a: FloatArray, w: Int, h: Int, x: Double, y: Double): Double {
        val xc = x.coerceIn(0.0, w - 1.0); val yc = y.coerceIn(0.0, h - 1.0)
        val x0 = xc.toInt().coerceAtMost(w - 2); val y0 = yc.toInt().coerceAtMost(h - 2)
        val fx = xc - x0; val fy = yc - y0
        val v00 = a[y0 * w + x0]; val v10 = a[y0 * w + x0 + 1]; val v01 = a[(y0 + 1) * w + x0]; val v11 = a[(y0 + 1) * w + x0 + 1]
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy
    }

    private fun inter(p: V, d: V, q: V, e: V): V? {
        val det = d.x * (-e.y) - (-e.x) * d.y
        if (abs(det) < 1e-9) return null
        val rx = q.x - p.x; val ry = q.y - p.y
        val t = (rx * (-e.y) - (-e.x) * ry) / det
        return V(p.x + d.x * t, p.y + d.y * t)
    }

    private fun area(q: Array<V>): Double {
        var s = 0.0
        for (i in 0 until 4) { val a = q[i]; val b = q[(i + 1) % 4]; s += a.x * b.y - b.x * a.y }
        return abs(s) / 2
    }

    private fun convex(q: Array<V>): Boolean {
        var pos = 0; var neg = 0
        for (i in 0 until 4) {
            val a = q[i]; val b = q[(i + 1) % 4]; val c = q[(i + 2) % 4]
            val cr = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cr > 0) pos++ else if (cr < 0) neg++
        }
        return pos == 4 || neg == 4
    }
}
