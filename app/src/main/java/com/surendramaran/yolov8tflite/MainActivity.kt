package com.surendramaran.yolov8tflite

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech // Import Baru
import android.util.Log
import android.view.KeyEvent // Import Baru
import android.widget.Toast // Import Baru
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.surendramaran.yolov8tflite.Constants.LABELS_PATH
import com.surendramaran.yolov8tflite.Constants.MODEL_PATH
import com.surendramaran.yolov8tflite.databinding.ActivityMainBinding
import java.util.Locale // Import Baru
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Tambahkan Interface TextToSpeech.OnInitListener
class MainActivity : AppCompatActivity(), Detector.DetectorListener, TextToSpeech.OnInitListener {
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false

    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var detector: Detector
    private lateinit var cameraExecutor: ExecutorService

    // --- VARIABEL BARU ---
    private var tts: TextToSpeech? = null
    private var lastBoundingBoxes: List<BoundingBox>? = null // Untuk menyimpan hasil deteksi terakhir
    // ---------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- INISIALISASI TTS ---
        tts = TextToSpeech(this, this)

        // --- EVENT LISTENER TOMBOL LAYAR ---
        binding.btnSpeak.setOnClickListener {
            speakDetectionResult()
        }
        // -------------------------

        detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)
        detector.setup()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    // ... (Fungsi startCamera, bindCameraUseCases, permissions biarkan sama seperti sebelumnya) ...
    // ... Agar kode tidak terlalu panjang, saya skip bagian yang tidak berubah ...

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider  = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")
        val rotation = binding.viewFinder.display.rotation
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview =  Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                if (isFrontCamera) {
                    postScale(-1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }
            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            detector.detect(rotatedBitmap)
        }
        cameraProvider.unbindAll()
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            preview?.setSurfaceProvider(binding.viewFinder.surfaceProvider)
        } catch(exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.CAMERA] == true) { startCamera() }
    }

    // --- FUNGSI DETECTOR ---

    override fun onEmptyDetect() {
        binding.overlay.invalidate()
        lastBoundingBoxes = emptyList() // Kosongkan data jika tidak ada deteksi
    }

    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        // SIMPAN HASIL DETEKSI KE VARIABEL GLOBAL
        lastBoundingBoxes = boundingBoxes

        runOnUiThread {
            binding.inferenceTime.text = "${inferenceTime}ms"
            binding.overlay.apply {
                setResults(boundingBoxes)
                invalidate()
            }
        }
    }

    // --- LOGIKA BARU UNTUK SUARA ---

    // 1. Setup Bahasa TTS
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // Set bahasa ke Indonesia
            val result = tts?.setLanguage(Locale("id", "ID"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Bahasa Indonesia tidak didukung, fallback ke US")
                tts?.setLanguage(Locale.US)
            }
        } else {
            Log.e("TTS", "Inisialisasi gagal")
        }
    }

    // 2. Fungsi Logika Membunyikan Suara
    private fun speakDetectionResult() {
        val boxes = lastBoundingBoxes
        if (boxes.isNullOrEmpty()) {
            Toast.makeText(this, "Tidak ada uang terdeteksi", Toast.LENGTH_SHORT).show()
            return
        }

        // Ambil deteksi dengan akurasi (confidence) tertinggi agar tidak salah baca
        val bestDetection = boxes.maxByOrNull { it.cnf }

        bestDetection?.let {
            val spokenText = mapLabelToSpokenText(it.clsName)
            // Bicara (QUEUE_FLUSH akan memotong suara sebelumnya jika ada)
            tts?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "")

            // Opsional: Tampilkan toast apa yang dibaca
            Toast.makeText(this, "Mendeteksi: $spokenText", Toast.LENGTH_SHORT).show()
        }
    }

    // 3. Mapping Label (100k) -> Kalimat (Seratus Ribu)
    private fun mapLabelToSpokenText(label: String): String {
        return when (label.lowercase()) {
            "100k" -> "Seratus ribu rupiah"
            "50k" -> "Lima puluh ribu rupiah"
            "20k" -> "Dua puluh ribu rupiah"
            "10k" -> "Sepuluh ribu rupiah"
            "5k" -> "Lima ribu rupiah"
            "2k" -> "Dua ribu rupiah"
            "1k" -> "Seribu rupiah"
            else -> label // Jika tidak dikenali, baca apa adanya
        }
    }

    // 4. Override Tombol Volume Fisik
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                speakDetectionResult()
                true // Return true agar volume sistem tidak berubah
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // --- CLEANUP ---
    override fun onDestroy() {
        super.onDestroy()
        detector.clear()
        cameraExecutor.shutdown()

        // Matikan TTS agar tidak memory leak
        tts?.stop()
        tts?.shutdown()
    }

    // ... (onResume dan Companion Object biarkan sama) ...
    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()){
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }
}