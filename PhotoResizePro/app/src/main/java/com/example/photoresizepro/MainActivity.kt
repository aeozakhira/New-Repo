package com.example.photoresizepro

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.photoresizepro.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val mainScope = CoroutineScope(Dispatchers.Main + Job())

    // ---- State ----
    private var sourceUri: Uri? = null
    private var sourceInfo: ImageProcessor.ImageInfo? = null
    private var processedBitmap: Bitmap? = null
    private var processedBytes: ByteArray? = null
    private var processedFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG
    private var pendingCameraUri: Uri? = null
    private var isUpdatingChipsProgrammatically = false

    companion object {
        private const val MAX_SIZE_BYTES = 25 * 1024 // 25 KB
    }

    // ---- Activity Result launchers ----

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onImageSelected(it) }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            pendingCameraUri?.let { onImageSelected(it) }
        } else {
            showSnackbar(getString(R.string.msg_resize_failed))
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            launchCamera()
        } else {
            showSnackbar(getString(R.string.msg_permission_denied))
        }
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            pickImageLauncher.launch("image/*")
        } else {
            showSnackbar(getString(R.string.msg_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    // =====================================================================
    // Setup
    // =====================================================================

    private fun setupListeners() {
        binding.btnSelectImage.setOnClickListener { requestPickImage() }
        binding.btnCapturePhoto.setOnClickListener { requestCapturePhoto() }
        binding.btnResize.setOnClickListener { processImage() }
        binding.btnSave.setOnClickListener { saveImage() }
        binding.btnShare.setOnClickListener { shareImage() }
        binding.btnClear.setOnClickListener { clearAll() }

        binding.sliderQuality.addOnChangeListener { _, value, _ ->
            binding.tvQualityValue.text = value.toInt().toString()
        }

        binding.chipGroupPresets.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isUpdatingChipsProgrammatically) return@setOnCheckedStateChangeListener
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds[0]) {
                R.id.chipPreset600x800 -> setDimensions(600, 800)
                R.id.chipPreset300x400 -> setDimensions(300, 400)
                R.id.chipPreset1200x1600 -> setDimensions(1200, 1600)
                R.id.chipPresetCustom -> { /* user will type manually */ }
            }
        }

        val markCustomOnEdit = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUpdatingChipsProgrammatically) return
                markCustomPreset()
            }
        }
        binding.etWidth.addTextChangedListener(markCustomOnEdit)
        binding.etHeight.addTextChangedListener(markCustomOnEdit)
    }

    private fun setDimensions(w: Int, h: Int) {
        isUpdatingChipsProgrammatically = true
        binding.etWidth.setText(w.toString())
        binding.etHeight.setText(h.toString())
        isUpdatingChipsProgrammatically = false
    }

    private fun markCustomPreset() {
        isUpdatingChipsProgrammatically = true
        binding.chipPresetCustom.isChecked = true
        isUpdatingChipsProgrammatically = false
    }

    // =====================================================================
    // Permissions + Image acquisition
    // =====================================================================

    private fun requestPickImage() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            pickImageLauncher.launch("image/*")
        } else {
            storagePermissionLauncher.launch(permission)
        }
    }

    private fun requestCapturePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val cacheDir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    private fun onImageSelected(uri: Uri) {
        sourceUri = uri
        sourceInfo = ImageProcessor.readImageInfo(this, uri)

        binding.imageOriginal.setImageURI(null)
        binding.imageOriginal.setImageURI(uri)

        sourceInfo?.let {
            binding.tvOriginalInfo.text = "${it.width}×${it.height}  •  ${ImageProcessor.formatFileSize(it.sizeBytes)}"
        }

        // Reset processed preview
        processedBitmap = null
        processedBytes = null
        binding.imageProcessed.setImageDrawable(null)
        binding.tvProcessedInfo.text = getString(R.string.info_output_default)
    }

    // =====================================================================
    // Processing
    // =====================================================================

    private fun processImage() {
        val uri = sourceUri
        if (uri == null) {
            showSnackbar(getString(R.string.msg_select_image_first))
            return
        }

        val width = binding.etWidth.text?.toString()?.toIntOrNull()
        val height = binding.etHeight.text?.toString()?.toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) {
            showSnackbar(getString(R.string.msg_invalid_dimensions))
            return
        }

        val maintainAspect = binding.switchMaintainAspect.isChecked
        val whiteBackground = binding.switchWhiteBackground.isChecked
        val autoCompress = binding.checkboxAutoCompress.isChecked
        val quality = binding.sliderQuality.value.toInt()
        val format = if (binding.radioGroupFormat.checkedRadioButtonId == R.id.radioPng) {
            Bitmap.CompressFormat.PNG
        } else {
            Bitmap.CompressFormat.JPEG
        }

        setLoading(true)

        mainScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    runProcessing(uri, width, height, maintainAspect, whiteBackground, autoCompress, quality, format)
                }
                if (result != null) {
                    processedBitmap = result.bitmap
                    processedBytes = result.bytes
                    processedFormat = result.format

                    binding.imageProcessed.setImageBitmap(result.bitmap)
                    binding.tvProcessedInfo.text =
                        "${result.bitmap.width}×${result.bitmap.height}  •  ${ImageProcessor.formatFileSize(result.bytes.size.toLong())}"

                    showSnackbar(getString(R.string.msg_resize_success))
                } else {
                    showSnackbar(getString(R.string.msg_resize_failed))
                }
            } catch (e: Exception) {
                showSnackbar(getString(R.string.msg_resize_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    /** Runs entirely off the main thread. */
    private fun runProcessing(
        uri: Uri,
        width: Int,
        height: Int,
        maintainAspect: Boolean,
        whiteBackground: Boolean,
        autoCompress: Boolean,
        quality: Int,
        format: Bitmap.CompressFormat
    ): ImageProcessor.ProcessResult? {
        // Decode at (roughly) the right sample size to keep memory bounded even for huge photos.
        val decoded = ImageProcessor.decodeSampledBitmapFromUri(this, uri, width * 2, height * 2)
            ?: return null

        val resized = ImageProcessor.resizeWithAspectRatio(
            decoded, width, height, maintainAspect, whiteBackground
        )
        if (decoded != resized) decoded.recycle()

        val flattened = if (whiteBackground && format == Bitmap.CompressFormat.JPEG) {
            // JPEG has no alpha channel anyway, but flatten explicitly for correctness/clarity.
            val f = ImageProcessor.flattenToWhite(resized)
            if (f != resized) resized.recycle()
            f
        } else {
            resized
        }

        val maxBytes = if (autoCompress) MAX_SIZE_BYTES else Int.MAX_VALUE
        val (bytes, usedQuality) = ImageProcessor.compressToTargetSize(flattened, format, maxBytes, quality)

        return ImageProcessor.ProcessResult(flattened, bytes, usedQuality, format)
    }

    // =====================================================================
    // Save / Share / Clear
    // =====================================================================

    private fun saveImage() {
        val bytes = processedBytes
        if (bytes == null) {
            showSnackbar(getString(R.string.msg_no_processed_image))
            return
        }

        mainScope.launch {
            val success = withContext(Dispatchers.IO) { writeToMediaStore(bytes) }
            if (success) {
                showSnackbar(getString(R.string.msg_saved))
            } else {
                showSnackbar(getString(R.string.msg_save_failed))
            }
        }
    }

    private fun writeToMediaStore(bytes: ByteArray): Boolean {
        return try {
            val ext = if (processedFormat == Bitmap.CompressFormat.PNG) "png" else "jpg"
            val mime = if (processedFormat == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "PRP_$timestamp.$ext"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoResizePro")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(collection, values) ?: return false

            resolver.openOutputStream(uri)?.use { out ->
                out.write(bytes)
            } ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun shareImage() {
        val bytes = processedBytes
        if (bytes == null) {
            showSnackbar(getString(R.string.msg_no_processed_image))
            return
        }

        mainScope.launch {
            val uri = withContext(Dispatchers.IO) { writeToShareCache(bytes) }
            if (uri != null) {
                val mime = if (processedFormat == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.btn_share)))
            } else {
                showSnackbar(getString(R.string.msg_save_failed))
            }
        }
    }

    private fun writeToShareCache(bytes: ByteArray): Uri? {
        return try {
            val ext = if (processedFormat == Bitmap.CompressFormat.PNG) "png" else "jpg"
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "share_${System.currentTimeMillis()}.$ext")
            FileOutputStream(file).use { it.write(bytes) }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    private fun clearAll() {
        sourceUri = null
        sourceInfo = null
        processedBitmap = null
        processedBytes = null
        pendingCameraUri = null

        binding.imageOriginal.setImageDrawable(null)
        binding.imageProcessed.setImageDrawable(null)
        binding.tvOriginalInfo.text = getString(R.string.info_original_default)
        binding.tvProcessedInfo.text = getString(R.string.info_output_default)

        setDimensions(600, 800)
        isUpdatingChipsProgrammatically = true
        binding.chipPreset600x800.isChecked = true
        isUpdatingChipsProgrammatically = false

        binding.switchMaintainAspect.isChecked = true
        binding.switchWhiteBackground.isChecked = true
        binding.sliderQuality.value = 85f
        binding.tvQualityValue.text = "85"
        binding.checkboxAutoCompress.isChecked = true
        binding.radioJpeg.isChecked = true

        showSnackbar(getString(R.string.msg_cleared))
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.btnResize.isEnabled = !loading
        binding.btnSave.isEnabled = !loading
        binding.btnShare.isEnabled = !loading
        binding.btnSelectImage.isEnabled = !loading
        binding.btnCapturePhoto.isEnabled = !loading
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainScope.coroutineContext[Job]?.cancel()
    }
}
