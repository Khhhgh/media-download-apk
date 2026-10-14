import 'dart:io';
import 'package:dio/dio.dart';
import 'package:gal/gal.dart';
import 'package:path_provider/path_provider.dart';

typedef ProgressCallback = void Function(int received, int total, double percent, String speed);

class StorageService {
  final Dio _dio = Dio();

  Future<String> downloadAndSaveToGallery({
    required String downloadUrl,
    required String title,
    String format = 'mp4',
    ProgressCallback? onProgress,
  }) async {
    final hasAccess = await Gal.hasAccess();
    if (!hasAccess) {
      await Gal.requestAccess();
    }

    final tempDir = await getTemporaryDirectory();
    final cleanTitle = title.replaceAll(RegExp(r'[^a-zA-Z0-9_-]'), '_');
    final filePath = '${tempDir.path}/media_${cleanTitle}_${DateTime.now().millisecondsSinceEpoch}.$format';

    int lastBytes = 0;
    int lastTime = DateTime.now().millisecondsSinceEpoch;
    String currentSpeed = '0 KB/s';

    await _dio.download(
      downloadUrl,
      filePath,
      onReceiveProgress: (received, total) {
        final now = DateTime.now().millisecondsSinceEpoch;
        if (now - lastTime > 400 || received == total) {
          final diffBytes = received - lastBytes;
          final diffSec = (now - lastTime) / 1000.0;
          if (diffSec > 0) {
            final bytesPerSec = diffBytes / diffSec;
            if (bytesPerSec >= 1024 * 1024) {
              currentSpeed = '${(bytesPerSec / (1024 * 1024)).toStringAsFixed(1)} MB/s';
            } else {
              currentSpeed = '${(bytesPerSec / 1024).toStringAsFixed(0)} KB/s';
            }
          }
          lastBytes = received;
          lastTime = now;
        }

        final double percent = total > 0 ? (received / total) * 100 : 0.0;
        onProgress?.call(received, total, percent, currentSpeed);
      },
    );

    // Save directly to device gallery via Gal
    final fmt = format.toLowerCase();
    if (fmt == 'mp4') {
      await Gal.putVideo(filePath, album: 'MediaDownload');
    } else if (fmt == 'jpg' || fmt == 'jpeg' || fmt == 'png' || fmt == 'webp' || fmt == 'gif') {
      await Gal.putImage(filePath, album: 'MediaDownload');
    } else if (fmt == 'mp3') {
      // Save audio (best-effort)
      try {
        await Gal.putAudio(filePath, album: 'MediaDownload');
      } catch (_) {}
    } else {
      // Default: try image for unknown non-video
      if (fmt != 'mp4') {
        try {
          await Gal.putImage(filePath, album: 'MediaDownload');
        } catch (_) {}
      }
    }

    return filePath;
  }
}
