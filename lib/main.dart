import 'dart:async';

import 'package:flutter/material.dart';

import 'native.dart';
import 'strings.dart';

void main() => runApp(const PhotoSizerApp());

class PhotoSizerApp extends StatelessWidget {
  const PhotoSizerApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: S.appTitle,
        debugShowCheckedModeBanner: false,
        theme: ThemeData(colorSchemeSeed: Colors.teal, useMaterial3: true),
        darkTheme: ThemeData(
            colorSchemeSeed: Colors.teal, brightness: Brightness.dark, useMaterial3: true),
        home: const HomePage(),
      );
}

String formatBytes(num b) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  var v = b.toDouble();
  var i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return '${v.toStringAsFixed(i == 0 ? 0 : 1)} ${units[i]}';
}

enum Phase { setup, running, done }

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  StreamSubscription<Map<String, dynamic>>? _sub;

  Phase _phase = Phase.setup;
  FolderInfo? _src;
  FolderInfo? _dst;
  ScanInfo? _scan;
  bool _scanning = false;
  bool _overwrite = true; // default, with warning + second confirmation
  bool _copyOthers = false;
  double _quality = 80;
  static const _sizes = [640, 800, 1024, 1280, 1600, 1920, 2560];
  int _maxSide = 1280;
  RunInfo? _run;
  bool _cancelling = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _sub = Native.events().listen(_onEvent);
    _restore();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  void _save() => Native.saveSettings(Settings(
        maxSide: _maxSide,
        quality: _quality.round(),
        overwrite: _overwrite,
        copyOthers: _copyOthers,
        dst: _dst,
      ));

  Future<void> _restore() async {
    final s = await Native.loadSettings();
    if (mounted) {
      setState(() {
        _maxSide = _sizes.contains(s.maxSide) ? s.maxSide : 1280;
        _quality = s.quality.clamp(60, 95).toDouble();
        _overwrite = s.overwrite;
        _copyOthers = s.copyOthers;
        _dst = s.dst;
      });
    }
    if (await Native.isBusy()) {
      setState(() => _phase = Phase.running); // events replay the latest state
      return;
    }
    final last = await Native.lastFolder();
    if (last != null && mounted) {
      setState(() => _src = last);
      _startScan();
    }
  }

  void _onEvent(Map<String, dynamic> m) {
    if (!mounted) return;
    switch (m['type']) {
      case 'scan':
        final info = ScanInfo(m);
        setState(() {
          _scan = info;
          _scanning = !info.done;
        });
      case 'progress':
        setState(() {
          _run = RunInfo(m);
          if (_phase != Phase.running) _phase = Phase.running;
        });
      case 'done':
        setState(() {
          _run = RunInfo(m);
          _phase = Phase.done;
          _cancelling = false;
        });
      case 'error':
        setState(() {
          _error = m['message'] as String?;
          _scanning = false;
        });
    }
  }

  void _startScan() {
    if (_src == null) return;
    setState(() {
      _scan = null;
      _scanning = true;
      _error = null;
    });
    Native.scan(_src!.uri);
  }

  Future<void> _pickSource() async {
    final f = await Native.pickFolder();
    if (f == null || !mounted) return;
    setState(() => _src = f);
    _startScan();
  }

  Future<void> _pickDest() async {
    final f = await Native.pickFolder(remember: false);
    if (f == null || !mounted) return;
    setState(() => _dst = f);
    _save();
  }

  bool get _destOk => _overwrite || (_dst != null && _dst!.uri != _src?.uri);

  bool get _canStart =>
      _src != null && _scan != null && !_scanning && _scan!.images > 0 && _destOk;

  Future<void> _start({required bool test}) async {
    final images = _scan!.images;
    if (_overwrite) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          icon: const Icon(Icons.warning_amber_rounded, color: Colors.red, size: 36),
          title: const Text(S.confirmOverwriteTitle),
          content: Text(S.confirmOverwriteBody(_src!.label, images, test, _maxSide)),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text(S.confirmCancel)),
            FilledButton(
              style: FilledButton.styleFrom(backgroundColor: Colors.red),
              onPressed: () => Navigator.pop(c, true),
              child: const Text(S.confirmGo),
            ),
          ],
        ),
      );
      if (ok != true) return;
    }
    final copyOthers = !_overwrite && _copyOthers;
    final total = test ? 10 : images + (copyOthers ? _scan!.others : 0);
    setState(() {
      _run = null;
      _cancelling = false;
      _phase = Phase.running;
    });
    await Native.run(
      src: _src!.uri,
      dst: _overwrite ? null : _dst!.uri,
      copyOthers: copyOthers,
      quality: _quality.round(),
      maxSide: _maxSide,
      limit: test ? 10 : 0,
      total: total,
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text(S.appTitle)),
        body: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: switch (_phase) {
              Phase.setup => _setupView(context),
              Phase.running => _runningView(context),
              Phase.done => _doneView(context),
            },
          ),
        ),
      );

  // ------------------------------------------------------------------ setup

  List<Widget> _setupView(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return [
      _card(S.sourceTitle, [
        Text(_src?.label ?? S.noFolder),
        const SizedBox(height: 8),
        if (_scanning) ...[
          const LinearProgressIndicator(),
          const SizedBox(height: 4),
          Text(_scan == null
              ? S.scanning
              : S.scanResult(_scan!.images, formatBytes(_scan!.imageBytes), _scan!.others,
                  formatBytes(_scan!.otherBytes))),
        ] else if (_scan != null)
          Text(S.scanResult(_scan!.images, formatBytes(_scan!.imageBytes), _scan!.others,
              formatBytes(_scan!.otherBytes))),
        if (_error != null) Text('${S.errorPrefix}$_error', style: TextStyle(color: scheme.error)),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: _pickSource,
          icon: const Icon(Icons.folder_open),
          label: Text(_src == null ? S.pickFolder : S.changeFolder),
        ),
      ]),
      _card(S.modeTitle, [
        RadioGroup<bool>(
          groupValue: _overwrite,
          onChanged: (v) {
            setState(() => _overwrite = v!);
            _save();
          },
          child: const Column(children: [
            RadioListTile<bool>(
              contentPadding: EdgeInsets.zero,
              value: true,
              title: Text(S.modeOverwrite),
            ),
            RadioListTile<bool>(
              contentPadding: EdgeInsets.zero,
              value: false,
              title: Text(S.modeNewFolder),
            ),
          ]),
        ),
        if (_overwrite)
          Container(
            margin: const EdgeInsets.only(top: 8),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: scheme.errorContainer,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Icon(Icons.warning_amber_rounded, color: scheme.error),
              const SizedBox(width: 8),
              Expanded(
                child: Text.rich(TextSpan(children: [
                  TextSpan(
                      text: '${S.overwriteWarningTitle} ',
                      style: TextStyle(fontWeight: FontWeight.bold, color: scheme.onErrorContainer)),
                  TextSpan(
                      text: S.overwriteWarning, style: TextStyle(color: scheme.onErrorContainer)),
                ])),
              ),
            ]),
          )
        else ...[
          const SizedBox(height: 8),
          Text(_dst?.label ?? S.destTitle, style: Theme.of(context).textTheme.bodyLarge),
          if (_dst != null && _dst!.uri == _src?.uri)
            Text(S.destSameAsSource, style: TextStyle(color: scheme.error)),
          const SizedBox(height: 8),
          OutlinedButton.icon(
              onPressed: _pickDest, icon: const Icon(Icons.create_new_folder), label: const Text(S.pickDest)),
          CheckboxListTile(
            contentPadding: EdgeInsets.zero,
            value: _copyOthers,
            onChanged: (v) {
              setState(() => _copyOthers = v ?? false);
              _save();
            },
            title: const Text(S.copyOthers),
            subtitle: const Text(S.copyOthersHint),
          ),
        ],
      ]),
      _card(S.resolutionTitle, [
        Wrap(spacing: 8, runSpacing: 4, children: [
          for (final px in _sizes)
            ChoiceChip(
              label: Text('$px px'),
              selected: _maxSide == px,
              onSelected: (_) {
                setState(() => _maxSide = px);
                _save();
              },
            ),
        ]),
        const SizedBox(height: 8),
        Text(S.sizeInfo(_maxSide)),
        const SizedBox(height: 8),
        Text(S.quality(_quality.round())),
        Slider(
          min: 60,
          max: 95,
          divisions: 35,
          value: _quality,
          label: _quality.round().toString(),
          onChanged: (v) => setState(() => _quality = v),
          onChangeEnd: (_) => _save(),
        ),
      ]),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(
          child: OutlinedButton(
            onPressed: _canStart ? () => _start(test: true) : null,
            child: const Text(S.testRun),
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: FilledButton(
            style: _overwrite ? FilledButton.styleFrom(backgroundColor: scheme.error) : null,
            onPressed: _canStart ? () => _start(test: false) : null,
            child: const Text(S.start),
          ),
        ),
      ]),
    ];
  }

  // ---------------------------------------------------------------- running

  List<Widget> _runningView(BuildContext context) {
    final r = _run;
    final frac = (r == null || r.total == 0) ? null : (r.done / r.total).clamp(0.0, 1.0);
    return [
      _card(S.running, [
        LinearProgressIndicator(value: frac),
        const SizedBox(height: 8),
        if (r != null) ...[
          Text(S.progress(r.done, r.total)),
          Text(S.resizedCount(r.resized)),
          Text(S.savedSpace(formatBytes(r.bytesBefore - r.bytesAfter))),
          if (r.failed > 0) Text(S.rFailed(r.failed)),
          const SizedBox(height: 8),
          Text(r.current, maxLines: 2, overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.bodySmall),
        ],
        const SizedBox(height: 12),
        const Text(S.keepAppOpen),
      ]),
      OutlinedButton(
        onPressed: _cancelling
            ? null
            : () {
                setState(() => _cancelling = true);
                Native.cancel();
              },
        child: Text(_cancelling ? S.cancelling : S.cancel),
      ),
    ];
  }

  // ------------------------------------------------------------------- done

  List<Widget> _doneView(BuildContext context) {
    final r = _run!;
    final lines = <String>[
      S.rResized(r.resized),
      if (r.skippedSmall > 0) S.rSkippedSmall(r.skippedSmall),
      if (r.skippedNotSmaller > 0) S.rSkippedNotSmaller(r.skippedNotSmaller),
      if (r.skippedExisting > 0) S.rSkippedExisting(r.skippedExisting),
      if (r.others > 0) S.rOthers(r.others),
      S.rFailed(r.failed),
      S.rSaved(formatBytes(r.bytesBefore), formatBytes(r.bytesAfter),
          formatBytes(r.bytesBefore - r.bytesAfter)),
    ];
    return [
      _card(r.cancelled ? S.cancelledTitle : S.doneTitle, [
        for (final l in lines) Text(l),
        if (r.fatal != null)
          Text('${S.errorPrefix}${r.fatal}',
              style: TextStyle(color: Theme.of(context).colorScheme.error)),
      ]),
      if (r.failures.isNotEmpty)
        Card(
          child: ExpansionTile(
            title: const Text(S.failuresTitle),
            children: [
              for (final f in r.failures)
                ListTile(dense: true, title: Text(f, style: const TextStyle(fontSize: 12))),
            ],
          ),
        ),
      const SizedBox(height: 8),
      FilledButton(
        onPressed: () {
          setState(() {
            _phase = Phase.setup;
            _run = null;
          });
          _startScan();
        },
        child: const Text(S.newTask),
      ),
    ];
  }

  Widget _card(String title, List<Widget> children) => Card(
        margin: const EdgeInsets.only(bottom: 12),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              ...children,
            ],
          ),
        ),
      );
}
