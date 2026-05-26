// ignore_for_file: public_member_api_docs, sort_constructors_first
import 'dart:convert';

///
/// Printer Model
///
class EpsonPrinterModel {
  /// Connectivity type: TCP | BT | USB
  String? type;

  /// Addrs
  String? ipAddress;
  String? bdAddress;
  String? macAddress;
  String? model;
  String? series;
  String? target;
  EpsonPrinterModel({
    this.type,
    this.ipAddress,
    this.bdAddress,
    this.macAddress,
    this.model,
    this.series,
    this.target,
  });

  EpsonPrinterModel copyWith({
    String? type,
    String? ipAddress,
    String? bdAddress,
    String? macAddress,
    String? model,
    String? series,
    String? target,
  }) {
    return EpsonPrinterModel(
      type: type ?? this.type,
      ipAddress: ipAddress ?? this.ipAddress,
      bdAddress: bdAddress ?? this.bdAddress,
      macAddress: macAddress ?? this.macAddress,
      model: model ?? this.model,
      series: series ?? this.series,
      target: target ?? this.target,
    );
  }

  Map<String, dynamic> toMap() {
    return <String, dynamic>{
      'type': type,
      'ipAddress': ipAddress,
      'bdAddress': bdAddress,
      'macAddress': macAddress,
      'model': model,
      'series': series,
      'target': target,
    };
  }

  factory EpsonPrinterModel.fromMap(Map<String, dynamic> map) {
    return EpsonPrinterModel(
      type: map['type'] != null ? map['type'] as String : null,
      ipAddress: map['ipAddress'] != null ? map['ipAddress'] as String : null,
      bdAddress: map['bdAddress'] != null ? map['bdAddress'] as String : null,
      macAddress:
          map['macAddress'] != null ? map['macAddress'] as String : null,
      model: map['model'] != null ? map['model'] as String : null,
      series: map['series'] != null ? map['series'] as String : null,
      target: map['target'] != null ? map['target'] as String : null,
    );
  }

  String toJson() => json.encode(toMap());

  factory EpsonPrinterModel.fromJson(String source) =>
      EpsonPrinterModel.fromMap(json.decode(source) as Map<String, dynamic>);

  @override
  String toString() {
    return 'EpsonPrinterModel(type: $type, ipAddress: $ipAddress, bdAddress: $bdAddress, macAddress: $macAddress, model: $model, series: $series, target: $target)';
  }

  @override
  bool operator ==(covariant EpsonPrinterModel other) {
    if (identical(this, other)) return true;

    return other.type == type &&
        other.ipAddress == ipAddress &&
        other.bdAddress == bdAddress &&
        other.macAddress == macAddress &&
        other.model == model &&
        other.series == series &&
        other.target == target;
  }

  @override
  int get hashCode {
    return type.hashCode ^
        ipAddress.hashCode ^
        bdAddress.hashCode ^
        macAddress.hashCode ^
        model.hashCode ^
        series.hashCode ^
        target.hashCode;
  }
}

class EPSONSeries {
  String id;
  List<String> models;

  EPSONSeries({required this.id, required this.models});
}

///
/// Unified status codes returned by the native plugin.
/// Keep in sync with EpsonStatusCode in EpsonEposPlugin.kt.
///
class EpsonStatusCode {
  EpsonStatusCode._();

  static const int success = 0;
  static const int printing = 1;

  // Connection (100-199)
  static const int errConnect = 100;
  static const int errDisconnect = 101;
  static const int errOffline = 102;
  static const int errNoResponse = 103;
  static const int errTimeout = 104;
  static const int errPort = 105;
  static const int errNotFound = 106;

  // Paper (200-299)
  static const int errReceiptEnd = 200;
  static const int errPaperFeed = 201;
  static const int errWrongPaper = 202;
  static const int errEmpty = 203;

  // Cover / cutter (300-399)
  static const int errCoverOpen = 300;
  static const int errAutocutter = 301;
  static const int errCutter = 302;
  static const int errMechanical = 303;

  // Hardware health (400-499)
  static const int errOverheatHead = 400;
  static const int errOverheatMotor = 401;
  static const int errOverheatBattery = 402;
  static const int errBatteryEnd = 403;
  static const int errUnrecover = 404;
  static const int errAutorecover = 405;

  // System (500-599)
  static const int errFailure = 500;
  static const int errSystem = 501;
  static const int errParam = 502;
  static const int errProcessing = 503;

  static const int unknown = 999;
}

///
/// Response
///
class EpsonPrinterResponse {
  EpsonPrinterResponse({
    required this.type,
    required this.success,
    this.statusCode = EpsonStatusCode.unknown,
    this.message,
    this.content,
  });

  String type;
  bool success;
  int statusCode;
  String? message;
  dynamic content;

  /// `true` when the printer call completed without any error.
  bool get isSuccess => statusCode == EpsonStatusCode.success;

  EpsonPrinterResponse copyWith({
    String? type,
    bool? success,
    int? statusCode,
    String? message,
    dynamic content,
  }) =>
      EpsonPrinterResponse(
        type: type ?? this.type,
        success: success ?? this.success,
        statusCode: statusCode ?? this.statusCode,
        message: message ?? this.message,
        content: content ?? this.content,
      );

  factory EpsonPrinterResponse.fromRawJson(String str) =>
      EpsonPrinterResponse.fromJson(json.decode(str));

  String toRawJson() => json.encode(toJson());

  factory EpsonPrinterResponse.fromJson(Map<String, dynamic> json) =>
      EpsonPrinterResponse(
        type: json["type"] ?? '',
        success: json["success"] ?? false,
        statusCode: json["status_code"] is int
            ? json["status_code"] as int
            : EpsonStatusCode.unknown,
        message: json["message"],
        content: json["content"],
      );

  Map<String, dynamic> toJson() => {
        "type": type,
        "success": success,
        "status_code": statusCode,
        "message": message,
        "content": content,
      };

  @override
  String toString() {
    return 'EpsonPrinterResponse(type: $type, success: $success, statusCode: $statusCode, message: $message, content: $content)';
  }
}
