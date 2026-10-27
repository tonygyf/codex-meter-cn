import 'dart:convert';

int _timestamp(dynamic value) {
  if (value is! int || value <= 0) throw const FormatException('时间格式错误');
  return value;
}

bool _keys(Map<String, dynamic> value, List<String> keys) =>
    value.length == keys.length && keys.every(value.containsKey);

class QuotaWindow {
  final double usedPercent;
  final int windowMinutes;
  final int? resetsAt;
  const QuotaWindow(this.usedPercent, this.windowMinutes, this.resetsAt);
  double get remaining => 100 - usedPercent;

  factory QuotaWindow.parse(dynamic value, int minutes) {
    if (value is! Map<String, dynamic> ||
        !_keys(value, ['usedPercent', 'windowMinutes', 'resetsAt']) ||
        value['usedPercent'] is! num ||
        !(value['usedPercent'] as num).isFinite ||
        value['usedPercent'] < 0 ||
        value['usedPercent'] > 100 ||
        value['windowMinutes'] is! int ||
        value['windowMinutes'] != minutes) {
      throw const FormatException('额度窗口格式错误');
    }
    return QuotaWindow(
      (value['usedPercent'] as num).toDouble(),
      minutes,
      value['resetsAt'] == null ? null : _timestamp(value['resetsAt']),
    );
  }
}

class Snapshot {
  final int collectedAt;
  final int expiresAt;
  final QuotaWindow? fiveHour;
  final QuotaWindow? weekly;
  final String raw;
  const Snapshot(
    this.collectedAt,
    this.expiresAt,
    this.fiveHour,
    this.weekly,
    this.raw,
  );

  factory Snapshot.parse(String raw, {DateTime? now}) {
    if (utf8.encode(raw).length > 16384) throw const FormatException('快照过大');
    final value = jsonDecode(raw);
    if (value is! Map<String, dynamic> ||
        !_keys(value, [
          'schemaVersion',
          'collectedAt',
          'expiresAt',
          'windows',
        ]) ||
        value['schemaVersion'] is! int ||
        value['schemaVersion'] != 1) {
      throw const FormatException('不支持的快照协议');
    }
    final collected = _timestamp(value['collectedAt']);
    final expires = _timestamp(value['expiresAt']);
    final current = (now ?? DateTime.now()).millisecondsSinceEpoch ~/ 1000;
    final windows = value['windows'];
    if (collected > current + 60 ||
        expires <= collected ||
        expires - collected > 3600 ||
        windows is! Map<String, dynamic> ||
        !_keys(windows, ['fiveHour', 'weekly'])) {
      throw const FormatException('快照时间或窗口无效');
    }
    return Snapshot(
      collected,
      expires,
      windows['fiveHour'] == null
          ? null
          : QuotaWindow.parse(windows['fiveHour'], 300),
      windows['weekly'] == null
          ? null
          : QuotaWindow.parse(windows['weekly'], 10080),
      raw,
    );
  }

  bool isStale(DateTime now) => now.millisecondsSinceEpoch ~/ 1000 >= expiresAt;

  static Snapshot demo() {
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    return Snapshot.parse(
      jsonEncode({
        'schemaVersion': 1,
        'collectedAt': now,
        'expiresAt': now + 900,
        'windows': {
          'fiveHour': {
            'usedPercent': 37,
            'windowMinutes': 300,
            'resetsAt': now + 7200,
          },
          'weekly': {
            'usedPercent': 68,
            'windowMinutes': 10080,
            'resetsAt': now + 172800,
          },
        },
      }),
    );
  }
}

String countdown(int? timestamp, DateTime now) {
  if (timestamp == null) return '重置时间未知';
  final seconds = timestamp - now.millisecondsSinceEpoch ~/ 1000;
  if (seconds <= 0) return '重置时间已到，等待新快照';
  final duration = Duration(seconds: seconds);
  final days = duration.inDays;
  final hours = duration.inHours % 24;
  final minutes = duration.inMinutes % 60;
  return days > 0
      ? '$days 天 $hours 小时后重置'
      : '$hours 小时 $minutes 分 ${seconds % 60} 秒后重置';
}
