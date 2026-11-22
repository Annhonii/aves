import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:aves/services/telegram_cloud_service.dart';
import 'package:aves/theme/icons.dart';
import 'package:aves/widgets/common/basic/scaffold.dart';
import 'package:material_ui/material_ui.dart';

/// Photos and videos stored (encrypted) in the Telegram channel. Thumbnails come from small encrypted
/// previews, so browsing does not download the originals.
class TelegramCloudGalleryPage extends StatefulWidget {
  static const routeName = '/settings/telegram_cloud/gallery';

  const TelegramCloudGalleryPage({super.key});

  @override
  State<TelegramCloudGalleryPage> createState() => _TelegramCloudGalleryPageState();
}

class _TelegramCloudGalleryPageState extends State<TelegramCloudGalleryPage> {
  List<TelegramCloudItem>? _items;
  String? _error;
  final Map<String, Future<Uint8List?>> _thumbnails = {};

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    setState(() {
      _items = null;
      _error = null;
    });
    try {
      final items = await telegramCloudService.list();
      if (mounted) setState(() => _items = items);
    } on TelegramCloudException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  Future<Uint8List?> _thumbnail(String path) {
    return _thumbnails.putIfAbsent(path, () async {
      try {
        return await telegramCloudService.thumbnail(path);
      } on TelegramCloudException {
        return null;
      }
    });
  }

  Future<void> _open(int index) async {
    final items = _items;
    if (items == null) return;
    final changed = await Navigator.of(context).push<bool>(
      MaterialPageRoute(
        settings: const RouteSettings(name: TelegramCloudViewerPage.routeName),
        builder: (context) => TelegramCloudViewerPage(items: items, initialIndex: index),
      ),
    );
    if (changed == true) await _load();
  }

  @override
  Widget build(BuildContext context) {
    final items = _items;
    Widget body;
    if (_error != null) {
      body = Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_error!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              OutlinedButton(onPressed: _load, child: const Text('Retry')),
            ],
          ),
        ),
      );
    } else if (items == null) {
      body = const Center(child: CircularProgressIndicator());
    } else if (items.isEmpty) {
      body = const Center(child: Text('Nothing uploaded yet'));
    } else {
      body = GridView.builder(
        padding: const EdgeInsets.all(2),
        gridDelegate: const SliverGridDelegateWithMaxCrossAxisExtent(maxCrossAxisExtent: 130, mainAxisSpacing: 2, crossAxisSpacing: 2),
        itemCount: items.length,
        itemBuilder: (context, index) {
          final item = items[index];
          return GestureDetector(
            onTap: () => unawaited(_open(index)),
            child: Stack(
              fit: StackFit.expand,
              children: [
                FutureBuilder<Uint8List?>(
                  future: _thumbnail(item.path),
                  builder: (context, snapshot) {
                    final bytes = snapshot.data;
                    if (bytes != null) return Image.memory(bytes, fit: BoxFit.cover, gaplessPlayback: true);
                    return ColoredBox(
                      color: Theme.of(context).colorScheme.surfaceContainerHighest,
                      child: Icon(snapshot.connectionState == ConnectionState.done ? AIcons.broken : AIcons.image),
                    );
                  },
                ),
                if (item.isVideo) const Positioned(right: 4, bottom: 4, child: Icon(AIcons.videoPlay, color: Colors.white)),
              ],
            ),
          );
        },
      );
    }
    return AvesScaffold(
      appBar: AppBar(
        title: Text(items == null ? 'Telegram cloud' : 'Telegram cloud (${items.length})'),
        actions: [IconButton(icon: const Icon(AIcons.refresh), onPressed: _load, tooltip: 'Refresh')],
      ),
      body: SafeArea(child: body),
    );
  }
}

class TelegramCloudViewerPage extends StatefulWidget {
  static const routeName = '/settings/telegram_cloud/viewer';

  final List<TelegramCloudItem> items;
  final int initialIndex;

  const TelegramCloudViewerPage({super.key, required this.items, required this.initialIndex});

  @override
  State<TelegramCloudViewerPage> createState() => _TelegramCloudViewerPageState();
}

class _TelegramCloudViewerPageState extends State<TelegramCloudViewerPage> {
  late final PageController _controller = PageController(initialPage: widget.initialIndex);
  late int _index = widget.initialIndex;
  final Set<String> _removed = {};

  List<TelegramCloudItem> get _visible => widget.items.where((i) => !_removed.contains(i.path)).toList();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _save(TelegramCloudItem item) async {
    try {
      await telegramCloudService.restore(item.path);
      _toast('Saved to Pictures/Telegram Cloud');
    } on TelegramCloudException catch (e) {
      _toast(e.message);
    }
  }

  Future<void> _delete(TelegramCloudItem item) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Delete from cloud?'),
        content: const Text(
          'The encrypted file is removed from the app and Telegram is asked to delete it. '
          'Telegram only allows this for messages younger than 48 hours; older ones may stay in the channel as unreadable data. '
          'Copies on this device are not touched.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Delete')),
        ],
      ),
    );
    if (confirmed != true) return;
    try {
      final outcome = await telegramCloudService.delete(item.path);
      if (!mounted) return;
      setState(() => _removed.add(item.path));
      _toast(outcome.refused > 0 ? 'Removed from the app. Telegram kept ${outcome.refused} old encrypted message(s).' : 'Deleted from the cloud');
      if (_visible.isEmpty) Navigator.of(context).pop(true);
    } on TelegramCloudException catch (e) {
      _toast(e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final visible = _visible;
    if (visible.isEmpty) return const AvesScaffold(body: SizedBox());
    final current = visible[_index.clamp(0, visible.length - 1)];
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, result) {
        if (!didPop) Navigator.of(context).pop(_removed.isNotEmpty);
      },
      child: AvesScaffold(
        appBar: AppBar(
          title: Text(current.name, overflow: TextOverflow.ellipsis),
          actions: [
            IconButton(icon: const Icon(AIcons.export), tooltip: 'Save to device', onPressed: () => unawaited(_save(current))),
            IconButton(icon: const Icon(AIcons.delete), tooltip: 'Delete from cloud', onPressed: () => unawaited(_delete(current))),
          ],
        ),
        body: PageView.builder(
          controller: _controller,
          itemCount: visible.length,
          onPageChanged: (i) => setState(() => _index = i),
          itemBuilder: (context, i) => _CloudPage(key: ValueKey(visible[i].path), item: visible[i]),
        ),
      ),
    );
  }
}

class _CloudPage extends StatefulWidget {
  final TelegramCloudItem item;

  const _CloudPage({super.key, required this.item});

  @override
  State<_CloudPage> createState() => _CloudPageState();
}

class _CloudPageState extends State<_CloudPage> {
  Uint8List? _preview;
  File? _full;
  String? _error;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    final item = widget.item;
    try {
      // the small preview is instant; the full file is decrypted in the background (images only)
      final preview = await telegramCloudService.thumbnail(item.path, preview: true);
      if (mounted && preview != null) setState(() => _preview = preview);
      if (item.isVideo) return;
      final path = await telegramCloudService.download(item.path);
      if (mounted) setState(() => _full = File(path));
    } on TelegramCloudException catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final full = _full;
    final preview = _preview;
    Widget content;
    if (full != null) {
      content = InteractiveViewer(
        maxScale: 6,
        child: Center(child: Image.file(full, fit: BoxFit.contain, errorBuilder: (context, error, stackTrace) => preview != null ? Image.memory(preview) : const Icon(AIcons.broken))),
      );
    } else if (preview != null) {
      content = Stack(
        fit: StackFit.expand,
        children: [
          InteractiveViewer(child: Center(child: Image.memory(preview, fit: BoxFit.contain))),
          if (!widget.item.isVideo) const Positioned(top: 8, right: 8, child: SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))),
          if (widget.item.isVideo) const Center(child: Icon(AIcons.videoPlay, size: 64, color: Colors.white70)),
        ],
      );
    } else if (_error != null) {
      content = Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(_error!, textAlign: TextAlign.center)));
    } else {
      content = const Center(child: CircularProgressIndicator());
    }
    return Column(
      children: [
        Expanded(child: content),
        if (widget.item.isVideo)
          const Padding(
            padding: EdgeInsets.all(8),
            child: Text('Videos are saved to the device to play: use the save button above.'),
          ),
        if (_error != null && preview != null) Padding(padding: const EdgeInsets.all(8), child: Text(_error!)),
      ],
    );
  }
}
