import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';
import 'snapshot.dart';

class MeterConfig {
  final String mode, directUrl, workerUrl, directToken, workerToken;
  final bool notifications;
  final int threshold;
  const MeterConfig({
    this.mode = 'demo',
    this.directUrl = 'http://127.0.0.1:8788/v1/snapshot',
    this.workerUrl = '',
    this.directToken = '',
    this.workerToken = '',
    this.notifications = false,
    this.threshold = 20,
  });
  String get url => mode == 'worker' ? workerUrl : directUrl;
  String get token => mode == 'worker' ? workerToken : directToken;
  Map<String, dynamic> toJson() => {
    'mode': mode,
    'directUrl': directUrl,
    'workerUrl': workerUrl,
    'directToken': directToken,
    'workerToken': workerToken,
    'notifications': notifications,
    'threshold': threshold,
  };
  factory MeterConfig.parse(String value) {
    final json = jsonDecode(value) as Map<String, dynamic>;
    return MeterConfig(
      mode: json['mode'] as String? ?? 'demo',
      directUrl: json['directUrl'] as String? ?? '',
      workerUrl: json['workerUrl'] as String? ?? '',
      directToken: json['directToken'] as String? ?? '',
      workerToken: json['workerToken'] as String? ?? '',
      notifications: json['notifications'] == true,
      threshold: json['threshold'] as int? ?? 20,
    );
  }
}

Uri validateEndpoint(String value) {
  final uri = Uri.parse(value);
  if (uri.userInfo.isNotEmpty ||
      uri.hasQuery ||
      uri.hasFragment ||
      uri.path != '/v1/snapshot' ||
      uri.host.isEmpty ||
      !(uri.scheme == 'https' ||
          uri.scheme == 'http' &&
              [
                '127.0.0.1',
                'localhost',
                '::1',
                '10.0.2.2',
              ].contains(uri.host))) {
    throw const FormatException('请填写 HTTPS 快照地址，路径为 /v1/snapshot；HTTP 仅限本机调试');
  }
  return uri;
}

String normalizeWorkerEndpoint(String value) {
  final input = value.trim();
  final uri = Uri.parse(input.contains('://') ? input : 'https://$input');
  if (uri.path.isEmpty || uri.path == '/') {
    return validateEndpoint(
      uri.replace(path: '/v1/snapshot').toString(),
    ).toString();
  }
  return validateEndpoint(uri.toString()).toString();
}

abstract class MeterPlatform {
  Future<bool> addQuickTile() async => false;
  Future<Map<String, dynamic>> load();
  Future<void> save(MeterConfig config);
  Future<void> update(Snapshot snapshot);
  Future<bool> requestNotifications();
}

class AndroidMeterPlatform extends MeterPlatform {
  @override
  Future<bool> addQuickTile() async =>
      await channel.invokeMethod<bool>('addQuickTile') ?? false;
  static const channel = MethodChannel('codex_meter/native');
  @override
  Future<Map<String, dynamic>> load() async => Map<String, dynamic>.from(
    await channel.invokeMapMethod<String, dynamic>('load') ?? {},
  );
  @override
  Future<void> save(MeterConfig config) =>
      channel.invokeMethod('save', jsonEncode(config.toJson()));
  @override
  Future<void> update(Snapshot snapshot) =>
      channel.invokeMethod('updateSnapshot', snapshot.raw);
  @override
  Future<bool> requestNotifications() async =>
      await channel.invokeMethod<bool>('requestNotifications') ?? false;
}

Future<Snapshot> fetchSnapshot(MeterConfig config) async {
  if (config.mode == 'demo') return Snapshot.demo();
  final uri = validateEndpoint(config.url);
  if (config.token.length < 32) throw const FormatException('读取令牌至少需要 32 个字符');
  final client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 10)
    ..userAgent = 'CodexMeterCN/0.1 Android';
  try {
    final request = await client
        .getUrl(uri)
        .timeout(const Duration(seconds: 12));
    request.followRedirects = false;
    request.headers.set(
      HttpHeaders.authorizationHeader,
      'Bearer ${config.token}',
    );
    final response = await request.close().timeout(const Duration(seconds: 12));
    if (response.statusCode != 200) {
      throw HttpException(switch (response.statusCode) {
        401 => '读取令牌无效，请检查设置',
        404 => '还没有快照或地址不正确',
        503 => '网关未就绪，请检查采集器或 Worker 配置',
        _ => '网关返回 ${response.statusCode}，请稍后重试',
      });
    }
    final body = <int>[];
    await for (final chunk in response.timeout(const Duration(seconds: 12))) {
      body.addAll(chunk);
      if (body.length > 16384) throw const FormatException('快照超过大小限制');
    }
    return Snapshot.parse(utf8.decode(body));
  } finally {
    client.close(force: true);
  }
}

Future<String> pairWorker(String server, String code) async {
  final uri = validateEndpoint(normalizeWorkerEndpoint(server));
  final normalized = code.trim().toUpperCase();
  if (!RegExp(r'^[2-9A-HJ-NP-Z]{6}-?[2-9A-HJ-NP-Z]{6}$').hasMatch(normalized)) {
    throw const FormatException('请输入管理页生成的 12 位连接码');
  }
  final client = HttpClient()
    ..connectionTimeout = const Duration(seconds: 10)
    ..userAgent = 'CodexMeterCN/0.1 Android';
  try {
    final request = await client
        .postUrl(uri.replace(path: '/api/pair'))
        .timeout(const Duration(seconds: 12));
    request.followRedirects = false;
    request.headers.contentType = ContentType.json;
    request.write(jsonEncode({'code': normalized}));
    final response = await request.close().timeout(const Duration(seconds: 12));
    if (response.statusCode != 200) {
      throw const FormatException('连接码无效、过期或已使用，请在管理页重新生成');
    }
    final bytes = <int>[];
    await for (final chunk in response.timeout(const Duration(seconds: 12))) {
      if (bytes.length + chunk.length > 4096) {
        throw const FormatException('连接响应异常');
      }
      bytes.addAll(chunk);
    }
    final value = jsonDecode(utf8.decode(bytes));
    if (value is! Map<String, dynamic> ||
        value['url'] != uri.toString() ||
        value['readToken'] is! String ||
        (value['readToken'] as String).length < 32 ||
        (value['readToken'] as String).length > 1000 ||
        RegExp(r'[\r\n]').hasMatch(value['readToken'])) {
      throw const FormatException('连接响应异常');
    }
    return value['readToken'] as String;
  } finally {
    client.close(force: true);
  }
}
