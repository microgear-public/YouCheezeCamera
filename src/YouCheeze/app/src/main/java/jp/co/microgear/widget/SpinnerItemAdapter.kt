package jp.co.microgear.widget

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import jp.co.microgear.R
import java.util.ArrayList

/**
 * スピナーアイテムアダプタ
 */
class SpinnerItemAdapter(context: Context?, list: ArrayList<SpinnerItem>) :
    ArrayAdapter<SpinnerItem>(
        context!!, R.layout.spinner_item, list) {

    init {
        setDropDownViewResource(R.layout.spinner_dropdown_item)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val textView = super.getView(position, convertView, parent) as TextView
        textView.text = getItem(position)!!.displayValue
        return textView
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val textView = super.getDropDownView(
            position,
            convertView,
            parent
        ) as TextView
        textView.text = getItem(position)!!.displayValue
        return textView
    }

    fun getPosition(value: Int): Int {
        for (i in 0 until this.count) if (getItem(i)?.value === value) {
            return i
        }
        return -1
    }
}