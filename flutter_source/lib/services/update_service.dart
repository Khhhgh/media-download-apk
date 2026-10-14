import 'dart:convert';
import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

class AppUpdateInfo {
  final bool hasUpdate;
  final String latestVersion;
  final String currentVersion;
  final String releaseTitle;
  final String releaseNotes;
  final String apkDownloadUrl;
  final String releasePageUrl;

  AppUpdateInfo({
    required this.hasUpdate,
    required this.latestVersion,
    required this.currentVersion,
    this.releaseTitle = '',
    this.releaseNotes = '',
    this.apkDownloadUrl = '',
    this.releasePageUrl = '',
  });
}

class AppUpdateService {
  static const String repoReleasesUrl =
      'https://api.github.com/repos/Khhhgh/media-download-apk/releases/latest';

  static Future<AppUpdateInfo?> checkForUpdate() async {
    try {
      final response = await http.get(
        Uri.parse(repoReleasesUrl),
        headers: {
          'Accept': 'application/vnd.github.v3+json',
          'User-Agent': 'MediaDownload-AppUpdateChecker/1.0',
        },
      );

      if (response.statusCode != 200) return null;

      final json = jsonDecode(response.body) as Map<String, dynamic>;
      final tagName = (json['tag_name'] as String? ?? '').trim();
      final releaseTitle = json['name'] as String? ?? tagName;
      final releaseNotes = json['body'] as String? ?? '';
      final htmlUrl = json['html_url'] as String? ??
          'https://github.com/Khhhgh/media-download-apk/releases';

      String apkUrl = '';
      final assets = json['assets'] as List<dynamic>?;
      if (assets != null) {
        for (final asset in assets) {
          final assetName = asset['name'] as String? ?? '';
          final downloadUrl = asset['browser_download_url'] as String? ?? '';
          if (assetName.toLowerCase().endsWith('.apk') && downloadUrl.isNotEmpty) {
            apkUrl = downloadUrl;
            break;
          }
        }
      }

      if (apkUrl.isEmpty && tagName.isNotEmpty) {
        apkUrl =
            'https://github.com/Khhhgh/media-download-apk/releases/download/$tagName/media-download-update.apk';
      }

      String currentVersion = '1.0';
      try {
        final pInfo = await PackageInfo.fromPlatform();
        currentVersion = pInfo.version;
      } catch (_) {}

      final isNewer = _isVersionNewer(tagName, currentVersion);

      return AppUpdateInfo(
        hasUpdate: isNewer,
        latestVersion: tagName.replaceAll('v', ''),
        currentVersion: currentVersion.replaceAll('v', ''),
        releaseTitle: releaseTitle,
        releaseNotes: releaseNotes,
        apkDownloadUrl: apkUrl,
        releasePageUrl: htmlUrl,
      );
    } catch (_) {
      return null;
    }
  }

  static bool _isVersionNewer(String latest, String current) {
    if (latest.isEmpty) return false;
    final lClean = latest.toLowerCase().replaceAll('v', '').trim();
    final cClean = current.toLowerCase().replaceAll('v', '').trim();
    if (lClean == cClean) return false;

    try {
      final lParts = lClean.split('.').map((e) => int.tryParse(e) ?? 0).toList();
      final cParts = cClean.split('.').map((e) => int.tryParse(e) ?? 0).toList();
      final maxLen = lParts.length > cParts.length ? lParts.length : cParts.length;

      for (int i = 0; i < maxLen; i++) {
        final l = i < lParts.length ? lParts[i] : 0;
        final c = i < cParts.length ? cParts[i] : 0;
        if (l > c) return true;
        if (l < c) return false;
      }
    } catch (_) {
      return lClean != cClean;
    }
    return false;
  }

  static Future<void> openDownloadUrl(String url) async {
    final uri = Uri.parse(url);
    if (await canLaunchUrl(uri)) {
      await launchUrl(uri, mode: LaunchMode.externalApplication);
    }
  }
}
