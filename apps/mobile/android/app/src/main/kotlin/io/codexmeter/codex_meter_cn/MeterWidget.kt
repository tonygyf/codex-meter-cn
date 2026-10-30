package io.codexmeter.codex_meter_cn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MeterWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = updateAll(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: android.os.Bundle) = updateOne(context, id)
    override fun onDeleted(context: Context, ids: IntArray) {
        val edit=MeterStore.prefs(context).edit()
        for(id in ids) edit.remove("widget_style_$id").remove("widget_theme_$id").remove("widget_motion_$id")
        edit.apply()
    }
    companion object {
        fun updateAll(context: Context) {
            val manager=AppWidgetManager.getInstance(context)
            var failed=false
            for(id in manager.getAppWidgetIds(ComponentName(context, MeterWidget::class.java))) {
                try {updateOne(context,id)}catch(_:Exception){failed=true}
            }
            MeterStore.prefs(context).edit().putBoolean("widgetRenderError",failed).apply()
        }
        fun updateOne(context: Context, id: Int) {
            val manager=AppWidgetManager.getInstance(context)
            val prefs=MeterStore.prefs(context)
            val snapshot=try { prefs.getString("snapshot",null)?.let{SnapshotProtocol.parse(it)} } catch(_:Exception){null}
            val style=prefs.getString("widget_style_$id",prefs.getString("defaultWidgetStyle","water"))!!
            val theme=prefs.getString("widget_theme_$id",prefs.getString("defaultWidgetTheme","mint"))!!
            val motion=prefs.getBoolean("widget_motion_$id",prefs.getBoolean("defaultWidgetMotion",true)) && style=="water"
            val display=context.resources.displayMetrics
            val frames=if(motion)QuotaWidgetRenderer.waterFrames(snapshot,prefs.getBoolean("lastError",false),theme,display.widthPixels*display.heightPixels)else emptyList()
            val options=manager.getAppWidgetOptions(id)
            val sizes=if(android.os.Build.VERSION.SDK_INT>=31)options.getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)?.distinct()?.take(16) else null
            val pixelBudget=display.widthPixels.toDouble()*display.heightPixels/4/(sizes?.size?:1)
            fun views(width:Int,height:Int):RemoteViews {
                val views=RemoteViews(context.packageName,R.layout.meter_widget)
                val scale=kotlin.math.sqrt(pixelBudget/(width.coerceIn(120,600).toDouble()*height.coerceIn(48,650))).coerceIn(.25,2.0).toFloat()
                views.setImageViewBitmap(R.id.widget_image,QuotaWidgetRenderer.render(width,height,snapshot,prefs.getBoolean("lastError",false),style,theme,surface=!motion,scale=scale))
                views.setViewVisibility(R.id.widget_water,if(motion)android.view.View.VISIBLE else android.view.View.GONE)
                views.removeAllViews(R.id.widget_water)
                for(bitmap in frames) {
                    val frame=RemoteViews(context.packageName,R.layout.widget_water_frame)
                    frame.setImageViewBitmap(R.id.water_frame,bitmap)
                    views.addView(R.id.widget_water,frame)
                }
                val (summary,_)=QuotaTileSummary.text(snapshot,System.currentTimeMillis()/1000,prefs.getBoolean("lastError",false))
                views.setContentDescription(R.id.widget_image,"Codex $summary")
                views.setOnClickPendingIntent(R.id.widget_image,PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                views.setOnClickPendingIntent(R.id.widget_config,PendingIntent.getActivity(context,id,Intent(context,WidgetConfigActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                return views
            }
            if(android.os.Build.VERSION.SDK_INT>=31) {
                if(!sizes.isNullOrEmpty()) {
                    manager.updateAppWidget(id,RemoteViews(sizes.take(16).associateWith{views(it.width.toInt(),it.height.toInt())}))
                    return
                }
            }
            val width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180)
            val height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,160)
            manager.updateAppWidget(id,views(width,height))
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val config = MeterStore.config(context) ?: return
            if (!config.optBoolean("notifications") || MeterStore.source(config) == null) return
            val name = intent.getStringExtra("window") ?: return
            val raw = MeterStore.prefs(context).getString("snapshot", null) ?: return
            val reset = SnapshotProtocol.reset(SnapshotProtocol.parse(raw).getJSONObject("windows").optJSONObject(name)) ?: return
            if (reset > System.currentTimeMillis() / 1000) return
            val id = "$name:$reset"
            if (MeterStore.prefs(context).getString("${name}_reset", null) == id) return
            val label = if (name == "fiveHour") "5 小时" else "每周"
            if (MeterStore.notify(context, if (name == "fiveHour") 20 else 21, "$label 预计重置时间已到", "请打开应用刷新，确认额度是否恢复。离线快照无法确认重置。")) {
                MeterStore.prefs(context).edit().putString("${name}_reset", id).apply()
            }
            MeterWidget.updateAll(context)
        } catch (_: Exception) { /* Keystore may be locked until the device is unlocked. */ }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val config = MeterStore.config(context) ?: return
            MeterStore.scheduleWork(context, config)
            MeterStore.prefs(context).getString("snapshot", null)?.let { MeterStore.remind(context, config, SnapshotProtocol.parse(it)) }
            MeterWidget.updateAll(context)
        } catch (_: Exception) { /* Opening the app restores scheduling if the Keystore was locked. */ }
    }
}
