import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';
import 'remote_config_service.dart';

class AdManager {
  static const String _counterKey = 'download_ad_counter_live';

  Future<int> getDownloadCount() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_counterKey) ?? 0;
  }

  Future<void> resetCounter() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_counterKey, 0);
  }

  /// Evaluates download frequency reactively from remote AdSettingsConfig:
  /// Downloads 1, 2: processed instantly.
  /// Download 3 (or configured triggerFrequency): Resets counter to 0, launches direct ad in external browser, returns true.
  Future<bool> handleDownloadTrigger(AdSettingsConfig adSettings) async {
    if (!adSettings.enabled) return false;

    final prefs = await SharedPreferences.getInstance();
    int current = prefs.getInt(_counterKey) ?? 0;
    current++;

    final threshold = adSettings.triggerFrequency > 0 ? adSettings.triggerFrequency : 3;

    if (current >= threshold) {
      await prefs.setInt(_counterKey, 0);
      await openAdLink(adSettings.url);
      return true;
    } else {
      await prefs.setInt(_counterKey, current);
      return false;
    }
  }

  Future<void> openAdLink(String url) async {
    try {
      final uri = Uri.parse(url);
      if (await canLaunchUrl(uri)) {
        await launchUrl(uri, mode: LaunchMode.externalApplication);
      }
    } catch (_) {}
  }
}
