//
//  PluginImplement.swift
//  epson_epos
//
//  Created by Thomas on 08/08/2024.
//

import Foundation
import Flutter
import Combine

class PluginImplement: NSObject {
    private var cancellable = Set<AnyCancellable>()
    
    fileprivate var result: FlutterResult?
    
    fileprivate var printers: [EpsonEposPrinterInfo] = []
    fileprivate var filterOption: Epos2FilterOption = Epos2FilterOption()
    
    private var printer: Epos2Printer?
    
    private var valuePrinterSeries: Epos2PrinterSeries = EPOS2_TM_M10
    private var valuePrinterModel: Epos2ModelLang = EPOS2_MODEL_SOUTHASIA
    private var printType: String = ""
    
    private enum Constants {
        static let discoverLookupInterval = 4.0 // 4 seconds
    }
    
    override init() {
        filterOption.deviceType = EPOS2_TYPE_ALL.rawValue
        filterOption.deviceModel = EPOS2_MODEL_ALL.rawValue
    }
    
    public func onDiscovery(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        if let args = call.arguments as? [String: Any] {
            print("Discovery args: \(args)")
            // Dart sends `int` which arrives as `NSNumber`; cast through both
            // `Int32` and `Int` to be safe across architectures.
            let portType: Int32
            if let value = args["type"] as? Int32 {
                portType = value
            } else if let value = args["type"] as? Int {
                portType = Int32(value)
            } else if let value = args["type"] as? NSNumber {
                portType = value.int32Value
            } else {
                portType = EPOS2_PORTTYPE_ALL.rawValue
            }
            filterOption.portType = portType
        } else {
            filterOption.portType = EPOS2_PORTTYPE_ALL.rawValue
        }
        let operation = OperationQueue()
        operation.addOperation { [weak self] in
            self?._onDiscovery(call, result: result)
        }
    }
    
    private func _onDiscovery(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        self.result = result

        // Cancel any pending finish-timer from a previous discovery so we do not
        // accidentally return the previous call's result for this new one.
        cancellable.forEach { $0.cancel() }
        cancellable.removeAll()
        printers.removeAll()

        // Discovery must be in the stopped state before calling `start` again.
        // If a previous session is still in PROCESSING state (e.g. user tapped
        // discovery twice in a row, or the SDK kept state from a prior run),
        // `start` will fail with EPOS2_ERR_ILLEGAL (code 5). Loop on stop()
        // until it returns either SUCCESS or something other than PROCESSING.
        var stopResult = EPOS2_SUCCESS.rawValue
        repeat {
            stopResult = Epos2Discovery.stop()
        } while stopResult == EPOS2_ERR_PROCESSING.rawValue

        let response = Epos2Discovery.start(filterOption, delegate: self)
        print("Epos2Discovery.start response: \(response) | portType: \(filterOption.portType) | printers count: \(printers.count)")
        let resp = EpsonEposPrinterResult(type: PluginMethods.onDiscovery.rawValue, success: false)
        if response != EPOS2_SUCCESS.rawValue {
            resp.statusCode = EpsonStatusCode.fromEposApi(response)
            resp.code = response
            resp.message = MessageHelper.errorEpos(response, method: "start")
            return result(try? resp.toJSONString())
        }
        
        Timer.publish(every: Constants.discoverLookupInterval, on: .main, in: .common)
            .autoconnect()
            .first()
            .receive(on: DispatchQueue.global())
            .sink(receiveValue: { [weak self] _ in
                _ = Epos2Discovery.stop()
                
                guard let self = self, let result = self.result else {
                    return
                }
                
                let successResp = EpsonEposPrinterResult(
                    type: PluginMethods.onDiscovery.rawValue,
                    success: true,
                    statusCode: EpsonStatusCode.success,
                    message: "Successfully!"
                )
                successResp.content = self.printers
                do {
                    let data = try successResp.toJSONString()
                    result(data)
                } catch let error {
                    let errorResp = EpsonEposPrinterResult(
                        type: PluginMethods.onDiscovery.rawValue,
                        success: false,
                        statusCode: EpsonStatusCode.errFailure,
                        message: error.localizedDescription
                    )
                    result(try? errorResp.toJSONString())
                }
            })
            .store(in: &cancellable)
    }
    
    public func connectDevice() {
        _ = Epos2Discovery.stop()
        
        let btConnection = Epos2BluetoothConnection()
        let bdAddress = NSMutableString()
        let result = btConnection?.connectDevice(bdAddress)
        if result != EPOS2_SUCCESS.rawValue {
            _ = Epos2Discovery.start(filterOption, delegate: self)
        }
    }
    
    public func restartDiscovery(_ sender: AnyObject) {
        var result = EPOS2_SUCCESS.rawValue
        
        while true {
            result = Epos2Discovery.stop()
            
            if result != EPOS2_ERR_PROCESSING.rawValue {
                if result == EPOS2_SUCCESS.rawValue {
                    break
                } else {
                    return
                }
            }
        }
        
        printers.removeAll()

        _ = Epos2Discovery.start(filterOption, delegate: self)
    }
}

private extension PluginImplement {
    func returnFailResultWith(method: String,
                              message: String,
                              statusCode: Int = EpsonStatusCode.errFailure,
                              code: Int32? = nil) {
        let resp = EpsonEposPrinterResult(
            type: method,
            success: false,
            statusCode: statusCode,
            message: message,
            code: code
        )
        self.result?(try? resp.toJSONString())
    }
    
    func handlePrinterReceive(code: Int32, status: Epos2PrinterStatusInfo) {
        let errorFromStatus = makeErrorMessage(status)
        let resp = EpsonEposPrinterResult(type: "onPrint\(printType)", success: false)
        resp.code = code
        
        let statusErrorCode = errorFromStatus.statusCode
        let hasSpecificError = statusErrorCode != EpsonStatusCode.unknown
        
        if code == EPOS2_CODE_SUCCESS.rawValue && !hasSpecificError {
            resp.success = true
            resp.statusCode = EpsonStatusCode.success
            resp.message = "Success"
        } else {
            resp.success = false
            if hasSpecificError {
                resp.statusCode = statusErrorCode
                resp.message = errorFromStatus.message
                resp.content = errorFromStatus.legacyCode
            } else {
                resp.statusCode = EpsonStatusCode.fromEposPrintCode(code)
                resp.message = sanitizeAnyMessage(MessageHelper.result(code, errMessage: ""))
                resp.content = "ERR_PRINT_CODE_\(code)"
            }
        }
        
        self.result?(try? resp.toJSONString())
    }

    /// Free-function sanitizer used outside the `Epos2PtrReceiveDelegate` extension.
    func sanitizeAnyMessage(_ message: String) -> String {
        let collapsed = message
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
        return collapsed
            .components(separatedBy: " ")
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }
}

extension PluginImplement: Epos2DiscoveryDelegate {
    func onDiscovery(_ deviceInfo: Epos2DeviceInfo!) {
        guard let deviceInfo = deviceInfo else { return }
        print("Discovered device: \(deviceInfo.deviceName ?? "Unknown") at \(deviceInfo.ipAddress ?? "No IP")")
        guard let printer = EpsonEposPrinterInfo.printer(from: deviceInfo) else {
            return
        }
        if let index = printers.firstIndex(where: { $0.ipAddress == deviceInfo.ipAddress }) {
            printers[index] = printer
        } else {
            printers.append(printer)
        }
    }
}

extension PluginImplement: Epos2PtrReceiveDelegate {
    public func onPrint(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        self.result = result
        if let args = call.arguments as? [String: Any], let type = args["type"] as? String {
            self.printType = type
        } else {
            self.printType = ""
        }
        
        guard initializePrinterObject() else {
            let resp = EpsonEposPrinterResult(
                type: call.method,
                success: false,
                statusCode: EpsonStatusCode.errSystem,
                message: eposLocalizedString("error_not_support_printer")
            )
            result(try? resp.toJSONString())
            return
        }
        
        DispatchQueue.global().async { [weak self] in
            self?.printData(call, result: result)
        }
    }
    
    func printData(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let target = args["target"] as? String,
              target.isEmpty == false,
              let series = args["series"] as? String else {
            returnFailResultWith(
                method: call.method,
                message: eposLocalizedString("error_missing_print_data"),
                statusCode: EpsonStatusCode.errParam
            )
            return
        }
        
        guard let commands = args["commands"] as? [[String: Any]] else {
            returnFailResultWith(
                method: call.method,
                message: eposLocalizedString("error_missing_print_data"),
                statusCode: EpsonStatusCode.errParam
            )
            return
        }
        
        guard connectPrinter(with: target, method: call.method) else {
            printer?.clearCommandBuffer()
            return
        }
        
        guard let printer = printer else { return }
        
        let generator = CommandGenerator()
        commands.forEach { command in
            generator.onGenerateCommandFor(printer: printer, command: command)
        }
        
        let resp = EpsonEposPrinterResult(type: call.method, success: false)
        do {
            let status = printer.sendData(Int(EPOS2_PARAM_DEFAULT))
            if status != EPOS2_SUCCESS.rawValue {
                // Try to read printer status info for a more specific error
                let statusError = makeErrorMessage(printer.getStatus())
                let message = MessageHelper.errorEpos(status, method: "sendData")
                
                resp.success = false
                resp.code = status
                if statusError.statusCode != EpsonStatusCode.unknown {
                    resp.statusCode = statusError.statusCode
                    resp.message = statusError.message
                    resp.content = statusError.legacyCode
                } else {
                    resp.statusCode = EpsonStatusCode.fromEposApi(status)
                    resp.message = sanitizeAnyMessage(message)
                    resp.content = "ERR_SEND_DATA_\(status)"
                }
                
                printer.clearCommandBuffer()
                _ = printer.disconnect()
                result(try resp.toJSONString())
            } else {
                // Wait for onPtrReceive
                print("Sent data to printer \(target) \(series)")
            }
        } catch let error {
            resp.statusCode = EpsonStatusCode.errFailure
            resp.message = error.localizedDescription
            resp.success = false
            result(try? resp.toJSONString())
        }
    }
    
    @discardableResult
    func initializePrinterObject() -> Bool {
        printer = Epos2Printer(printerSeries: valuePrinterSeries.rawValue, lang: valuePrinterModel.rawValue)
        guard let printer = printer else { return false }
        printer.setReceiveEventDelegate(self)
        return true
    }
    
    func connectPrinter(with target: String, method: String) -> Bool {
        guard let printer = printer else { return false }
        
        // Note: This API must be used from background thread only
        let result = printer.connect(target, timeout: Int(EPOS2_PARAM_DEFAULT))
        if result != EPOS2_SUCCESS.rawValue {
            let message = MessageHelper.errorEpos(result, method: "connect")
            returnFailResultWith(
                method: method,
                message: sanitizeAnyMessage(message),
                statusCode: EpsonStatusCode.fromEposApi(result),
                code: result
            )
            return false
        }
        
        return true
    }
    
    @discardableResult
    func disconnectPrinter() -> Bool {
        guard let printer = printer else { return false }
        
        // Try to end any in-flight transaction, then disconnect best-effort.
        _ = printer.endTransaction()
        printer.setReceiveEventDelegate(nil)
        
        // Note: This API must be used from background thread only
        let result = printer.disconnect()
        printer.clearCommandBuffer()
        
        if result != EPOS2_SUCCESS.rawValue {
            let message = MessageHelper.errorEpos(result, method: "disconnect")
            print("disconnectPrinter error: \(message)")
            return false
        }
        return true
    }
    
    // MARK: - Epos2PtrReceiveDelegate
    func onPtrReceive(_ printerObj: Epos2Printer!, code: Int32, status: Epos2PrinterStatusInfo!, printJobId: String!) {
        let queue = OperationQueue()
        queue.addOperation { [weak self] in
            guard let self = self else { return }
            self.disconnectPrinter()
            self.handlePrinterReceive(code: code, status: status)
        }
    }
    
    func dispPrinterWarnings(_ status: Epos2PrinterStatusInfo?) {
        guard let status = status else { return }
        let warningMsg = NSMutableString()
        
        if status.paper == EPOS2_PAPER_NEAR_END.rawValue {
            warningMsg.append(eposLocalizedString("warn_receipt_near_end"))
        }
        if status.batteryLevel == EPOS2_BATTERY_LEVEL_1.rawValue {
            warningMsg.append(eposLocalizedString("warn_battery_near_end"))
        }
        if status.paperTakenSensor == EPOS2_REMOVAL_DETECT_PAPER.rawValue {
            warningMsg.append(eposLocalizedString("warn_detect_paper"))
        }
        if status.paperTakenSensor == EPOS2_REMOVAL_DETECT_UNKNOWN.rawValue {
            warningMsg.append(eposLocalizedString("warn_detect_unknown"))
        }
        print("Printer warnings: \(warningMsg)")
    }

    /// Result of translating a `Epos2PrinterStatusInfo` into something we can
    /// return to the Flutter layer.
    struct PrinterStatusError {
        let statusCode: Int
        let message: String
        /// String code kept for backward compatibility with previous payload.
        let legacyCode: String
    }

    /// Normalize a message coming from `ePOS2Localizable.strings` so it doesn't
    /// contain stray newlines that would make Flutter loggers (like `logger`)
    /// render the output across multiple lines.
    private func sanitizeMessage(_ message: String) -> String {
        let collapsed = message
            .replacingOccurrences(of: "\r", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
        return collapsed
            .components(separatedBy: " ")
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    func makeErrorMessage(_ status: Epos2PrinterStatusInfo?) -> PrinterStatusError {
        var errMsg = ""
        var legacyCode = "ERR_UNKNOWN"
        var statusCode = EpsonStatusCode.unknown
        guard let status = status else {
            return PrinterStatusError(statusCode: EpsonStatusCode.unknown, message: "", legacyCode: "ERR_UNKNOWN")
        }
        
        if status.online == EPOS2_FALSE {
            errMsg += eposLocalizedString("err_offline")
            legacyCode = "ERR_OFFLINE"
            statusCode = EpsonStatusCode.errOffline
        }
        if status.connection == EPOS2_FALSE {
            errMsg += eposLocalizedString("err_no_response")
            legacyCode = "ERR_NO_RESPONSE"
            statusCode = EpsonStatusCode.errNoResponse
        }
        if status.coverOpen == EPOS2_TRUE {
            errMsg += eposLocalizedString("err_cover_open")
            legacyCode = "ERR_COVER_OPEN"
            statusCode = EpsonStatusCode.errCoverOpen
        }
        if status.paper == EPOS2_PAPER_EMPTY.rawValue {
            errMsg += eposLocalizedString("err_receipt_end")
            legacyCode = "ERR_RECEIPT_END"
            statusCode = EpsonStatusCode.errReceiptEnd
        }
        if status.paperFeed == EPOS2_TRUE || status.panelSwitch == EPOS2_SWITCH_ON.rawValue {
            errMsg += eposLocalizedString("err_paper_feed")
            legacyCode = "ERR_PAPER_FEED"
            statusCode = EpsonStatusCode.errPaperFeed
        }
        if status.errorStatus == EPOS2_MECHANICAL_ERR.rawValue || status.errorStatus == EPOS2_AUTOCUTTER_ERR.rawValue {
            errMsg += eposLocalizedString("err_autocutter")
            errMsg += eposLocalizedString("err_need_recover")
            legacyCode = "ERR_AUTOCUTTER"
            statusCode = EpsonStatusCode.errAutocutter
        }
        if status.errorStatus == EPOS2_UNRECOVER_ERR.rawValue {
            errMsg += eposLocalizedString("err_unrecover")
            legacyCode = "ERR_UNRECOVER"
            statusCode = EpsonStatusCode.errUnrecover
        }
        
        if status.errorStatus == EPOS2_AUTORECOVER_ERR.rawValue {
            switch status.autoRecoverError {
            case EPOS2_HEAD_OVERHEAT.rawValue:
                errMsg += eposLocalizedString("err_head")
                errMsg += eposLocalizedString("err_overheat")
                legacyCode = "ERR_OVERHEAT_HEAD"
                statusCode = EpsonStatusCode.errOverheatHead
            case EPOS2_MOTOR_OVERHEAT.rawValue:
                errMsg += eposLocalizedString("err_motor")
                errMsg += eposLocalizedString("err_overheat")
                legacyCode = "ERR_OVERHEAT_MOTOR"
                statusCode = EpsonStatusCode.errOverheatMotor
            case EPOS2_BATTERY_OVERHEAT.rawValue:
                errMsg += eposLocalizedString("err_battery")
                errMsg += eposLocalizedString("err_overheat")
                legacyCode = "ERR_OVERHEAT_BATTERY"
                statusCode = EpsonStatusCode.errOverheatBattery
            case EPOS2_WRONG_PAPER.rawValue:
                errMsg += eposLocalizedString("err_wrong_paper")
                legacyCode = "ERR_WRONG_PAPER"
                statusCode = EpsonStatusCode.errWrongPaper
            default:
                break
            }
        }
        if status.batteryLevel == EPOS2_BATTERY_LEVEL_0.rawValue {
            errMsg += eposLocalizedString("err_battery_real_end")
            legacyCode = "ERR_BATTERY_END"
            statusCode = EpsonStatusCode.errBatteryEnd
        }
        if status.removalWaiting == EPOS2_REMOVAL_WAIT_PAPER.rawValue {
            errMsg += eposLocalizedString("err_wait_removal")
            legacyCode = "ERR_WAIT_REMOVAL"
            statusCode = EpsonStatusCode.errWaitRemoval
        }
        if status.unrecoverError == EPOS2_HIGH_VOLTAGE_ERR.rawValue ||
            status.unrecoverError == EPOS2_LOW_VOLTAGE_ERR.rawValue {
            errMsg += eposLocalizedString("err_voltage")
            legacyCode = "ERR_VOLTAGE"
            statusCode = EpsonStatusCode.errVoltage
        }
        
        if errMsg.isEmpty {
            return PrinterStatusError(statusCode: EpsonStatusCode.unknown, message: "", legacyCode: "ERR_UNKNOWN")
        }
        return PrinterStatusError(
            statusCode: statusCode,
            message: sanitizeMessage(errMsg),
            legacyCode: legacyCode
        )
    }
}
