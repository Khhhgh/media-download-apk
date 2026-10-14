import 'dart:convert';
import 'package:dio/dio.dart';
import 'remote_config_service.dart';

class MediaResult {
  final String title;
  final String videoUrl;
  final String? audioUrl;
  final String? thumbnailUrl;
  final String platform;
  final String? author;
  final List<QualityOption> qualities;
  final List<CarouselItem> carouselItems;

  MediaResult({
    required this.title,
    required this.videoUrl,
    this.audioUrl,
    this.thumbnailUrl,
    required this.platform,
    this.author,
    this.qualities = const [],
    this.carouselItems = const [],
  });
}

class QualityOption {
  final String label;
  final String url;
  final String format;
  final bool isHd;

  QualityOption({
    required this.label,
    required this.url,
    this.format = 'mp4',
    this.isHd = false,
  });
}

/// A single slide inside a multi-item post (e.g. Instagram Carousel).
class CarouselItem {
  final String url;
  final String format;
  final String label;

  CarouselItem({
    required this.url,
    this.format = 'jpg',
    this.label = '',
  });
}

class MasterDownloadEngine {
  final Dio _dio = Dio(
    BaseOptions(
      connectTimeout: const Duration(seconds: 15),
      receiveTimeout: const Duration(seconds: 20),
      followRedirects: true,
      maxRedirects: 5,
      headers: {
        'User-Agent':
            'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
      },
    ),
  );

  bool isYouTubeUrl(String url) {
    final lower = url.toLowerCase().trim();
    return lower.contains('youtube.com') ||
        lower.contains('youtu.be') ||
        lower.contains('yt.be');
  }

  /// Extracts pure URL if surrounded by extra share text
  String extractUrl(String text) {
    final match = RegExp(r'https?://[^\s]+', caseSensitive: false).firstMatch(text.trim());
    return match != null ? match.group(0)! : text.trim();
  }

  /// Unshortens shortened URLs (vt.tiktok.com, vm.tiktok.com, fb.watch, t.co)
  Future<String> unshortenUrl(String rawUrl) async {
    final clean = extractUrl(rawUrl);
    try {
      final response = await _dio.get(clean);
      final finalUrl = response.realUri.toString();
      if (finalUrl.isNotEmpty && finalUrl != clean) {
        return finalUrl;
      }
    } catch (_) {}
    return clean;
  }

  Future<MediaResult> resolve(String rawUrl, Map<String, EngineConfig> engines) async {
    final cleanUrl = extractUrl(rawUrl);
    if (isYouTubeUrl(cleanUrl)) {
      throw Exception('YOUTUBE_BLOCKED');
    }

    final lower = cleanUrl.toLowerCase();

    // 1. TikTok & Douyin (including tt.site, vt.tiktok.com, vm.tiktok.com)
    if (lower.contains('tiktok.com') || lower.contains('douyin.com') || lower.contains('tt.site')) {
      final expanded = await unshortenUrl(cleanUrl);
      final tikWmEndpoint = engines['tiktok']?.primaryApi ?? 'https://www.tikwm.com/api/';
      final res = await _executeTikWmExtractor(expanded, tikWmEndpoint);
      if (res != null) return res;
      // Fallback
      return await _executeCobaltExtractor(
        url: expanded,
        apiUrl: engines['tiktok']?.fallbackApi ?? 'https://co.wuk.sh/api/json',
        platform: 'TikTok',
      ) ?? (throw Exception('Failed to extract TikTok video.'));
    }

    // 2. Twitter / X
    if (lower.contains('twitter.com') || lower.contains('x.com')) {
      final expanded = await unshortenUrl(cleanUrl);
      final fxRes = await _executeFxTwitterExtractor(expanded);
      if (fxRes != null) return fxRes;
      return await _executeCobaltExtractor(
        url: expanded,
        apiUrl: engines['twitter']?.primaryApi ?? 'https://api.cobalt.tools/api/json',
        platform: 'Twitter/X',
      ) ?? (throw Exception('Failed to extract Twitter/X video.'));
    }

    // 3. Facebook
    if (lower.contains('facebook.com') || lower.contains('fb.watch')) {
      final expanded = await unshortenUrl(cleanUrl);
      final fbHtmlRes = await _executeFacebookHtmlExtractor(expanded);
      if (fbHtmlRes != null) return fbHtmlRes;
      return await _executeCobaltExtractor(
        url: expanded,
        apiUrl: engines['facebook']?.primaryApi ?? 'https://api.cobalt.tools/api/json',
        platform: 'Facebook',
      ) ?? (throw Exception('Failed to extract Facebook video.'));
    }

    // 4. Instagram (video-first embed parser -> carousel aware; Cobalt as fallback)
    if (lower.contains('instagram.com') || lower.contains('instagr.am')) {
      final expanded = await unshortenUrl(cleanUrl);
      final embed = await _executeInstagramEmbed(expanded);
      if (embed != null) return embed;
      return await _executeCobaltExtractor(
        url: expanded,
        apiUrl: engines['instagram']?.primaryApi ?? 'https://api.cobalt.tools/api/json',
        platform: 'Instagram',
      ) ?? (throw Exception('Failed to extract Instagram media.'));
    }

    // 5. Dynamic Engine Matching
    for (final entry in engines.entries) {
      final platformKey = entry.key;
      final engine = entry.value;

      try {
        final regex = RegExp(engine.regex, caseSensitive: false);
        if (regex.hasMatch(cleanUrl)) {
          final res = await _executeCobaltExtractor(
            url: cleanUrl,
            apiUrl: engine.primaryApi,
            platform: platformKey,
          );
          if (res != null) return res;

          if (engine.fallbackApi.isNotEmpty) {
            final fallbackRes = await _executeCobaltExtractor(
              url: cleanUrl,
              apiUrl: engine.fallbackApi,
              platform: platformKey,
            );
            if (fallbackRes != null) return fallbackRes;
          }
        }
      } catch (_) {}
    }

    // 6. Generic Fallback
    final generic = await _executeCobaltExtractor(
      url: cleanUrl,
      apiUrl: 'https://api.cobalt.tools/api/json',
      platform: 'Media',
    );
    if (generic != null) return generic;

    throw Exception('Failed to extract media from all remote failover scrapers.');
  }

  Future<MediaResult?> _executeTikWmExtractor(String url, String endpoint) async {
    try {
      final cleanEndpoint = endpoint.endsWith('/') ? endpoint : '$endpoint/';
      final response = await _dio.get(
        cleanEndpoint,
        queryParameters: {'url': url, 'hd': 1},
        options: Options(headers: {'Referer': 'https://www.tikwm.com/'}),
      );

      if (response.statusCode == 200 && response.data != null) {
        final data = response.data['data'];
        if (data != null) {
          final title = data['title'] ?? 'TikTok Video';
          String cover = data['cover'] ?? '';
          String hdPlay = data['hdplay'] ?? '';
          String play = data['play'] ?? '';
          String music = data['music'] ?? '';
          final author = data['author']?['nickname'] ?? 'Creator';

          if (hdPlay.startsWith('/')) hdPlay = 'https://www.tikwm.com$hdPlay';
          if (play.startsWith('/')) play = 'https://www.tikwm.com$play';
          if (music.startsWith('/')) music = 'https://www.tikwm.com$music';
          if (cover.startsWith('/')) cover = 'https://www.tikwm.com$cover';

          final primaryVideo = hdPlay.isNotEmpty ? hdPlay : play;
          final List<QualityOption> list = [];
          if (hdPlay.isNotEmpty) list.add(QualityOption(label: '1080p HD (No Watermark)', url: hdPlay, isHd: true));
          if (play.isNotEmpty) list.add(QualityOption(label: '720p Clean (Fast)', url: play));
          if (music.isNotEmpty) list.add(QualityOption(label: 'Original Audio (MP3)', url: music, format: 'mp3'));

          if (primaryVideo.isNotEmpty) {
            return MediaResult(
              title: title,
              videoUrl: primaryVideo,
              audioUrl: music.isNotEmpty ? music : null,
              thumbnailUrl: cover.isNotEmpty ? cover : null,
              platform: 'TikTok',
              author: author,
              qualities: list,
            );
          }
        }
      }
    } catch (_) {}
    return null;
  }

  Future<MediaResult?> _executeFxTwitterExtractor(String url) async {
    try {
      final uri = Uri.parse(url);
      final apiUrl = 'https://api.fxtwitter.com${uri.path}';
      final response = await _dio.get(apiUrl);

      if (response.statusCode == 200 && response.data != null) {
        final tweet = response.data['tweet'];
        if (tweet != null && tweet['media'] != null) {
          final videos = tweet['media']['videos'];
          if (videos is List && videos.isNotEmpty) {
            final videoUrl = videos[0]['url'];
            final thumbUrl = videos[0]['thumbnail_url'];
            final text = tweet['text'] ?? 'Twitter Video';
            final author = tweet['author']?['name'] ?? 'X User';

            return MediaResult(
              title: text,
              videoUrl: videoUrl,
              thumbnailUrl: thumbUrl,
              platform: 'Twitter/X',
              author: author,
              qualities: [QualityOption(label: 'Original Quality (HD)', url: videoUrl, isHd: true)],
            );
          }
        }
      }
    } catch (_) {}
    return null;
  }

  Future<MediaResult?> _executeFacebookHtmlExtractor(String url) async {
    try {
      final response = await _dio.get(url);
      if (response.statusCode == 200 && response.data is String) {
        final html = response.data as String;
        final hdMatch = RegExp(r'"(?:playable_url_quality_hd|browser_native_hd_url)":"([^"]+)"').firstMatch(html);
        final sdMatch = RegExp(r'"(?:playable_url|browser_native_sd_url)":"([^"]+)"').firstMatch(html);

        String? hdUrl = hdMatch != null ? _cleanFbUrl(hdMatch.group(1)!) : null;
        String? sdUrl = sdMatch != null ? _cleanFbUrl(sdMatch.group(1)!) : null;

        final chosen = hdUrl ?? sdUrl;
        if (chosen != null && chosen.isNotEmpty) {
          final List<QualityOption> list = [];
          if (hdUrl != null) list.add(QualityOption(label: '1080p HD', url: hdUrl, isHd: true));
          if (sdUrl != null) list.add(QualityOption(label: 'Standard Quality', url: sdUrl));

          return MediaResult(
            title: 'Facebook Video',
            videoUrl: chosen,
            platform: 'Facebook',
            qualities: list,
          );
        }
      }
    } catch (_) {}
    return null;
  }

  String _cleanFbUrl(String raw) {
    return raw
        .replaceAll(r'\/', '/')
        .replaceAll(r'\u002526', '&')
        .replaceAll(r'\u0026', '&')
        .replaceAll('&amp;', '&');
  }

  static const String _mobileUserAgent =
      'Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1';

  String _cleanEscaped(String raw) => raw
      .replaceAll(r'\/', '/')
      .replaceAll(r'\u0026', '&')
      .replaceAll('&amp;', '&')
      .trim();

  Future<MediaResult?> _executeInstagramEmbed(String url) async {
    final shortcodeMatch = RegExp(r'/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)').firstMatch(url);
    if (shortcodeMatch == null) return null;
    final shortcode = shortcodeMatch.group(1)!;
    final isReel = url.contains('/reel/') || url.contains('/reels/') || url.contains('/tv/');

    final candidates = [
      'https://www.instagram.com/p/$shortcode/embed/captioned/',
      'https://www.instagram.com/p/$shortcode/embed/',
    ];

    for (final embedUrl in candidates) {
      try {
        final response = await _dio.get(
          embedUrl,
          options: Options(headers: {
            'User-Agent': _mobileUserAgent,
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'en-US,en;q=0.9',
            'Sec-Fetch-Mode': 'navigate',
          }),
        );
        if (response.statusCode != 200 || response.data is! String) continue;
        final result = _parseInstagramHtml(response.data as String, url, isReel);
        if (result != null) return result;
      } catch (_) {}
    }
    return null;
  }

  MediaResult? _parseInstagramHtml(String html, String pageUrl, bool isReel) {
    String? caption;
    final capMatch = RegExp(r'<div class="Caption"[^>]*>(.*?)</div>', dotAll: true).firstMatch(html);
    if (capMatch != null) {
      caption = capMatch
          .group(1)!
          .replaceAll(RegExp(r'<[^>]+>'), ' ')
          .replaceAll('&amp;', '&')
          .trim();
    }
    if (caption == null || caption.isEmpty) {
      caption = RegExp(r'<meta\s+(?:property|name)="og:title"\s+content="([^"]+)"')
          .firstMatch(html)
          ?.group(1)
          ?.replaceAll('&amp;', '&')
          .trim();
    }
    final title = (caption == null || caption.isEmpty)
        ? 'Instagram Media'
        : caption.substring(0, caption.length > 60 ? 60 : caption.length);

    final videoUrls = <String>[];
    for (final m in RegExp(r'"video_url"\s*:\s*"(https:.+?)"').allMatches(html)) {
      final v = _cleanEscaped(m.group(1)!);
      if (v.isNotEmpty && !videoUrls.contains(v)) videoUrls.add(v);
    }
    if (videoUrls.isEmpty) {
      for (final m in RegExp(r'<video[^>]*src="([^"]+)"').allMatches(html)) {
        final v = _cleanEscaped(m.group(1)!);
        if (v.isNotEmpty && !videoUrls.contains(v)) videoUrls.add(v);
      }
    }

    final imageUrls = <String>[];
    for (final m in RegExp(r'"display_url"\s*:\s*"(https:.+?)"').allMatches(html)) {
      final p = _cleanEscaped(m.group(1)!);
      if (p.isNotEmpty && !imageUrls.contains(p)) imageUrls.add(p);
    }
    if (imageUrls.isEmpty) {
      for (final m in RegExp(r'<img[^>]*class="[^"]*EmbeddedMediaImage[^"]*"[^>]*src="([^"]+)"').allMatches(html)) {
        final p = _cleanEscaped(m.group(1)!);
        if (p.isNotEmpty && !imageUrls.contains(p)) imageUrls.add(p);
      }
    }

    // Video found -> strictly return MP4, never the JPG cover.
    if (videoUrls.isNotEmpty) {
      final items = <CarouselItem>[];
      for (var i = 0; i < videoUrls.length; i++) {
        items.add(CarouselItem(url: videoUrls[i], format: 'mp4', label: 'Video ${i + 1}'));
      }
      for (var i = 0; i < imageUrls.length; i++) {
        items.add(CarouselItem(url: imageUrls[i], format: 'jpg', label: 'Photo ${i + 1}'));
      }
      return MediaResult(
        title: title,
        videoUrl: videoUrls.first,
        thumbnailUrl: imageUrls.isNotEmpty ? imageUrls.first : null,
        platform: 'Instagram',
        qualities: [
          QualityOption(label: 'Highest Quality Video (MP4)', url: videoUrls.first, format: 'mp4', isHd: true)
        ],
        carouselItems: items,
      );
    }

    // A Reel/TV link is always a video: never save its poster as JPG.
    if (isReel) return null;

    if (imageUrls.isNotEmpty) {
      final items = <CarouselItem>[];
      for (var i = 0; i < imageUrls.length; i++) {
        items.add(CarouselItem(url: imageUrls[i], format: 'jpg', label: 'Photo ${i + 1}'));
      }
      return MediaResult(
        title: title,
        videoUrl: imageUrls.first,
        thumbnailUrl: imageUrls.first,
        platform: 'Instagram',
        qualities: [
          QualityOption(label: 'Original Image (JPG)', url: imageUrls.first, format: 'jpg', isHd: true)
        ],
        carouselItems: items,
      );
    }
    return null;
  }

  Future<MediaResult?> _executeCobaltExtractor({
    required String url,
    required String apiUrl,
    required String platform,
  }) async {
    try {
      final response = await _dio.post(
        apiUrl,
        data: {
          'url': url,
          'videoQuality': 'max',
          'filenamePattern': 'basic',
          'audioFormat': 'mp3'
        },
        options: Options(headers: {'Accept': 'application/json'}),
      );

      if (response.statusCode == 200 && response.data != null) {
        final res = response.data;
        final status = res['status'];
        if (status == 'tunnel' || status == 'redirect' || status == 'success') {
          final videoUrl = res['url'] ?? '';
          final audioUrl = res['audio'];
          final filename = res['filename'] ?? '$platform Video';

          if (videoUrl.isNotEmpty) {
            return MediaResult(
              title: filename,
              videoUrl: videoUrl,
              audioUrl: audioUrl,
              platform: platform.toUpperCase(),
              qualities: [
                QualityOption(label: 'High Quality (HD)', url: videoUrl, isHd: true)
              ],
            );
          }
        }
      }
    } catch (_) {}
    return null;
  }
}
