package jp.co.microgear

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.android.synthetic.main.activity_gps.*
import kotlinx.android.synthetic.main.activity_gps_video.*
import kotlinx.android.synthetic.main.activity_gps_video.MovieTime
import kotlinx.android.synthetic.main.activity_gps_video.locationText
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.schedule

class GpsActivity : AppCompatActivity() {
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
     * 撮影開始/終了のサウンドを再生するオブジェクト
     */
    private val sound = MediaActionSound()

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gps)

        // 没入型モード設定
        setImmersiveMode()

        // 必要なすべての権限をチェック
        val deniededPermissions = arrayOf(
            // 外部記憶領域への読み書き権限
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE,    // 本来不要(WRITE_EXTERNAL_STORAGEはこの権限も包含する)
            // 位置情報権限
            Manifest.permission.ACCESS_COARSE_LOCATION, // 無線ネットワークを使って大まかな位置情報を取得する権限
            Manifest.permission.ACCESS_FINE_LOCATION    // GPSによる詳細な位置情報を取得する権限
        ).filter { permission -> ContextCompat.checkSelfPermission(this,permission) != PackageManager.PERMISSION_GRANTED }

        if (deniededPermissions.count() > 0) {
            // 権限が足りない場合はリクエストする
            ActivityCompat.requestPermissions(this, deniededPermissions.toTypedArray(), PERMISSION_CODE)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.fromParts("package", this.packageName, null)
                startActivity(intent)
                // 現在のアクティビティを閉じる
                finish()
            } else {
                // 権限が満たされている場合は位置情報のリッスンを開始
                startLocationListener()
            }
        }

        // 撮影音をロード
        sound.load(MediaActionSound.START_VIDEO_RECORDING)
        sound.load(MediaActionSound.STOP_VIDEO_RECORDING)

        // 画面がスリープしないように設定
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        if (requestCode == GpsVideoActivity.PERMISSION_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("debug", "checkSelfPermission true")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    !Environment.isExternalStorageManager()) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.fromParts("package", this.packageName, null)
                    startActivity(intent)
                    // 現在のアクティビティを閉じる
                    finish()
                } else {
                    // 使用が許可された時は位置情報のリッスンを開始
                    startLocationListener()
                }
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
                .setMessage("ゆーちーずは位置情報が(GPS、無線ネットワーク共に)有効になるまで使用できません。")
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
        super.onResume()

        // 録画開始/停止ボタンのイベントハンドラ
        gpsSwitch.setOnCheckedChangeListener { _, isChecked -> onSwitchChanged(isChecked) }
    }

    /**
     * 撮影開始/停止スイッチ切替時の処理
     */
    private fun onSwitchChanged(isChecked: Boolean) {
        if (isChecked) {
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
            gpsSwitch.isChecked = false
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

        val loggingFile = File(movieDir, "${recordingStartTime}_GPS.gpx")

        // 撮影時刻を更新するタイマーの開始
        movieTimeUpdateTimer = Timer()
        movieTimeUpdateTimer!!.schedule(1000, 1000) { tickMovieTime() }
        // 位置情報の記録を開始
        val interval =  (AppSettings.markerUpdateInterval * 1000).toLong()
        gpsLogger.startLogging(loggingFile, interval)

        // 撮影開始の音声を再生する
        sound.play(MediaActionSound.START_VIDEO_RECORDING)
    }

    /**
     * 撮影を終了する
     */
    private fun stopRecording() {
        // 画面の回転をアンロック（デフォルトに戻す）
        setLockOrientation(false)

        // 撮影中であれば、タイマーを停止する
        movieTimeUpdateTimer?.cancel()
        movieTimeUpdateTimer = null
        resetMovieTime()
        if(gpsLogger.isStarted) {
            // 位置情報のロギングを停止する
            gpsLogger.stopLogging()
            // 撮影終了の音声を再生する
            sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        }
    }

    /**
     * 撮影時間の表示を1秒更新する
     */
    private fun tickMovieTime() {
        movieTime += 1
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
     * ウィンドウフォーカス移動時処理
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            setImmersiveMode()
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
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (movieTimeUpdateTimer != null) {
            // 撮影中なら「戻る」処理無効
            Toast.makeText(this,"GPS記録を停止してから終了してください", Toast.LENGTH_SHORT).show()
        } else {
            // 撮影中なら「戻る」処理有効
            super.onBackPressed()
        }
    }
}