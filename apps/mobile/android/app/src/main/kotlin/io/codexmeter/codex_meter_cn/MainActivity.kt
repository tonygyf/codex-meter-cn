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
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "codex_meter/native").setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "load" -> result.success(mapOf("config" to MeterStore.config(this)?.toString(), "snapshot" to MeterStore.prefs(this).getString("snapshot", null)))
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
