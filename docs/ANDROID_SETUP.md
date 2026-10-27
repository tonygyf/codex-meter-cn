# Android 接入和待验收项

## 当前工程

- Flutter 3.35.3 / Dart 3.9.2，Android applicationId `io.codexmeter.codex_meter_cn`。
- Kotlin 2.1.0、Android Gradle Plugin 8.9.1、Gradle 8.12，Flutter 当前模板最低 Android 7.0 / API 24。
- Android SDK 和 Java 路径由本机生成的 `local.properties` 提供，不提交该文件。
- WorkManager 约每 15 分钟尝试后台联网，Widget 系统更新周期 30 分钟；接收新快照时立即更新 Widget。Widget 倒计时为最近刷新时的摘要，不是每秒跳动。
- 请求 INTERNET、Android 13+ 通知权限、开机广播；不请求精确闹钟权限。
- 重启或更新 APK 后恢复后台任务及尚未到期的预计重置提醒。系统强行停止后需要手动打开应用。

## 本地构建

```sh
cd apps/mobile
flutter doctor -v
flutter pub get
flutter analyze
flutter test
flutter build apk --debug
```

若 Android 许可提示未接受，请由所有者自行阅读并完成 `flutter doctor --android-licenses`。如果 Gradle 下载包损坏，请用自己的干净 Gradle 缓存或 CI 构建；本次未清理或修改你已有的全局 Gradle 配置。

## 需在真实手机完成

- [ ] Android 13+ 允许 / 拒绝通知权限，并检查系统设置中的通知频道。
- [ ] 在桌面添加 3×2 小组件，调整大小，点击打开应用；大字体、深色桌面和不同 Launcher 下检查布局。
- [ ] 关闭网络后重开应用，检查缓存和过期提示；切换直连 / Worker 后不显示旧数据源缓存。
- [ ] 设置阈值，上传新鲜测试快照，验证同一窗口不重复通知。
- [ ] 用短暂的未来重置时间验证预计重置提醒，随后确认界面不会自动恢复额度。
- [ ] 锁屏、省电、重启、厂商后台限制和系统强行停止场景。
- [ ] 用自己的 HTTPS 网关检查真机网络；调试 loopback 不代表跨设备连接可用。

## 正式签名占位

MVP release 仍沿用 Flutter 模板的 debug 签名配置。**不要直接当作正式公开发行版。** 后续由所有者提供正式 applicationId、版本策略和签名密钥，修改 `android/app/build.gradle.kts` 的 signingConfigs。密钥、密码与 `key.properties` 必须放在仓库外或专用 CI Secrets。当前工作流刻意只构建 debug APK，没有设置 Release 发布动作。
