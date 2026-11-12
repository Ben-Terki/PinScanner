package com.pinbot.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pinbot.scanner.data.HistoryRepository
import com.pinbot.scanner.data.ScanRecord
import com.pinbot.scanner.databinding.ActivityMainBinding
import com.pinbot.scanner.ocr.FrameText
import com.pinbot.scanner.ocr.PinCandidate
import com.pinbot.scanner.ocr.PinLayout
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    companion object {
        /** Two matching reads within this window confirm a code. */
        private const val HOLD_WINDOW_MS = 4_000L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: HistoryRepository

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var camera: Camera? = null
    private var busy = false
    private var torchOn = false
    @Volatile
    private var showingResult = false
    private var pending: PinCandidate? = null
    private var pendingAt = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.permissionCard.visibility = View.GONE
                startCamera()
            } else {
                showPermissionHelp()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = HistoryRepository(this)

        binding.copyButton.setOnClickListener { copyCode() }
        binding.againButton.setOnClickListener { resumeScanning() }
        binding.historyButton.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        binding.torchButton.setOnClickListener { toggleTorch() }
        binding.grantButton.setOnClickListener { requestCamera() }
        binding.settingsButton.setOnClickListener { openAppSettings() }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        runCatching { recognizer.close() }
    }

    // ---------------------------------------------------------------- camera

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCamera() {
        if (hasCameraPermission()) {
            binding.permissionCard.visibility = View.GONE
            startCamera()
            return
        }
        if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) && binding.permissionCard.isShown) {
            // Asked before and refused for good: send the user to the settings page.
            openAppSettings()
            return
        }
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { image -> analyze(image) }

            runCatching {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                applyTorch()
                setStatus(getString(R.string.status_searching))
            }.onFailure {
                setStatus(getString(R.string.status_camera_failed))
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        applyTorch()
        binding.torchButton.text =
            getString(if (torchOn) R.string.torch_on else R.string.torch)
    }

    private fun applyTorch() {
        runCatching { camera?.cameraControl?.enableTorch(torchOn) }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    // ------------------------------------------------------------------- OCR

    private fun analyze(image: ImageProxy) {
        if (busy || showingResult) {
            image.close()
            return
        }
        val media = image.image
        if (media == null) {
            image.close()
            return
        }
        busy = true
        val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
        recognizer.process(input)
            .addOnSuccessListener(analysisExecutor) { text ->
                val width = max(image.width, image.height)
                val found = PinLayout.candidates(FrameText.glyphs(text), width)
                mainHandler.post { onCandidates(found) }
            }
            .addOnCompleteListener(analysisExecutor) {
                busy = false
                image.close()
            }
    }

    private fun onCandidates(found: List<PinCandidate>) {
        if (showingResult || isFinishing) return
        val candidate = found.firstOrNull()
        if (candidate == null) {
            binding.guide.setHot(false)
            binding.peek.visibility = View.INVISIBLE
            setStatus(getString(R.string.status_searching))
            return
        }

        val now = SystemClock.elapsedRealtime()
        binding.peek.visibility = View.VISIBLE
        binding.peek.text = candidate.formatted
        binding.guide.setHot(true)

        val previous = pending
        if (previous != null && previous.digits == candidate.digits && now - pendingAt <= HOLD_WINDOW_MS) {
            showResult(candidate)
            return
        }
        pending = candidate
        pendingAt = now
        setStatus(getString(R.string.status_hold, candidate.formatted))
    }

    private fun showResult(candidate: PinCandidate) {
        showingResult = true
        pending = null
        binding.guide.setHot(false)
        binding.peek.visibility = View.INVISIBLE
        binding.resultCard.visibility = View.VISIBLE
        binding.pinText.text = candidate.formatted
        binding.confidenceText.text =
            getString(R.string.confidence, (candidate.confidence * 100).toInt())
        setStatus(getString(R.string.status_scanned))
        binding.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        val saved = repository.append(
            ScanRecord(
                timestamp = System.currentTimeMillis(),
                digits = candidate.digits,
                confidence = candidate.confidence,
            ),
        )
        if (saved) toast(getString(R.string.saved_to_history))
    }

    private fun resumeScanning() {
        showingResult = false
        pending = null
        binding.resultCard.visibility = View.GONE
        setStatus(getString(R.string.status_searching))
    }

    private fun copyCode() {
        val text = binding.pinText.text?.toString().orEmpty()
        if (text.isBlank()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("top-up code", text))
        toast(getString(R.string.copied))
    }

    private fun setStatus(text: String) {
        binding.status.text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun showPermissionHelp() {
        binding.permissionCard.visibility = View.VISIBLE
        setStatus(getString(R.string.status_no_permission))
    }
}
