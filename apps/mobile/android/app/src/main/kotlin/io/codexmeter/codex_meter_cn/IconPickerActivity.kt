package io.codexmeter.codex_meter_cn

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.*

class IconPickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(28),dp(24),dp(24));setBackgroundColor(Color.rgb(244,248,244))}
        val scroll=ScrollView(this);scroll.addView(root);setContentView(scroll);applyMeterInsets(scroll)
        root.addView(TextView(this).apply{text="选一个，放在桌面";textSize=26f;setTextColor(Color.rgb(24,67,54))})
        root.addView(TextView(this).apply{text="水量与回环 · 三款图标随时切换\n启动器可能需要几秒刷新；不会清除账号或额度。";setPadding(0,dp(12),0,dp(18))})
        val variants=listOf(Triple("Ripple","A · 涟漪环",R.mipmap.ic_launcher_ripple),Triple("Drop","B · 水滴环",R.mipmap.ic_launcher_drop),Triple("Bottle","C · 水量瓶",R.mipmap.ic_launcher_bottle))
        for((key,name,icon) in variants) {
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(0,dp(12),0,dp(12))}
            row.addView(ImageView(this).apply{setImageResource(icon);contentDescription=name},LinearLayout.LayoutParams(dp(88),dp(88)))
            row.addView(Button(this).apply{text="$name · 使用";setOnClickListener{
                val chosen=ComponentName(this@IconPickerActivity,"$packageName.Icon$key")
                packageManager.setComponentEnabledSetting(chosen,PackageManager.COMPONENT_ENABLED_STATE_ENABLED,PackageManager.DONT_KILL_APP)
                for((other,_,_) in variants) if(other!=key)packageManager.setComponentEnabledSetting(ComponentName(this@IconPickerActivity,"$packageName.Icon$other"),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)
                MeterStore.prefs(this@IconPickerActivity).edit().putString("launcherIcon",key).apply()
                Toast.makeText(this@IconPickerActivity,"已切换为 $name",Toast.LENGTH_SHORT).show()
            }},LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,dp(64)))
            root.addView(row)
        }
    }
}
