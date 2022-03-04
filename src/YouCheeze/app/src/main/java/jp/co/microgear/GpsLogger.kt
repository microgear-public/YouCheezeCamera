package jp.co.microgear

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import org.w3c.dom.Element
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.concurrent.schedule

/**
 * 位置情報ロガー
 */
class GpsLogger(private val locationManager: LocationManager) : LocationListener {
    companion object {
        private val QR_VERSION = "01"
    }
    /**
     * gpxファイル出力用DOMBuilder
     */
    private var domBuilder: DocumentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()

    /**
     * gpxファイル出力用Transformer
     */
    private val domTransformer: Transformer = TransformerFactory.newInstance().newTransformer()

    /**
     * 位置情報リスナー
     */
    private var locationListener: LocationListener? = null

    /**
     * 現在位置情報
     */
    private var location: Location? = null

    /**
     * 位置情報を定周期でロギングするタイマー
     */
    private var timer: Timer? = null

    /**
     * 記録される位置情報
     */
    private val recordedLocations = mutableMapOf<Date, Location>()

    /**
     * 記録された位置情報が最後に出力されるFile
     */
    private var loggingFile: File? = null

    /**
     * 位置情報の記録が開始されているか
     */
    val isStarted: Boolean get() = (timer != null)

    /**
     * 位置情報のリスニングが開始されているか
     */
    val isStartedLocationListening get() = (location != null)

    /**
     * 位置情報リスナーを開始する
     */
    @SuppressLint("MissingPermission")
    fun starListener() : Boolean {
        // 位置情報が有効化チェック
        return if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val interval = 1000L    // 位置情報の取得間隔は1秒固定
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 50f, this)
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,interval, 50f, this)

            true
        } else {
            false
        }
    }

    /**
     * 位置情報が更新された時の処理
     */
    override fun onLocationChanged(location: Location) {
        this.location = location

        locationListener?.onLocationChanged(location)
    }

    /**
     * 位置情報のリスナーを登録する
     */
    fun setOnLocationChangeListener(onLocationListener: LocationListener) {
        this.locationListener = onLocationListener
    }

    /**
     * 位置情報のロギングを開始する
     * @param file 出力ファイル
     * @param qrCodeOverlay QRCodeオーバーレイヘルパー
     * @param interval 位置情報を記録する間隔(ミリ秒)
     */
    fun startLogging(file: File?, qrCodeOverlay: QrCodeOverlay?, interval: Long) {
        // 位置情報をクリアする
        recordedLocations.clear()
        if (file != null) {
            loggingFile = file
        }
        // タイマーを開始
        timer = Timer()
        timer!!.schedule(0, interval) {
            val now = Date()
            recordedLocations[now] = location!!
            qrCodeOverlay?.saveQrImage(getQRCodeContents(now, location!!), recordedLocations.count() - 1)
        }
    }


    /**
     * 位置情報のロギングを開始する
     * @param file 出力ファイル
     * @param interval 位置情報を記録する間隔(ミリ秒)
     */
    fun startLogging(file: File, interval: Long) {
        startLogging(file, null, interval)
    }

    /**
     * 位置情報のロギングを開始する
     * @param qrCodeOverlay QRCodeオーバーレイヘルパー
     * @param interval 位置情報を記録する間隔(ミリ秒)
     */
    fun startLogging(qrCodeOverlay: QrCodeOverlay, interval: Long) {
        startLogging(null, qrCodeOverlay, interval)
    }

    /**
     * 位置情報のロギングを終了する
     */
    fun stopLogging() {
        // タイマー停止
        timer?.cancel()
        // 位置情報ログをファイル保存
        if (loggingFile != null) {
            saveLocation()
        }
        // 必要なくなった変数のクリア
        loggingFile = null
        timer = null
    }

    /**
     * 位置情報データをファイル出力する
     * @args file 出力ファイル
     * @args locations 位置情報データ
     */
    private fun saveLocation() {
        // TransFormer初期化
        domTransformer.setOutputProperty(OutputKeys.INDENT, "yes")
        domTransformer.setOutputProperty(OutputKeys.METHOD, "xml")

        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
        formatter.timeZone = Calendar.getInstance(TimeZone.getTimeZone("UTC")).timeZone

        val doc = domBuilder.newDocument()
        // ドキュメントルート
        val root = doc.createElement("gpx")
        root.setAttribute("version", "1.0")
        root.setAttribute("creator", "YouCheeze")
        root.setAttribute("xmlns", "http://www.topografix.com/GPX/1/0")
        root.setAttribute("xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance")
        root.setAttribute("xsi:schemaLocation", "http://www.topografix.com/GPX/1/0 http://www.topografix.com/GPX/1/0/gpx.xsd")
        doc.appendChild(root)
        appendTextElement(root, "name", "YouCheeze GPS Logger")
        appendTextElement(root, "desc", "YouCheeze created GPS Logging Data.")
        appendTextElement(root, "time", formatter.format(Date()))
        // トラッキング
        val elmTrk = doc.createElement("trk")
        root.appendChild(elmTrk)
        appendTextElement(elmTrk, "name", "YouCheeze - ${loggingFile!!.nameWithoutExtension}")
        val elmTrkSeg = doc.createElement("trkseg")
        elmTrk.appendChild(elmTrkSeg)
        for (info in recordedLocations) {
            val lat = "%,.5f".format(info.value.latitude)
            val lon = "%,.5f".format(info.value.longitude)
            val ele = "%,.1f".format(info.value.accuracy)
            val time = formatter.format(info.key)
            val speed = "%,.5f".format(info.value.speed)
            val sat = "0"   // サテライトはとりあえず0固定
            val elmTrkPt = doc.createElement("trkpt")
            elmTrkPt.setAttribute("lat", lat)
            elmTrkPt.setAttribute("lon", lon)
            elmTrkSeg.appendChild(elmTrkPt)
            appendTextElement(elmTrkPt, "ele", ele)
            appendTextElement(elmTrkPt, "time", time)
            appendTextElement(elmTrkPt, "speed", speed)
            appendTextElement(elmTrkPt, "sat", sat)
        }
        domTransformer.transform(DOMSource(doc), StreamResult(loggingFile))
    }

    private fun appendTextElement(element: Element, name: String, value: String) {
        val newChild = element.ownerDocument.createElement(name)
        newChild.textContent = value
        element.appendChild(newChild)

    }

    private fun getQRCodeContents(date: Date, location: Location): String {
        // 位置情報のcontentsを作成
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
        formatter.timeZone = Calendar.getInstance(TimeZone.getTimeZone("UTC")).timeZone
        val lat = "%+09.5f".format(location.latitude)
        val lon = "%+010.5f".format(location.longitude)
        val accuracy = "%+07.1f".format(location.accuracy)
        val time = formatter.format(date)

        return "$QR_VERSION$time$lat$lon$accuracy"
    }
}