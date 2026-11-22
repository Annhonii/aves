import 'dart:typed_data';

import 'package:aves/services/common/channel.dart';
import 'package:flutter/services.dart';

/// A readable failure coming from the Telegram cloud (wrong token, wrong passphrase, network...).
class TelegramCloudException implements Exception {
  final String message;

  const TelegramCloudException(this.message);

  @override
  String toString() => message;
}

class TelegramCloudItem {
  final String path;
  final int size;
  final int takenMs;
  final int addedMs;
  final bool hasPreview;

  const TelegramCloudItem({
    required this.path,
    required this.size,
    required this.takenMs,
    required this.addedMs,
    required this.hasPreview,
  });

  factory TelegramCloudItem.fromMap(Map<dynamic, dynamic> map) {
    return TelegramCloudItem(
      path: map['path'] as String,
      size: (map['size'] as num?)?.toInt() ?? 0,
      takenMs: (map['takenMs'] as num?)?.toInt() ?? 0,
      addedMs: (map['addedMs'] as num?)?.toInt() ?? 0,
      hasPreview: map['hasPreview'] as bool? ?? false,
    );
  }

  String get name => path.split('/').last;

  String get album => path.contains('/') ? path.substring(0, path.lastIndexOf('/')) : '';

  bool get isVideo {
    final dot = name.lastIndexOf('.');
    final ext = dot < 0 ? '' : name.substring(dot + 1).toLowerCase();
    return const {'mp4', 'mkv', 'webm', 'mov', '3gp', 'avi', 'm4v'}.contains(ext);
  }
}

class TelegramDeleteOutcome {
  final bool existed;
  final int deleted;
  final int refused;

  const TelegramDeleteOutcome({required this.existed, required this.deleted, required this.refused});
}

class TelegramCloudService {
  static const _platform = AvesMethodChannel('deckers.thibault/aves/telegram_cloud');

  Future<T> _call<T>(String method, [Map<String, Object?>? args]) async {
    try {
      final result = await _platform.invokeMethod<T>(method, args);
      return result as T;
    } on PlatformException catch (e) {
      throw TelegramCloudException(e.message ?? 'Telegram cloud error');
    }
  }

  /// Returns the saved channel id when a cloud is configured on this device, otherwise null.
  Future<String?> configuredChat() async {
    final result = await _call<Map<dynamic, dynamic>>('status');
    return result['configured'] == true ? result['chatId'] as String? : null;
  }

  /// Validates the bot, channel and passphrase, restores the index from the channel when needed,
  /// and returns the number of items already in the cloud.
  Future<int> connect({required String chatId, required String token, required String passphrase}) async {
    final result = await _call<Map<dynamic, dynamic>>('connect', {
      'chatId': chatId,
      'token': token,
      'passphrase': passphrase,
    });
    return (result['count'] as num?)?.toInt() ?? 0;
  }

  /// Forgets the credentials and the local index. Encrypted files stay in the Telegram channel.
  Future<void> disconnect() => _call<bool>('disconnect');

  Future<List<TelegramCloudItem>> list() async {
    final result = await _call<List<dynamic>>('list');
    return result.map((v) => TelegramCloudItem.fromMap(v as Map<dynamic, dynamic>)).toList();
  }

  Future<Uint8List?> thumbnail(String path, {bool preview = false}) async {
    return _call<Uint8List?>('thumbnail', {'path': path, 'preview': preview});
  }

  /// Uploads one local entry. Returns true when it was uploaded, false when an identical one was already there.
  Future<bool> upload({required String uri, required String path, required String mimeType, required int takenMs}) async {
    final result = await _call<Map<dynamic, dynamic>>('upload', {
      'uri': uri,
      'path': path,
      'mimeType': mimeType,
      'takenMs': takenMs,
    });
    return result['uploaded'] == true;
  }

  /// Decrypts the item into the app cache and returns the file path.
  Future<String> download(String path) => _call<String>('download', {'path': path});

  /// Saves a decrypted copy into the device gallery.
  Future<void> restore(String path) => _call<String>('restore', {'path': path});

  Future<TelegramDeleteOutcome> delete(String path) async {
    final result = await _call<Map<dynamic, dynamic>>('delete', {'path': path});
    return TelegramDeleteOutcome(
      existed: result['existed'] == true,
      deleted: (result['deleted'] as num?)?.toInt() ?? 0,
      refused: (result['refused'] as num?)?.toInt() ?? 0,
    );
  }
}

final TelegramCloudService telegramCloudService = TelegramCloudService();
