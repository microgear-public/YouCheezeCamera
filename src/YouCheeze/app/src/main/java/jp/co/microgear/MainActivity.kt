package jp.co.microgear

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import kotlinx.android.synthetic.main.activity_gps_video.*
import kotlinx.android.synthetic.main.activity_main.*
import java.util.*
import kotlin.concurrent.schedule


class MainActivity : AppCompatActivity() {

    /**
     * QRコードオーバーレイの処理状況を更新するタイマー
     */
    private var qrOverlayProcWatchTimer = Timer()

    var myRecorder:MediaRecorder? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val videoSize:Size

        // QRコードオーバーレイ処理状況表示更新タイマーを初期化
        qrOverlayProcWatchTimer.schedule(0, 500) { updateQrOverlayProcMsg() }
    }

    override fun onResume() {
        super.onResume()
        menuSetting.setOnClickListener {

            val intent = Intent(this, SettingActivity::class.java)
            startActivity(intent)
        }

        menuGps.setOnClickListener {
            val intent = Intent(this, GpsActivity::class.java)
            startActivity(intent)
        }

        menuMovie.setOnClickListener {
            val intent = Intent(this, GpsVideoActivity::class.java)
            startActivity(intent)
        }

        menuFolder.setOnClickListener {
            // ACTION_VIEWのIntentを生成
            val intent = Intent(Intent.ACTION_VIEW)
            intent.type = DocumentsContract.Document.MIME_TYPE_DIR
            intent.flags = (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

            try {
                // アクティビティ開始
                startActivity(intent)

                // 撮影ファイル格納場所を示すメッセージを表示
                Toast.makeText(this,
                    getString(R.string.message_saved_file_location), Toast.LENGTH_LONG ).show()
            } catch (e: ActivityNotFoundException) {
                // ファイルアプリ起動失敗
                Toast.makeText(this,
                    getString(R.string.message_intent_not_found), Toast.LENGTH_SHORT ).show()
            }
        }

        btnToSite.setOnClickListener{
            val uri = Uri.parse(getString(R.string.url_you_cheeze_converter))
            val intent = Intent(Intent.ACTION_VIEW, uri)
            try {
                // アクティビティ開始
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // ファイルアプリ起動失敗
                Toast.makeText(this,
                    getString(R.string.message_intent_not_found), Toast.LENGTH_SHORT ).show()
            }
        }

        txtLicenses.text = HtmlCompat.fromHtml(
            String.format("<u>%s</u>", getString(R.string.menu_licenses)), FROM_HTML_MODE_COMPACT);
        txtLicenses.setOnClickListener {
            val intent = Intent(this, OssLicensesMenuActivity::class.java)
            intent.putExtra("title", getString(R.string.title_licenses))
            startActivity(intent)
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
            if (textProcmsg.text != message) {
                textProcmsg.text = message
            }
        }
    }
}