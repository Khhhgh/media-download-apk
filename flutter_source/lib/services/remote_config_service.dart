import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class EngineConfig {
  final String regex;
  final String primaryApi;
  final String fallbackApi;

  EngineConfig({
    required this.regex,
    required this.primaryApi,
    required this.fallbackApi,
  });

  factory EngineConfig.fromJson(Map<String, dynamic> json) {
    return EngineConfig(
      regex: json['regex'] ?? '',
      primaryApi: json['primary_api'] ?? '',
      fallbackApi: json['fallback_api'] ?? '',
    );
  }
}

class AdSettingsConfig {
  final String url;
  final int triggerFrequency;
  final bool enabled;

  AdSettingsConfig({
    required this.url,
    required this.triggerFrequency,
    required this.enabled,
  });

  factory AdSettingsConfig.fromJson(Map<String, dynamic> json) {
    return AdSettingsConfig(
      url: json['url'] ?? 'https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce',
      triggerFrequency: json['trigger_frequency'] ?? 3,
      enabled: json['enabled'] ?? true,
    );
  }
}

class RemoteConfigService {
  static const String remoteConfigUrl =
      'https://gist.githubusercontent.com/Khhhgh/ba31c38a8b3e3e5b3c4b7998e5cd3c44/raw/engines.json';
  static const String _cacheKey = 'cached_remote_engines_json_v2';

  Map<String, dynamic> _rawConfig = {};
  AdSettingsConfig _adSettings = AdSettingsConfig(
    url: 'https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce',
    triggerFrequency: 3,
    enabled: true,
  );
  final Map<String, EngineConfig> _engines = {};

  AdSettingsConfig get adSettings => _adSettings;
  Map<String, EngineConfig> get engines => _engines;
  Map<String, dynamic> get rawConfig => _rawConfig;

  Future<void> init() async {
    final prefs = await SharedPreferences.getInstance();

    // 1. Try local cache from SharedPreferences
    final cached = prefs.getString(_cacheKey);
    if (cached != null && cached.isNotEmpty) {
      try {
        _parseAndApply(cached);
      } catch (_) {}
    }

    // 2. Fallback to bundled asset if still empty
    if (_engines.isEmpty) {
      try {
        final assetString = await rootBundle.loadString('assets/remote_config.json');
        _parseAndApply(assetString);
      } catch (_) {
        _applyDefaultConfig();
      }
    }

    // 3. Fetch live remote updates in background
    fetchRemoteUpdates();
  }

  Future<bool> fetchRemoteUpdates([String? customUrl]) async {
    final url = customUrl ?? remoteConfigUrl;
    try {
      final response = await http
          .get(Uri.parse(url), headers: {'Cache-Control': 'no-cache'})
          .timeout(const Duration(seconds: 10));

      if (response.statusCode == 200 && response.body.isNotEmpty) {
        _parseAndApply(response.body);

        // Immediate persistent caching
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(_cacheKey, response.body);
        return true;
      }
    } catch (_) {}
    return false;
  }

  void _parseAndApply(String jsonString) {
    final decoded = json.decode(jsonString);
    if (decoded is Map<String, dynamic>) {
      _rawConfig = decoded;
      if (decoded.containsKey('ad_settings')) {
        _adSettings = AdSettingsConfig.fromJson(decoded['ad_settings']);
      }
      if (decoded.containsKey('engines') && decoded['engines'] is Map<String, dynamic>) {
        _engines.clear();
        final enginesMap = decoded['engines'] as Map<String, dynamic>;
        enginesMap.forEach((key, val) {
          if (val is Map<String, dynamic>) {
            _engines[key] = EngineConfig.fromJson(val);
          }
        });
      }
    }
  }

  void _applyDefaultConfig() {
    _adSettings = AdSettingsConfig(
      url: 'https://asiafilm.org/4/f89ce863226eeb82a6e8daabbe6fd6ce',
      triggerFrequency: 3,
      enabled: true,
    );
    _engines['tiktok'] = EngineConfig(
      regex: r'(tiktok\.com|douyin\.com)',
      primaryApi: 'https://api.tikwm.com/api/',
      fallbackApi: 'https://co.wuk.sh/api/json',
    );
    _engines['instagram'] = EngineConfig(
      regex: r'(instagram\.com|instagr\.am)',
      primaryApi: 'https://api.cobalt.tools/api/json',
      fallbackApi: 'https://v3.saveig.app/api/ajaxSearch',
    );
    _engines['twitter'] = EngineConfig(
      regex: r'(twitter\.com|x\.com)',
      primaryApi: 'https://api.cobalt.tools/api/json',
      fallbackApi: 'https://twitsave.com/info',
    );
    _engines['facebook'] = EngineConfig(
      regex: r'(facebook\.com|fb\.watch)',
      primaryApi: 'https://api.cobalt.tools/api/json',
      fallbackApi: 'https://snapsave.app/action.php',
    );
  }
}
