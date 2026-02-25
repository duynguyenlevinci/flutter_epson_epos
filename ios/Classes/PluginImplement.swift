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
        if let args = call.arguments as? [String: Any] { // Cast to the expected type
            print("Discovery args: \(args)")
                let type = args["type"] as? Int32 ?? EPOS2_PORTTYPE_ALL.rawValue
                filterOption.portType = type
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
        let response = Epos2Discovery.start(filterOption, delegate: self)
        print("Epos2Discovery.start response: \(response) | printers count: \(printers.count)")
        let resp = EpsonEposPrinterResult.init(type: PluginMethods.onDiscovery.rawValue, success: false)
        if response != EPOS2_SUCCESS.rawValue {
            resp.message = MessageHelper.errorEpos(response, method: "start")
            return result(try? resp.toJSONString())
        }
        
        Timer.publish(every: Constants.discoverLookupInterval, on: .main, in: .common)
            .autoconnect()
            .first()
            .receive(on: DispatchQueue.global())
            .sink(receiveValue: { [weak self] _ in
                // Stop discover
                Epos2Discovery.stop()
                
                guard let self = self, let result = self.result else {
                    return
                }
                
                // return the result
                var resp = EpsonEposPrinterResult.init(type: PluginMethods.onDiscovery.rawValue, success: true)
                resp.content = self.printers
                do {
                    let data = try resp.toJSONString()
                    result(data)
                } catch let error {
                    resp = EpsonEposPrinterResult.init(type: PluginMethods.onDiscovery.rawValue, success: true)
                    resp.success = false
                    resp.message = error.localizedDescription
                    result(try? resp.toJSONString())
                }
            })
            .store(in: &cancellable)
    }
    
    public func connectDevice() {
        Epos2Discovery.stop()
        
        let btConnection = Epos2BluetoothConnection()
        let BDAddress = NSMutableString()
        let result = btConnection?.connectDevice(BDAddress)
        if result == EPOS2_SUCCESS.rawValue {
//            delegate?.discoveryView(self, onSelectPrinterTarget: BDAddress as String)
//            delegate = nil
        }
        else {
            Epos2Discovery.start(filterOption, delegate:self)
//            printerView.reloadData()
        }
    }
    
    public func restartDiscovery(_ sender: AnyObject) {
        var result = EPOS2_SUCCESS.rawValue;
        
        while true {
            result = Epos2Discovery.stop()
            
            if result != EPOS2_ERR_PROCESSING.rawValue {
                if (result == EPOS2_SUCCESS.rawValue) {
                    break;
                }
                else {
//                    MessageView.showErrorEpos(result, method:"stop")
                    return;
                }
            }
        }
        
        printers.removeAll()
//        printerView.reloadData()

        result = Epos2Discovery.start(filterOption, delegate:self)
        if result != EPOS2_SUCCESS.rawValue {
//            MessageView.showErrorEpos(result, method:"start")
        }
    }
}

private extension PluginImplement {
    func returnFailResultWith(method: String, message: String) {
        let resp = EpsonEposPrinterResult(type: method, success: false)
        resp.message = message
        self.result?(try? resp.toJSONString())
    }
    
    func handlePrinterReceive(code: Int32, status: Epos2PrinterStatusInfo) {
        let errorFromStatus = makeErrorMessage(status)
        let resp = EpsonEposPrinterResult(type: "onPrint\(printType)", success: false)
        resp.code = code
        
        let hasSpecificError = !(errorFromStatus["code"]?.isEmpty ?? true) && errorFromStatus["code"] != "ERR_UNKNOWN"
        
        if code == EPOS2_CODE_SUCCESS.rawValue && !hasSpecificError {
            resp.success = true
            resp.message = "Success"
        } else {
            resp.success = false
            if hasSpecificError {
                resp.message = errorFromStatus["message"]
                resp.content = errorFromStatus["code"]
            } else {
                resp.message = MessageHelper.result(code, errMessage: "")
                resp.content = "ERR_PRINT_CODE_\(code)"
            }
        }
        
        self.result?(try? resp.toJSONString())
    }
}

extension PluginImplement: Epos2DiscoveryDelegate {
    func onDiscovery(_ deviceInfo: Epos2DeviceInfo!) {
        print("Discovered device: \(deviceInfo.deviceName ?? "Unknown") at \(deviceInfo.ipAddress ?? "No IP")")
        guard let printer = EpsonEposPrinterInfo.printer(from: deviceInfo) else {
            return
        }
        let printerIndex = printers.firstIndex(where: { e in
            e.ipAddress == deviceInfo.ipAddress
        })
        
        if let index = printerIndex, index > -1 {
            printers[index] = printer
        } else {
            printers.append(printer)
        }
    }
}

extension PluginImplement: Epos2PtrReceiveDelegate {
    public func onPrint(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        self.result = result
        self.printType = "" // Reset or set based on call if needed
        
        guard initializePrinterObject() == true else {
            let resp = EpsonEposPrinterResult(type: call.method, success: false)
            resp.message = NSLocalizedString("error_not_support_printer", comment: "")
            result(try? resp.toJSONString())
            return
        }
        
        DispatchQueue.global().async { [weak self] in
            self?.printData(call, result: result)
        }
    }
    
    func printData(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? Dictionary<String, Any>, let target: String = args["target"] as? String, target.isEmpty == false, let series = args["series"] as? String else {
            returnFailResultWith(method: call.method, message: NSLocalizedString("error_missing_print_data", comment: ""))
            return
        }
        
        guard let commands: Array<Dictionary<String, Any>> =
                args["commands"] as? Array<Dictionary<String, Any>> else {
            returnFailResultWith(method: call.method, message: NSLocalizedString("error_missing_print_data", comment: ""))
            return
        }
        
        guard connectPrinter(with: target, method: call.method) == true else {
            printer?.clearCommandBuffer()
            return
        }
        
        guard let printer = printer else { return }
        var status = EPOS2_SUCCESS.rawValue
        
        let generator = CommandGenerator()
        commands.forEach { command in
            generator.onGenerateCommandFor(printer: printer, command: command)
        }
        
        let resp = EpsonEposPrinterResult(type: call.method, success: false)
        do {
            let status = printer.sendData(Int(EPOS2_PARAM_DEFAULT))
            if status != EPOS2_SUCCESS.rawValue {
                let message = MessageHelper.errorEpos(status, method: "sendData")
                resp.message = message
                resp.success = false
                resp.content = "ERR_SEND_DATA_\(status)"
                
                printer.clearCommandBuffer()
                printer.disconnect()
                result(try resp.toJSONString());
            } else {
                // Wait for onPtrReceive
                print("Sent data to printer \(target) \(series)")
                // resp.success = true
                // resp.message = "Printed \(target) \(series)"
                // result(try resp.toJSONString());
            }
        } catch let error {
            resp.message = error.localizedDescription
            resp.success = false
            result(try? resp.toJSONString())
        }
    }
    
    @discardableResult
    func initializePrinterObject() -> Bool {
        printer = Epos2Printer(printerSeries: valuePrinterSeries.rawValue, lang: valuePrinterModel.rawValue)
        
        if printer == nil {
            return false
        }
        printer?.setReceiveEventDelegate(self)
        
        return true
    }
    
    func connectPrinter(with target: String, method: String) -> Bool {
        var result: Int32 = EPOS2_SUCCESS.rawValue
        
        if printer == nil {
            return false
        }
        
        // Note: This API must be used from background thread only
        result = printer!.connect(target, timeout:Int(EPOS2_PARAM_DEFAULT))
        if result != EPOS2_SUCCESS.rawValue {
            let message = MessageHelper.errorEpos(result, method:"connect")
            returnFailResultWith(method: method, message: message)
            return false
        }
        
        return true
    }
    
    @discardableResult
    func disconnectPrinter() -> Bool {
        var result: Int32 = EPOS2_SUCCESS.rawValue
        
        if printer == nil {
            return false
        }
        
        // Note: This API must be used from background thread only
        result = printer!.disconnect()
        if result != EPOS2_SUCCESS.rawValue {
            DispatchQueue.main.async(execute: { [weak self] in
                let message = MessageHelper.errorEpos(result, method:"disconnect")
                self?.returnFailResultWith(method: PluginMethods.onPrint.rawValue, message: message)
            })
            return false
        }

        printer!.clearCommandBuffer()
        
        return true
    }
    
    // MARK: - Epos2PtrReceiveDelegate
    func onPtrReceive(_ printerObj: Epos2Printer!, code: Int32, status: Epos2PrinterStatusInfo!, printJobId: String!) {
        
        let queue = OperationQueue()
        queue.addOperation({ [weak self] in
            guard let self = self else {
                return
            }
            if self.disconnectPrinter() == false {
                self.handlePrinterReceive(code: code, status: status)
            }
//            dispPrinterWarnings(status)
        })
    }
    
    func dispPrinterWarnings(_ status: Epos2PrinterStatusInfo?) {
        if status == nil {
            return
        }
        
//        OperationQueue.main.addOperation({ [self] in
//            textWarnings.text = ""
//        })
        let wanringMsg = NSMutableString()
        
        if status!.paper == EPOS2_PAPER_NEAR_END.rawValue {
            wanringMsg.append(NSLocalizedString("warn_receipt_near_end", comment:""))
        }
        
        if status!.batteryLevel == EPOS2_BATTERY_LEVEL_1.rawValue {
            wanringMsg.append(NSLocalizedString("warn_battery_near_end", comment:""))
        }
        
        if status!.paperTakenSensor == EPOS2_REMOVAL_DETECT_PAPER.rawValue {
            wanringMsg.append(NSLocalizedString("warn_detect_paper", comment:""))
        }
        
        if status!.paperTakenSensor == EPOS2_REMOVAL_DETECT_UNKNOWN.rawValue {
            wanringMsg.append(NSLocalizedString("warn_detect_unknown", comment:""))
        }
        
//        OperationQueue.main.addOperation({ [self] in
//            textWarnings.text = wanringMsg as String
//        })
    }

    func makeErrorMessage(_ status: Epos2PrinterStatusInfo?) -> [String: String] {
        var errMsg = ""
        var errCode = ""
        guard let status = status else {
            return ["message": "", "code": "ERR_UNKNOWN"]
        }
    
        if status.online == EPOS2_FALSE {
            errMsg += NSLocalizedString("err_offline", comment:"")
            errCode = "ERR_OFFLINE"
        }
        if status.connection == EPOS2_FALSE {
            errMsg += NSLocalizedString("err_no_response", comment:"")
            errCode = "ERR_NO_RESPONSE"
        }
        if status.coverOpen == EPOS2_TRUE {
            errMsg += NSLocalizedString("err_cover_open", comment:"")
            errCode = "ERR_COVER_OPEN"
        }
        if status.paper == EPOS2_PAPER_EMPTY.rawValue {
            errMsg += NSLocalizedString("err_receipt_end", comment:"")
            errCode = "ERR_RECEIPT_END"
        }
        if status.paperFeed == EPOS2_TRUE || status.panelSwitch == EPOS2_SWITCH_ON.rawValue {
            errMsg += NSLocalizedString("err_paper_feed", comment:"")
            errCode = "ERR_PAPER_FEED"
        }
        if status.errorStatus == EPOS2_MECHANICAL_ERR.rawValue || status.errorStatus == EPOS2_AUTOCUTTER_ERR.rawValue {
            errMsg += NSLocalizedString("err_autocutter", comment:"")
            errMsg += NSLocalizedString("err_need_recover", comment:"")
            errCode = "ERR_AUTOCUTTER"
        }
        if status.errorStatus == EPOS2_UNRECOVER_ERR.rawValue {
            errMsg += NSLocalizedString("err_unrecover", comment:"")
            errCode = "ERR_UNRECOVER"
        }
    
        if status.errorStatus == EPOS2_AUTORECOVER_ERR.rawValue {
            if status.autoRecoverError == EPOS2_HEAD_OVERHEAT.rawValue {
                errMsg += NSLocalizedString("err_head", comment:"")
                errMsg += NSLocalizedString("err_overheat", comment:"")
                errCode = "ERR_OVERHEAT_HEAD"
            }
            if status.autoRecoverError == EPOS2_MOTOR_OVERHEAT.rawValue {
                errMsg += NSLocalizedString("err_motor", comment:"")
                errMsg += NSLocalizedString("err_overheat", comment:"")
                errCode = "ERR_OVERHEAT_MOTOR"
            }
            if status.autoRecoverError == EPOS2_BATTERY_OVERHEAT.rawValue {
                errMsg += NSLocalizedString("err_battery", comment:"")
                errMsg += NSLocalizedString("err_overheat", comment:"")
                errCode = "ERR_OVERHEAT_BATTERY"
            }
            if status.autoRecoverError == EPOS2_WRONG_PAPER.rawValue {
                errMsg += NSLocalizedString("err_wrong_paper", comment:"")
                errCode = "ERR_WRONG_PAPER"
            }
        }
        if status.batteryLevel == EPOS2_BATTERY_LEVEL_0.rawValue {
            errMsg += NSLocalizedString("err_battery_real_end", comment:"")
            errCode = "ERR_BATTERY_END"
        }
        if (status.removalWaiting == EPOS2_REMOVAL_WAIT_PAPER.rawValue) {
            errMsg += NSLocalizedString("err_wait_removal", comment:"")
            errCode = "ERR_WAIT_REMOVAL"
        }
        if (status.unrecoverError == EPOS2_HIGH_VOLTAGE_ERR.rawValue ||
            status.unrecoverError == EPOS2_LOW_VOLTAGE_ERR.rawValue) {
            errMsg += NSLocalizedString("err_voltage", comment:"");
            errCode = "ERR_VOLTAGE"
        }
    
        if errMsg.isEmpty {
            errCode = "ERR_UNKNOWN"
        }
    
        return ["message": errMsg, "code": errCode]
    }
}
