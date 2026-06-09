import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';

import 'enums.dart';
import 'helpers.dart';
import 'models.dart';

class EpsonEPOS {
  static const MethodChannel _channel = const MethodChannel('epson_epos');

  static EpsonEPOSHelper _eposHelper = EpsonEPOSHelper();

  static bool _isPrinterPlatformSupport({bool throwError = false}) {
    if (Platform.isAndroid || Platform.isIOS) return true;
    if (throwError) {
      throw PlatformException(
        code: "platformNotSupported",
        message: "Device not supported",
      );
    }
    return false;
  }

  static Future<List<EpsonPrinterModel>?> onDiscovery({
    EpsonEPOSPortType type = EpsonEPOSPortType.ALL,

    /// Optional subnet broadcast for TCP discovery (e.g. `192.168.1.255`).
    /// When omitted on Android, the plugin auto-detects from the active network.
    String? broadcast,
  }) async {
    print("FINDING EPSON PRINTER");
    if (!_isPrinterPlatformSupport(throwError: true)) return null;
    dynamic printType = _eposHelper.getPortType(
      type,
      returnInt: Platform.isIOS,
    );
    final Map<String, dynamic> params = {"type": printType};
    if (broadcast != null && broadcast.isNotEmpty) {
      params["broadcast"] = broadcast;
    }
    String? rep = await _channel.invokeMethod('onDiscovery', params);
    if (rep != null) {
      final response = EpsonPrinterResponse.fromRawJson(rep);
      print("onDiscovery: $response");
      if (!response.success) {
        print(
          "onDiscovery failed: statusCode=${response.statusCode}, message=${response.message}",
        );
        return [];
      }
      List<dynamic>? prs = response.content;
      if (prs == null) {
        return [];
      }
      if (prs.length > 0) {
        return prs.map((e) {
          final modelName = e['model'];
          final modelSeries = _eposHelper.getSeries(modelName);
          return EpsonPrinterModel(
            ipAddress: e['ipAddress'],
            bdAddress: e['bdAddress'],
            macAddress: e['macAddress'],
            type: printType.toString(),
            model: modelName,
            series: modelSeries?.id,
            target: e['target'],
          );
        }).toList();
      } else {
        return [];
      }
    }
    return [];
  }

  /// Send the [commands] to the [printer] and wait for the printer's response.
  ///
  /// Returns an [EpsonPrinterResponse] with:
  ///  - [EpsonPrinterResponse.statusCode] : an `int` defined in [EpsonStatusCode]
  ///    (e.g. `EpsonStatusCode.success`, `EpsonStatusCode.errCoverOpen`,
  ///    `EpsonStatusCode.errReceiptEnd`, ...).
  ///  - [EpsonPrinterResponse.message]    : a human-readable description of the
  ///    error / success.
  ///
  /// On platforms that don't return a parseable payload, a response with
  /// `statusCode = EpsonStatusCode.unknown` is returned.
  static Future<EpsonPrinterResponse> onPrint(
    EpsonPrinterModel printer,
    List<Map<String, dynamic>> commands,
  ) async {
    final Map<String, dynamic> params = {
      "type": printer.type,
      "series": printer.series,
      "commands": commands,
      "target": printer.target,
    };
    final dynamic raw = await _channel.invokeMethod('onPrint', params);
    return _parseResponse(raw, type: 'onPrint');
  }

  /// Parse the platform channel result into a typed [EpsonPrinterResponse].
  /// Native side returns a JSON string; older callers may also receive a Map.
  static EpsonPrinterResponse _parseResponse(
    dynamic raw, {
    required String type,
  }) {
    try {
      if (raw is String && raw.isNotEmpty) {
        return EpsonPrinterResponse.fromRawJson(raw);
      }
      if (raw is Map) {
        return EpsonPrinterResponse.fromJson(Map<String, dynamic>.from(raw));
      }
    } catch (_) {
      // fall through to unknown response
    }
    return EpsonPrinterResponse(
      type: type,
      success: false,
      statusCode: EpsonStatusCode.unknown,
      message: 'Invalid response from native plugin',
    );
  }

  static Future<dynamic> getPrinterSetting(EpsonPrinterModel printer) async {
    final Map<String, dynamic> params = {
      "type": printer.type,
      "series": printer.series,
      "target": printer.target,
    };
    return await _channel.invokeMethod('getPrinterSetting', params);
  }

  static Future<dynamic> setPrinterSetting(
    EpsonPrinterModel printer, {
    int? paperWidth,
    int? printDensity,
    int? printSpeed,
  }) async {
    final Map<String, dynamic> params = {
      "type": printer.type,
      "series": printer.series,
      "paper_width": paperWidth,
      "print_density": printDensity,
      "print_speed": printSpeed,
      "target": printer.target,
    };
    return await _channel.invokeMethod('setPrinterSetting', params);
  }
}
