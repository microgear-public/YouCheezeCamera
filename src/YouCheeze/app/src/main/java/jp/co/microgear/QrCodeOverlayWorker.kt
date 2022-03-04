package jp.co.microgear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Worker
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.SessionState
import java.io.File
import java.lang.Exception
import java.util.concurrent.atomic.AtomicInteger

/**
 * QRコードオーバーレイワーカー
 */
class QrCodeOverlayWorker(context: Context?, params: WorkerParameters?) : Worker(
    context!!, params!!
) {
    /**
     * スタティックメンバ
     */
    companion object {

        /**
         * 処理名
         */
        const val WORK_NAME = "YouCheezeQrCodeOverlayProc"

        /**
         * 通知チェンネルID
         */
        private const val NOTIFICATION_CHANNEL = "you_cheeze_qr_code_overlay_worker"

        /**
         * 通知ID
         */
        private val notificationId = AtomicInteger(1)
    }

    /**
     * 通知マネージャ
     */
    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * コンストラクタ
     */
    init {

        // 通知チャンネルを生成
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL) == null) {
            val notificationChannel = NotificationChannel(
                NOTIFICATION_CHANNEL,
                context!!.getString(R.string.qr_overlay_notification_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(notificationChannel)
        }
    }

    /**
     * QRコードオーバーレイ
     */
    override fun doWork(): Result {

        // パラメータを取得
        val qrImageDir = File(inputData.getString("qrImageDirPath")!!)
        val videoFile = File(inputData.getString("videoFilePath")!!)
        val outputFile = File(inputData.getString("outputFilePath")!!)
        val qrCodeJpegFormat = inputData.getString("qrCodeJpegFormat")!!
        val qrSaveInterval = inputData.getInt("qrSaveInterval", AppSettings.DEFAULT_MARKER_UPDATE_INTERVAL)
        val qrCodeVideoFile = File(qrImageDir.path, "putTracking.mp4")

        try {
            // 処理中通知を設定
            val processingNotification = createProcessingNotification(outputFile)
            setForegroundAsync(ForegroundInfo(notificationId.getAndIncrement(), processingNotification))

            // すでに出力結果（動画ファイル）がある場合は削除
            if (outputFile.exists()) outputFile.delete()
            if (qrCodeVideoFile.exists()) qrCodeVideoFile.delete()

            // 座標のQRコードをオーバーレイした動画ファイルを作成
            val completed = executeOverlay(qrImageDir, videoFile, qrCodeVideoFile, outputFile, qrCodeJpegFormat, qrSaveInterval)

            if (!completed) {
                // キャンセル通知
                notificationManager.notify(notificationId.getAndIncrement(), createCanceledNotification(outputFile))
                // 途中でキャンセルされた場合は作りかけたファイルを破棄
                if (outputFile.exists()) outputFile.delete()
                if (qrCodeVideoFile.exists()) qrCodeVideoFile.delete()
            } else {
                // 最後まで動画作成が実行された場合は後処理
                // QRコード画像をディレクトリごと削除
                qrImageDir.listFiles()!!.forEach { it.delete() }
                qrImageDir.delete()

                // 元動画ファイルを削除
                videoFile.delete()

                // 処理完了を通知
                val completedNotification = createCompletedNotification(true, outputFile)
                notificationManager.notify(notificationId.getAndIncrement(), completedNotification)
            }

            // 正常終了
            return Result.success()
        } catch (ex: Exception) {

            // 処理失敗を通知
            val completedNotification = createCompletedNotification(false, outputFile)
            notificationManager.notify(notificationId.getAndIncrement(), completedNotification)
            return Result.failure()
        }
    }

    /**
     * 処理中通知生成
     */
    private fun createProcessingNotification(outputFile: File) : Notification {

        // 処理中断のPendingIntent
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        // メッセージ
        val message = String.format(applicationContext.getString(R.string.qr_overlay_notification_processing_title), outputFile.name)

        // テキスト
        val text = String.format(applicationContext.getString(R.string.qr_overlay_notification_filename_text), outputFile.name)

        // 処理中通知を生成
        return createDefaultNotificationBuilder(message, text)
            .setOngoing(true)   // 消去不可
            .addAction(android.R.drawable.ic_delete, applicationContext.getString(R.string.qr_overlay_notification_cancel), cancelIntent)
            .build()
    }

    /**
     * 完了通知生成
     */
    private fun createCompletedNotification(result: Boolean, outputFile: File) : Notification {

        // メッセージ
        val message = if (result)
            applicationContext.getString(R.string.qr_overlay_notification_completed_title)
        else
            applicationContext.getString(R.string.qr_overlay_notification_failed_title)
        // テキスト
        val text = String.format(applicationContext.getString(R.string.qr_overlay_notification_filename_text), outputFile.name)

        // 完了通知を生成
        return createDefaultNotificationBuilder(message, text)
            .setOngoing(false)
            .build()
    }

    /**
     * キャンセル通知生成
     */
    private fun createCanceledNotification(outputFile: File) : Notification {

        // メッセージ
        val message = applicationContext.getString(R.string.qr_overlay_notification_canceled_title)
        // テキスト
        val text = String.format(applicationContext.getString(R.string.qr_overlay_notification_filename_text), outputFile.name)

        // キャンセル通知を生成
        return createDefaultNotificationBuilder(message, text)
            .setOngoing(false)
            .build()
    }

    /**
     * デフォルト設定の通知ビルダー生成
     */
    private fun createDefaultNotificationBuilder(title: String, text: String) : NotificationCompat.Builder {
        return NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.you_cheeze_icon_small)
            .setLargeIcon((applicationContext.getDrawable(R.drawable.you_cheeze_icon) as BitmapDrawable).bitmap)
    }
    /**
     * QRコードを動画にオーバーレイして保存する
     */
    private fun executeOverlay(
        qrImageDir: File,
        videoFile: File,
        qrCodeVideoFile: File,
        outputFile: File,
        qrCodeJpegFormat: String,
        interval: Int) : Boolean {

        // QRコードの画像からアニメーションを作成
        if (!executeFFmpeg(" -y -r ${"%,.2f".format(1.0 / interval)} -i \"${qrImageDir.path}/${qrCodeJpegFormat}\" -r $interval \"${qrCodeVideoFile.path}\"")) {
            // 途中でキャンセルされた
            return false
        }

        // 作成されたQRコード画像をオリジナルの動画にオーバーレイ
        if (!executeFFmpeg(" -y -i \"${videoFile.path}\" -i \"${qrCodeVideoFile.path}\" -b 8000k -filter_complex \"overlay=x=0:y=0\" -preset ultrafast  \"${outputFile.path}\"")) {
            // 途中でキャンセルされた
            return false
        }

        // 最後まで実行された
        return true
    }

    /**
     * FFMPEGコマンド実行
     * @param command 実行する FFMPEG コマンド
     * @return true=最後まで完了、false=途中でキャンセル
     */
    private fun executeFFmpeg(command: String) : Boolean {
        var canceled = false
        val session = FFmpegKit.executeAsync(command) {}
        do {
            Thread.sleep(500)
            if (this.isStopped && session.state == SessionState.RUNNING) {
                session.cancel()
                canceled = true
            }
        } while (session.state == SessionState.RUNNING)

        return !canceled
    }
}