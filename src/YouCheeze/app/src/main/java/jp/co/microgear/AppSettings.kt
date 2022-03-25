package jp.co.microgear

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/**
 * 開始ボタン位置
 */
enum class StartButtonPosition(val code: Int) {

    /** 左側 */
    LEFT(0),

    /** 右側 */
    RIGHT(1);

    companion object {

        /**
         * 列挙値取得
         */
        fun get(code: Int): StartButtonPosition =
            values()
                .find { it.code == code }
                ?: throw Exception("StartButtonPosition code not found.")
    }
}

/**
 * アプリケーション設定
 */
class AppSettings private constructor() {

    companion object {

        /** フリー版撮影時間上限（分単位、0以下の場合は制限なし） */
        const val MAX_REC_RESTRICTION_MIN = 10

        /** マーカー更新間隔デフォルト値 */
        const val DEFAULT_MARKER_UPDATE_INTERVAL = 3

        /** 開始ボタン位置デフォルト値 */
        private val DEFAULT_START_BUTTON_POS = StartButtonPosition.RIGHT

        /** 設定ファイル名 */
        private const val SHARED_PREF_NAME = "app_settings"

        /** マーカー更新間隔キー */
        private const val PROP_MARKER_UPDATE_INTERVAL = "marker_update_interval"

        /** 開始ボタン位置キー */
        private const val PROP_START_BUTTON_POS = "start_button_pos"

        /**
         * SharedPreferencesインスタンス
         */
        var sharedPref: SharedPreferences? = null

        /**
         * 初期化
         */
        fun initialize(context: Context) {

            // SharedPreferencesを生成
            sharedPref = context.getSharedPreferences(SHARED_PREF_NAME, Context.MODE_PRIVATE)
        }

        /**
         * マーカー更新間隔
         */
        var markerUpdateInterval: Int
            get() = getInt(PROP_MARKER_UPDATE_INTERVAL, DEFAULT_MARKER_UPDATE_INTERVAL)
            set(value) {
                putInt(PROP_MARKER_UPDATE_INTERVAL, value)
            }

        /**
         * 開始ボタン位置
         */
        var startButtonPos: StartButtonPosition
            get() = StartButtonPosition.get(getInt(PROP_START_BUTTON_POS, DEFAULT_START_BUTTON_POS.code))
            set(value) {
                putInt(PROP_START_BUTTON_POS, value.code)
            }

        /**
         * 動画ファイル保存先
         */
        val movieSaveDir: File = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"YouCheeze")

        /**
         * 設定（数値）書き込み
         */
        private fun putInt(name: String, value: Int) {

            // 設定値書き込み
            val editor = sharedPref?.edit()
                ?: throw Exception("Application settings not initialized.")
            editor.putInt(name, value)
            editor.apply()
        }

        /**
         * 設定（数値）読み込み
         */
        private fun getInt(name: String, default: Int): Int {

            // 設定値読み込み
            return sharedPref?.getInt(name, default)
                ?: throw Exception("Application settings not initialized.")
        }
    }
}