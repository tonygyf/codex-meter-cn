import 'dart:convert';
import 'package:codex_meter_cn/meter_app.dart';
import 'package:codex_meter_cn/meter_service.dart';
import 'package:codex_meter_cn/snapshot.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class MemoryPlatform implements MeterPlatform {
  Map<String, dynamic> data = {};
  MeterConfig? saved;
  @override
  Future<Map<String, dynamic>> load() async => data;
  @override
  Future<void> save(MeterConfig c) async {
    saved = c;
  }

  @override
  Future<void> update(Snapshot value) async {
    data['snapshot'] = value.raw;
  }

  @override
  Future<bool> requestNotifications() async => true;
}

void main() {
  testWidgets(
    'Chinese demo renders and configuration can be saved without a service',
    (tester) async {
      final platform = MemoryPlatform();
      await tester.pumpWidget(MeterApp(platform: platform));
      await tester.pumpAndSettle();
      expect(find.text('5 小时额度'), findsOneWidget);
      expect(find.text('每周额度'), findsOneWidget);
      expect(find.text('63%'), findsOneWidget);
      expect(find.text('演示模式 · 模拟数据'), findsOneWidget);
      await tester.tap(find.byTooltip('数据源与提醒'));
      await tester.pumpAndSettle();
      expect(find.text('数据源与提醒'), findsOneWidget);
      await tester.ensureVisible(find.text('保存并连接'));
      await tester.tap(find.text('保存并连接'));
      await tester.pumpAndSettle();
      expect(platform.saved!.mode, 'demo');
      await tester.pumpWidget(const SizedBox());
    },
  );
  testWidgets('unavailable source keeps stale cached quotas visible', (
    tester,
  ) async {
    final platform = MemoryPlatform();
    final snapshot = jsonDecode(Snapshot.demo().raw);
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    snapshot['collectedAt'] = now - 2000;
    snapshot['expiresAt'] = now - 1100;
    platform.data = {
      'config': jsonEncode(
        const MeterConfig(
          mode: 'direct',
          directUrl: 'https://host/wrong',
        ).toJson(),
      ),
      'snapshot': jsonEncode(snapshot),
    };
    await tester.pumpWidget(MeterApp(platform: platform));
    await tester.pumpAndSettle();
    expect(find.text('快照已过期'), findsOneWidget);
    expect(find.text('63%'), findsOneWidget);
    expect(find.text('上次剩余'), findsNWidgets(2));
    await tester.pumpWidget(const SizedBox());
  });
}
