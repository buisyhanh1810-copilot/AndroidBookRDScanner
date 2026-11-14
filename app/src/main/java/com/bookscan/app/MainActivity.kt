package com.bookscan.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var spSplit: Spinner
    private lateinit var cbCrop: CheckBox
    private lateinit var cbContent: CheckBox
    private lateinit var cbDeskew: CheckBox
    private lateinit var cbMerge: CheckBox
    private lateinit var spOutput: Spinner
    private lateinit var spRotate: Spinner
    private lateinit var cbCurve: CheckBox
    private lateinit var cbTrack: CheckBox
    private lateinit var spCalib: Spinner
    private lateinit var btnCalib: Button
    private lateinit var tvCalib: TextView
    private var profile: ColorProfile? = null
    private lateinit var tvVersion: TextView
    private lateinit var sbInk: SeekBar
    private lateinit var tvInk: TextView
    private lateinit var inkBox: View
    private lateinit var spPaper: Spinner
    private lateinit var etPageW: EditText
    private lateinit var etPageH: EditText
    private lateinit var tvSpread: TextView
    private var settingPreset = false
    private lateinit var tabs: TabLayout
    private lateinit var tvTitle: TextView
    private lateinit var splitBox: View
    private lateinit var singleBox: View
    private lateinit var spGutter: Spinner
    private lateinit var cbRowFix: CheckBox
    private var singleMode = false          // false: tab "Hai trang", true: tab "Một trang"
    private var lastNote: String? = null
    private lateinit var mergeBox: View
    private lateinit var cbHFix: CheckBox
    private lateinit var etMergeMargin: EditText
    private lateinit var etGutterMargin: EditText

    // Khổ giấy chuẩn: tên, rộng, cao (cm) của MỘT trang. Mục cuối là tuỳ chỉnh.
    private val papers = listOf(
        Triple("A4", 21.0, 29.7), Triple("A5", 14.8, 21.0), Triple("A3", 29.7, 42.0),
        Triple("B5 (ISO)", 17.6, 25.0), Triple("B4 (ISO)", 25.0, 35.3),
        Triple("Letter", 21.59, 27.94), Triple("Legal", 21.59, 35.56),
        Triple("Tuỳ chỉnh", 0.0, 0.0)
    )
    private lateinit var etMargin: EditText
    private lateinit var etDelay: EditText
    private lateinit var etInterval: EditText
    private lateinit var etCount: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvQueue: TextView
    private lateinit var progress: ProgressBar
    private lateinit var results: LinearLayout
    private lateinit var btnCamera: Button
    private lateinit var btnPick: Button
    private lateinit var btnProcess: Button
    private lateinit var btnClear: Button
    private lateinit var btnPdf: Button

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private var counter = 0
    private val queue = mutableListOf<Uri>()      // ảnh gốc chờ xử lý
    private val saved = mutableListOf<Pair<Uri, Double>>()   // trang đã lưu + px/cm
    private var busy = false

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) addToQueue(uris)
        }

    // Chụp tự động định thời: CaptureActivity trả về danh sách ảnh đã chụp.
    private val autoCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val list = r.data?.getStringArrayListExtra(CaptureActivity.RESULT_URIS).orEmpty()
            if (list.isNotEmpty()) {
                addToQueue(list.map { Uri.parse(it) })
                tvStatus.text = "Đã chụp ${list.size} ảnh, đang ở hàng đợi – bấm Xử lý."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "Không nạp được OpenCV", Toast.LENGTH_LONG).show()
        }

        tabs = findViewById(R.id.tabs)
        tvTitle = findViewById(R.id.tvTitle)
        splitBox = findViewById(R.id.splitBox)
        singleBox = findViewById(R.id.singleBox)
        spGutter = findViewById(R.id.spGutter)
        mergeBox = findViewById(R.id.mergeBox)
        cbHFix = findViewById(R.id.cbHFix)
        etMergeMargin = findViewById(R.id.etMergeMargin)
        etGutterMargin = findViewById(R.id.etGutterMargin)
        cbRowFix = findViewById(R.id.cbRowFix)
        spSplit = findViewById(R.id.spSplit)
        cbCrop = findViewById(R.id.cbCrop)
        cbContent = findViewById(R.id.cbContent)
        cbDeskew = findViewById(R.id.cbDeskew)
        cbMerge = findViewById(R.id.cbMerge)
        spOutput = findViewById(R.id.spOutput)
        spRotate = findViewById(R.id.spRotate)
        cbCurve = findViewById(R.id.cbCurve)
        cbTrack = findViewById(R.id.cbTrack)
        spCalib = findViewById(R.id.spCalib)
        btnCalib = findViewById(R.id.btnCalib)
        tvCalib = findViewById(R.id.tvCalib)
        tvVersion = findViewById(R.id.tvVersion)
        sbInk = findViewById(R.id.sbInk)
        tvInk = findViewById(R.id.tvInk)
        inkBox = findViewById(R.id.inkBox)
        spPaper = findViewById(R.id.spPaper)
        etPageW = findViewById(R.id.etPageW)
        etPageH = findViewById(R.id.etPageH)
        tvSpread = findViewById(R.id.tvSpread)
        etMargin = findViewById(R.id.etMargin)
        etDelay = findViewById(R.id.etDelay)
        etInterval = findViewById(R.id.etInterval)
        etCount = findViewById(R.id.etCount)
        tvStatus = findViewById(R.id.tvStatus)
        tvQueue = findViewById(R.id.tvQueue)
        progress = findViewById(R.id.progress)
        results = findViewById(R.id.results)
        btnCamera = findViewById(R.id.btnCamera)
        btnPick = findViewById(R.id.btnPick)
        btnProcess = findViewById(R.id.btnProcess)
        btnClear = findViewById(R.id.btnClear)
        btnPdf = findViewById(R.id.btnPdf)

        spSplit.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Tự động", "Luôn tách đôi", "Không tách (1 trang)")
        )

        spRotate.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Không xoay", "Xoay 180° (ảnh bị ngược)", "Xoay 90° sang phải", "Xoay 90° sang trái")
        )
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        tvVersion.text = "Phiên bản $ver"
        spOutput.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Nguyên gốc (ảnh màu)", "Đen trắng")
        )
        val pf = getSharedPreferences("scan", MODE_PRIVATE)
        spGutter.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Gáy ở bên trái trang", "Gáy ở bên phải trang", "Không có gáy (tờ rời)")
        )
        spGutter.setSelection(pf.getInt("gs", 2).coerceIn(0, 2))
        pf.getString("mm", null)?.let { etMergeMargin.setText(it) }
        pf.getString("gm", null)?.let { etGutterMargin.setText(it) }
        cbMerge.setOnCheckedChangeListener { _, _ -> applyMode() }
        cbRowFix.isChecked = pf.getBoolean("rf", true)
        cbHFix.isChecked = pf.getBoolean("hf", true)
        spRotate.setSelection(pf.getInt("rt", 0).coerceIn(0, 3))
        cbCurve.isChecked = pf.getBoolean("cv", true)
        cbTrack.isChecked = pf.getBoolean("tk", true)
        cbContent.isChecked = pf.getBoolean("cc", false)
        spCalib.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Tự động (mặc định)", "Nâng cao (chọn ảnh mẫu)")
        )
        spCalib.setSelection(pf.getInt("cb", 0).coerceIn(0, 1))
        spCalib.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshCalib()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        btnCalib.setOnClickListener {
            val i = Intent(this, CalibrateActivity::class.java)
            if (queue.isNotEmpty()) i.putExtra(CalibrateActivity.EXTRA_FIRST, queue[0].toString())
            calibLauncher.launch(i)
        }
        loadProfile()
        spOutput.setSelection(pf.getInt("ot", 1).coerceIn(0, 1))
        sbInk.progress = pf.getInt("ink", 5).coerceIn(0, 30)
        fun refreshInk() {
            tvInk.text = "Độ đậm đen trắng: +${sbInk.progress}%  (cao hơn = chữ đậm hơn, thấp hơn = nhạt hơn)"
            inkBox.visibility = if (spOutput.selectedItemPosition == 1) View.VISIBLE else View.GONE
        }
        spOutput.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = refreshInk()
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        sbInk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) = refreshInk()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        refreshInk()

        btnCamera.setOnClickListener { startAutoCapture() }
        btnPick.setOnClickListener { pickImages.launch("image/*") }
        btnProcess.setOnClickListener { processQueue() }
        btnClear.setOnClickListener { queue.clear(); updateQueueUi() }
        btnPdf.setOnClickListener { exportPdf() }
        val pref = getSharedPreferences("scan", MODE_PRIVATE)
        spPaper.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            papers.map { (n, w, h) -> if (w > 0) "$n  –  ${fmt(w)} × ${fmt(h)} cm" else n }
        )
        // Mặc định A4; nếu đã lưu thì khôi phục (kể cả giá trị người dùng tự sửa)
        val idx = pref.getInt("pp", 0).coerceIn(0, papers.size - 1)
        settingPreset = true
        spPaper.setSelection(idx)
        etPageW.setText(pref.getString("pw", null) ?: fmt(papers[0].second))
        etPageH.setText(pref.getString("ph", null) ?: fmt(papers[0].third))
        settingPreset = false
        spPaper.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val (_, w, h) = papers[pos]
                if (w > 0 && !settingPreset) {
                    settingPreset = true
                    etPageW.setText(fmt(w)); etPageH.setText(fmt(h))
                    settingPreset = false
                }
                updateSpreadInfo()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (settingPreset) return
                // Sửa tay: nếu khác mọi khổ chuẩn thì chuyển sang "Tuỳ chỉnh"
                val w = pageW(); val h = pageH()
                val m = papers.indexOfFirst { it.second > 0 && Math.abs(it.second - w) < 0.005 && Math.abs(it.third - h) < 0.005 }
                val target = if (m >= 0) m else papers.size - 1
                if (spPaper.selectedItemPosition != target) {
                    settingPreset = true; spPaper.setSelection(target); settingPreset = false
                }
                updateSpreadInfo()
            }
        }
        etPageW.addTextChangedListener(watcher)
        etPageH.addTextChangedListener(watcher)
        tabs.addTab(tabs.newTab().setText("Hai trang"))
        tabs.addTab(tabs.newTab().setText("Một trang"))
        singleMode = pref.getInt("tab", 0) == 1
        tabs.getTabAt(if (singleMode) 1 else 0)?.select()
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                singleMode = tab?.position == 1
                getSharedPreferences("scan", MODE_PRIVATE).edit().putInt("tab", if (singleMode) 1 else 0).apply()
                applyMode()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        applyMode()
        pref.getString("mg3", null)?.let { etMargin.setText(it) }
        pref.getString("cd", null)?.let { etDelay.setText(it) }
        pref.getString("ci", null)?.let { etInterval.setText(it) }
        pref.getString("cc", null)?.let { etCount.setText(it) }
        updateQueueUi()
    }

    private val calibLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { loadProfile() }

    private fun loadProfile() {
        profile = ColorProfile.fromJson(getSharedPreferences("scan", MODE_PRIVATE).getString(CalibrateActivity.PREF_PROFILE, null))
        refreshCalib()
    }

    private fun refreshCalib() {
        val adv = spCalib.selectedItemPosition == 1
        btnCalib.visibility = if (adv) View.VISIBLE else View.GONE
        tvCalib.text = if (!adv) "Tự ước lượng màu nền/giấy từ chính ảnh đang xử lý."
        else if (profile != null) "Đã có hồ sơ nền/giấy" + (if (profile!!.inkRatio > 0) "/chữ" else "") + " – dùng cho cả loạt ảnh. Bấm nút để chọn lại."
        else "Chưa có hồ sơ – sẽ dùng chế độ tự động cho tới khi bạn chọn ảnh mẫu."
    }

    private fun options() = ScanOptions(
        calibAdvanced = spCalib.selectedItemPosition == 1 && profile != null,
        profile = profile,
        rotate = intArrayOf(0, 180, 90, 270)[spRotate.selectedItemPosition.coerceIn(0, 3)],
        curve = cbCurve.isChecked,
        trackEdges = cbTrack.isChecked,
        output = if (spOutput.selectedItemPosition == 0) OutputType.ORIGINAL else OutputType.BW,
        blackBoost = sbInk.progress / 100.0,
        split = SplitMode.values()[spSplit.selectedItemPosition],
        autoCrop = cbCrop.isChecked,
        cropContent = cbContent.isChecked,
        deskew = cbDeskew.isChecked,
        merge = cbMerge.isChecked && !singleMode,
        // tab Hai trang: sách mở ngang, rộng = 2 trang; tab Một trang: đúng khổ một trang
        spreadWcm = if (singleMode) pageW() else 2 * pageW(),
        spreadHcm = pageH(),
        singlePage = singleMode,
        gutterSide = spGutter.selectedItemPosition.coerceIn(0, 2),
        rowFix = cbRowFix.isChecked,
        compressFix = cbHFix.isChecked,
        mergeMarginCm = signedCm(etMergeMargin),
        gutterMarginCm = signedCm(etGutterMargin),
        marginCm = (etMargin.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.5).coerceIn(0.0, 5.0)
    )

    private fun signedCm(e: EditText) = (e.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0).coerceIn(-5.0, 5.0)

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
    private fun pageW() = (etPageW.text.toString().replace(',', '.').toDoubleOrNull() ?: 21.0).coerceIn(5.0, 60.0)
    private fun pageH() = (etPageH.text.toString().replace(',', '.').toDoubleOrNull() ?: 29.7).coerceIn(5.0, 80.0)
    private fun updateSpreadInfo() {
        tvSpread.text = if (singleMode)
            "Một trang: ${fmt(pageW())} × ${fmt(pageH())} cm – chụp thẳng góc, trang chiếm gần đầy khung hình"
        else
            "Sách mở (2 trang): ${fmt(2 * pageW())} × ${fmt(pageH())} cm – camera chụp ngang"
    }

    /** Bật/tắt các tuỳ chọn theo tab đang chọn (Hai trang / Một trang). */
    private fun applyMode() {
        tvTitle.text = if (singleMode) "Quét sách – từng trang một" else "Quét sách – tách 2 trang"
        splitBox.visibility = if (singleMode) View.GONE else View.VISIBLE
        cbMerge.visibility = if (singleMode) View.GONE else View.VISIBLE
        cbCrop.visibility = if (singleMode) View.GONE else View.VISIBLE
        singleBox.visibility = if (singleMode) View.VISIBLE else View.GONE
        mergeBox.visibility = if (!singleMode && cbMerge.isChecked) View.VISIBLE else View.GONE
        updateSpreadInfo()
    }

    private fun addToQueue(uris: List<Uri>) {
        queue.addAll(uris)
        updateQueueUi()
    }

    private fun updateQueueUi() {
        tvQueue.text = "Hàng đợi: ${queue.size} ảnh"
        btnProcess.text = if (queue.isEmpty()) "Xử lý hàng đợi" else "Xử lý ${queue.size} ảnh"
        btnProcess.isEnabled = !busy && queue.isNotEmpty()
        btnClear.isEnabled = !busy && queue.isNotEmpty()
    }

    private fun startAutoCapture() {
        val delaySec = (etDelay.text.toString().replace(',', '.').toDoubleOrNull() ?: 5.0).coerceIn(0.0, 600.0)
        val intervalSec = (etInterval.text.toString().replace(',', '.').toDoubleOrNull() ?: 3.0).coerceIn(1.0, 3600.0)
        val count = (etCount.text.toString().toIntOrNull() ?: 0).coerceIn(0, 100000)
        getSharedPreferences("scan", MODE_PRIVATE).edit()
            .putString("cd", etDelay.text.toString())
            .putString("ci", etInterval.text.toString())
            .putString("cc", etCount.text.toString()).apply()
        autoCapture.launch(Intent(this, CaptureActivity::class.java)
            .putExtra(CaptureActivity.EXTRA_DELAY, delaySec)
            .putExtra(CaptureActivity.EXTRA_INTERVAL, intervalSec)
            .putExtra(CaptureActivity.EXTRA_COUNT, count))
    }

    private fun setBusy(b: Boolean) {
        busy = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        btnCamera.isEnabled = !b
        btnPick.isEnabled = !b
        btnPdf.isEnabled = !b
        updateQueueUi()
    }

    private fun processQueue() {
        if (queue.isEmpty()) return
        val opt = options()
        getSharedPreferences("scan", MODE_PRIVATE).edit()
            .putString("pw", etPageW.text.toString())
            .putString("ph", etPageH.text.toString())
            .putInt("pp", spPaper.selectedItemPosition)
            .putInt("ot", spOutput.selectedItemPosition)
            .putInt("rt", spRotate.selectedItemPosition)
            .putBoolean("cv", cbCurve.isChecked)
            .putBoolean("tk", cbTrack.isChecked)
            .putInt("gs", spGutter.selectedItemPosition)
            .putString("mm", etMergeMargin.text.toString())
            .putString("gm", etGutterMargin.text.toString())
            .putBoolean("rf", cbRowFix.isChecked)
            .putBoolean("hf", cbHFix.isChecked)
            .putBoolean("cc", cbContent.isChecked)
            .putInt("cb", spCalib.selectedItemPosition)
            .putInt("ink", sbInk.progress)
            .putString("mg3", etMargin.text.toString()).apply()
        val work = queue.toList()
        setBusy(true)
        lifecycleScope.launch {
            var total = 0
            var errors = 0
            val notes = ArrayList<String>()
            for ((i, uri) in work.withIndex()) {
                tvStatus.text = "Đang xử lý ảnh ${i + 1}/${work.size}…"
                try {
                    val outs = withContext(Dispatchers.Default) { processOne(uri, opt) }
                    for ((u, th) in outs) addThumb(u, th)
                    total += outs.size
                    lastNote?.let { if (it.isNotBlank()) notes.add("Ảnh ${i + 1}: $it") }
                    queue.remove(uri)
                    updateQueueUi()
                } catch (e: Throwable) {
                    errors++
                    tvStatus.text = "Lỗi ở ảnh ${i + 1}: ${e.message}"
                }
            }
            tvStatus.text = "Xong: $total trang đã lưu vào Pictures/BookScanner" +
                (if (errors > 0) " ($errors ảnh lỗi, còn trong hàng đợi)" else "") +
                (if (notes.isNotEmpty()) "\n" + notes.joinToString("\n") else "")
            setBusy(false)
        }
    }

    private fun processOne(uri: Uri, opt: ScanOptions): List<Pair<Uri, Bitmap>> {
        val bmp = Exporter.decode(this, uri)
        val res = BookScanner.process(bmp, opt)
        lastNote = res.note
        bmp.recycle()
        val dpi = Math.round(res.pxPerCm * 2.54).toInt()
        return res.pages.map { p ->
            counter++
            val name = "scan_${stamp}_%03d".format(counter)
            val u = Exporter.saveJpeg(this, p, name, dpi)
            val th = Bitmap.createScaledBitmap(p, 500, (500f * p.height / p.width).toInt().coerceAtLeast(1), true)
            p.recycle()
            saved.add(u to res.pxPerCm)
            u to th
        }
    }

    private fun addThumb(uri: Uri, th: Bitmap) {
        val iv = ImageView(this).apply {
            setImageBitmap(th)
            adjustViewBounds = true
            setPadding(0, 8, 0, 8)
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        results.addView(iv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun exportPdf() {
        if (saved.isEmpty()) {
            Toast.makeText(this, "Chưa có trang nào", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        tvStatus.text = "Đang tạo PDF…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Exporter.savePdf(this@MainActivity, saved.toList(), "scan_$stamp") }
                tvStatus.text = "Đã lưu PDF vào Download/BookScanner"
            } catch (e: Throwable) {
                tvStatus.text = "Lỗi PDF: ${e.message}"
            }
            setBusy(false)
        }
    }
}
