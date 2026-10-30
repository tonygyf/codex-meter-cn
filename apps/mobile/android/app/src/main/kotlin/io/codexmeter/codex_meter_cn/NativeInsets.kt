package io.codexmeter.codex_meter_cn

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

fun Activity.applyMeterInsets(view: View) {
    view.setOnApplyWindowInsetsListener { target,insets ->
        if(Build.VERSION.SDK_INT>=30) {
            val bars=insets.getInsets(WindowInsets.Type.systemBars())
            target.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            window.insetsController?.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            @Suppress("DEPRECATION") target.setPadding(insets.systemWindowInsetLeft,insets.systemWindowInsetTop,insets.systemWindowInsetRight,insets.systemWindowInsetBottom)
            @Suppress("DEPRECATION") window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        insets
    }
    view.requestApplyInsets()
}
