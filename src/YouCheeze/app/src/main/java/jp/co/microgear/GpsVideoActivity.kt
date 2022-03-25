package jp.co.microgear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.media.MediaActionSound
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import jp.co.microgear.widget.CameraView
import kotlinx.android.synthetic.main.activity_gps_video.*
import java.io.File
import java.lang.Exception
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule

class GpsVideoActivity : AppCompatActivity() {
    private lateinit var gpsLogger: GpsLogger
    companion object {
        // 権限リクエスト時のコード（任意の値）
        const val PERMISSION_CODE = 1000
    }

    /**
     * 撮影時間
     */
    private var movieTime = 0

    /**
     * 撮影中の撮影時間表示を更新するタイマー
     */
    private var movieTimeUpdateTimer: Timer? = null

    /**
     * QRコードオーバーレイの処理状況を更新するタイマー
     */
    private var qrOverlayProcWatchTimer = Timer()

    /**
     * QRコード オーバーレイヘルパー
     */
    private val qrCodeOverlay = QrCodeOverlay()

    /**
     * 撮影開始/終了のサウンドを再生するオブジェクト
     */
    private val sound = MediaActionSound()

    /**
     * CameraViewフラグメント
     */
    private lateinit var cameraView: CameraView

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gps_video)
        if (savedInstanceState == null) {
            // CameraViewを画面にセット
            cameraView = CameraView()
            supportFragmentManager.beginTransaction().replace(R.id.cameraViewContainer, cameraView).commit()
        } else {
            cameraView = supportFragmentManager.fragments.firstOrNull() { it is CameraView } as CameraView
        }
        cameraView.cameraViewListener = object : CameraView.CameraViewListener {
            override fun onError() {
                // カメラでエラーが発生したら終了する
                finish()
            }

        }

        // 没入型モード設定
        setImmersiveMode()

        // 必要なすべての権限をチェック
        val deniededPermissions = arrayOf(
            // 外部記憶領域への読み書き権限
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,    // 本来不要(WRITE_EXTERNAL_STORAGEはこの権限も包含する)
            // 撮影権限
            Manifest.permission.CAMERA,
            // マイク権限
            Manifest.permission.RECORD_AUDIO,
            // 位置情報権限
            Manifest.permission.ACCESS_COARSE_LOCATION, // 無線ネットワークを使って大まかな位置情報を取得する権限
            Manifest.permission.ACCESS_FINE_LOCATION    // GPSによる詳細な位置情報を取得する権限
        ).filter { permission -> ContextCompat.checkSelfPermission(this,permission) != PackageManager.PERMISSION_GRANTED }

        if (deniededPermissions.count() > 0) {
            // 権限が足りない場合はリクエストする
            ActivityCompat.requestPermissions(this, deniededPermissions.toTypedArray(), PERMISSION_CODE)
        } else {
            // 権限が満たされている場合は位置情報のリッスンを開始
            startLocationListener()
            // カメラプレビューの開始
            cameraView.start()
        }

        // 撮影音をロード
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        // 画面がスリープしないように設定
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // QRコードオーバーレイ処理状況表示更新タイマーを初期化
        qrOverlayProcWatchTimer.schedule(0, 500) { updateQrOverlayProcMsg() }
    }

    /**
     * 権限リクエストのコールバック処理
     */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("debug", "checkSelfPermission true")
                // 使用が許可された時は位置情報のリッスンを開始
                startLocationListener()
                // カメラプレビューの開始
                cameraView.start()
            } else {
                // 拒否された時はトーストを表示して終了
                Toast.makeText(this,"必要な権限が許可されませんでした", Toast.LENGTH_SHORT).show()
                // 現在のアクティビティを閉じる
                finish()
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    /**
     * 位置情報リスナーを開始する
     */
    private fun startLocationListener() {
        Log.d("debug", "startLocationListener()")

        // Instances of LocationManager class must be obtained using Context.getSystemService(Class)
        gpsLogger = GpsLogger(getSystemService(Context.LOCATION_SERVICE) as LocationManager)
        gpsLogger.setOnLocationChangeListener { location ->
            // 位置情報が更新されたら、画面に表示する
            locationText.text = "${"%,.5f".format(location.latitude)}, ${"%,.5f".format(location.longitude)}"
        }

        if (!gpsLogger.starListener()) {
            // 位置情報が有効ではない場合、ダイアログを表示
            AlertDialog.Builder(this)
                .setTitle("位置情報が有効ではありません")
                .setMessage("ゆーちーずカメラは位置情報が(GPS、無線ネットワーク共に)有効になるまで使用できません。")
                .setPositiveButton("設定を開く") { _, _ ->
                    // 設定アプリに遷移
                    val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                    startActivity(settingsIntent)
                    // 設定からメニューへ戻るために現在のアクティビティは終了しておく
                    finish()
                }
                .setNegativeButton("終了する") { _, _ ->
                    finish()
                }
                .show()
        }
    }

    override fun onResume() {
        // 録画ボタンの表示制御
        when(resources.configuration.orientation) {
            // 横向きの場合
            Configuration.ORIENTATION_LANDSCAPE -> {
                // 設定に従いボタン位置を変更
                when(AppSettings.startButtonPos) {
                    StartButtonPosition.RIGHT -> {
                        // 画面右側
                        setRecButtonPos(false)
                    }
                    else -> {
                        // 画面左側
                        setRecButtonPos(true)
                    }
                }
            }
            else -> {
                // 縦向きの場合は下部固定
                setRecButtonPos(false)
            }
        }

        super.onResume()

        // 録画開始/停止ボタンのイベントハンドラ
        uptakebtn.setOnClickListener { onClickRecording() }
        downtakebtn.setOnClickListener { onClickRecording() }
    }

    /**
     * 撮影開始/停止ボタンタップ時の処理
     */
    private fun onClickRecording() {
        if (movieTimeUpdateTimer == null) {
            // 撮影中でなければ撮影開始
            startRecording()
        } else {
            // 撮影中なら撮影終了
            stopRecording()
        }
    }

    /**
     * 撮影を開始する
     */
    private fun startRecording() {
        if (!gpsLogger.isStartedLocationListening) {
            Toast.makeText(this, "GPS特定中のためお待ちください", Toast.LENGTH_LONG).show()
            return
        }

        // 画面の回転をロック
        setLockOrientation(true)

        val recordingStartTime = getToday()

        // 出力フォルダを初期化
        val movieDir = AppSettings.movieSaveDir
        if (!movieDir.exists()) {
            movieDir.mkdirs()
        }

        val movieWorkDir = File(applicationContext.getExternalFilesDir(null), recordingStartTime)

        val tempVideoFile = File(movieWorkDir, "$recordingStartTime.mp4")
        qrCodeOverlay.initialize(
            movieWorkDir,
            tempVideoFile,
            File(movieDir, "${recordingStartTime}_Video.mp4")
        )

        // 撮影時刻を更新するタイマーの開始
        movieTimeUpdateTimer = Timer()
        movieTimeUpdateTimer!!.schedule(1000, 1000) { tickMovieTime() }

        if (movieWorkDir.exists()) {
            movieWorkDir.listFiles().forEach { it.delete() }
            Files.deleteIfExists(movieWorkDir.toPath())
            Files.createDirectory(movieWorkDir.toPath())
        } else {
            Files.createDirectory(movieWorkDir.toPath())
        }
        // 位置情報の記録を開始
        val interval = (AppSettings.markerUpdateInterval * 1000).toLong()
        gpsLogger.startLogging(qrCodeOverlay, interval)

        // 動画撮影を開始
        cameraView.startVideo(tempVideoFile.absolutePath) {
            setRecButtonState(ButtonState.Stop)
        }

        // 撮影開始の音声を再生する
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
        // 撮影ボタンの状態を更新
        setRecButtonState(ButtonState.Hidden)
    }

    /**
     * 撮影を終了する
     */
    private fun stopRecording() {
        var retryCounter = 0;
        while (true) {
            try {
                // 画面の回転をアンロック（デフォルトに戻す）
                setLockOrientation(false)

                // 撮影中であれば、タイマーを停止する
                movieTimeUpdateTimer?.cancel()
                movieTimeUpdateTimer = null
                resetMovieTime()
                if(gpsLogger.isStarted) {
                    // 位置情報のロギングを停止する
                    gpsLogger.stopLogging()
                    // 撮影を停止する
                    cameraView.stopVideo() {
                        // 撮影停止完了後
                        setRecButtonState(ButtonState.Start)
                    }
                    // 座標のQRコードをオーバーレイする
                    qrCodeOverlay.executeOverlay(AppSettings.markerUpdateInterval, applicationContext)
                    // 撮影終了の音声を再生する
                    sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
                }
                // 撮影ボタンの状態を更新
                setRecButtonState(ButtonState.Hidden)
                return;
            } catch (ex: Exception) {
                if (retryCounter++ >= 10) throw ex;
                Thread.sleep(300)
            }
        }
    }

    /**
     * 撮影中タイマーイベント処理
     */
    private fun tickMovieTime() {
        movieTime += 1

        // 機能制限あり
        if (AppSettings.MAX_REC_RESTRICTION_MIN > 0) {

            // 撮影時間が上限（分刻み）を超えている
            if ((AppSettings.MAX_REC_RESTRICTION_MIN * 60) < movieTime) {
                runOnUiThread { // UI操作を含む為、メインスレッドで実行

                    // 撮影停止しメッセージを表示
                    stopRecording()
                    Toast.makeText(this, String.format(getString(R.string.message_rec_restriction),
                        AppSettings.MAX_REC_RESTRICTION_MIN), Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        // 撮影時間表示を更新
        MovieTime.text = "${"%0,2d".format(movieTime / 60)}:${"%0,2d".format(movieTime % 60)}"
    }

    /**
     * 撮影時間を 00:00 に戻す
     */
    private fun resetMovieTime() {
        movieTime = 0
        MovieTime.text = "00:00"
    }

    /**
     * 撮影開始／停止ボタン位置切り替え
     */
    private fun setRecButtonPos(enableUpper: Boolean) {
        if (enableUpper) {
            // 上部ボタン表示
            uptakebtn.visibility = View.VISIBLE
            downtakebtn.visibility = View.GONE
            if (upprocmsg == null) {
                downprocmsg.visibility = View.VISIBLE
            } else {
                upprocmsg.visibility = View.VISIBLE
                downprocmsg.visibility = View.GONE
            }
        } else {
            // 下部ボタン表示
            uptakebtn.visibility = View.GONE
            downtakebtn.visibility = View.VISIBLE
            if (upprocmsg != null) {
                upprocmsg.visibility = View.GONE
            }
            downprocmsg.visibility = View.VISIBLE
        }
    }

    /**
     * ウィンドウフォーカス移動時処理
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            setImmersiveMode()
        }
    }

    private enum class ButtonState {
        Start,
        Stop,
        Hidden
    }

    /**
     * 撮影開始／停止ボタン切り替え
     */
    private fun setRecButtonState(state: ButtonState) {
        val buttons: MutableList<ImageButton> = mutableListOf()
        if (uptakebtn.visibility != View.GONE) buttons.add(uptakebtn)
        if (downtakebtn.visibility != View.GONE) buttons.add(downtakebtn)

        if (state == ButtonState.Hidden) {
            buttons.forEach { b -> b.visibility = View.INVISIBLE }
        } else {
            buttons.forEach { b -> b.visibility = View.VISIBLE }
            // イメージ取得
            val image = if (state == ButtonState.Start) R.drawable.rec_start else R.drawable.rec_stop
            // イメージを差し替え
            buttons.forEach { b -> b.setImageResource(image) }
        }
    }

    /**
     * 没入型モード設定
     */
    private fun setImmersiveMode() {
        // フルスクリーン
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
//                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
//                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    private fun getToday(): String {
        val date = Date()
        val format = SimpleDateFormat("YYYYMMddHHmmss", Locale.getDefault())
        return format.format(date)
    }

    private fun setLockOrientation(isLock: Boolean) {
        requestedOrientation = when (isLock) {
            true -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onPause() {
        super.onPause()
        // 撮影中なら停止を呼び出しておく
        if (movieTimeUpdateTimer != null) stopRecording()
        cameraView.stop()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (movieTimeUpdateTimer != null) {
            // 撮影中なら「戻る」処理無効
            Toast.makeText(this,"撮影停止してから終了してください", Toast.LENGTH_SHORT).show()
        } else {
            // 撮影中なら「戻る」処理有効
            super.onBackPressed()
        }
    }

    /**
     * QRコードオーバーレイ処理状況表示
     */
    private fun updateQrOverlayProcMsg() {

        // 実行中・待ちのQRコードオーバーレイ処理数を取得
        val workerCount = QrCodeOverlay.getQueuedWorkerCount(applicationContext)

        // 処理中メッセージを更新
        val message =
            if (workerCount > 0)
                String.format(getString(R.string.message_qr_overlay_processing), workerCount)
            else ""

        // メッセージを更新
        runOnUiThread {
            val procmsg = if (downprocmsg.visibility != View.GONE) downprocmsg else upprocmsg
            if (procmsg.text != message) {
                procmsg.text = message
            }
        }
    }
}