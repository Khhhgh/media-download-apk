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
      connectTimeout: const Duration(seconds: 12),
      receiveTimeout: const Duration(seconds: 15),
    ),
  );

  bool isYouTubeUrl(String url) {
    final lower = url.toLowerCase().trim();
    return lower.contains('youtube.com') ||
        lower.contains('youtu.be') ||
        lower.contains('yt.be');
  }

  Future<MediaResult> resolve(String url, Map<String, EngineConfig> engines) async {
    final cleanUrl = url.trim();
    if (isYouTubeUrl(cleanUrl)) {
      throw Exception('YOUTUBE_BLOCKED');
    }

    // 1. Dynamically iterate over platform keys in engines map
    for (final entry in engines.entries) {
      final platformKey = entry.key;
      final engine = entry.value;

      try {
        final regex = RegExp(engine.regex, caseSensitive: false);
        if (regex.hasMatch(cleanUrl)) {
          // Route extraction to primary_api, auto-switch to fallback_api on != 200 or timeout
          return await _executeExtractionWithFailover(
            url: cleanUrl,
            platform: platformKey,
            primaryApi: engine.primaryApi,
            fallbackApi: engine.fallbackApi,
          );
        }
      } catch (e) {
        // Continue to other engines if regex fails
      }
    }

    // 2. Generic fallback if URL doesn't match specific dynamic regex
    return await _executeCobaltExtractor(
      url: cleanUrl,
      apiUrl: 'https://api.cobalt.tools/api/json',
      platform: 'Media',
    );
  }

  Future<MediaResult> _executeExtractionWithFailover({
    required String url,
    required String platform,
    required String primaryApi,
    required String fallbackApi,
  }) async {
    // Attempt Primary API
    try {
      final result = await _callApi(url: url, platform: platform, apiUrl: primaryApi);
      if (result != null) return result;
    } catch (_) {
      // Primary API encountered error/timeout, failover to secondary
    }

    // Attempt Fallback API
    if (fallbackApi.isNotEmpty) {
      try {
        final result = await _callApi(url: url, platform: platform, apiUrl: fallbackApi);
        if (result != null) return result;
      } catch (_) {}
    }

    throw Exception('Failed to extract media via both primary and fallback APIs.');
  }

  Future<MediaResult?> _callApi({
    required String url,
    required String platform,
    required String apiUrl,
  }) async {
    if (apiUrl.contains('tikwm.com')) {
      return await _executeTikWmExtractor(url, apiUrl);
    } else {
      return await _executeCobaltExtractor(url: url, apiUrl: apiUrl, platform: platform);
    }
  }

  Future<MediaResult?> _executeTikWmExtractor(String url, String endpoint) async {
    final response = await _dio.get(
      endpoint,
      queryParameters: {'url': url, 'count': 12, 'cursor': 0, 'web': 1, 'hd': 1},
    );

    if (response.statusCode == 200 && response.data != null) {
      final data = response.data['data'];
      if (data != null) {
        final title = data['title'] ?? 'TikTok Video';
        final cover = data['cover'];
        final hdPlay = data['hdplay'];
        final play = data['play'];
        final music = data['music'];
        final author = data['author']?['nickname'] ?? 'Creator';

        final primaryVideo = (hdPlay != null && hdPlay.isNotEmpty) ? hdPlay : play;
        final List<QualityOption> list = [];
        if (hdPlay != null) list.add(QualityOption(label: '1080p HD (No Watermark)', url: hdPlay, isHd: true));
        if (play != null) list.add(QualityOption(label: '720p Clean (Fast)', url: play));
        if (music != null) list.add(QualityOption(label: 'Original Audio (MP3)', url: music, format: 'mp3'));

        return MediaResult(
          title: title,
          videoUrl: primaryVideo,
          audioUrl: music,
          thumbnailUrl: cover,
          platform: 'TikTok',
          author: author,
          qualities: list,
        );
      }
    }
    return null;
  }

  Future<MediaResult?> _executeCobaltExtractor({
    required String url,
    required String apiUrl,
    required String platform,
  }) async {
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
    return null;
  }
}
