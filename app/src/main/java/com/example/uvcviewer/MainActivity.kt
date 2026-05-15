package com.example.uvcviewer

import android.Manifest
import android.app.ActivityManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.format.Formatter
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat

class MainActivity : AppCompatActivity() {
    private val sync = Any()
    private val trackerLock = Any()

    private lateinit var usbMonitor: USBMonitor
    private var uvcCamera: UVCCamera? = null

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    private var previewSurface: Surface? = null
    private var isActive = false
    private var isPreview = false

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: TrackingOverlayView
    private lateinit var barsView: TrackingBarsView
    private lateinit var statusText: TextView
    private lateinit var setReferenceButton: Button
    private lateinit var resetButton: Button
    private lateinit var quitButton: Button
    private lateinit var performanceText: TextView

    private var openCvOk = false
    private var tracker: Trial32Tracker? = null

    private val perfHandler = Handler(Looper.getMainLooper())
    private var lastCpuStatTotal = 0L
    private var lastCpuStatIdle = 0L
    private var lastAppCpuTime = android.os.Process.getElapsedCpuTime()
    private var lastWallTime = System.currentTimeMillis()
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    private var currentFps = 0.0
    private val perfUpdateRunnable = object : Runnable {
        override fun run() {
            updatePerformanceMetrics()
            perfHandler.postDelayed(this, 1000)
        }
    }

    private var previewWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH
    private var previewHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT

    private var yPlane: ByteArray = ByteArray(0)
    private var grayRawMat: Mat? = null

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setStatus("Camera permission granted. Plug in the USB camera.")
                val requestedFromIntent = maybeRequestUsbPermissionFromIntent(intent)
                if (!requestedFromIntent) {
                    requestUsbPermissionForFirstDevice()
                }
            } else {
                setStatus("Camera permission is required to access a USB camera on this Android version.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Adjust preview container to maintain 960x720 aspect ratio
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val aspectRatio = 720.0 / 960.0
        val desiredHeight = (screenWidth * aspectRatio).toInt()
        val previewContainer = findViewById<FrameLayout>(R.id.preview_container)
        val params = previewContainer.layoutParams as LinearLayout.LayoutParams
        params.height = desiredHeight
        params.weight = 0f
        previewContainer.layoutParams = params

        openCvOk = OpenCVLoader.initDebug()
        if (openCvOk) {
            tracker = Trial32Tracker()
        }

        surfaceView = findViewById(R.id.camera_surface_view)
        overlayView = findViewById(R.id.tracking_overlay)
        barsView = findViewById(R.id.tracking_bars)
        statusText = findViewById(R.id.status_text)
        performanceText = findViewById(R.id.performance_text)
        setReferenceButton = findViewById(R.id.set_reference_button)
        resetButton = findViewById(R.id.reset_button)
        quitButton = findViewById(R.id.quit_button)

        if (!openCvOk) {
            setStatus("OpenCV failed to initialize. Tracking will not work.")
        }

        surfaceView.holder.addCallback(surfaceCallback)

        cameraThread = HandlerThread("UvcCameraThread").apply { start() }
        cameraHandler = Handler(cameraThread.looper)

        usbMonitor = USBMonitor(this, deviceConnectListener)

        setReferenceButton.setOnClickListener {
            val t = tracker ?: run {
                setStatus("OpenCV not available.")
                return@setOnClickListener
            }
            if (t.getLastGrayForReference().empty()) {
                setStatus("Waiting for camera frames...")
                return@setOnClickListener
            }
            synchronized(trackerLock) {
                t.setReferenceFromLastFrame()
            }
            t.last_status_message?.let { setStatus(it) }
        }

        resetButton.setOnClickListener {
            val t = tracker ?: return@setOnClickListener
            synchronized(trackerLock) {
                t.resetTrackingManual()
            }
            t.last_status_message?.let { setStatus(it) }
        }

        quitButton.setOnClickListener {
            closeCamera()
            finishAndRemoveTask()
        }

        if (ensureCameraPermission()) {
            setStatus("Ready. Plug in the USB camera (OTG).")
            val requestedFromIntent = maybeRequestUsbPermissionFromIntent(intent)
            if (!requestedFromIntent) {
                requestUsbPermissionForFirstDevice()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeRequestUsbPermissionFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        synchronized(sync) {
            usbMonitor.register()
        }
        perfHandler.post(perfUpdateRunnable)
    }

    override fun onStop() {
        perfHandler.removeCallbacks(perfUpdateRunnable)
        closeCamera()
        synchronized(sync) {
            usbMonitor.unregister()
        }
        super.onStop()
    }

    override fun onDestroy() {
        closeCamera()
        synchronized(sync) {
            usbMonitor.destroy()
        }
        cameraThread.quitSafely()

        grayRawMat?.release()
        grayRawMat = null

        super.onDestroy()
    }

    private fun ensureCameraPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PermissionChecker.PERMISSION_GRANTED
        if (!granted) {
            setStatus("Requesting camera permission...")
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        return granted
    }

    private fun maybeRequestUsbPermissionFromIntent(intent: android.content.Intent): Boolean {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
        if (device != null) {
            if (!ensureCameraPermission()) return false
            setStatus("USB device attached. Requesting permission...")
            usbMonitor.requestPermission(device)
            return true
        }
        return false
    }

    private fun requestUsbPermissionForFirstDevice() {
        val devices = getConnectedUsbDevices()
        if (devices.isEmpty()) {
            setStatus("No USB devices found. Make sure OTG is enabled and the camera is connected.")
            return
        }
        setStatus("Requesting permission for USB device...")
        usbMonitor.requestPermission(devices.first())
    }

    private fun requestUsbPermissionForFirstDeviceIfOnlyOne(deviceFromCallback: UsbDevice?) {
        var device = deviceFromCallback
        val devices = getConnectedUsbDevices()
        val count = devices.size
        if (device == null && count > 0) {
            device = devices.firstOrNull()
        }
        if (device == null) return

        if (count == 1) {
            setStatus("USB camera detected. Requesting permission...")
            usbMonitor.requestPermission(device)
        } else {
            setStatus("Multiple USB devices detected. Unplug extra devices if needed.")
        }
    }

    private fun getConnectedUsbDevices(): List<UsbDevice> {
        val manager = getSystemService(USB_SERVICE) as UsbManager
        return manager.deviceList.values.toList()
    }

    private fun openCamera(ctrlBlock: UsbControlBlock) {
        cameraHandler.post {
            synchronized(sync) {
                uvcCamera?.destroy()
                uvcCamera = null
                isActive = false
                isPreview = false
            }

            val camera = UVCCamera()
            try {
                camera.open(ctrlBlock)

                try {
                    // Mirror trial_32.py (960x720) as closely as the camera supports.
                    previewWidth = Trial32Tracker.FRAME_WIDTH
                    previewHeight = Trial32Tracker.FRAME_HEIGHT
                    camera.setPreviewSize(
                        previewWidth,
                        previewHeight,
                        UVCCamera.FRAME_FORMAT_MJPEG,
                    )
                } catch (_: IllegalArgumentException) {
                    try {
                        camera.setPreviewSize(
                            previewWidth,
                            previewHeight,
                            UVCCamera.DEFAULT_PREVIEW_MODE,
                        )
                    } catch (_: IllegalArgumentException) {
                        previewWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH
                        previewHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT
                        try {
                            camera.setPreviewSize(
                                previewWidth,
                                previewHeight,
                                UVCCamera.FRAME_FORMAT_MJPEG,
                            )
                        } catch (_: IllegalArgumentException) {
                            camera.setPreviewSize(
                                previewWidth,
                                previewHeight,
                                UVCCamera.DEFAULT_PREVIEW_MODE,
                            )
                        }
                    }
                }

                synchronized(sync) {
                    uvcCamera = camera
                    isActive = true
                    isPreview = false
                }

                val surface = previewSurface
                if (surface != null) {
                    camera.setPreviewDisplay(surface)
                    camera.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
                    camera.startPreview()
                    synchronized(sync) {
                        isPreview = true
                    }
                    runOnUiThread { setStatus("Preview started. Tap Set reference to start tracking.") }
                } else {
                    runOnUiThread { setStatus("Camera opened. Waiting for preview surface...") }
                }
            } catch (t: Throwable) {
                try {
                    camera.destroy()
                } catch (_: Throwable) {
                }
                runOnUiThread { setStatus("Failed to open camera: ${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    private fun closeCamera() {
        cameraHandler.post {
            synchronized(sync) {
                val camera = uvcCamera
                uvcCamera = null
                isActive = false
                isPreview = false
                try {
                    camera?.setFrameCallback(null, 0)
                } catch (_: Throwable) {
                }
                try {
                    camera?.stopPreview()
                } catch (_: Throwable) {
                }
                try {
                    camera?.destroy()
                } catch (_: Throwable) {
                }
            }
            runOnUiThread {
                setStatus("Camera disconnected.")
                overlayView.setFrameState(null)
                barsView.setFrameState(null)
            }
        }
    }

    private val deviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            runOnUiThread {
                if (!ensureCameraPermission()) return@runOnUiThread
                requestUsbPermissionForFirstDeviceIfOnlyOne(device)
            }
        }

        override fun onConnect(device: UsbDevice?, ctrlBlock: UsbControlBlock?, createNew: Boolean) {
            if (ctrlBlock == null) return
            runOnUiThread { setStatus("USB permission granted. Opening camera...") }
            openCamera(ctrlBlock)
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: UsbControlBlock?) {
            closeCamera()
        }

        override fun onDettach(device: UsbDevice?) {
            runOnUiThread { setStatus("USB device detached.") }
        }

        override fun onCancel(device: UsbDevice?) {
            runOnUiThread { setStatus("USB permission denied. Unplug/replug and try again.") }
        }
    }

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            // no-op
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (width == 0 || height == 0) return

            previewSurface = holder.surface
            synchronized(sync) {
                if (isActive && !isPreview && uvcCamera != null && previewSurface != null) {
                    try {
                        uvcCamera?.setPreviewDisplay(previewSurface)
                        uvcCamera?.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
                        uvcCamera?.startPreview()
                        isPreview = true
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            previewSurface = null
            synchronized(sync) {
                try {
                    uvcCamera?.setFrameCallback(null, 0)
                } catch (_: Throwable) {
                }
                try {
                    uvcCamera?.stopPreview()
                } catch (_: Throwable) {
                }
                isPreview = false
            }
        }
    }

    private fun setStatus(message: String) {
        statusText.text = message
    }

    private fun updatePerformanceMetrics() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().apply {
            activityManager.getMemoryInfo(this)
        }

        val totalRam = memoryInfo.totalMem
        val availRam = memoryInfo.availMem
        val usedRam = totalRam - availRam
        val memoryPercent = if (totalRam > 0) usedRam * 100.0 / totalRam else 0.0
        val lowMemoryState = if (memoryInfo.lowMemory) "YES" else "NO"

        val runtime = Runtime.getRuntime()
        val appUsedRam = runtime.totalMemory() - runtime.freeMemory()
        val appMaxRam = runtime.maxMemory()

        // Get app's actual process memory (PSS)
        val pssKb = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))[0].totalPss
        val appPidMemory = pssKb * 1024L
        val appRamPercent = if (totalRam > 0) appPidMemory * 100.0 / totalRam else 0.0

        val cpuUsage = readCpuUsagePercent()
        val appCpuUsage = readAppCpuUsagePercent()

        performanceText.text = buildString {
            append("RAM total: ${formatBytes(totalRam)}\n")
            append("RAM used: ${formatBytes(usedRam)} (${String.format(Locale.US, "%.1f%%", memoryPercent)})\n")
            append("RAM available: ${formatBytes(availRam)}\n")
            append("Low memory threshold: ${formatBytes(memoryInfo.threshold)}\n")
            append("Low memory: $lowMemoryState\n")
            append("App memory (heap): ${formatBytes(appUsedRam)} / ${formatBytes(appMaxRam)}\n")
            append("App RAM of total: ${formatBytes(appPidMemory)} (${String.format(Locale.US, "%.1f%%", appRamPercent)})\n")
            append("FPS (processed): ${String.format(Locale.US, "%.1f", currentFps)}\n")
            append("CPU load: ${String.format(Locale.US, "%.1f%%", cpuUsage)}\n")
            append("App CPU contribution: ${String.format(Locale.US, "%.1f%%", appCpuUsage)}\n")
            append("GPU usage: N/A (not available via Android APIs)")
        }
    }

    private fun formatBytes(bytes: Long): String {
        return Formatter.formatFileSize(this, bytes)
    }

    private fun readCpuUsagePercent(): Double {
        return try {
            val statFile = File("/proc/stat")
            if (!statFile.exists()) return 0.0
            val line = statFile.useLines { lines -> lines.firstOrNull() } ?: return 0.0
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 5 || parts[0] != "cpu") return 0.0

            val user = parts[1].toLongOrNull() ?: 0L
            val nice = parts[2].toLongOrNull() ?: 0L
            val system = parts[3].toLongOrNull() ?: 0L
            val idle = parts[4].toLongOrNull() ?: 0L
            val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0L
            val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0L
            val softIrq = parts.getOrNull(7)?.toLongOrNull() ?: 0L

            val total = user + nice + system + idle + iowait + irq + softIrq
            val usage = if (lastCpuStatTotal <= 0L) {
                0.0
            } else {
                val totalDiff = total - lastCpuStatTotal
                val idleDiff = idle - lastCpuStatIdle
                if (totalDiff <= 0L) 0.0 else (100.0 * (totalDiff - idleDiff) / totalDiff).coerceIn(0.0, 100.0)
            }

            lastCpuStatTotal = total
            lastCpuStatIdle = idle
            usage
        } catch (_: Exception) {
            0.0
        }
    }

    private fun readAppCpuUsagePercent(): Double {
        return try {
            val currentAppCpuTime = android.os.Process.getElapsedCpuTime() // ms of CPU used by this process
            val currentWall = System.currentTimeMillis()

            val appCpuDiff = currentAppCpuTime - lastAppCpuTime
            val wallDiff = currentWall - lastWallTime

            lastAppCpuTime = currentAppCpuTime
            lastWallTime = currentWall

            if (wallDiff <= 0L || appCpuDiff <= 0L) return 0.0

            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            // Percentage = (app cpu ms during interval) / (wall ms * cores) * 100
            val pct = (appCpuDiff.toDouble() / (wallDiff.toDouble() * cores.toDouble())) * 100.0
            pct.coerceIn(0.0, 100.0)
        } catch (_: Exception) {
            0.0
        }
    }

    private val frameCallback = IFrameCallback { frame: ByteBuffer ->
        // Update FPS
        frameCount++
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFpsTime >= 1000) {
            currentFps = frameCount.toDouble()
            frameCount = 0
            lastFpsTime = currentTime
        }

        val t = tracker ?: return@IFrameCallback
        if (!openCvOk) return@IFrameCallback

        val w = previewWidth
        val h = previewHeight
        if (w <= 0 || h <= 0) return@IFrameCallback

        val ySize = w * h
        if (ySize <= 0) return@IFrameCallback

        frame.clear()
        if (frame.remaining() < ySize) return@IFrameCallback

        if (yPlane.size != ySize) {
            yPlane = ByteArray(ySize)
        }
        frame.get(yPlane, 0, ySize)

        val grayRaw = grayRawMat?.let { existing ->
            if (existing.rows() == h && existing.cols() == w && existing.type() == CvType.CV_8UC1) {
                existing
            } else {
                existing.release()
                Mat(h, w, CvType.CV_8UC1).also { grayRawMat = it }
            }
        } ?: Mat(h, w, CvType.CV_8UC1).also { grayRawMat = it }

        grayRaw.put(0, 0, yPlane)

        val state = synchronized(trackerLock) {
            t.process(grayRaw)
        }

        runOnUiThread {
            overlayView.setFrameState(state)
            barsView.setFrameState(state)
            state.status_message?.let { statusText.text = it }
        }
    }
}
