import 'dart:io';
import 'package:codex_meter_cn/meter_service.dart';
import 'package:codex_meter_cn/snapshot.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('real local HTTP snapshot, read token and redirect refusal', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    var redirect = false;
    server.listen((request) async {
      expect(request.headers.value('Authorization'), 'Bearer ${'r' * 40}');
      request.response.statusCode = redirect ? 302 : 200;
      if (redirect) {
        request.response.headers.set(
          'Location',
          'https://other.example/v1/snapshot',
        );
      } else {
        request.response.write(Snapshot.demo().raw);
      }
      await request.response.close();
    });
    try {
      final config = MeterConfig(
        mode: 'direct',
        directUrl: 'http://127.0.0.1:${server.port}/v1/snapshot',
        directToken: 'r' * 40,
      );
      expect((await fetchSnapshot(config)).fiveHour!.remaining, 63);
      redirect = true;
      await expectLater(fetchSnapshot(config), throwsA(isA<HttpException>()));
    } finally {
      await server.close(force: true);
    }
  });
}
