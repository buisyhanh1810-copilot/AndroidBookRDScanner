package com.bookscan.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Chế độ Nâng cao: người dùng mở ảnh mẫu của đúng bối cảnh chụp (cùng nền, ánh sáng), khoanh vài vùng
 * NỀN, GIẤY, CHỮ rồi lưu hồ sơ màu. Hồ sơ được dùng cho cả loạt ảnh chụp trong bối cảnh đó.
 */
class CalibrateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FIRST = "first"          // uri ảnh đầu hàng đợi (tuỳ chọn)
        const val PREF_PROFILE = "profile"
    }

    private lateinit var picker: RegionPickerView
    private lateinit var tvHelp: TextView
    private lateinit var rbBg: RadioButton
    private lateinit var rbPaper: RadioButton
    private lateinit var rbInk: RadioButton
    private lateinit var btnSave: Button

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) load(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibrate)
        picker = findViewById(R.id.picker)
        tvHelp = findViewById(R.id.tvHelp)
        rbBg = findViewById(R.id.rbBg)
        rbPaper = findViewById(R.id.rbPaper)
        rbInk = findViewById(R.id.rbInk)
        btnSave = findViewById(R.id.btnSave)
        val btnOpen: Button = findViewById(R.id.btnOpen)
        val btnFirst: Button = findViewById(R.id.btnFirst)
        val btnClear: Button = findViewById(R.id.btnClearRegions)

        rbPaper.isChecked = true
        picker.type = 1
        rbBg.setOnClickListener { picker.type = 0 }
        rbPaper.setOnClickListener { picker.type = 1 }
        rbInk.setOnClickListener { picker.type = 2 }
        picker.onChange = { refresh() }
        btnOpen.setOnClickListener { pick.launch("image/*") }
        val first = intent.getStringExtra(EXTRA_FIRST)
        btnFirst.isEnabled = first != null
        btnFirst.setOnClickListener { if (first != null) load(Uri.parse(first)) }
        btnClear.setOnClickListener { picker.clearAll() }
        btnSave.setOnClickListener { save() }
        refresh()
        if (first != null) load(Uri.parse(first))
    }

    private fun load(uri: Uri) {
        lifecycleScope.launch {
            val b = try {
                withContext(Dispatchers.IO) { Exporter.decode(this@CalibrateActivity, uri, 1600) }
            } catch (e: Throwable) { null }
            if (b == null) Toast.makeText(this@CalibrateActivity, "Không mở được ảnh", Toast.LENGTH_LONG).show()
            else picker.setImage(b)
        }
    }

    private fun refresh() {
        tvHelp.text = "Kéo ngón tay khoanh vùng: Nền (xanh dương), Giấy (xanh lá), Chữ (đỏ, tuỳ chọn). " +
            "Đã chọn: nền ${picker.count(0)}, giấy ${picker.count(1)}, chữ ${picker.count(2)} (mỗi loại tối đa ${RegionPickerView.MAX_PER_TYPE} vùng)."
        btnSave.isEnabled = picker.image() != null && picker.count(0) > 0 && picker.count(1) > 0
    }

    /** Lấy mẫu điểm ảnh (L, a, b) trong các vùng đã chọn. */
    private fun sample(lab: Mat, t: Int, scale: Double, maxPts: Int): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var total = 0L
        val rects = picker.rects(t)
        for (r in rects) total += (r.width() * scale * r.height() * scale).toLong()
        val step = Math.max(1, Math.sqrt(total.toDouble() / maxPts).toInt())
        val px = ByteArray(3)
        for (r in rects) {
            val x0 = (r.left * scale).toInt().coerceIn(0, lab.cols() - 1)
            val x1 = (r.right * scale).toInt().coerceIn(0, lab.cols())
            val y0 = (r.top * scale).toInt().coerceIn(0, lab.rows() - 1)
            val y1 = (r.bottom * scale).toInt().coerceIn(0, lab.rows())
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    lab.get(y, x, px)
                    out.add(doubleArrayOf((px[0].toInt() and 0xFF).toDouble(), (px[1].toInt() and 0xFF).toDouble(), (px[2].toInt() and 0xFF).toDouble()))
                    x += step
                }
                y += step
            }
        }
        return out
    }

    private fun save() {
        val bmp: Bitmap = picker.image() ?: return
        val scale = Math.min(1.0, 1400.0 / Math.max(bmp.width, bmp.height))
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        if (scale < 1.0) Imgproc.resize(bgr, bgr, Size(), scale, scale, Imgproc.INTER_AREA)
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val prof = ColorProfile.fromSamples(
            sample(lab, 1, scale, 6000), sample(lab, 0, scale, 6000), sample(lab, 2, scale, 4000)
        )
        rgba.release(); bgr.release(); lab.release()
        if (prof == null) {
            Toast.makeText(this, "Vùng giấy quá nhỏ – hãy khoanh vùng lớn hơn", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences("scan", MODE_PRIVATE).edit().putString(PREF_PROFILE, prof.toJson()).apply()
        Toast.makeText(this, "Đã lưu hồ sơ nền/giấy" + (if (prof.inkRatio > 0) "/chữ" else ""), Toast.LENGTH_LONG).show()
        setResult(Activity.RESULT_OK, Intent())
        finish()
    }
}
