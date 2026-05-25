import Flutter
import UIKit

public class SwiftEpsonEposPlugin: NSObject, FlutterPlugin {
    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "epson_epos", binaryMessenger: registrar.messenger())
        let instance = SwiftEpsonEposPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
    }

    private let pluginImplement = PluginImplement()

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let method = PluginMethods(rawValue: call.method) else {
            result(FlutterMethodNotImplemented)
            return
        }

        switch method {
        case .onDiscovery:
            pluginImplement.onDiscovery(call, result: result)
        case .onPrint:
            pluginImplement.onPrint(call, result: result)
        case .getPrinterSetting, .setPrinterSetting:
            result(FlutterMethodNotImplemented)
        }
    }
}
