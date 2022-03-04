package jp.co.microgear.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import jp.co.microgear.R
import java.lang.Long
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore

class CameraView : Fragment() {
    private val background = CameraBackgroundThread()
    private lateinit var cameraController: CameraController
    var cameraViewListener: CameraViewListener? = null
    private var startBooking = false
    private var canStart = false

    override fun onCreateView(inflater: LayoutInflater,
                              container: ViewGroup?,
                              savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.camera_view, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val texturePreview = view.findViewById<AutoFitTextureView>(R.id.preview)
        if (startBooking) {
            cameraController = CameraController(requireActivity(), texturePreview, background, cameraViewListener)
            background.start()
        } else {
            canStart = true
        }
    }

    fun start() {
        if (!::cameraController.isInitialized) {
            if (canStart) {
                val texturePreview = requireView().findViewById<AutoFitTextureView>(R.id.preview)
                cameraController = CameraController(requireActivity(), texturePreview, background, cameraViewListener)
                background.start()
            }
            startBooking = true
        } else if (!cameraController.openedCamera) {
            cameraController.openCamera()
        }
    }

    fun stop() {
        if (::cameraController.isInitialized) {
            cameraController?.closeCamera()
            background.stop()
        }
    }

    fun startVideo(filePath: String, startedCallback: () -> Unit) {
        cameraController.startRecording(filePath, startedCallback)
    }

    fun stopVideo(stopedCallback: () -> Unit) {
        cameraController.stopRecording(stopedCallback)
    }

    interface CameraViewListener {
        fun onError()
    }

    private class CameraController(val activity: FragmentActivity, val preview: AutoFitTextureView, val backgroundThread: CameraBackgroundThread, private val cameraViewListener: CameraViewListener?) {

        private val lock = Semaphore(1)
        private var mediaRecorder: MediaRecorderController ? = null
        private var cameraDevice: CameraDevice? = null
        private lateinit var previewSize: Size
        private var captureSession: CameraCaptureSession? = null

        val openedCamera: Boolean = cameraDevice != null

        init {
            // textureViewが有効になったタイミングでカメラをopenできるようにListenerを登録しておく
            this.preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    openCamera(width, height)
                }

                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                }

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) = true

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
            // もしも、すでにtextureViewが有効になっているならここでopenCamera
            if (this.preview.isAvailable) {
                openCamera()
            }
        }

        fun openCamera() {
            openCamera(this.preview.width, this.preview.height)
        }

        @SuppressLint("MissingPermission")
        fun openCamera(width: Int, height: Int) {
            val manager = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // カメラリソースをロック
            if (!lock.tryAcquire()) {
                throw java.lang.RuntimeException("Time out waiting to lock camera opening.")
            }
            // カメラIDを取得（背面カメラ）
            val cameraId = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList[0]

            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw RuntimeException("Cannot get available preview/video sizes")
            // デバイスの基準の向きを取得
            val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
            // サイズを選択 NOTE:色々選べるらしい
            val videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java), Size(16, 9))
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java), width, height, videoSize)
            // プレビューを表示するtextureViewにアスペクト比を設定
            if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                preview.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                preview.setAspectRatio(previewSize.height, previewSize.width)
            }
            mediaRecorder = MediaRecorderController(
                sensorOrientation!!,
                activity.windowManager.defaultDisplay.rotation,
                videoSize)

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {

                override fun onOpened(camera: CameraDevice) {
                    lock.release()
                    cameraDevice = camera
                    startPreview() {}
                    configureTransform(preview.width, preview.height)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    lock.release()
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    lock.release()
                    camera.close()
                    cameraDevice = null
                    cameraViewListener?.onError()
                }

            }, null)
        }

        fun closeCamera() {
            try {
                lock.acquire()
                stopPreview()
                cameraDevice?.close()
                cameraDevice = null
                mediaRecorder?.release()
                mediaRecorder = null
            } finally {
                lock.release()
            }
        }

        /**
         * あんまり大きいサイズだとキャプチャできないらしいので「最大幅=1080」としている
         */
        private fun chooseVideoSize(choices: Array<Size>, aspectRatio: Size) = choices.firstOrNull {
            it.width == it.height * aspectRatio.width / aspectRatio.height && it.width <= 1080 } ?: choices[choices.size - 1]

        private fun chooseOptimalSize(
            choices: Array<Size>,
            width: Int,
            height: Int,
            aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val w = aspectRatio.width
            val h = aspectRatio.height
            val bigEnough = choices.filter {
                it.height == it.width * h / w && it.width >= width && it.height >= height }

            // Pick the smallest of those, assuming we found any
            return if (bigEnough.isNotEmpty()) {
                Collections.min(bigEnough) { lhs, rhs ->
                    // We cast here to ensure the multiplications won't overflow
                    Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
                }
            } else {
                choices[0]
            }
        }

        private fun startPreview(startedCallback: () -> Unit) {
            // まだカメラがopenしていなければ何もしない
            if (cameraDevice == null || !preview.isAvailable) return;
            // プレビューがすでに開始しているならいったん止める
            stopPreview()
            // プレビュー用のtextureViewからsurfaceを取得
            val texture = preview.surfaceTexture?.apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }
            val previewSurface = Surface(texture)
            // キャプチャリクエストの生成
            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice!!.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(previewSurface)), Executors.newCachedThreadPool(), object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview(previewRequestBuilder)
                    activity?.runOnUiThread {
                        startedCallback()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            }))
        }

        private fun stopPreview() {
            captureSession?.close()
            captureSession = null
        }

        private fun updatePreview(previewRequestBuilder: CaptureRequest.Builder) {
            if (cameraDevice == null) return

            previewRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            captureSession?.setRepeatingRequest(previewRequestBuilder.build(),
                null, backgroundThread.backgroundHandler)
        }

        private fun configureTransform(viewWidth: Int, viewHeight: Int) {
            activity ?: return
            val rotation = (activity as FragmentActivity).windowManager.defaultDisplay.rotation
            val matrix = Matrix()
            val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
            val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
            val centerX = viewRect.centerX()
            val centerY = viewRect.centerY()

            if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                val scale = Math.max(
                    viewHeight.toFloat() / previewSize.height,
                    viewWidth.toFloat() / previewSize.width)
                with(matrix) {
                    postScale(scale, scale, centerX, centerY)
                    postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
                }
            }
            preview.setTransform(matrix)
        }

        fun startRecording(filePath: String, startedCallback: () -> Unit) {
            // まだカメラがopenしていなければ何もしない
            if (cameraDevice == null || !preview.isAvailable) return
            // プレビューがすでに開始しているならいったん止める
            stopPreview()
            // プレビュー用のtextureViewからsurfaceを取得
            val texture = preview.surfaceTexture?.apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }
            val previewSurface = Surface(texture)
            // 撮影用のsurfaceを取得
            mediaRecorder?.setup(filePath)
            val recorderSurface = mediaRecorder!!.surface
            // キャプチャリクエストの生成
            val previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }
            cameraDevice!!.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(OutputConfiguration(previewSurface), OutputConfiguration(recorderSurface)), Executors.newCachedThreadPool(), object : CameraCaptureSession.StateCallback() {

                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updatePreview(previewRequestBuilder)
                    activity?.runOnUiThread {
                        mediaRecorder?.start()
                        startedCallback()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) = Unit
            }))
        }

        fun stopRecording(stopedCallback: () -> Unit) {
            mediaRecorder?.stop()
            startPreview(stopedCallback)
        }
    }

    /**
     * @param sensorOrientation デバイスの基準の向き
     * @param rotation デバイスの現在の回転
     * @param size 撮影する映像のサイズ
     */
    private class MediaRecorderController(private val sensorOrientation: Int, private val rotation: Int, private val size: Size) {
        val mediaRecorder = MediaRecorder()
        val surface: Surface get() { return mediaRecorder.surface }

        init {
            when (sensorOrientation) {
                SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                    mediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
                SENSOR_ORIENTATION_INVERSE_DEGREES ->
                    mediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
            }
        }

        fun release() {
            mediaRecorder.release()
        }

        fun start() {
            mediaRecorder.start()
        }

        fun stop() {
            mediaRecorder.apply {
                stop()
                reset()
            }
        }

        fun setup(filePath: String) {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(filePath)
                setVideoEncodingBitRate(10000000)
                setVideoFrameRate(30)
                setVideoSize(size.width, size.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                prepare()
            }
        }

        companion object {

            private const val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90

            private const val SENSOR_ORIENTATION_INVERSE_DEGREES = 270

            private val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
                append(Surface.ROTATION_0, 90)
                append(Surface.ROTATION_90, 0)
                append(Surface.ROTATION_180, 270)
                append(Surface.ROTATION_270, 180)
            }
            private val INVERSE_ORIENTATIONS = SparseIntArray().apply {
                append(Surface.ROTATION_0, 270)
                append(Surface.ROTATION_90, 180)
                append(Surface.ROTATION_180, 90)
                append(Surface.ROTATION_270, 0)
            }

        }
    }

    private class CameraBackgroundThread {
        private val _tag = "CameraView.CameraBackgroundThread"

        var backgroundThread: HandlerThread? = null
            private set

        var backgroundHandler: Handler? = null
            private set

        fun start() {
            backgroundThread = HandlerThread("CameraBackground")
            backgroundThread!!.start()
            backgroundHandler = Handler(backgroundThread!!.looper)
        }

        fun stop() {
            backgroundThread?.quitSafely()
            try {
                backgroundThread?.join()
                backgroundThread = null
                backgroundHandler = null
            } catch (e: InterruptedException) {
                Log.e(_tag, e.toString())
            }
        }
    }
}