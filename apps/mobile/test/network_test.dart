import 'dart:io';
import 'dart:convert';
import 'package:codex_meter_cn/meter_service.dart';
import 'package:codex_meter_cn/snapshot.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('real local HTTP snapshot, read token and redirect refusal', () async {
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    var redirect = false;
    server.listen((request) async {
      expect(request.headers.value('Authorization'), 'Bearer ${'r' * 40}');
      expect(
        request.headers.value(HttpHeaders.userAgentHeader),
        contains('CodexMeterCN'),
      );
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
  test(
    'pairing sends a code in the body and rejects a different endpoint',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      var wrongEndpoint = false;
      server.listen((request) async {
        expect(request.uri.path, '/api/pair');
        expect(request.method, 'POST');
        expect(request.headers.value('Authorization'), isNull);
        expect(
          jsonDecode(await utf8.decoder.bind(request).join())['code'],
          '234567-ABCDEF',
        );
        request.response.headers.contentType = ContentType.json;
        request.response.write(
          jsonEncode({
            'url': wrongEndpoint
                ? 'https://other.example/v1/snapshot'
                : 'http://127.0.0.1:${server.port}/v1/snapshot',
            'readToken': 'r' * 40,
          }),
        );
        await request.response.close();
      });
      try {
        final url = 'http://127.0.0.1:${server.port}/v1/snapshot';
        expect(await pairWorker(url, '234567-ABCDEF'), 'r' * 40);
        wrongEndpoint = true;
        await expectLater(
          pairWorker(url, '234567-ABCDEF'),
          throwsFormatException,
        );
      } finally {
        await server.close(force: true);
      }
    },
  );
}
