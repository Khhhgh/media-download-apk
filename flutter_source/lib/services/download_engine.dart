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

  MediaResult({
    required this.title,
    required this.videoUrl,
    this.audioUrl,
    this.thumbnailUrl,
    required this.platform,
    this.author,
    this.qualities = const [],
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

    // 4. Instagram
    if (lower.contains('instagram.com') || lower.contains('instagr.am')) {
      final expanded = await unshortenUrl(cleanUrl);
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
