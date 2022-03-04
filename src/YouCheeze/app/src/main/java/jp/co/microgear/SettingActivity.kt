package jp.co.microgear

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_setting.*
import jp.co.microgear.widget.SpinnerItem
import jp.co.microgear.widget.SpinnerItemAdapter
import java.util.*

/**
 * 設定画面
 */
class SettingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setting)

        // マーカー更新間隔入力欄初期化
        initMarkerIntervalSpinner()

        // 開始ボタン位置入力欄初期化
        initStartButtonPosSpinner()
    }

    /**
     * マーカー更新間隔入力欄初期化
     */
    private fun initMarkerIntervalSpinner() {

        // アイテムリストを生成
        val items: ArrayList<SpinnerItem> = arrayListOf(
            SpinnerItem(1, getString(R.string.item_marker_interval_1sec)),
            SpinnerItem(5, getString(R.string.item_marker_interval_5sec)),
            SpinnerItem(10, getString(R.string.item_marker_interval_10sec))
        )

        // スピナーのアダプタを設定
        val adapter = SpinnerItemAdapter(this, items)
        spinMarkerInterval.adapter = adapter

        // 現在の設定を選択状態に反映
        spinMarkerInterval.setSelection(adapter.getPosition(AppSettings.markerUpdateInterval))

        // アイテム選択変更時イベントハンドラ
        spinMarkerInterval.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            /**
             * アイテム選択時処理
             */
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {

                // アプリケーション設定を更新
                val item = parent?.selectedItem as? SpinnerItem
                if (item != null) {
                    AppSettings.markerUpdateInterval = item.value
                }
            }

            /**
             * アイテム未選択終了時処理
             */
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
    }

    /**
     * 開始ボタン位置入力欄初期化
     */
    private fun initStartButtonPosSpinner() {

        // アイテムリストを生成
        val items: ArrayList<SpinnerItem> = arrayListOf(
            SpinnerItem(StartButtonPosition.LEFT.code, getString(R.string.item_start_button_pos_left)),
            SpinnerItem(StartButtonPosition.RIGHT.code, getString(R.string.item_start_button_pos_right))
        )

        // スピナーのアダプタを設定
        val adapter = SpinnerItemAdapter(this, items)
        spinStartButtonPos.adapter = adapter

        // 現在の設定を選択状態に反映
        spinStartButtonPos.setSelection(adapter.getPosition(AppSettings.startButtonPos.code))

        // アイテム選択変更時イベントハンドラ
        spinStartButtonPos.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            /**
             * アイテム選択時処理
             */
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {

                // アプリケーション設定を更新
                val item = parent?.selectedItem as? SpinnerItem
                if (item != null) {
                    AppSettings.startButtonPos = StartButtonPosition.get(item.value)
                }
            }

            /**
             * アイテム未選択終了時処理
             */
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
    }
}