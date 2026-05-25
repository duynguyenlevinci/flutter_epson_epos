//
//  CommandsGenerator.swift
//  epson_epos
//
//  Created by Thomas on 11/08/2024.
//

import Foundation

public class CommandGenerator: NSObject {
    func onGenerateCommandFor(printer: Epos2Printer?, command: [String: Any]) {
        guard let printer = printer else { return }
        
        guard let commandId = command["id"] as? String, commandId.isEmpty == false else {
            return
        }
        
        let commandValue = command["value"]
        
        switch commandId {
        case "appendText":
            guard let commandValue = commandValue as? String else { return }
            printer.addText(commandValue)
            
        case "printRawData":
            guard let commandValue = commandValue as? FlutterStandardTypedData else { return }
            let data = Data(commandValue.data)
            printer.addCommand(data)
            
        case "addImage":
            guard let commandValue = commandValue as? String,
                  let width = command["width"] as? Int,
                  let height = command["height"] as? Int,
                  let posX = command["posX"] as? Int,
                  let posY = command["posY"] as? Int,
                  let bitmap = convertBase64ToImage(commandValue) else { return }
            printer.add(
                bitmap,
                x: posX,
                y: posY,
                width: width,
                height: height,
                color: EPOS2_COLOR_1.rawValue,
                mode: EPOS2_MODE_MONO.rawValue,
                halftone: EPOS2_HALFTONE_DITHER.rawValue,
                brightness: Double(EPOS2_PARAM_DEFAULT),
                compress: EPOS2_COMPRESS_AUTO.rawValue
            )
            
        case "addFeedLine":
            guard let commandValue = commandValue as? Int else { return }
            printer.addFeedLine(commandValue)
            
        case "addCut":
            guard let commandValue = commandValue as? String else { return }
            switch commandValue {
            case "CUT_FEED":
                printer.addCut(EPOS2_CUT_FEED.rawValue)
            case "CUT_NO_FEED":
                printer.addCut(EPOS2_CUT_NO_FEED.rawValue)
            case "CUT_RESERVE":
                printer.addCut(EPOS2_CUT_RESERVE.rawValue)
            default:
                printer.addCut(EPOS2_PARAM_DEFAULT)
            }
            
        case "addLineSpace":
            guard let commandValue = commandValue as? Int else { return }
            printer.addFeedLine(commandValue)
            
        case "addTextAlign":
            guard let commandValue = commandValue as? String else { return }
            switch commandValue {
            case "LEFT":
                printer.addTextAlign(EPOS2_ALIGN_LEFT.rawValue)
                
            case "CENTER":
                printer.addTextAlign(EPOS2_ALIGN_CENTER.rawValue)
                
            case "RIGHT":
                printer.addTextAlign(EPOS2_ALIGN_RIGHT.rawValue)
                
            default:
                printer.addTextAlign(EPOS2_PARAM_DEFAULT)
            }
            
        case "addTextFont":
            guard let commandValue = commandValue as? String else { return }
            switch commandValue {
            case "FONT_A":
                printer.addTextFont(EPOS2_FONT_A.rawValue)
                
            case "FONT_B":
                printer.addTextFont(EPOS2_FONT_B.rawValue)
                
            case "FONT_C":
                printer.addTextFont(EPOS2_FONT_C.rawValue)
                
            case "FONT_D":
                printer.addTextFont(EPOS2_FONT_D.rawValue)
                
            case "FONT_E":
                printer.addTextFont(EPOS2_FONT_E.rawValue)
            default:
                break
            }
            
        case "addTextSmooth":
            guard let commandValue = commandValue as? Bool else { return }
            if commandValue {
                printer.addTextSmooth(EPOS2_TRUE)
            } else {
                printer.addTextSmooth(EPOS2_FALSE)
            }
            
        case "addTextSize":
            guard let width = command["width"] as? Int, let height = command["height"] as? Int else { return }
            //                Log.d(logTag, "setTextSize: width: $width, height: $height")
            printer.addTextSize(width, height: height)
            
        case "addTextStyle":
            let reverseValue: Int32 = (command["reverse"] as? Bool).map { $0 ? EPOS2_TRUE : EPOS2_FALSE } ?? EPOS2_PARAM_DEFAULT
            let ulValue: Int32 = (command["ul"] as? Bool).map { $0 ? EPOS2_TRUE : EPOS2_FALSE } ?? EPOS2_PARAM_DEFAULT
            let emValue: Int32 = (command["em"] as? Bool).map { $0 ? EPOS2_TRUE : EPOS2_FALSE } ?? EPOS2_PARAM_DEFAULT
            
            let colorValue: Int32
            switch command["color"] as? String {
            case "COLOR_NONE": colorValue = EPOS2_COLOR_NONE.rawValue
            case "COLOR_1":    colorValue = EPOS2_COLOR_1.rawValue
            case "COLOR_2":    colorValue = EPOS2_COLOR_2.rawValue
            case "COLOR_3":    colorValue = EPOS2_COLOR_3.rawValue
            case "COLOR_4":    colorValue = EPOS2_COLOR_4.rawValue
            default:           colorValue = EPOS2_PARAM_DEFAULT
            }
            
            printer.addTextStyle(reverseValue, ul: ulValue, em: emValue, color: colorValue)
            
        case "addBarcode":
            let barcodeWidth = command["width"] as? Int ?? 2
            let barcodeHeight = command["height"] as? Int ?? 100
            let barcode = command["barcode"] as? String ?? ""
            let type: Int32 = (command["type"] as? Int).map { Int32($0) } ?? EPOS2_BARCODE_EAN13.rawValue
            let textPosition: Int32 = (command["position"] as? Int).map { Int32($0) } ?? EPOS2_HRI_BELOW.rawValue
            
            let font: Int32
            switch command["font"] as? String {
            case "FONT_A": font = EPOS2_FONT_A.rawValue
            case "FONT_B": font = EPOS2_FONT_B.rawValue
            case "FONT_C": font = EPOS2_FONT_C.rawValue
            case "FONT_D": font = EPOS2_FONT_D.rawValue
            case "FONT_E": font = EPOS2_FONT_E.rawValue
            default:       font = EPOS2_FONT_A.rawValue
            }
            
            printer.addBarcode(
                barcode,
                type: type,
                hri: textPosition,
                font: font,
                width: barcodeWidth,
                height: barcodeHeight
            )
        case "addPageBegin":
            printer.addPageBegin()
            
        case "addPageArea":
            guard let commandValue = commandValue as? Dictionary<String, Any>,
                  let x = commandValue["x"] as? Int,
                  let y = commandValue["y"] as? Int,
                  let w = commandValue["w"] as? Int,
                  let h = commandValue["h"] as? Int else { return }
            printer.addPageArea(x, y: y, width: w, height: h)
            
        case "addPageEnd":
            printer.addPageEnd()
            
        case "addPagePosition":
            guard let commandValue = commandValue as? Dictionary<String, Any>,
                  let x = commandValue["x"] as? Int,
                  let y = commandValue["y"] as? Int else { return }
            printer.addPagePosition(x, y: y)
        case "addKick":
            printer.addPulse(EPOS2_DRAWER_2PIN.rawValue, time: EPOS2_PULSE_200.rawValue * 100 / 100)

        default:
            print("Command not supported \(commandId)")
            break
        }
    }
}

private extension CommandGenerator {
    func convertBase64ToImage(_ base64String: String) -> UIImage? {
        guard let imageData = Data(base64Encoded: base64String) else {
            return nil
        }
        return UIImage(data: imageData)
    }
}
