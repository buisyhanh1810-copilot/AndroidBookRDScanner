package com.bookscan.app

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Xử lý ảnh chụp MỘT trang (tab "Một trang"). Khác tab hai trang ở chỗ:
 *  - không có gáy giữa ảnh: khung giấy dò theo khổ MỘT trang (lề trái/phải như nhau);
 *  - cạnh gáy (nếu người dùng chọn) coi là cạnh trong, không bắt dính; còn lại cả 3 hoặc 4 cạnh được dò lại;
 *  - kiểm tra khổ giấy từ khung dò được (cảnh báo khi lệch > 10%);
 *  - nắn dòng chữ: dò hàng chữ thẳng, chỉnh nghiêng và độ xòe theo chiều dọc (trang cong gần gáy).
 * Kích thước trang truyền qua [ScanOptions.spreadWcm] / [ScanOptions.spreadHcm].
 *
 * Chưa kiểm thử trên điện thoại thật; thuật toán dò hàng/nắn dọc đã thử bằng prototype Python.
 */
object SinglePageScanner {

    const val GUTTER_LEFT = 0
    const val GUTTER_RIGHT = 1
    const val GUTTER_NONE = 2

    fun process(img: Mat, opt: ScanOptions): ScanResult {
        val pwCm = opt.spreadWcm
        val phCm = opt.spreadHcm
        val notes = ArrayList<String>()

        var q = try { EdgeDetector.detectPageQuad(img, pwCm, phCm) } catch (e: Exception) { null }
        if (q == null) {
            q = try { BookScanner.detectQuad(img) } catch (e: Exception) { null }
            if (q != null) notes.add("Không thấy chữ, lấy vùng giấy sáng làm khung. Nên kiểm tra.")
        }
        if (q == null) throw IllegalStateException("Không thấy trang trong ảnh")

        val ar = aspectError(q, img.cols(), img.rows(), pwCm / phCm)
        if (ar > 0.10) notes.add("Khung lệch khổ giấy ${Math.round(ar * 100)}%: có thể sai khung hoặc chụp nghiêng.")

        // px/cm: theo độ phân giải thật của ảnh, tối đa 300 dpi
        val pxW = (hypot(q[1].x - q[0].x, q[1].y - q[0].y) + hypot(q[2].x - q[3].x, q[2].y - q[3].y)) / 2
        val pxH = (hypot(q[3].x - q[0].x, q[3].y - q[0].y) + hypot(q[2].x - q[1].x, q[2].y - q[1].y)) / 2
        val ppc = min(sqrt((pxW / pwCm) * (pxH / phCm)), 300.0 / 2.54).coerceAtLeast(20.0)
        val pw = Math.round(pwCm * ppc).toInt()
        val h = Math.round(phCm * ppc).toInt()

        // BƯỚC 1: cắt thô trang KÈM NỀN (nắn gần đúng), chừa 1,5 cm quanh trang để dò lại mép
        val m = Math.round(1.5 * ppc).toInt()
        val dstIn = MatOfPoint2f(
            Point(m.toDouble(), m.toDouble()), Point(m + pw - 1.0, m.toDouble()),
            Point(m + pw - 1.0, m + h - 1.0), Point(m.toDouble(), m + h - 1.0)
        )
        val srcQ = MatOfPoint2f(*q)
        val mm = Imgproc.getPerspectiveTransform(srcQ, dstIn)
        val crop = Mat()
        Imgproc.warpPerspective(img, crop, mm, Size((pw + 2 * m).toDouble(), (h + 2 * m).toDouble()),
            Imgproc.INTER_LINEAR, org.opencv.core.Core.BORDER_REPLICATE)

        // BƯỚC 2: dò lại mép giấy; cạnh gáy không dò (0=trên,1=phải,2=dưới,3=trái)
        val outer = when (opt.gutterSide) {
            GUTTER_LEFT -> setOf(0, 1, 2)
            GUTTER_RIGHT -> setOf(0, 2, 3)
            else -> setOf(0, 1, 2, 3)
        }
        var q2 = try {
            EdgeDetector.refinePage(crop, doubleArrayOf(m.toDouble(), m.toDouble(), (m + pw).toDouble(), (m + h).toDouble()), ppc, outer)
        } catch (e: Exception) { null }

        // BƯỚC 2b: bám biên chính xác (mép ngoài tự do, cạnh gáy giữ nguyên)
        if (opt.trackEdges && q2 != null) {
            val skip = when (opt.gutterSide) { GUTTER_LEFT -> 3; GUTTER_RIGHT -> 1; else -> null }
            val t = try { EdgeTracker.refineCrop(img, mm, q2, q, pwCm, phCm, skip) } catch (e: Throwable) { null }
            if (t != null) { q2 = t; notes.add("Đã bám biên.") }
        }

        var page: Mat? = null
        if (opt.curve && q2 != null) {
            val curves = try { EdgeWarp.detect(crop, q2, ppc, h) } catch (e: Exception) { null }
            if (curves != null && curves.any()) {
                page = try { EdgeWarp.warp(img, mm.inv(), q2, pw, h, curves) } catch (e: Throwable) { null }
            }
        }
        if (page == null) {
            val finalQ: MatOfPoint2f = if (q2 != null) {
                val back = MatOfPoint2f()
                Core.perspectiveTransform(MatOfPoint2f(*q2), back, mm.inv())
                back
            } else srcQ
            val d2 = MatOfPoint2f(Point(0.0, 0.0), Point(pw - 1.0, 0.0), Point(pw - 1.0, h - 1.0), Point(0.0, h - 1.0))
            val m2 = Imgproc.getPerspectiveTransform(finalQ, d2)
            page = Mat()
            Imgproc.warpPerspective(img, page, m2, Size(pw.toDouble(), h.toDouble()),
                Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        }
        crop.release()

        // BƯỚC 3: nắn theo dòng chữ (nghiêng + xòe dọc). Lỗi hoặc không đủ dòng -> giữ nguyên.
        if (opt.rowFix) {
            val r = try { RowStraightener.correct(page!!, ppc, opt.gutterSide) } catch (e: Throwable) { null }
            if (r != null) {
                if (r.mat !== page) { page!!.release(); page = r.mat }
                notes.add(r.note)
            } else notes.add("Không nắn hàng chữ (ít chữ hoặc đã thẳng).")
        }

        // BƯỚC 4: chữ bị nén ngang gần gáy (sách dày) – chỉ khi biết cạnh gáy và nén đủ rõ
        if (opt.compressFix && opt.gutterSide != GUTTER_NONE) {
            val r = try { HorizontalFlattener.correct(page!!, ppc, opt.gutterSide) } catch (e: Throwable) { null }
            if (r != null) { page!!.release(); page = r.mat; notes.add(r.note) }
        }

        var cleaned = BookScanner.cleanPage(page!!, opt, ppc, cropNow = true)
        if (opt.gutterSide != GUTTER_NONE && opt.gutterMarginCm != 0.0) {
            cleaned = BookScanner.adjustSide(cleaned, right = opt.gutterSide == GUTTER_RIGHT,
                px = Math.round(opt.gutterMarginCm * ppc).toInt())
        }
        return ScanResult(listOf(BookScanner.toBitmap(cleaned)), ppc, notes.joinToString(" "))
    }

    /**
     * Sai lệch tương đối giữa tỉ lệ rộng/cao suy ra từ 4 góc và tỉ lệ khổ giấy, với tiêu cự giả định 0,6 × đường chéo ảnh
     * (điện thoại thường ~26 mm). Công thức Zhang–He cho hình chữ nhật chụp phối cảnh. Không phân biệt A4 với A5.
     */
    fun aspectError(q: Array<Point>, imgW: Int, imgH: Int, expected: Double): Double {
        val f = 0.6 * hypot(imgW.toDouble(), imgH.toDouble())
        val cx = imgW / 2.0
        val cy = imgH / 2.0
        fun v(p: Point) = doubleArrayOf(p.x - cx, p.y - cy, f)
        fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        val m1 = v(q[0]); val m2 = v(q[1]); val m4 = v(q[2]); val m3 = v(q[3])   // tl, tr, bl, br theo Zhang–He
        val k2d = dot(cross(m2, m4), m3)
        val k3d = dot(cross(m3, m4), m2)
        if (abs(k2d) < 1e-9 || abs(k3d) < 1e-9) return 0.0
        val k2 = dot(cross(m1, m4), m3) / k2d
        val k3 = dot(cross(m1, m4), m2) / k3d
        val n2 = doubleArrayOf(k2 * m2[0] - m1[0], k2 * m2[1] - m1[1], k2 * m2[2] - m1[2])
        val n3 = doubleArrayOf(k3 * m3[0] - m1[0], k3 * m3[1] - m1[1], k3 * m3[2] - m1[2])
        val ln2 = sqrt(dot(n2, n2))
        val ln3 = sqrt(dot(n3, n3))
        if (ln3 < 1e-9) return 0.0
        val r = ln2 / ln3
        return abs(r / expected - 1.0)
    }
}

/**
 * Nắn trang theo các hàng chữ (đã nắn phẳng về khổ giấy, px/cm = ppc).
 * Trang dày mở ra: hàng chữ trên/dưới hàng giữa xòe ra theo chiều dọc, mức xòe thay đổi dần theo x (mạnh nhất gần gáy).
 * Cách làm: mặt nạ mực -> góc nghiêng chung bằng độ sắc profile chiếu -> đỉnh = hàng chữ ->
 * với mỗi cửa sổ cột 1,5 cm: đo độ lệch từng hàng so với vị trí ở vùng tham chiếu (xa gáy),
 * hồi quy d = α·(y − ȳ) + β -> làm trơn α(x), β(x) bằng đa thức bậc 3 -> remap nguồn_y = Y + x·tanθ + α(Y − ȳ) + β.
 * Điều kiện thoát: ít hơn 8 hàng, góc nghiêng chạm giới hạn ±3°, phủ dưới nửa chiều rộng, hoặc độ xòe quá nhỏ.
 */
object RowStraightener {
    class Result(val mat: Mat, val note: String)

    private const val WORK_PPC = 30.0        // px/cm khi dò hàng (ảnh thu nhỏ)
    private const val WIN_CM = 1.5

    fun correct(page: Mat, ppc: Double, gutterSide: Int): Result? {
        val sc = min(1.0, WORK_PPC / ppc)
        val wp = ppc * sc                     // px/cm ở ảnh làm việc
        val small = Mat()
        Imgproc.resize(page, small, Size(), sc, sc, Imgproc.INTER_AREA)
        val g = Mat()
        if (small.channels() == 1) small.copyTo(g) else Imgproc.cvtColor(small, g, Imgproc.COLOR_BGR2GRAY)
        small.release()
        val W = g.cols()
        val H = g.rows()
        val ink = inkMask(g, wp)
        g.release()
        val inkB = ByteArray(W * H)
        ink.get(0, 0, inkB)
        ink.release()

        // --- góc nghiêng chung: cực đại độ sắc profile chiếu ---
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        var k = 0
        for (y in 0 until H) for (x in 0 until W) {
            if (inkB[y * W + x].toInt() != 0) { if (k++ % 2 == 0) { xs.add(x); ys.add(y) } }
        }
        if (xs.size < 2000) return null
        val n = xs.size
        var bestT = 0.0
        var bestS = -1.0
        var bestH = DoubleArray(0)
        var tDeg = -3.0
        while (tDeg <= 3.0001) {
            val tn = tan(Math.toRadians(tDeg))
            val hist = DoubleArray(3 * H + 1)
            for (i in 0 until n) {
                val yy = Math.round(ys[i] - xs[i] * tn).toInt() + H
                if (yy in 0..3 * H) hist[yy] += 1.0
            }
            val hs = smooth3(hist)
            var s = 0.0
            for (v in hs) s += v * v
            if (s > bestS) { bestS = s; bestT = tDeg; bestH = hs }
            tDeg += 0.1
        }
        if (abs(bestT) >= 2.95) return null           // chạm giới hạn: không đáng tin
        val tt = tan(Math.toRadians(bestT))

        // --- hàng chữ = đỉnh của profile ---
        val hs = gauss(bestH, 1.5)
        val pos = hs.filter { it > 0 }.sorted()
        if (pos.isEmpty()) return null
        val p95 = pos[(pos.size * 0.95).toInt().coerceAtMost(pos.size - 1)]
        val minDist = (0.35 * wp).toInt().coerceAtLeast(2)
        val cand = (1 until hs.size - 1).filter { hs[it] >= hs[it - 1] && hs[it] > hs[it + 1] && hs[it] > 0.25 * p95 }
            .sortedByDescending { hs[it] }
        val chosen = ArrayList<Int>()
        for (c in cand) if (chosen.none { abs(it - c) < minDist }) chosen.add(c)
        val rows = chosen.sorted().map { (it - H).toDouble() }
        if (rows.size < 8) return null
        val pitch = rows.zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }
        val yMean = rows.average()

        // --- độ lệch từng hàng trong từng cửa sổ cột ---
        val win = (WIN_CM * wp).toInt().coerceAtLeast(8)
        val nw = W / win
        if (nw < 4) return null
        val d = Array(rows.size) { DoubleArray(nw) { Double.NaN } }
        val minPix = (60 * (wp / 50.0) * (wp / 50.0)).toInt().coerceAtLeast(12)
        for (i in rows.indices) {
            val cy = rows[i]
            for (w in 0 until nw) {
                val x0 = w * win
                val x1 = x0 + win
                val yl = cy + (x0 + x1) / 2.0 * tt
                val y0 = (yl - 0.32 * pitch).toInt()
                val y1 = (yl + 0.32 * pitch).toInt() + 1
                if (y0 < 0 || y1 >= H) continue
                val vals = ArrayList<Double>()
                for (y in y0 until y1) for (x in x0 until x1) {
                    if (inkB[y * W + x].toInt() != 0) vals.add(y - x * tt - cy)
                }
                if (vals.size < minPix) continue
                vals.sort()
                d[i][w] = vals[vals.size / 2]
            }
        }
        // tham chiếu: vùng xa gáy (3 cửa sổ đầu/cuối); không gáy -> trung vị cả hàng
        for (i in rows.indices) {
            val refIdx = when (gutterSide) {
                SinglePageScanner.GUTTER_RIGHT -> (0 until 3)
                SinglePageScanner.GUTTER_LEFT -> (nw - 3 until nw)
                else -> (0 until nw)
            }
            val rv = refIdx.map { d[i][it] }.filter { !it.isNaN() }.sorted()
            if (rv.isEmpty()) { for (w in 0 until nw) d[i][w] = Double.NaN; continue }
            val r = rv[rv.size / 2]
            for (w in 0 until nw) if (!d[i][w].isNaN()) d[i][w] -= r
        }

        // --- hồi quy α, β cho từng cửa sổ ---
        val xc = DoubleArray(nw) { (it + 0.5) * win }
        val al = DoubleArray(nw) { Double.NaN }
        val be = DoubleArray(nw) { Double.NaN }
        for (w in 0 until nw) {
            val idx = rows.indices.filter { !d[it][w].isNaN() }
            if (idx.size < 8) continue
            val a = DoubleArray(idx.size) { rows[idx[it]] - yMean }
            val y = DoubleArray(idx.size) { d[idx[it]][w] }
            var ok = BooleanArray(idx.size) { true }
            var p = doubleArrayOf(0.0, 0.0)
            for (iter in 0 until 3) {
                val f = linFit(a, y, ok) ?: break
                p = f
                val r = DoubleArray(idx.size) { y[it] - (p[0] * a[it] + p[1]) }
                val rr = r.filterIndexed { i, _ -> ok[i] }.map { abs(it) }.sorted()
                val s = 1.4826 * rr[rr.size / 2] + 0.3 * wp / 50.0 * 1.0
                val nok = BooleanArray(idx.size) { abs(r[it]) < 2.5 * s }
                if (nok.count { it } < 6) break
                ok = nok
            }
            al[w] = p[0]; be[w] = p[1]
        }
        val valid = (0 until nw).filter { !al[it].isNaN() }
        if (valid.size < 4 || valid.size < nw / 2) return null

        // --- làm trơn bậc 3 theo x, giữ hằng ngoài vùng có dữ liệu ---
        val vx = DoubleArray(valid.size) { xc[valid[it]] }
        val ca = polyFit3(vx, DoubleArray(valid.size) { al[valid[it]] }, W.toDouble()) ?: return null
        val cb = polyFit3(vx, DoubleArray(valid.size) { be[valid[it]] }, W.toDouble()) ?: return null
        val lo = vx.min()
        val hi = vx.max()
        val fa = DoubleArray(W)
        val fb = DoubleArray(W)
        var maxA = 0.0
        var maxB = 0.0
        for (x in 0 until W) {
            val xcl = x.toDouble().coerceIn(lo, hi)
            fa[x] = evalPoly3(ca, xcl, W.toDouble()).coerceIn(-0.10, 0.10)
            fb[x] = evalPoly3(cb, xcl, W.toDouble()).coerceIn(-0.6 * wp, 0.6 * wp)
            maxA = max(maxA, abs(fa[x])); maxB = max(maxB, abs(fb[x]))
        }
        // độ xòe quá nhỏ (< 0,2 mm lệch cho hàng xa nhất) và không nghiêng đáng kể -> khỏi chỉnh
        val spanCm = (rows.max() - rows.min()) / wp
        val worstMm = (maxA * spanCm / 2 + maxB / wp) * 10.0
        if (worstMm < 0.8 && abs(bestT) < 0.15) return null

        // --- remap toàn độ phân giải ---
        val PW = page.cols()
        val PH = page.rows()
        val mapX = Mat(PH, PW, CvType.CV_32F)
        val mapY = Mat(PH, PW, CvType.CV_32F)
        val rowX = FloatArray(PW) { it.toFloat() }
        val rowY = FloatArray(PW)
        val inv = 1.0 / sc
        for (Y in 0 until PH) {
            val ys2 = Y * sc
            for (X in 0 until PW) {
                val xsm = (X * sc).coerceIn(0.0, W - 1.0)
                val xi = xsm.toInt()
                val a = fa[xi]
                val b = fb[xi]
                val srcYs = ys2 + xsm * tt + a * (ys2 - yMean) + b
                rowY[X] = (srcYs * inv).toFloat()
            }
            mapX.put(Y, 0, rowX)
            mapY.put(Y, 0, rowY)
        }
        val out = Mat()
        Imgproc.remap(page, out, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        mapX.release(); mapY.release()
        val note = "Nắn chữ: nghiêng %.2f°, %d hàng, lệch ≤ %.1f mm.".format(bestT, rows.size, worstMm)
        return Result(out, note)
    }

    // ---------- tiện ích ----------
    private fun inkMask(g: Mat, wp: Double): Mat {
        val gf = Mat()
        g.convertTo(gf, CvType.CV_32F)
        val k1 = (0.8 * wp).toInt().coerceAtLeast(3)
        val bg = Mat()
        Imgproc.dilate(gf, bg, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k1.toDouble(), k1.toDouble())))
        Imgproc.GaussianBlur(bg, bg, Size(), 0.8 * wp)
        val nrm = Mat()
        Core.add(bg, org.opencv.core.Scalar(1e-3), bg)
        Core.divide(gf, bg, nrm)
        gf.release(); bg.release()
        val k2 = ((1.2 * wp).toInt() or 1).toDouble()
        val loc = Mat()
        Imgproc.blur(nrm, loc, Size(k2, k2))
        Core.multiply(loc, org.opencv.core.Scalar(0.9), loc)
        val thr = Mat()
        Core.min(loc, org.opencv.core.Scalar(0.86), thr)
        val ink = Mat()
        Core.compare(nrm, thr, ink, Core.CMP_LT)       // 255 ở nơi có mực
        nrm.release(); loc.release(); thr.release()
        val m = (0.5 * wp).toInt()
        if (m > 0 && ink.rows() > 2 * m && ink.cols() > 2 * m) {
            val z = org.opencv.core.Scalar(0.0)
            ink.submat(0, m, 0, ink.cols()).setTo(z)
            ink.submat(ink.rows() - m, ink.rows(), 0, ink.cols()).setTo(z)
            ink.submat(0, ink.rows(), 0, m).setTo(z)
            ink.submat(0, ink.rows(), ink.cols() - m, ink.cols()).setTo(z)
        }
        return ink
    }

    private fun smooth3(a: DoubleArray): DoubleArray {
        val o = DoubleArray(a.size)
        for (i in a.indices) {
            var s = 0.0
            for (j in -1..1) { val k = i + j; if (k in a.indices) s += a[k] }
            o[i] = s / 3.0
        }
        return o
    }

    private fun gauss(a: DoubleArray, sigma: Double): DoubleArray {
        val r = (3 * sigma).toInt().coerceAtLeast(1)
        val w = DoubleArray(2 * r + 1) { Math.exp(-0.5 * (it - r) * (it - r) / (sigma * sigma)) }
        val sw = w.sum()
        val o = DoubleArray(a.size)
        for (i in a.indices) {
            var s = 0.0
            for (j in -r..r) { val k = i + j; if (k in a.indices) s += a[k] * w[j + r] }
            o[i] = s / sw
        }
        return o
    }

    /** y = p0·a + p1 trên các mẫu có ok */
    private fun linFit(a: DoubleArray, y: DoubleArray, ok: BooleanArray): DoubleArray? {
        var n = 0.0; var sa = 0.0; var sy = 0.0; var saa = 0.0; var say = 0.0
        for (i in a.indices) if (ok[i]) { n++; sa += a[i]; sy += y[i]; saa += a[i] * a[i]; say += a[i] * y[i] }
        val den = n * saa - sa * sa
        if (n < 3 || abs(den) < 1e-9) return null
        val p0 = (n * say - sa * sy) / den
        return doubleArrayOf(p0, (sy - p0 * sa) / n)
    }

    private fun polyFit3(x: DoubleArray, y: DoubleArray, scale: Double): DoubleArray? {
        val deg = 3
        val m = Array(deg + 1) { DoubleArray(deg + 2) }
        for (i in x.indices) {
            val t = x[i] / scale * 2 - 1
            val pw = DoubleArray(deg + 1) { Math.pow(t, it.toDouble()) }
            for (r in 0..deg) {
                for (c in 0..deg) m[r][c] += pw[r] * pw[c]
                m[r][deg + 1] += pw[r] * y[i]
            }
        }
        for (r in 0..deg) m[r][r] += 1e-6     // chống suy biến
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

    private fun evalPoly3(c: DoubleArray, x: Double, scale: Double): Double {
        val t = x / scale * 2 - 1
        return c[0] + t * (c[1] + t * (c[2] + t * c[3]))
    }
}
