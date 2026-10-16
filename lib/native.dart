import 'package:flutter/services.dart';

class FolderInfo {
  const FolderInfo(this.uri, this.label);
  final String uri;
  final String label;

  static FolderInfo? from(Object? raw) {
    if (raw == null) return null;
    final m = Map<String, dynamic>.from(raw as Map);
    return FolderInfo(m['uri'] as String, m['label'] as String);
  }
}

class Settings {
  const Settings({
    required this.maxSide,
    required this.quality,
    required this.overwrite,
    required this.copyOthers,
    this.dst,
  });
  final int maxSide, quality;
  final bool overwrite, copyOthers;
  final FolderInfo? dst;

  static Settings from(Map<String, dynamic> m) => Settings(
        maxSide: m['maxSide'] as int,
        quality: m['quality'] as int,
        overwrite: m['overwrite'] as bool,
        copyOthers: m['copyOthers'] as bool,
        dst: FolderInfo.from(m['dst']),
      );
}

class ScanInfo {
  ScanInfo(Map<String, dynamic> m)
      : done = m['done'] as bool,
        images = (m['images'] as num).toInt(),
        imageBytes = (m['imageBytes'] as num).toInt(),
        others = (m['others'] as num).toInt(),
        otherBytes = (m['otherBytes'] as num).toInt();
  final bool done;
  final int images, imageBytes, others, otherBytes;
}

class RunInfo {
  RunInfo(Map<String, dynamic> m)
      : isDone = m['type'] == 'done',
        total = (m['total'] as num).toInt(),
        done = (m['done'] as num).toInt(),
        resized = (m['resized'] as num).toInt(),
        skippedSmall = (m['skippedSmall'] as num).toInt(),
        skippedNotSmaller = (m['skippedNotSmaller'] as num).toInt(),
        skippedExisting = (m['skippedExisting'] as num).toInt(),
        copiedAsIs = (m['copiedAsIs'] as num).toInt(),
        others = (m['others'] as num).toInt(),
        failed = (m['failed'] as num).toInt(),
        bytesBefore = (m['bytesBefore'] as num).toInt(),
        bytesAfter = (m['bytesAfter'] as num).toInt(),
        current = (m['current'] as String?) ?? '',
        cancelled = (m['cancelled'] as bool?) ?? false,
        fatal = m['fatal'] as String?,
        failures = ((m['failures'] as List?) ?? const []).cast<String>();
  final bool isDone;
  final int total, done, resized, skippedSmall, skippedNotSmaller, skippedExisting;
  final int copiedAsIs, others, failed, bytesBefore, bytesAfter;
  final String current;
  final bool cancelled;
  final String? fatal;
  final List<String> failures;
}

class Native {
  static const _method = MethodChannel('photo_sizer/native');
  static const _events = EventChannel('photo_sizer/events');

  static Stream<Map<String, dynamic>> events() =>
      _events.receiveBroadcastStream().map((e) => Map<String, dynamic>.from(e as Map));

  /// [remember]: store it as the "last source folder" (not wanted for the destination folder).
  static Future<FolderInfo?> pickFolder({bool remember = true}) async =>
      FolderInfo.from(await _method.invokeMethod('pickFolder', {'remember': remember}));

  static Future<Settings> loadSettings() async =>
      Settings.from(Map<String, dynamic>.from(await _method.invokeMethod('loadSettings') as Map));

  static Future<void> saveSettings(Settings s) => _method.invokeMethod('saveSettings', {
        'maxSide': s.maxSide,
        'quality': s.quality,
        'overwrite': s.overwrite,
        'copyOthers': s.copyOthers,
        'dst': s.dst?.uri,
      });

  static Future<FolderInfo?> lastFolder() async =>
      FolderInfo.from(await _method.invokeMethod('lastFolder'));

  static Future<void> scan(String uri) => _method.invokeMethod('scan', {'uri': uri});

  static Future<void> run({
    required String src,
    String? dst,
    required bool copyOthers,
    required int quality,
    required int limit,
    required int total,
    int maxSide = 1280,
  }) =>
      _method.invokeMethod('run', {
        'src': src,
        'dst': dst,
        'copyOthers': copyOthers,
        'quality': quality,
        'limit': limit,
        'total': total,
        'maxSide': maxSide,
      });

  static Future<void> cancel() => _method.invokeMethod('cancel');

  static Future<bool> isBusy() async => (await _method.invokeMethod<bool>('isBusy')) ?? false;
}
