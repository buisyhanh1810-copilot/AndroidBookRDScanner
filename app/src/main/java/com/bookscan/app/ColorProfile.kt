package com.bookscan.app

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hồ sơ màu của một bối cảnh chụp (giấy / nền / mực) trong không gian (L, a+128, b+128) của OpenCV.
 * - Tự động: ước lượng ngay từ ảnh đang xử lý (giấy = vùng giữa ảnh, nền = viền ngoài).
 * - Nâng cao: người dùng chọn vùng nền / giấy / chữ trên một ảnh mẫu, lưu lại và dùng cho cả loạt ảnh.
 * Hồ sơ chỉ làm thêm một tín hiệu "xác suất là giấy" cho bước dò mép, không thay thế các tín hiệu cũ.
 */
class ColorProfile(
    val paperMean: DoubleArray,
    val paperSd: DoubleArray,
    val bgMean: List<DoubleArray>,
    val bgSd: List<DoubleArray>,
    val inkRatio: Double          // độ sáng chữ / độ sáng giấy (0..1); <=0 nếu không có mẫu chữ
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("pm", JSONArray(paperMean.toList()))
        o.put("ps", JSONArray(paperSd.toList()))
        o.put("bm", JSONArray(bgMean.map { JSONArray(it.toList()) }))
        o.put("bs", JSONArray(bgSd.map { JSONArray(it.toList()) }))
        o.put("ink", inkRatio)
        return o.toString()
    }

    companion object {
        private fun arr(a: JSONArray): DoubleArray = DoubleArray(a.length()) { a.getDouble(it) }

        fun fromJson(s: String?): ColorProfile? {
            if (s.isNullOrEmpty()) return null
            return try {
                val o = JSONObject(s)
                val bm = o.getJSONArray("bm"); val bs = o.getJSONArray("bs")
                ColorProfile(
                    arr(o.getJSONArray("pm")), arr(o.getJSONArray("ps")),
                    (0 until bm.length()).map { arr(bm.getJSONArray(it)) },
                    (0 until bs.length()).map { arr(bs.getJSONArray(it)) },
                    o.optDouble("ink", 0.0)
                )
            } catch (e: Exception) { null }
        }

        private val PAPER_FLOOR = doubleArrayOf(4.0, 3.0, 3.0)
        private val BG_FLOOR = doubleArrayOf(5.0, 4.0, 4.0)

        private fun median(v: DoubleArray): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sortedArray()
            return s[s.size / 2]
        }

        private fun meanSd(pts: List<DoubleArray>, floor: DoubleArray): Pair<DoubleArray, DoubleArray> {
            val m = DoubleArray(3); val sd = DoubleArray(3)
            for (c in 0 until 3) m[c] = median(DoubleArray(pts.size) { pts[it][c] })
            for (c in 0 until 3) {
                var s = 0.0
                var avg = 0.0
                for (p in pts) avg += p[c]
                avg /= max(1, pts.size)
                for (p in pts) s += (p[c] - avg) * (p[c] - avg)
                sd[c] = max(floor[c], sqrt(s / max(1, pts.size)))
            }
            return m to sd
        }

        /** k-means đơn giản (k<=3) khởi tạo xa nhất, trả về các cụm (trung bình, độ lệch) có >=30 mẫu. */
        private fun clusters(pts: List<DoubleArray>, k: Int): List<Pair<DoubleArray, DoubleArray>> {
            if (pts.size < 60) return emptyList()
            val cen = ArrayList<DoubleArray>()
            cen.add(pts[0].copyOf())
            while (cen.size < k) {
                var best = pts[0]; var bd = -1.0
                for (p in pts) {
                    var dmin = Double.MAX_VALUE
                    for (c in cen) { val d = dist2(p, c); if (d < dmin) dmin = d }
                    if (dmin > bd) { bd = dmin; best = p }
                }
                cen.add(best.copyOf())
            }
            val asg = IntArray(pts.size)
            for (iter in 0 until 12) {
                for (i in pts.indices) {
                    var bi = 0; var bd = Double.MAX_VALUE
                    for (j in cen.indices) { val d = dist2(pts[i], cen[j]); if (d < bd) { bd = d; bi = j } }
                    asg[i] = bi
                }
                for (j in cen.indices) {
                    val s = DoubleArray(3); var n = 0
                    for (i in pts.indices) if (asg[i] == j) { for (c in 0 until 3) s[c] += pts[i][c]; n++ }
                    if (n > 0) for (c in 0 until 3) cen[j][c] = s[c] / n
                }
            }
            val out = ArrayList<Pair<DoubleArray, DoubleArray>>()
            for (j in cen.indices) {
                val mem = ArrayList<DoubleArray>()
                for (i in pts.indices) if (asg[i] == j) mem.add(pts[i])
                if (mem.size < 30) continue
                val sd = DoubleArray(3)
                for (c in 0 until 3) {
                    var s = 0.0
                    for (p in mem) s += (p[c] - cen[j][c]) * (p[c] - cen[j][c])
                    sd[c] = max(BG_FLOOR[c], sqrt(s / mem.size))
                }
                out.add(cen[j].copyOf() to sd)
            }
            return out
        }

        private fun dist2(a: DoubleArray, b: DoubleArray): Double {
            var s = 0.0
            for (c in 0 until 3) { val d = a[c] - b[c]; s += d * d }
            return s
        }

        private fun build(paper: List<DoubleArray>, bg: List<DoubleArray>, ink: List<DoubleArray>): ColorProfile? {
            if (paper.size < 30) return null
            // giấy: chỉ lấy 40% điểm sáng nhất để loại chữ lẫn trong vùng chọn
            val sorted = paper.sortedByDescending { it[0] }
            val sel = sorted.take(max(30, (sorted.size * 0.4).toInt()))
            val (pm, ps) = meanSd(sel, PAPER_FLOOR)
            val cl = clusters(bg, 3)
            var ratio = 0.0
            if (ink.size >= 30) {
                val l = DoubleArray(ink.size) { ink[it][0] }.sortedArray()
                val dark = l[(l.size * 0.15).toInt()]            // mực = nhóm tối nhất của vùng chữ
                ratio = (dark / max(1.0, pm[0])).coerceIn(0.05, 0.95)
            }
            return ColorProfile(pm, ps, cl.map { it.first }, cl.map { it.second }, ratio)
        }

        /** Nâng cao: từ các điểm ảnh người dùng chọn (mỗi điểm = [L, a+128, b+128]). */
        fun fromSamples(paper: List<DoubleArray>, bg: List<DoubleArray>, ink: List<DoubleArray>): ColorProfile? =
            build(paper, bg, ink)

        /** Tự động: ls/a/b là các mảng w*h của ảnh thu nhỏ (a, b đã trừ 128). */
        fun auto(ls: FloatArray, a: FloatArray, b: FloatArray, w: Int, h: Int): ColorProfile? {
            val cx0 = (0.3 * w).toInt(); val cx1 = (0.7 * w).toInt()
            val cy0 = (0.3 * h).toInt(); val cy1 = (0.7 * h).toInt()
            val paper = ArrayList<DoubleArray>()
            for (y in cy0 until cy1 step 2) for (x in cx0 until cx1 step 2) {
                val i = y * w + x
                paper.add(doubleArrayOf(ls[i].toDouble(), a[i] + 128.0, b[i] + 128.0))
            }
            val base = build(paper, emptyList(), emptyList()) ?: return null
            val fw = max(6, (0.04 * max(w, h)).toInt())
            val frame = ArrayList<DoubleArray>()
            var total = 0
            for (y in 0 until h) for (x in 0 until w) {
                if (y >= fw && y < h - fw && x >= fw && x < w - fw) continue
                total++
                if ((x + y) % 2 != 0) continue
                val i = y * w + x
                val p = doubleArrayOf(ls[i].toDouble(), a[i] + 128.0, b[i] + 128.0)
                var d2 = 0.0
                for (c in 0 until 3) { val z = (p[c] - base.paperMean[c]) / base.paperSd[c]; d2 += z * z }
                if (sqrt(d2) > 3.5) frame.add(p)
            }
            if (frame.size * 2 < 0.25 * total) return base      // viền toàn giống giấy: không có mô hình nền
            val cl = clusters(frame, 3)
            return ColorProfile(base.paperMean, base.paperSd, cl.map { it.first }, cl.map { it.second }, 0.0)
        }
    }

    /**
     * Bản đồ "xác suất là giấy" (0..1) cho ảnh w*h; null nếu không có mô hình nền hoặc nền có độ sáng khác giấy nhiều
     * (khi đó tín hiệu độ sáng sẵn có đã đủ tốt, thử nghiệm cho thấy thêm vào không có lợi).
     */
    fun posterior(ls: FloatArray, a: FloatArray, b: FloatArray, w: Int, h: Int): FloatArray? {
        if (bgMean.isEmpty()) return null
        var near = false
        for (m in bgMean) if (abs(m[0] - paperMean[0]) <= 30.0) near = true
        if (!near) return null
        val out = FloatArray(w * h)
        for (i in 0 until w * h) {
            val l = ls[i].toDouble(); val aa = a[i] + 128.0; val bb = b[i] + 128.0
            val zl = (l - paperMean[0]) / paperSd[0]; val za = (aa - paperMean[1]) / paperSd[1]; val zb = (bb - paperMean[2]) / paperSd[2]
            val lp = exp(-0.5 * min(zl * zl + za * za + zb * zb, 60.0))
            var lb = 0.0
            for (k in bgMean.indices) {
                val m = bgMean[k]; val sd = bgSd[k]
                val yl = (l - m[0]) / sd[0]; val ya = (aa - m[1]) / sd[1]; val yb = (bb - m[2]) / sd[2]
                lb = max(lb, exp(-0.5 * min(yl * yl + ya * ya + yb * yb, 60.0)))
            }
            out[i] = (lp / (lp + lb + 1e-6)).toFloat()
        }
        return out
    }
}
