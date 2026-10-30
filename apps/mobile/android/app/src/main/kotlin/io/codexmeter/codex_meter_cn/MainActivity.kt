package io.codexmeter.codex_meter_cn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

class MainActivity : FlutterActivity() {
    private var permissionResult: MethodChannel.Result? = null
    override fun onResume() {
        super.onResume()
        try { MeterMonitorService.sync(this,true) } catch (_: Exception) { /* Periodic fallback remains scheduled. */ }
    }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "codex_meter/native").setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "widgetAppearance" -> { startActivity(android.content.Intent(this,WidgetConfigActivity::class.java));result.success(null) }
                    "appAppearance" -> { startActivity(android.content.Intent(this,IconPickerActivity::class.java));result.success(null) }
                    "batterySettings" -> { startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")));result.success(null) }
                    "addQuickTile" -> {
                        if (Build.VERSION.SDK_INT >= 33) {
                            getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                                android.content.ComponentName(this, MeterTileService::class.java), "Codex 额度",
                                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_quota_tile), mainExecutor
                            ) { code -> result.success(code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED || code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) }
                        } else result.success(false)
                    }
                    "load" -> result.success(mapOf("config" to MeterStore.config(this)?.toString(), "snapshot" to MeterStore.prefs(this).getString("snapshot", null),"monitorState" to MeterStore.prefs(this).getString("monitorState","stopped"),"lastAttempt" to MeterStore.prefs(this).getLong("lastAttempt",0),"lastError" to MeterStore.prefs(this).getBoolean("lastError",false)))
                    "save" -> { MeterStore.save(this, JSONObject(call.arguments as String)); result.success(null) }
                    "updateSnapshot" -> { MeterStore.accept(this, call.arguments as String); result.success(null) }
                    "requestNotifications" -> {
                        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            result.success(true)
                        } else if (permissionResult != null) result.success(false)
                        else { permissionResult = result; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 19) }
                    }
                    else -> result.notImplemented()
                }
            } catch (_: Exception) { result.error("meter_error", "无法读写本地额度设置", null) }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 19) {
            permissionResult?.success(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            permissionResult = null
        }
    }
}
