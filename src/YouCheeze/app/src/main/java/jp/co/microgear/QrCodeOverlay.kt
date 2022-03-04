package jp.co.microgear

import android.content.Context
import android.graphics.Bitmap
import androidx.work.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder
import java.io.File

/**
 * QRコードを動画にオーバーレイするクラス
 */
class QrCodeOverlay() {
    companion object {
        private const val QR_CODE_JPEG_FORMAT = "putTracking%05d.jpg"

        /**
         * 処理中・処理待ちのワーカー数
         */
        fun getQueuedWorkerCount(context: Context): Int {

            // 処理中・処理待ちのワーカー数
            var result = 0

            // キューからWorkの一覧を取得
            val workInfos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
                QrCodeOverlayWorker.WORK_NAME)
            workInfos.let {

                // 処理待ち、実行中のみカウント
                result = workInfos.get().filter {
                    it.state == WorkInfo.State.ENQUEUED
                            || it.state == WorkInfo.State.RUNNING
                            || it.state == WorkInfo.State.BLOCKED
                }.size
            }
            return result
        }
    }

    /**
     * QRコード画像の保存ディレクトリ
     */
    private lateinit var qrImgeDir: File

    /**
     * QRコードをオーバーレイする対象の動画ファイル
     */
    private lateinit var videoFile: File

    /**
     * QRコードがオーバーレイされた動画ファイル
     */
    private lateinit var outputFile: File

    /**
     * 初期化
     * @param qrImgeDir QRコード画像の保存ディレクトリ
     * @param videoFile QRコードをオーバーレイする対象の動画ファイル
     * @param outputFile QRコードがオーバーレイされた動画を保存するファイル
     */
    fun initialize(qrImgeDir: File, videoFile: File, outputFile: File) {
        this.qrImgeDir = qrImgeDir
        this.videoFile = videoFile
        this.outputFile = outputFile
    }

    /**
     * QRコード画像を生成して保存用ディレクトリへ保存
     */
    fun saveQrImage(contents: String, index: Int) {
        // contentsをQRコード画像に変換
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.L
        hints[EncodeHintType.MARGIN] = 1
        val barcodeEncoder = BarcodeEncoder()
        val bitmap = barcodeEncoder.encodeBitmap(contents, BarcodeFormat.QR_CODE, 60, 60, hints)
        // 画像をjpeg形式で保存
        File(qrImgeDir.path, QR_CODE_JPEG_FORMAT.format(index)).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
        }
    }

    /**
     * QRコードを動画にオーバーレイして保存する
     */
    fun executeOverlay(interval: Int, context: Context) {

        // 処理パラメータを生成
        val params = Data.Builder()
            .putString("qrImageDirPath", qrImgeDir.absolutePath)
            .putString("videoFilePath", videoFile.absolutePath)
            .putString("outputFilePath", outputFile.absolutePath)
            .putString("qrCodeJpegFormat", QR_CODE_JPEG_FORMAT)
            .putInt("qrSaveInterval", interval)
            .build()

        // WorkManagerに登録
        WorkManager.getInstance(context).enqueueUniqueWork(
            QrCodeOverlayWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequest.Builder(
                QrCodeOverlayWorker::class.java
            ).setInputData(params).build()
        )
    }
}