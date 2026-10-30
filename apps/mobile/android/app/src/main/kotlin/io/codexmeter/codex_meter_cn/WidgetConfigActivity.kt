package io.codexmeter.codex_meter_cn

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.*

class WidgetConfigActivity : Activity() {
    private val flippers=mutableListOf<ViewFlipper>()
    override fun onPause() {for(f in flippers)f.stopFlipping();super.onPause()}
    override fun onResume() {super.onResume();for(f in flippers)f.startFlipping()}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        val prefs=MeterStore.prefs(this)
        var style=prefs.getString(if(id==0)"defaultWidgetStyle" else "widget_style_$id",prefs.getString("defaultWidgetStyle","water"))!!
        var theme=prefs.getString(if(id==0)"defaultWidgetTheme" else "widget_theme_$id",prefs.getString("defaultWidgetTheme","mint"))!!
        var motion=prefs.getBoolean(if(id==0)"defaultWidgetMotion" else "widget_motion_$id",prefs.getBoolean("defaultWidgetMotion",true))
        if(id!=0)setResult(RESULT_CANCELED)
        fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(28),dp(22),dp(24));setBackgroundColor(Color.rgb(246,249,246))}
        val scroll=ScrollView(this);scroll.addView(root);setContentView(scroll);applyMeterInsets(scroll)
        fun label(text:String,size:Float=14f)=TextView(this).apply {this.text=text;textSize=size;setTextColor(Color.rgb(25,67,54));setPadding(0,dp(10),0,dp(10))}
        root.addView(label("让额度，像水一样清楚",24f))
        root.addView(label("组件整体是水量容器，水位代表 5 小时剩余。拖动桌面边框即可调整尺寸；2×1 仅显示 5 小时。"))
        val previews=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        fun redraw() {
            for(f in flippers)f.stopFlipping()
            flippers.clear()
            previews.removeAllViews()
            val snapshot=try {prefs.getString("snapshot",null)?.let{SnapshotProtocol.parse(it)}}catch(_:Exception){null}
            val frames=if(motion && style=="water")QuotaWidgetRenderer.waterFrames(snapshot,prefs.getBoolean("lastError",false),theme)else emptyList()
            for((title,height) in listOf("2 × 1 · 随手一瞥" to 82,"2 × 2 · 日常额度" to 180,"2 × 3 · 水位与重置" to 280)) {
                previews.addView(label(title,12f))
                val container=FrameLayout(this).apply{background=getDrawable(R.drawable.widget_background);clipToOutline=true}
                val params=LinearLayout.LayoutParams(dp(230),dp(height)).apply{gravity=android.view.Gravity.CENTER_HORIZONTAL}
                if(frames.isNotEmpty()) {
                    val flipper=ViewFlipper(this).apply{flipInterval=WaterMotion.INTERVAL_MILLIS}
                    for(bitmap in frames)flipper.addView(ImageView(this).apply{setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_XY},FrameLayout.LayoutParams(-1,-1))
                    container.addView(flipper,FrameLayout.LayoutParams(-1,-1));flipper.startFlipping();flippers.add(flipper)
                }
                val image=ImageView(this).apply {setImageBitmap(QuotaWidgetRenderer.render(230,height,snapshot,prefs.getBoolean("lastError",false),style,theme,surface=frames.isEmpty()));scaleType=ImageView.ScaleType.FIT_XY;contentDescription=title}
                container.addView(image,FrameLayout.LayoutParams(-1,-1))
                previews.addView(container,params)
            }
        }
        fun choices(title:String,values:List<Pair<String,String>>,selected:String,change:(String)->Unit) {
            root.addView(label(title,16f))
            val group=RadioGroup(this).apply {orientation=RadioGroup.HORIZONTAL}
            for((value,name) in values) {
                val button=RadioButton(this).apply {text=name;this.id=android.view.View.generateViewId();isChecked=value==selected}
                group.addView(button)
                button.setOnClickListener {change(value);redraw()}
            }
            root.addView(group)
        }
        choices("样式",listOf("water" to "整面水位","bar" to "极简电量"),style){style=it}
        choices("主题",listOf("mint" to "薄荷","night" to "深海","sand" to "暖沙"),theme){theme=it}
        root.addView(CheckBox(this).apply{text="动态水面 · 多层不规则波动";isChecked=motion;setOnCheckedChangeListener{_,checked->motion=checked;redraw()}})
        val save=Button(this).apply {text=if(id==0)"保存为默认外观" else "应用到此组件"}
        root.addView(save)
        save.setOnClickListener {
            val edit=prefs.edit()
            if(id==0)edit.putString("defaultWidgetStyle",style).putString("defaultWidgetTheme",theme).putBoolean("defaultWidgetMotion",motion)
            else edit.putString("widget_style_$id",style).putString("widget_theme_$id",theme).putBoolean("widget_motion_$id",motion)
            edit.apply()
            MeterWidget.updateAll(this)
            setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()
        }
        root.addView(previews)
        root.addView(label("水位代表缓存的 5 小时剩余额度。桌面以原生逐帧方式播放不规则水面，启动器或省电模式可能暂停动画；可关闭动态水面节电。",12f))
        redraw()
    }
}
