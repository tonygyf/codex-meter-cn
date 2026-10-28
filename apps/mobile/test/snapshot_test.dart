import 'dart:convert';
import 'dart:io';
import 'package:codex_meter_cn/meter_service.dart';
import 'package:codex_meter_cn/snapshot.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  final raw = File(
    '../../packages/protocol/fixtures/snapshot-v1.json',
  ).readAsStringSync();
  final now = DateTime.fromMillisecondsSinceEpoch(1793100000 * 1000);
  test('shared fixture parses percentages and expires exactly at boundary', () {
    final value = Snapshot.parse(raw, now: now);
    expect(value.fiveHour!.remaining, 63);
    expect(value.weekly!.remaining, 32);
    expect(value.isStale(now.add(const Duration(seconds: 899))), false);
    expect(value.isStale(now.add(const Duration(seconds: 900))), true);
  });
  test('null windows stay unknown and reset never invents restored quota', () {
    final json = jsonDecode(raw);
    json['windows']['fiveHour'] = null;
    expect(Snapshot.parse(jsonEncode(json), now: now).fiveHour, isNull);
    expect(countdown(1793100000, now), contains('等待新快照'));
    expect(countdown(null, now), '重置时间未知');
  });
  test(
    'reject secret fields, bad versions, invalid windows and future timestamps',
    () {
      for (final mutate in <void Function(dynamic)>[
        (v) => v['accessToken'] = 'private',
        (v) => v['schemaVersion'] = 2,
        (v) => v['windows']['fiveHour']['usedPercent'] = 101,
        (v) => v['windows']['weekly']['windowMinutes'] = 300,
        (v) => v['collectedAt'] = 1793100120,
        (v) => v['expiresAt'] = 1793103601,
      ]) {
        final value = jsonDecode(raw);
        mutate(value);
        expect(
          () => Snapshot.parse(jsonEncode(value), now: now),
          throwsFormatException,
        );
      }
    },
  );
  test('URL rules prevent plaintext remote credentials and URL tokens', () {
    for (final value in [
      'http://public.example/v1/snapshot',
      'https://u:p@host/v1/snapshot',
      'https://host/v1/snapshot?token=secret',
      'https://host/wrong',
    ]) {
      expect(() => validateEndpoint(value), throwsFormatException);
    }
    expect(validateEndpoint('https://host/v1/snapshot').scheme, 'https');
    expect(
      validateEndpoint('http://127.0.0.1:8788/v1/snapshot').host,
      '127.0.0.1',
    );
  });
  test('both modes use independent persisted read credentials', () {
    const c = MeterConfig(
      mode: 'worker',
      directToken: 'direct',
      workerToken: 'worker',
      workerUrl: 'https://host/v1/snapshot',
    );
    final parsed = MeterConfig.parse(jsonEncode(c.toJson()));
    expect(parsed.token, 'worker');
    expect(parsed.url, c.workerUrl);
    expect(parsed.directToken, 'direct');
  });
  test('Worker hostname is normalized without weakening URL validation', () {
    expect(
      normalizeWorkerEndpoint('meter.example.com'),
      'https://meter.example.com/v1/snapshot',
    );
    expect(
      normalizeWorkerEndpoint('https://meter.example.com/'),
      'https://meter.example.com/v1/snapshot',
    );
    expect(
      normalizeWorkerEndpoint('https://meter.example.com/v1/snapshot'),
      'https://meter.example.com/v1/snapshot',
    );
    for (final value in [
      'http://meter.example.com',
      'meter.example.com?token=secret',
      'https://u:p@meter.example.com',
    ]) {
      expect(() => normalizeWorkerEndpoint(value), throwsFormatException);
    }
  });
}
