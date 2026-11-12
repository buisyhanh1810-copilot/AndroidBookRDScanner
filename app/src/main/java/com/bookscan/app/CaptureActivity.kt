package com.bookscan.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * Chụp tự động định thời bằng CameraX: không hiện màn xác nhận/OK/chụp lại.
 * Cứ mỗi [EXTRA_INTERVAL] giây tự chụp 1 ảnh, có thể Tạm dừng / Tiếp tục.
 * Kết thúc (đủ số ảnh, bấm "Dừng và lưu" hoặc Back) -> trả danh sách ảnh về MainActivity.
 */
class CaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DELAY = "delay"        // giây chờ trước ảnh đầu
        const val EXTRA_INTERVAL = "interval"  // giây giữa các ảnh
        const val EXTRA_COUNT = "count"        // 0 = không giới hạn
        const val RESULT_URIS = "uris"
    }

    private lateinit var preview: PreviewView
    private lateinit var tvInfo: TextView
    private lateinit var flash: View
    private lateinit var btnPause: Button
    private lateinit var btnStop: Button

    private var imageCapture: ImageCapture? = null
    private var loop: Job? = null
    private var paused = false
    private var finished = false
    private val taken = ArrayList<String>()
    private var tone: ToneGenerator? = null

    private val askCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera() else {
                Toast.makeText(this, "Cần cấp quyền camera để chụp", Toast.LENGTH_LONG).show()
                finishWithResult()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        preview = findViewById(R.id.preview)
        tvInfo = findViewById(R.id.tvInfo)
        flash = findViewById(R.id.flash)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        tone = try { ToneGenerator(AudioManager.STREAM_SYSTEM, 60) } catch (_: Throwable) { null }

        btnPause.setOnClickListener {
            paused = !paused
            btnPause.text = if (paused) "Tiếp tục" else "Tạm dừng"
        }
        btnStop.setOnClickListener { finishWithResult() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishWithResult()
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val prev = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, cap)
                imageCapture = cap
                startLoop()
            } catch (e: Throwable) {
                Toast.makeText(this, "Không mở được camera: ${e.message}", Toast.LENGTH_LONG).show()
                finishWithResult()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLoop() {
        val delaySec = intent.getDoubleExtra(EXTRA_DELAY, 5.0).coerceAtLeast(0.0)
        val intervalSec = intent.getDoubleExtra(EXTRA_INTERVAL, 3.0).coerceAtLeast(1.0)
        val maxCount = intent.getIntExtra(EXTRA_COUNT, 0)
        loop = lifecycleScope.launch {
            var first = true
            while (isActive && (maxCount == 0 || taken.size < maxCount)) {
                var remain = ((if (first) delaySec else intervalSec) * 1000).toLong()
                first = false
                while (remain > 0) {
                    if (paused) {
                        showInfo("TẠM DỪNG – đã chụp ${taken.size}${total(maxCount)}")
                    } else {
                        showInfo("Chụp sau %.1f s – đã chụp %d%s".format(remain / 1000.0, taken.size, total(maxCount)))
                        remain -= 100
                    }
                    delay(100)
                }
                shoot()
            }
            finishWithResult()
        }
    }

    private fun total(max: Int) = if (max > 0) "/$max" else ""
    private fun showInfo(t: String) { tvInfo.text = t }

    private suspend fun shoot() {
        val cap = imageCapture ?: return
        val dir = File(cacheDir, "images").apply { mkdirs() }
        val f = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        cap.targetRotation = windowManager.defaultDisplay.rotation
        val uri: Uri? = suspendCancellableCoroutine { cont ->
            cap.takePicture(
                ImageCapture.OutputFileOptions.Builder(f).build(),
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                        cont.resume(FileProvider.getUriForFile(this@CaptureActivity, "$packageName.fileprovider", f))
                    }
                    override fun onError(e: ImageCaptureException) { cont.resume(null) }
                })
        }
        if (uri != null) {
            taken.add(uri.toString())
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
            flash.visibility = View.VISIBLE
            delay(80)
            flash.visibility = View.GONE
        }
    }

    private fun finishWithResult() {
        if (finished) return
        finished = true
        loop?.cancel()
        setResult(RESULT_OK, Intent().putStringArrayListExtra(RESULT_URIS, taken))
        finish()
    }

    override fun onDestroy() {
        tone?.release()
        super.onDestroy()
    }
}
