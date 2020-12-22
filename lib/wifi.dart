import 'dart:async';

import 'package:flutter/services.dart';

enum WifiState { error, success, already }

class Wifi {
  static const MethodChannel _channel =
      const MethodChannel('plugins.ly.com/wifi');

  /// Returns the ssid of the current network
  @Deprecated('use network.ssid instead')
  static Future<String?> get ssid async {
    await requestLocationPermission();
    return (await currentNetwork)?.ssid;
  }

  /// Returns the signal strength of the current network
  ///
  /// Throws [PlatformException] if not connected to network
  @Deprecated('use network.level instead')
  static Future<int?> get level async => (await currentNetwork)?.level;

  /// Returns the IP-Adress of the device in the current network
  ///
  /// Throws [PlatformException] if not connected to network
  @Deprecated('use network.ip instead')
  static Future<String?> get ip async => (await currentNetwork)?.ip;

  /// Requests the location permission
  ///
  /// This is required for the method [listNetworks] and [ConnectedWifiResult.ssid].
  /// Throws [PlatformException] when called while already requesting the
  /// permission.
  static Future<bool> requestLocationPermission() async =>
      await _channel.invokeMethod<bool>('locationPermission') ?? false;

  static Future<bool> requestConnectionPermission() async =>
      await _channel.invokeMethod<bool>('changeNetworkPermission') ?? false;

  @Deprecated('use listNetworks method')
  static Future<List<ScannedWifiResult>> list(String key) => listNetworks();

  static Future<ConnectedWifiResult?> get currentNetwork async {
    final result =
        await _channel.invokeMethod<Map<dynamic, dynamic>>('networkInfo');
    if (result != null) {
      return ConnectedWifiResult.fromJson(result);
    }
    return null;
  }

  /// Scans the networks in range
  ///
  /// Throws [PlatformException] if [requestLocationPermission] didn't yield the
  /// ACCESS_FINE_LOCATION permission
  static Future<List<ScannedWifiResult>> listNetworks() async {
    final results = (await _channel.invokeMethod<List<dynamic>>('list'))
        ?.cast<Map<dynamic, dynamic>>();
    if (results != null) {
      List<ScannedWifiResult> resultList = [];
      for (final result in results) {
        resultList.add(ScannedWifiResult.fromJson(result));
      }
      return resultList;
    }
    return List.empty();
  }

  static Future<WifiState> connection(String ssid, String password) async {
    throw UnimplementedError('Working on it');
    final Map<String, dynamic> params = {
      'ssid': ssid,
      'password': password,
    };
    final state = await _channel.invokeMethod<int>('connection', params) ?? -1;
    switch (state) {
      case 0:
        return WifiState.error;
      case 1:
        return WifiState.success;
      case 2:
        return WifiState.already;
      default:
        return WifiState.error;
    }
  }
}

enum ChannelWidth { ch20Mhz, ch40Mhz, ch80Mhz, ch160Mhz, ch80MhzPlus, unknown }

ChannelWidth _channelWidthFromId(int id) {
  switch (id) {
    case 0:
      return ChannelWidth.ch20Mhz;
    case 1:
      return ChannelWidth.ch40Mhz;
    case 2:
      return ChannelWidth.ch80Mhz;
    case 3:
      return ChannelWidth.ch160Mhz;
    case 4:
      return ChannelWidth.ch80MhzPlus;
    default:
      return ChannelWidth.unknown;
  }
}

class NotConnectedException implements Exception {}

class _WifiResult {
  final String ssid;
  final String bssid;
  final int? level;

  _WifiResult(this.ssid, this.bssid, this.level);
}

class ConnectedWifiResult extends _WifiResult {
  final String ip;
  final int linkSpeed;
  final int downloadLinkSpeed;
  final int uploadLinkSpeed;
  final bool hiddenSsid;
  final DhcpInfo dhcpInfo;

  ConnectedWifiResult(
    String ssid,
    String bssid,
    int? level,
    this.ip,
    this.linkSpeed,
    this.downloadLinkSpeed,
    this.uploadLinkSpeed,
    this.hiddenSsid,
    this.dhcpInfo,
  ) : super(ssid, bssid, level);

  factory ConnectedWifiResult.fromJson(Map<dynamic, dynamic> json) {
    if (json['bssid'] == null || json['ip'] == null) {
      throw NotConnectedException();
    }
    return ConnectedWifiResult(
      json['ssid'],
      json['bssid'],
      json['level'],
      json['ip'],
      json['linkSpeed'],
      json['downloadLinkSpeed'],
      json['uploadLinkSpeed'],
      json['hiddenSsid'],
      DhcpInfo.fromJson(json['dhcpInfo']),
    );
  }
}

class DhcpInfo {
  final String dns1;
  final String dns2;
  final String gateway;
  final String netmask;

  DhcpInfo(
    this.dns1,
    this.dns2,
    this.gateway,
    this.netmask,
  );

  factory DhcpInfo.fromJson(Map<dynamic, dynamic> json) {
    return DhcpInfo(
      json['dns1'],
      json['dns2'],
      json['gateway'],
      json['netmask'],
    );
  }
}

class ScannedWifiResult extends _WifiResult {
  final int frequency;
  final String capabilities;

  ///
  ///
  /// Returns [ChannelWidth.unknown] on devices prior to Android SDK Level 23 (M)
  final ChannelWidth channelWidth;

  static const _24GhzStart = 2412;
  static const _24GhzEnd = 2484;
  static const _5GhzStart = 5160;
  static const _5GhzEnd = 5865;

  ScannedWifiResult(String ssid, String bssid, int? level, this.frequency,
      this.capabilities, this.channelWidth)
      : super(ssid, bssid, level);

  bool get is24Ghz => frequency >= _24GhzStart && frequency <= _24GhzEnd;

  bool get is5Ghz => frequency >= _5GhzStart && frequency <= _5GhzEnd;

  factory ScannedWifiResult.fromJson(Map<dynamic, dynamic> json) {
    return ScannedWifiResult(
      json['ssid'],
      json['bssid'],
      json['level'],
      json['frequency'],
      json['capabilities'],
      _channelWidthFromId(json['channelWidth']),
    );
  }
}
