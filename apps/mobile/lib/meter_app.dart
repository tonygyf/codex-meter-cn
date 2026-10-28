import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'meter_service.dart';
import 'snapshot.dart';

class MeterApp extends StatelessWidget {
  final MeterPlatform platform;
  const MeterApp({super.key, required this.platform});
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Codex Meter CN',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xff126b59)),
      useMaterial3: true,
      scaffoldBackgroundColor: const Color(0xfff5f7f5),
    ),
    home: MeterHome(platform: platform),
  );
}

class MeterHome extends StatefulWidget {
  final MeterPlatform platform;
  const MeterHome({super.key, required this.platform});
  @override
  State<MeterHome> createState() => _MeterHomeState();
}

class _MeterHomeState extends State<MeterHome> with WidgetsBindingObserver {
  MeterConfig config = const MeterConfig();
  Snapshot? snapshot;
  String? error;
  bool loading = false, ready = false;
  Timer? tick, poll;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
    poll = Timer.periodic(const Duration(minutes: 5), (_) {
      if (WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed) {
        refresh();
      }
    });
    initialize();
  }

  Future<void> initialize() async {
    try {
      final stored = await widget.platform.load();
      if (!mounted) return;
      if (stored['config'] is String) {
        config = MeterConfig.parse(stored['config']);
      }
      if (config.mode != 'demo' && stored['snapshot'] is String) {
        try {
          snapshot = Snapshot.parse(stored['snapshot']);
        } on FormatException {
          /* Ignore invalid cache. */
        }
      }
    } catch (_) {
      error = '本地设置读取失败，请重新保存数据源';
    }
    if (!mounted) return;
    setState(() => ready = true);
    await refresh();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && ready) initialize();
  }

  Future<void> refresh() async {
    if (loading || !ready) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final fresh = await fetchSnapshot(config);
      if (!mounted) return;
      setState(() => snapshot = fresh);
      if (config.mode != 'demo') {
        try {
          await widget.platform.update(fresh);
        } catch (_) {
          if (mounted) setState(() => error = '额度已读取，但本地缓存或提醒保存失败');
        }
      }
    } on FormatException catch (e) {
      if (mounted) setState(() => error = e.message);
    } on HttpException catch (e) {
      if (mounted) setState(() => error = e.message);
    } catch (_) {
      if (mounted) setState(() => error = '暂时无法连接，显示上次保存的额度');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  Future<void> settings() async {
    final result = await Navigator.of(context).push<MeterConfig>(
      MaterialPageRoute(
        builder: (_) => SettingsPage(config: config, platform: widget.platform),
      ),
    );
    if (result == null || !mounted) return;
    setState(() {
      config = result;
      snapshot = null;
      error = null;
    });
    await refresh();
  }

  @override
  void dispose() {
    tick?.cancel();
    poll?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final stale = snapshot?.isStale(now) ?? false;
    final source = switch (config.mode) {
      'worker' => '自部署 Worker',
      'direct' => '直连采集器',
      _ => '演示模式 · 模拟数据',
    };
    return Scaffold(
      appBar: AppBar(
        title: const Text('Codex 额度'),
        actions: [
          IconButton(
            onPressed: loading ? null : settings,
            tooltip: '数据源与提醒',
            icon: const Icon(Icons.settings_outlined),
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: refresh,
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Text(
              'Codex Meter CN',
              style: Theme.of(context).textTheme.labelLarge,
            ),
            const SizedBox(height: 8),
            Text(
              '心中有数，安心开工',
              style: Theme.of(
                context,
              ).textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 16),
            Wrap(
              spacing: 8,
              children: [
                Chip(label: Text(source)),
                Chip(
                  label: Text(
                    snapshot == null
                        ? '等待数据'
                        : stale
                        ? '快照已过期'
                        : error != null
                        ? '连接异常'
                        : '快照可用',
                  ),
                ),
              ],
            ),
            if (config.mode == 'demo')
              const Notice('当前为模拟数据。前往设置接入采集器或 Worker，演示模式不会触发提醒。'),
            if (error != null) Notice(error!),
            if (stale) const Notice('数据已过期，仅供参考。刷新成功前不会推断额度已恢复。'),
            const SizedBox(height: 12),
            QuotaCard(
              title: '5 小时额度',
              window: snapshot?.fiveHour,
              now: now,
              stale: stale,
            ),
            const SizedBox(height: 12),
            QuotaCard(
              title: '每周额度',
              window: snapshot?.weekly,
              now: now,
              stale: stale,
            ),
            const SizedBox(height: 20),
            if (snapshot != null)
              Text(
                '采集时间：${DateTime.fromMillisecondsSinceEpoch(snapshot!.collectedAt * 1000).toLocal().toString().split('.').first}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: loading ? null : refresh,
              icon: loading
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.refresh),
              label: Text(loading ? '正在刷新…' : '刷新额度'),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              onPressed: () async {
                final added = await widget.platform.addQuickTile();
                if (context.mounted) {
                  ScaffoldMessenger.of(context).showSnackBar(
                    SnackBar(
                      content: Text(
                        added
                            ? 'Codex 额度快捷开关已添加，下拉状态栏查看'
                            : '下拉快捷设置 → 编辑 → 添加「Codex 额度」。点击刷新，长按打开应用。',
                      ),
                    ),
                  );
                }
              },
              icon: const Icon(Icons.dashboard_customize_outlined),
              label: const Text('添加状态栏额度快捷开关'),
            ),
            const SizedBox(height: 16),
            const Text(
              '桌面长按 → 小组件 → Codex Meter CN。后台约每 15 分钟尝试更新，实际时间由 Android 决定。',
              style: TextStyle(color: Colors.black54),
            ),
          ],
        ),
      ),
    );
  }
}

class QuotaCard extends StatelessWidget {
  final String title;
  final QuotaWindow? window;
  final DateTime now;
  final bool stale;
  const QuotaCard({
    super.key,
    required this.title,
    required this.window,
    required this.now,
    required this.stale,
  });
  @override
  Widget build(BuildContext context) {
    final resetPassed =
        window?.resetsAt != null &&
        window!.resetsAt! <= now.millisecondsSinceEpoch ~/ 1000;
    final historical = stale || resetPassed;
    return Card(
      elevation: 0,
      color: Colors.white,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Text(
                  window == null
                      ? '—'
                      : '${window!.remaining.toStringAsFixed(0)}%',
                  style: Theme.of(context).textTheme.displaySmall?.copyWith(
                    fontWeight: FontWeight.w700,
                    color: historical ? Colors.grey : const Color(0xff126b59),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.only(left: 8, bottom: 6),
                  child: Text(
                    window == null
                        ? '暂无额度数据'
                        : historical
                        ? '上次剩余'
                        : '剩余',
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            LinearProgressIndicator(
              value: window == null ? 0 : window!.remaining / 100,
              minHeight: 7,
              color: historical
                  ? Colors.grey
                  : window != null && window!.remaining <= 20
                  ? Colors.orange
                  : const Color(0xff126b59),
              backgroundColor: const Color(0xffe8eee9),
              borderRadius: BorderRadius.circular(8),
            ),
            const SizedBox(height: 14),
            Text(countdown(window?.resetsAt, now)),
          ],
        ),
      ),
    );
  }
}

class Notice extends StatelessWidget {
  final String text;
  const Notice(this.text, {super.key});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 6),
    child: Text(
      text,
      style: TextStyle(color: Theme.of(context).colorScheme.onSurfaceVariant),
    ),
  );
}

class SettingsPage extends StatefulWidget {
  final MeterConfig config;
  final MeterPlatform platform;
  const SettingsPage({super.key, required this.config, required this.platform});
  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  late String mode;
  late bool notifications;
  late double threshold;
  late final TextEditingController directUrl,
      workerUrl,
      directToken,
      workerToken,
      pairCode;
  String? error;
  bool saving = false, pairing = false;
  @override
  void initState() {
    super.initState();
    final c = widget.config;
    mode = c.mode;
    notifications = c.notifications;
    threshold = c.threshold.toDouble();
    directUrl = TextEditingController(text: c.directUrl);
    workerUrl = TextEditingController(text: c.workerUrl);
    directToken = TextEditingController(text: c.directToken);
    workerToken = TextEditingController(text: c.workerToken);
    pairCode = TextEditingController();
  }

  @override
  void dispose() {
    for (final c in [
      directUrl,
      workerUrl,
      directToken,
      workerToken,
      pairCode,
    ]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> save() async {
    setState(() {
      saving = true;
      error = null;
    });
    try {
      final c = MeterConfig(
        mode: mode,
        directUrl: directUrl.text.trim(),
        workerUrl: mode == 'worker'
            ? normalizeWorkerEndpoint(workerUrl.text)
            : workerUrl.text.trim(),
        directToken: directToken.text.trim(),
        workerToken: workerToken.text.trim(),
        notifications: notifications,
        threshold: threshold.round(),
      );
      if (mode != 'demo') {
        validateEndpoint(c.url);
        if (c.token.length < 32) throw const FormatException('读取令牌至少需要 32 个字符');
      }
      await widget.platform.save(c);
      if (mounted) Navigator.pop(context, c);
    } on FormatException catch (e) {
      if (mounted) setState(() => error = e.message);
    } catch (_) {
      if (mounted) setState(() => error = '设置保存失败，请重试');
    } finally {
      if (mounted) setState(() => saving = false);
    }
  }

  Future<void> pairAndSave() async {
    setState(() {
      pairing = true;
      error = null;
    });
    try {
      final token = await pairWorker(workerUrl.text, pairCode.text);
      if (!mounted) return;
      workerToken.text = token;
      pairCode.clear();
      await save();
    } on FormatException catch (e) {
      if (mounted) setState(() => error = e.message);
    } catch (_) {
      if (mounted) setState(() => error = '无法连接 Worker，请检查域名和网络');
    } finally {
      if (mounted) setState(() => pairing = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('数据源与提醒')),
    body: ListView(
      padding: const EdgeInsets.all(20),
      children: [
        SegmentedButton<String>(
          segments: const [
            ButtonSegment(value: 'demo', label: Text('演示')),
            ButtonSegment(value: 'direct', label: Text('直连')),
            ButtonSegment(value: 'worker', label: Text('Worker')),
          ],
          selected: {mode},
          onSelectionChanged: saving || pairing
              ? null
              : (value) => setState(() => mode = value.first),
        ),
        const SizedBox(height: 24),
        if (mode != 'demo') ...[
          TextField(
            controller: mode == 'worker' ? workerUrl : directUrl,
            enabled: !saving && !pairing,
            autocorrect: false,
            keyboardType: TextInputType.url,
            decoration: InputDecoration(
              labelText: mode == 'worker' ? 'Worker 域名或完整地址' : '快照完整地址',
              hintText: mode == 'worker'
                  ? 'meter.example.com'
                  : 'https://…/v1/snapshot',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 16),
          if (mode == 'worker') ...[
            TextField(
              controller: pairCode,
              enabled: !saving && !pairing,
              autocorrect: false,
              textCapitalization: TextCapitalization.characters,
              decoration: const InputDecoration(
                labelText: '一次性连接码',
                hintText: '管理页生成，5 分钟有效',
                border: OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 8),
            OutlinedButton(
              onPressed: saving || pairing ? null : pairAndSave,
              child: Text(pairing ? '连接中…' : '用连接码连接并保存'),
            ),
            const SizedBox(height: 12),
          ],
          TextField(
            controller: mode == 'worker' ? workerToken : directToken,
            obscureText: true,
            autocorrect: false,
            enableSuggestions: false,
            decoration: const InputDecoration(
              labelText: '只读令牌（使用连接码时自动填写）',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          const Text(
            '这里只填写网关的只读令牌，不填写 ChatGPT 凭证或 Worker 写入令牌。直连也读取采集器的统一快照接口。',
          ),
          const SizedBox(height: 20),
        ],
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('本地额度与重置提醒'),
          subtitle: const Text('重置提醒是预计时间提示，额度是否恢复需刷新确认'),
          value: notifications,
          onChanged: saving ? null : (v) => setState(() => notifications = v),
        ),
        Text('剩余额度不高于 ${threshold.round()}% 时提醒'),
        Slider(
          value: threshold,
          min: 5,
          max: 50,
          divisions: 9,
          label: '${threshold.round()}%',
          onChanged: saving ? null : (v) => setState(() => threshold = v),
        ),
        OutlinedButton(
          onPressed: () async {
            final allowed = await widget.platform.requestNotifications();
            if (context.mounted) {
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(allowed ? '通知权限已开启' : '请在系统设置允许通知，然后返回应用'),
                ),
              );
            }
          },
          child: const Text('开启 Android 通知权限'),
        ),
        const SizedBox(height: 12),
        const Text(
          '系统省电、强行停止和厂商后台限制可能延迟提醒；不是精确闹钟。令牌由 Android Keystore 加密保存，系统备份已关闭。',
        ),
        if (error != null) Notice(error!),
        const SizedBox(height: 24),
        FilledButton(
          onPressed: saving || pairing ? null : save,
          child: Text(saving ? '保存中…' : '保存并连接'),
        ),
      ],
    ),
  );
}
