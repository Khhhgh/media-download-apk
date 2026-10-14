import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'services/ad_manager.dart';
import 'services/download_engine.dart';
import 'services/remote_config_service.dart';
import 'services/storage_service.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final configService = RemoteConfigService();
  await configService.init();

  runApp(MediaDownloadApp(configService: configService));
}

class MediaDownloadApp extends StatefulWidget {
  final RemoteConfigService configService;
  const MediaDownloadApp({super.key, required this.configService});

  @override
  State<MediaDownloadApp> createState() => _MediaDownloadAppState();
}

class _MediaDownloadAppState extends State<MediaDownloadApp> {
  bool _isArabic = true;

  void _toggleLanguage() {
    setState(() {
      _isArabic = !_isArabic;
    });
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'media download - تحميل الميديا',
      debugShowCheckedModeBanner: false,
      locale: _isArabic ? const Locale('ar') : const Locale('en'),
      supportedLocales: const [Locale('ar'), Locale('en')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0B0E14),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF00F2FE),
          secondary: Color(0xFF2563EB),
          surface: Color(0xFF131926),
        ),
      ),
      home: HomeScreen(
        configService: widget.configService,
        isArabic: _isArabic,
        onToggleLanguage: _toggleLanguage,
      ),
    );
  }
}

class HomeScreen extends StatefulWidget {
  final RemoteConfigService configService;
  final bool isArabic;
  final VoidCallback onToggleLanguage;

  const HomeScreen({
    super.key,
    required this.configService,
    required this.isArabic,
    required this.onToggleLanguage,
  });

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final TextEditingController _urlController = TextEditingController();
  final MasterDownloadEngine _engine = MasterDownloadEngine();
  final StorageService _storageService = StorageService();
  final AdManager _adManager = AdManager();

  int _currentIndex = 0;
  bool _isLoading = false;
  MediaResult? _extractedMedia;
  double _downloadPercent = 0.0;
  String _downloadSpeed = '';
  bool _isDownloading = false;
  int _adCounter = 0;

  final List<Map<String, dynamic>> _history = [];

  @override
  void initState() {
    super.initState();
    _loadAdCounter();
  }

  Future<void> _loadAdCounter() async {
    final count = await _adManager.getDownloadCount();
    setState(() {
      _adCounter = count;
    });
  }

  Future<void> _pasteFromClipboard() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (data?.text != null && data!.text!.isNotEmpty) {
      _urlController.text = data.text!.trim();
      _handleExtract();
    }
  }

  Future<void> _handleExtract() async {
    final url = _urlController.text.trim();
    if (url.isEmpty) return;

    if (_engine.isYouTubeUrl(url)) {
      _showYouTubeDialog();
      return;
    }

    setState(() {
      _isLoading = true;
      _extractedMedia = null;
    });

    try {
      final res = await _engine.resolve(url, widget.configService.engines);
      setState(() {
        _extractedMedia = res;
      });
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.isArabic ? 'فشل استخراج الرابط، يرجى التحقق من الرابط.' : 'Failed to extract media link.',
          ),
          backgroundColor: Colors.redAccent,
        ),
      );
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  void _showYouTubeDialog() {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFF131926),
        title: Text(
          widget.isArabic ? 'تحميل يوتيوب غير مدعوم' : 'YouTube Blocked',
          style: const TextStyle(color: Colors.redAccent, fontWeight: FontWeight.bold),
        ),
        content: Text(
          widget.isArabic
              ? 'تطبيق "media download - تحميل الميديا" متوافق مع سياسات الخدمة. يرجى استخدام 15+ منصة مدعومة مثل تيك توك وإنستغرام وفيسبوك.'
              : 'YouTube downloading is strictly prohibited by platform policy. Please use supported platforms like TikTok, Instagram, Twitter, etc.',
          style: const TextStyle(color: Color(0xFF94A3B8)),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(widget.isArabic ? 'حسناً' : 'OK', style: const TextStyle(color: Color(0xFF00F2FE))),
          )
        ],
      ),
    );
  }

  Future<void> _startDownload(String downloadUrl, String format) async {
    if (_extractedMedia == null) return;

    // Adsterra frequency check (every 4th download triggers ad concurrently)
    final adTriggered = await _adManager.handleDownloadTrigger(widget.configService.adSettings);
    await _loadAdCounter();

    if (adTriggered) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.isArabic ? 'فتح الرابط الإعلاني وبدء التحميل في الخلفية...' : 'Opening sponsor link & downloading...',
          ),
        ),
      );
    }

    setState(() {
      _isDownloading = true;
      _downloadPercent = 0.0;
    });

    try {
      final savedPath = await _storageService.downloadAndSaveToGallery(
        downloadUrl: downloadUrl,
        title: _extractedMedia!.title,
        format: format,
        onProgress: (received, total, percent, speed) {
          setState(() {
            _downloadPercent = percent;
            _downloadSpeed = speed;
          });
        },
      );

      setState(() {
        _history.insert(0, {
          'title': _extractedMedia!.title,
          'platform': _extractedMedia!.platform,
          'path': savedPath,
          'time': DateTime.now().toString(),
        });
      });

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.isArabic ? 'تم الحفظ في المعرض بنجاح! / Saved to Gallery' : 'Saved to Gallery successfully!',
          ),
          backgroundColor: const Color(0xFF10B981),
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(widget.isArabic ? 'فشل التحميل' : 'Download failed: $e'),
          backgroundColor: Colors.redAccent,
        ),
      );
    } finally {
      setState(() {
        _isDownloading = false;
      });
    }
  }

  Future<void> _startCarouselDownload() async {
    final media = _extractedMedia;
    if (media == null || media.carouselItems.isEmpty) return;

    await _adManager.handleDownloadTrigger(widget.configService.adSettings);
    await _loadAdCounter();

    setState(() {
      _isDownloading = true;
      _downloadPercent = 0.0;
    });

    try {
      var index = 0;
      for (final item in media.carouselItems) {
        index++;
        final slot = index;
        final savedPath = await _storageService.downloadAndSaveToGallery(
          downloadUrl: item.url,
          title: '${media.title}_slide_$slot',
          format: item.format,
          onProgress: (received, total, percent, speed) {
            final overall = ((slot - 1) + percent / 100) / media.carouselItems.length * 100;
            setState(() {
              _downloadPercent = overall;
              _downloadSpeed = speed;
            });
          },
        );
        _history.insert(0, {
          'title': '${media.title} #$slot',
          'platform': media.platform,
          'path': savedPath,
          'time': DateTime.now().toString(),
        });
      }

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            widget.isArabic
                ? 'تم حفظ جميع العناصر (${media.carouselItems.length}) في المعرض!'
                : 'Saved all ${media.carouselItems.length} slides to Gallery!',
          ),
          backgroundColor: const Color(0xFF10B981),
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(widget.isArabic ? 'فشل تحميل العناصر' : 'Carousel download failed: $e'),
          backgroundColor: Colors.redAccent,
        ),
      );
    } finally {
      setState(() {
        _isDownloading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: const Color(0xFF131926),
        elevation: 0,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'media download - تحميل الميديا',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.white),
            ),
            Text(
              widget.isArabic ? 'Turbo Slot: $_adCounter / 4' : 'Turbo Slot: $_adCounter / 4',
              style: const TextStyle(fontSize: 10, color: Color(0xFF00F2FE)),
            ),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.language, color: Color(0xFF00F2FE)),
            onPressed: widget.onToggleLanguage,
          ),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        backgroundColor: const Color(0xFF131926),
        selectedIndex: _currentIndex,
        onDestinationSelected: (idx) => setState(() => _currentIndex = idx),
        destinations: [
          NavigationDestination(
            icon: const Icon(Icons.download),
            label: widget.isArabic ? 'التحميل' : 'Download',
          ),
          NavigationDestination(
            icon: const Icon(Icons.video_library),
            label: widget.isArabic ? 'السجل' : 'Library',
          ),
        ],
      ),
      body: _currentIndex == 0 ? _buildDownloaderTab() : _buildHistoryTab(),
    );
  }

  Widget _buildDownloaderTab() {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          Container(
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: const Color(0xFF182030),
              borderRadius: BorderRadius.circular(16),
              border: Border.all(color: const Color(0xFF243048)),
            ),
            child: Column(
              children: [
                TextField(
                  controller: _urlController,
                  decoration: InputDecoration(
                    hintText: widget.isArabic ? 'الصق الرابط هنا...' : 'Paste social media link...',
                    hintStyle: const TextStyle(color: Color(0xFF64748B), fontSize: 13),
                    prefixIcon: const Icon(Icons.link, color: Color(0xFF00F2FE)),
                    suffixIcon: IconButton(
                      icon: const Icon(Icons.paste, color: Color(0xFF00F2FE)),
                      onPressed: _pasteFromClipboard,
                    ),
                    filled: true,
                    fillColor: const Color(0xFF0B0E14),
                    border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide.none),
                  ),
                ),
                const SizedBox(height: 12),
                SizedBox(
                  width: double.infinity,
                  height: 48,
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: const Color(0xFF2563EB),
                      shape: RoundedCornerShape(12),
                    ),
                    onPressed: _isLoading ? null : _handleExtract,
                    child: _isLoading
                        ? const CircularProgressIndicator(color: Colors.white)
                        : Text(
                            widget.isArabic ? 'استخراج وتحميل الميديا' : 'Extract & Download',
                            style: const TextStyle(fontWeight: FontWeight.bold, color: Colors.white),
                          ),
                  ),
                ),
              ],
            ),
          ),
          if (_isDownloading) ...[
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF131926),
                borderRadius: BorderRadius.circular(14),
                border: Border.all(color: const Color(0xFF00F2FE)),
              ),
              child: Column(
                children: [
                  Text('Downloading: ${_downloadPercent.toStringAsFixed(0)}% ($_downloadSpeed)'),
                  const SizedBox(height: 8),
                  LinearProgressIndicator(value: _downloadPercent / 100),
                ],
              ),
            ),
          ],
          if (_extractedMedia != null) ...[
            const SizedBox(height: 16),
            Container(
              padding: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: const Color(0xFF182030),
                borderRadius: BorderRadius.circular(16),
                border: Border.all(color: const Color(0xFF00F2FE)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(_extractedMedia!.title, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                  if (_extractedMedia!.carouselItems.length > 1) ...[
                    const SizedBox(height: 10),
                    ElevatedButton.icon(
                      style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF10B981)),
                      onPressed: _isDownloading ? null : _startCarouselDownload,
                      icon: const Icon(Icons.collections),
                      label: Text(
                        widget.isArabic
                            ? 'تحميل كل العناصر (${_extractedMedia!.carouselItems.length})'
                            : 'Download All Slides (${_extractedMedia!.carouselItems.length})',
                      ),
                    ),
                  ] else if (_extractedMedia!.qualities.isNotEmpty) ...[
                    ..._extractedMedia!.qualities.map(
                      (q) => Padding(
                        padding: const EdgeInsets.only(top: 8),
                        child: ElevatedButton.icon(
                          style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF2563EB)),
                          onPressed: _isDownloading ? null : () => _startDownload(q.url, q.format),
                          icon: const Icon(Icons.download),
                          label: Text(q.label),
                        ),
                      ),
                    ),
                  ] else ...[
                    const SizedBox(height: 10),
                    ElevatedButton.icon(
                      style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF10B981)),
                      onPressed: _isDownloading ? null : () => _startDownload(_extractedMedia!.videoUrl, 'mp4'),
                      icon: const Icon(Icons.download),
                      label: Text(widget.isArabic ? 'حفظ الفيديو في المعرض (HD)' : 'Save Video to Gallery (HD)'),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildHistoryTab() {
    if (_history.isEmpty) {
      return Center(
        child: Text(widget.isArabic ? 'لا توجد تنزيلات سابقة' : 'No downloads yet'),
      );
    }
    return ListView.builder(
      itemCount: _history.length,
      itemBuilder: (ctx, idx) {
        final item = _history[idx];
        return ListTile(
          leading: const Icon(Icons.video_file, color: Color(0xFF00F2FE)),
          title: Text(item['title'] ?? ''),
          subtitle: Text(item['platform'] ?? ''),
        );
      },
    );
  }
}
