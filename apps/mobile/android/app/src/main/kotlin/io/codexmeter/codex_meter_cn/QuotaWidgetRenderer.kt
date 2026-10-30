package io.codexmeter.codex_meter_cn

import android.graphics.*
import org.json.JSONObject
import kotlin.math.*

object WidgetSizing {
    fun tier(width: Int, height: Int): Int = if (height < 145 || width < 130) 1 else if (height < 230) 2 else 3
}

object WaterMotion {
    const val FRAMES=150
    const val INTERVAL_MILLIS=20
    fun frameWidth(screenPixels: Int): Int = sqrt(screenPixels.toDouble()/2/FRAMES/1.5).toInt().coerceIn(16,64)
    fun surface(remaining: Double, x: Double, phase: Double, offset: Double = 0.0): Double {
        val level=1-remaining.coerceIn(0.0,100.0)/100
        val amplitude=min(.02,min(level,1-level)/1.5)
        val t=x*2*PI
        return level+amplitude*(sin(t+phase+offset)+.32*sin(2*t-2*phase+.8)+.18*sin(3*t+3*phase+2.1))
    }
}

object QuotaWidgetRenderer {
    data class Palette(val background: Int, val ink: Int, val muted: Int, val track: Int, val water: Int)
    fun palette(theme: String) = when(theme) {
        "night" -> Palette(Color.rgb(17,42,37), Color.rgb(239,251,244), Color.rgb(151,183,166), Color.rgb(40,67,57), Color.rgb(98,218,176))
        "sand" -> Palette(Color.rgb(249,244,231), Color.rgb(55,67,45), Color.rgb(135,135,111), Color.rgb(232,228,207), Color.rgb(133,165,112))
        else -> Palette(Color.rgb(238,248,242), Color.rgb(24,67,54), Color.rgb(107,140,124), Color.rgb(216,234,224), Color.rgb(53,163,122))
    }
    fun render(width: Int, height: Int, snapshot: JSONObject?, offline: Boolean, style: String, theme: String, now: Long = System.currentTimeMillis()/1000, surface: Boolean = true, scale: Float = 2f): Bitmap {
        val w = width.coerceIn(120,600).toFloat(); val h = height.coerceIn(48,650).toFloat()
        val bitmap = Bitmap.createBitmap((w*scale).toInt().coerceAtLeast(1),(h*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap); c.scale(scale,scale)
        val colors=palette(theme); val p=Paint(Paint.ANTI_ALIAS_FLAG)
        fun rect(x:Float,y:Float,r:Float,b:Float,color:Int,radius:Float=10f) { p.color=color;c.drawRoundRect(x,y,r,b,radius,radius,p) }
        fun text(value:String,x:Float,y:Float,size:Float,color:Int=colors.ink,bold:Boolean=false) {
            p.color=color;p.textSize=size;p.typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL);c.drawText(value,x,y,p)
        }
        val windows=snapshot?.getJSONObject("windows")
        fun window(name:String)=windows?.optJSONObject(name)
        fun remaining(name:String)=window(name)?.getDouble("usedPercent")?.let { 100-it }
        fun value(name:String)=remaining(name)?.let{"${it.roundToInt()}%"}?:"—"
        val historical=snapshot!=null && (offline || snapshot.getLong("expiresAt")<=now || listOf("fiveHour","weekly").any { SnapshotProtocol.reset(window(it))?.let{r->r<=now}==true })
        val tier=WidgetSizing.tier(width,height)
        val pad=if(tier==1)14f else 18f
        if(surface) rect(0f,0f,w,h,colors.background,24f)
        fun bar(name:String,x:Float,y:Float,length:Float,thick:Float) {
            rect(x,y,x+length,y+thick,colors.track,thick/2)
            remaining(name)?.let { if(it>0)rect(x,y,x+length*(it/100).toFloat(),y+thick,if(historical)colors.muted else colors.water,thick/2) }
        }
        if(style=="water" && surface) {
            val shape=Path().apply {addRoundRect(0f,0f,w,h,24f,24f,Path.Direction.CW)}
            c.save(); c.clipPath(shape)
            remaining("fiveHour")?.let { amount ->
                val level=h*(1-amount.toFloat()/100)
                val wave=Path().apply {moveTo(-4f,level);cubicTo(w*.28f,level-8,w*.65f,level+8,w+4,level);lineTo(w+4,h+4);lineTo(-4f,h+4);close()}
                p.color=if(historical)colors.muted else colors.water
                p.alpha=if(theme=="night")115 else 80
                c.drawPath(wave,p)
                p.alpha=255;p.style=Paint.Style.STROKE;p.strokeWidth=1.5f;p.color=Color.argb(100,255,255,255)
                c.drawPath(Path().apply{moveTo(-4f,level);cubicTo(w*.28f,level-8,w*.65f,level+8,w+4,level)},p)
                p.style=Paint.Style.FILL
                p.color=Color.argb(35,255,255,255)
                c.drawOval(w*.1f,level+18,w*.1f+5,level+23,p)
                c.drawOval(w*.8f,level+30,w*.8f+3,level+33,p)
            }
            p.style=Paint.Style.STROKE;p.strokeWidth=1.3f;p.color=Color.argb(75,255,255,255)
            c.drawRoundRect(1f,1f,w-1,h-1,23f,23f,p);p.style=Paint.Style.FILL
            c.restore()
        }
        if(tier==1) {
            text(if(historical)"5 小时 · 上次" else "5 小时",pad,24f,11f,colors.muted)
            text(value("fiveHour"),w-76f,34f,27f,bold=true)
            bar("fiveHour",pad,h-18f,w-pad*2,6f)
        } else {
            text("CODEX",pad,28f,10f,colors.muted,true)
            text(if(snapshot==null)"等待连接" else if(historical)"上次剩余" else "剩余额度",pad,47f,12f,colors.muted)
            if(style=="water") {
                val numberSize=if(tier==3)min(w*.3f,68f) else min(w*.27f,54f)
                val baseline=if(tier==3)h*.42f else h*.58f
                text(value("fiveHour"),pad,baseline,numberSize,bold=true)
                text("5 小时剩余",pad,baseline+22f,11f,colors.muted)
                rect(pad,h-51f,w-pad,h-19f,colors.background,12f)
                text("每周",pad+11,h-30f,11f,colors.muted)
                text(value("weekly"),w-pad-57,h-29f,18f,bold=true)
                if(tier==3) {
                    text("5h · ${SnapshotProtocol.countdown(window("fiveHour"),now)}",pad,baseline+53f,10f,colors.muted)
                    text("周 · ${SnapshotProtocol.countdown(window("weekly"),now)}",pad,baseline+71f,10f,colors.muted)
                }
            } else {
                val row=(h-68f-(if(tier==3)40f else 0f))/2
                for((i,name) in listOf("fiveHour","weekly").withIndex()) {
                    val top=66f+i*row
                    text(if(i==0)"5 小时" else "每周",pad,top,11f,colors.muted)
                    text(value(name),w-83f,top+3,24f,bold=true)
                    bar(name,pad,top+15,w-pad*2,9f)
                    if(tier==3)text(SnapshotProtocol.countdown(window(name),now),pad,top+42,10f,colors.muted)
                }
            }
            if(tier==3) {
                val stamp=snapshot?.let{java.text.SimpleDateFormat("HH:mm",java.util.Locale.CHINA).format(java.util.Date(it.getLong("collectedAt")*1000))}?:"—"
                text("${if(historical)"历史快照" else "最近采集"} · $stamp",pad,h-12f,9f,colors.muted)
            }
        }
        return bitmap
    }
    fun waterFrames(snapshot: JSONObject?, offline: Boolean, theme: String, screenPixels: Int = 1080*2400): List<Bitmap> {
        val colors=palette(theme)
        val (_,fresh)=QuotaTileSummary.text(snapshot,System.currentTimeMillis()/1000,offline)
        val amount=snapshot?.getJSONObject("windows")?.optJSONObject("fiveHour")?.getDouble("usedPercent")?.let{100-it}
        val width=WaterMotion.frameWidth(screenPixels);val height=width*3/2
        return (0 until WaterMotion.FRAMES).map { index ->
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            val c=Canvas(bitmap);c.drawColor(colors.background)
            if(amount!=null) {
                val phase=index*2*PI/WaterMotion.FRAMES
                fun wave(offset:Double):Path=Path().apply {
                    for(x in -2..width+2) {
                        val y=height*WaterMotion.surface(amount,x.toDouble()/width,phase,offset)
                        if(x==-2)moveTo(x.toFloat(),y.toFloat())else lineTo(x.toFloat(),y.toFloat())
                    }
                    lineTo(width+2f,height+2f);lineTo(-2f,height+2f);close()
                }
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(fresh)colors.water else colors.muted;alpha=if(theme=="night")65 else 40}
                c.drawPath(wave(1.4),p)
                p.alpha=if(theme=="night")100 else 65;c.drawPath(wave(0.0),p)
            }
            bitmap
        }
    }
}
