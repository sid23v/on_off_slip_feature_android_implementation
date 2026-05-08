package com.example.uvcviewer

import android.Manifest
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera

class MainActivity : AppCompatActivity() {
    private val sync = Any()

    private lateinit var usbMonitor: USBMonitor
    private var uvcCamera: UVCCamera? = null

    private lateinit var cameraThread: HandlerThread
    private lateinit var cameraHandler: Handler

    private var previewSurface: Surface? = null
    private var isActive = false
    private var isPreview = false

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var retryButton: Button
    private lateinit var disconnectButton: Button

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                setStatus("Camera permission granted. Plug in the USB camera.")
                // If the app was launched by USB attach intent, try to request USB permission now.
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

        surfaceView = findViewById(R.id.camera_surface_view)
        statusText = findViewById(R.id.status_text)
        retryButton = findViewById(R.id.retry_button)
        disconnectButton = findViewById(R.id.disconnect_button)

        surfaceView.holder.addCallback(surfaceCallback)

        cameraThread = HandlerThread("UvcCameraThread").apply { start() }
        cameraHandler = Handler(cameraThread.looper)

        usbMonitor = USBMonitor(this, deviceConnectListener)

        retryButton.setOnClickListener {
            if (!ensureCameraPermission()) return@setOnClickListener
            requestUsbPermissionForFirstDevice()
        }

        disconnectButton.setOnClickListener {
            closeCamera()
        }

        if (ensureCameraPermission()) {
            setStatus("Ready. Plug in the USB camera (OTG).")
            maybeRequestUsbPermissionFromIntent(intent)
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
    }

    override fun onStop() {
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
        super.onDestroy()
    }

    private fun ensureCameraPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PermissionChecker.PERMISSION_GRANTED
        if (!granted) {
            setStatus("Requesting camera permission…")
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        return granted
    }

    private fun maybeRequestUsbPermissionFromIntent(intent: android.content.Intent): Boolean {
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
        if (device != null) {
            if (!ensureCameraPermission()) return false
            setStatus("USB device attached. Requesting permission…")
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
        setStatus("Requesting permission for USB device…")
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
            setStatus("USB camera detected. Requesting permission…")
            usbMonitor.requestPermission(device)
        } else {
            setStatus("Multiple USB devices detected. Tap Retry and unplug extra devices if needed.")
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
                    camera.setPreviewSize(
                        UVCCamera.DEFAULT_PREVIEW_WIDTH,
                        UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                        UVCCamera.FRAME_FORMAT_MJPEG,
                    )
                } catch (_: IllegalArgumentException) {
                    // Fallback to the library default mode (usually YUV)
                    camera.setPreviewSize(
                        UVCCamera.DEFAULT_PREVIEW_WIDTH,
                        UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                        UVCCamera.DEFAULT_PREVIEW_MODE,
                    )
                }

                synchronized(sync) {
                    uvcCamera = camera
                    isActive = true
                    isPreview = false
                }

                val surface = previewSurface
                if (surface != null) {
                    camera.setPreviewDisplay(surface)
                    camera.startPreview()
                    synchronized(sync) {
                        isPreview = true
                    }
                    runOnUiThread { setStatus("Preview started.") }
                } else {
                    runOnUiThread { setStatus("Camera opened. Waiting for preview surface…") }
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
                    camera?.stopPreview()
                } catch (_: Throwable) {
                }
                try {
                    camera?.destroy()
                } catch (_: Throwable) {
                }
            }
            runOnUiThread { setStatus("Camera disconnected.") }
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
            runOnUiThread { setStatus("USB permission granted. Opening camera…") }
            openCamera(ctrlBlock)
        }

        override fun onDisconnect(device: UsbDevice?, ctrlBlock: UsbControlBlock?) {
            closeCamera()
        }

        override fun onDettach(device: UsbDevice?) {
            runOnUiThread { setStatus("USB device detached.") }
        }

        override fun onCancel(device: UsbDevice?) {
            runOnUiThread { setStatus("USB permission denied. Tap Retry to request again.") }
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
}
