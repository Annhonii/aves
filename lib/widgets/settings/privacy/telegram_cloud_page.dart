import 'dart:async';

import 'package:aves/model/entry/entry.dart';
import 'package:aves/model/source/collection_source.dart';
import 'package:aves/services/common/services.dart';
import 'package:aves/services/telegram_cloud_service.dart';
import 'package:aves/widgets/common/basic/scaffold.dart';
import 'package:aves/widgets/settings/privacy/telegram_cloud_gallery_page.dart';
import 'package:material_ui/material_ui.dart';
import 'package:provider/provider.dart';

class TelegramCloudPage extends StatefulWidget {
  static const routeName = '/settings/telegram_cloud';

  const TelegramCloudPage({super.key});

  @override
  State<TelegramCloudPage> createState() => _TelegramCloudPageState();
}

class _TelegramCloudPageState extends State<TelegramCloudPage> {
  static const _foldersKey = 'telegram_cloud_folders';

  final _chatController = TextEditingController();
  final _tokenController = TextEditingController();
  final _passphraseController = TextEditingController();

  bool _loading = true;
  bool _busy = false;
  String? _chatId;
  String? _error;
  Set<String> _selectedFolders = {};

  bool _uploading = false;
  bool _cancelRequested = false;
  int _uploadDone = 0;
  int _uploadTotal = 0;
  int _uploadFailed = 0;
  String? _uploadError;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void dispose() {
    _cancelRequested = true;
    _chatController.dispose();
    _tokenController.dispose();
    _passphraseController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    String? chat;
    try {
      chat = await telegramCloudService.configuredChat();
    } on TelegramCloudException catch (e) {
      _error = e.message;
    }
    final saved = await securityService.readValue<String>(_foldersKey);
    if (!mounted) return;
    setState(() {
      _chatId = chat;
      _selectedFolders = saved == null || saved.isEmpty ? {} : saved.split('\n').toSet();
      _loading = false;
    });
  }

  Future<void> _connect() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final chat = _chatController.text.trim();
      await telegramCloudService.connect(
        chatId: chat,
        token: _tokenController.text.trim(),
        passphrase: _passphraseController.text,
      );
      _tokenController.clear();
      _passphraseController.clear();
      if (!mounted) return;
      setState(() => _chatId = chat.replaceFirst('telegram://', ''));
    } on TelegramCloudException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _disconnect() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Disconnect Telegram cloud?'),
        content: const Text(
          'This device forgets the bot token, the passphrase and the local index. '
          'Files already uploaded stay in your channel, encrypted. You can reconnect later with the same channel, bot and passphrase.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Disconnect')),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _busy = true);
    try {
      await telegramCloudService.disconnect();
      if (mounted) setState(() => _chatId = null);
    } on TelegramCloudException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Local folders (directories) that contain media, with their item count.
  Map<String, int> _folders(CollectionSource source) {
    final counts = <String, int>{};
    for (final entry in source.visibleEntries) {
      final dir = entry.directory;
      if (dir == null) continue;
      counts[dir] = (counts[dir] ?? 0) + 1;
    }
    return counts;
  }

  Future<void> _toggleFolder(String dir, bool selected) async {
    setState(() {
      if (selected) {
        _selectedFolders.add(dir);
      } else {
        _selectedFolders.remove(dir);
      }
    });
    await securityService.writeValue<String>(_foldersKey, _selectedFolders.join('\n'));
  }

  Future<void> _upload(CollectionSource source) async {
    final entries = source.visibleEntries.where((e) => e.directory != null && e.path != null && _selectedFolders.contains(e.directory)).toList();
    if (entries.isEmpty) return;
    setState(() {
      _uploading = true;
      _cancelRequested = false;
      _uploadDone = 0;
      _uploadFailed = 0;
      _uploadTotal = entries.length;
      _uploadError = null;
    });
    var consecutiveFailures = 0;
    for (final AvesEntry entry in entries) {
      if (_cancelRequested || !mounted) break;
      final dir = entry.directory!;
      final album = dir.split('/').where((s) => s.isNotEmpty).lastOrNull ?? 'root';
      final name = entry.path!.split('/').last;
      try {
        await telegramCloudService.upload(
          uri: entry.uri,
          path: '$album/$name',
          mimeType: entry.mimeType,
          takenMs: entry.sourceDateTakenMillis ?? entry.dateModifiedMillis ?? 0,
        );
        consecutiveFailures = 0;
      } on TelegramCloudException catch (e) {
        _uploadFailed++;
        _uploadError = e.message;
        consecutiveFailures++;
      }
      if (!mounted) return;
      setState(() => _uploadDone++);
      // stop early on a persistent problem (no network, kicked bot...) instead of failing every file
      if (consecutiveFailures >= 5) break;
    }
    if (mounted) setState(() => _uploading = false);
  }

  @override
  Widget build(BuildContext context) {
    return AvesScaffold(
      appBar: AppBar(title: const Text('Telegram cloud')),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : _chatId == null
            ? _buildSetup(context)
            : _buildConnected(context),
      ),
    );
  }

  Widget _buildSetup(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        const Text(
          'Back up chosen folders to a private Telegram channel. Every file is encrypted on this device with AES-256 '
          'before upload and is stored as an opaque .bin document, so it cannot be opened from Telegram, and only this '
          'app with your passphrase can decode it.\n\n'
          '1. Create a private channel.\n'
          '2. Create a bot with @BotFather and add it to the channel as an administrator.\n'
          '3. Enter the channel id (for example -1001234567890), the bot token and a long passphrase (12+ characters).\n\n'
          'The passphrase never leaves your device and cannot be recovered: if you lose it, the uploaded files are gone. '
          'Telegram only lets bots delete messages younger than 48 hours, so older files removed from the app may remain '
          'in the channel as unreadable encrypted data.',
        ),
        const SizedBox(height: 16),
        TextField(
          controller: _chatController,
          enabled: !_busy,
          decoration: const InputDecoration(labelText: 'Channel id', hintText: '-1001234567890', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _tokenController,
          enabled: !_busy,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(labelText: 'Bot token', border: OutlineInputBorder()),
        ),
        const SizedBox(height: 12),
        TextField(
          controller: _passphraseController,
          enabled: !_busy,
          obscureText: true,
          autocorrect: false,
          enableSuggestions: false,
          decoration: const InputDecoration(labelText: 'Encryption passphrase (12+ characters)', border: OutlineInputBorder()),
        ),
        if (_error != null) ...[
          const SizedBox(height: 12),
          Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
        const SizedBox(height: 16),
        FilledButton(
          onPressed: _busy ? null : _connect,
          child: _busy
              ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
              : const Text('Connect'),
        ),
      ],
    );
  }

  Widget _buildConnected(BuildContext context) {
    final source = context.read<CollectionSource>();
    final folders = _folders(source).entries.toList()..sort((a, b) => a.key.compareTo(b.key));
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text('Connected to channel $_chatId', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 12),
        FilledButton(
          onPressed: _uploading
              ? null
              : () => Navigator.of(context).push(
                  MaterialPageRoute(
                    settings: const RouteSettings(name: TelegramCloudGalleryPage.routeName),
                    builder: (context) => const TelegramCloudGalleryPage(),
                  ),
                ),
          child: const Text('Open cloud gallery'),
        ),
        const SizedBox(height: 24),
        Text('Folders to upload', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        ...folders.map(
          (folder) => CheckboxListTile(
            value: _selectedFolders.contains(folder.key),
            onChanged: _uploading ? null : (v) => _toggleFolder(folder.key, v ?? false),
            title: Text(folder.key.split('/').where((s) => s.isNotEmpty).lastOrNull ?? folder.key),
            subtitle: Text('${folder.key} · ${folder.value} items'),
            controlAffinity: ListTileControlAffinity.leading,
          ),
        ),
        const SizedBox(height: 8),
        if (_uploading || _uploadTotal > 0) ...[
          LinearProgressIndicator(value: _uploadTotal == 0 ? null : _uploadDone / _uploadTotal),
          const SizedBox(height: 4),
          Text('$_uploadDone / $_uploadTotal processed${_uploadFailed > 0 ? ' · $_uploadFailed failed' : ''}'),
          if (_uploadError != null) Text(_uploadError!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          const SizedBox(height: 8),
        ],
        Row(
          children: [
            Expanded(
              child: FilledButton(
                onPressed: _uploading || _selectedFolders.isEmpty ? null : () => unawaited(_upload(source)),
                child: const Text('Upload selected folders'),
              ),
            ),
            if (_uploading) ...[
              const SizedBox(width: 8),
              OutlinedButton(onPressed: () => setState(() => _cancelRequested = true), child: const Text('Stop')),
            ],
          ],
        ),
        const SizedBox(height: 4),
        const Text('Files already in the cloud are skipped. Keep the app open while uploading.'),
        const SizedBox(height: 24),
        OutlinedButton(onPressed: _busy || _uploading ? null : _disconnect, child: const Text('Disconnect this device')),
      ],
    );
  }
}
