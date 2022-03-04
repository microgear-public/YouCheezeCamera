package jp.co.microgear

import android.app.Application

/**
 * アプリケーション拡張
 */
class YouCheezeApp : Application() {

    /**
     * アプリケーション生成
     */
    override fun onCreate() {
        super.onCreate()

        // アプリケーション設定を初期化
        AppSettings.initialize(applicationContext)
    }
}