//
//  MessageHelper.swift
//  epson_epos
//
//  Created by Thomas on 08/08/2024.
//

import Foundation

class MessageHelper {
    class func errorEpos(_ resultCode: Int32, method: String) -> String {
        let msg = String(format: "%@\n%@\n\n%@\n%@\n",
                         NSLocalizedString("methoderr_errcode", comment:""),
                         getEposErrorText(resultCode),
                         NSLocalizedString("methoderr_method", comment:""),
                         method)
        return msg
    }
    
    class func errorEposBt(_ resultCode: Int32, method: String) -> String {
        let msg = String(format: "%@\n%@\n\n%@\n%@\n",
                         NSLocalizedString("methoderr_errcode", comment:""),
                         getEposBtErrorText(resultCode),
                         NSLocalizedString("methoderr_method", comment:""),
                         method)
        return msg
    }
    
    class func result(_ code: Int32, errMessage: String) -> String {
        var msg: String = ""
        
        if errMessage.isEmpty {
            msg = String(format:"%@\n%@\n",
                         NSLocalizedString("statusmsg_result", comment: ""),
                         getEposResultText(code))
        }
        else {
            msg = String(format:"%@\n%@\n\n%@\n%@\n",
                         NSLocalizedString("statusmsg_result", comment: ""),
                         getEposResultText(code),
                         NSLocalizedString("statusmsg_description", comment: ""),
                         errMessage)
        }
        
        return msg
    }
    
    class fileprivate func getEposErrorText(_ error : Int32) -> String {
        var errText = ""
        switch (error) {
        case EPOS2_SUCCESS.rawValue:
            errText = "SUCCESS"
            break
        case EPOS2_ERR_PARAM.rawValue:
            errText = "ERR_PARAM"
            break
        case EPOS2_ERR_CONNECT.rawValue:
            errText = "ERR_CONNECT"
            break
        case EPOS2_ERR_TIMEOUT.rawValue:
            errText = "ERR_TIMEOUT"
            break
        case EPOS2_ERR_MEMORY.rawValue:
            errText = "ERR_MEMORY"
            break
        case EPOS2_ERR_ILLEGAL.rawValue:
            errText = "ERR_ILLEGAL"
            break
        case EPOS2_ERR_PROCESSING.rawValue:
            errText = "ERR_PROCESSING"
            break
        case EPOS2_ERR_NOT_FOUND.rawValue:
            errText = "ERR_NOT_FOUND"
            break
        case EPOS2_ERR_IN_USE.rawValue:
            errText = "ERR_IN_USE"
            break
        case EPOS2_ERR_TYPE_INVALID.rawValue:
            errText = "ERR_TYPE_INVALID"
            break
        case EPOS2_ERR_DISCONNECT.rawValue:
            errText = "ERR_DISCONNECT"
            break
        case EPOS2_ERR_ALREADY_OPENED.rawValue:
            errText = "ERR_ALREADY_OPENED"
            break
        case EPOS2_ERR_ALREADY_USED.rawValue:
            errText = "ERR_ALREADY_USED"
            break
        case EPOS2_ERR_BOX_COUNT_OVER.rawValue:
            errText = "ERR_BOX_COUNT_OVER"
            break
        case EPOS2_ERR_BOX_CLIENT_OVER.rawValue:
            errText = "ERR_BOXT_CLIENT_OVER"
            break
        case EPOS2_ERR_UNSUPPORTED.rawValue:
            errText = "ERR_UNSUPPORTED"
            break
        case EPOS2_ERR_FAILURE.rawValue:
            errText = "ERR_FAILURE"
            break
        default:
            errText = String(format:"%d", error)
            break
        }
        return errText
    }
    
    class fileprivate func getEposBtErrorText(_ error : Int32) -> String {
        var errText = ""
        switch (error) {
        case EPOS2_BT_SUCCESS.rawValue:
            errText = "SUCCESS"
            break
        case EPOS2_BT_ERR_PARAM.rawValue:
            errText = "ERR_PARAM"
            break
        case EPOS2_BT_ERR_UNSUPPORTED.rawValue:
            errText = "ERR_UNSUPPORTED"
            break
        case EPOS2_BT_ERR_CANCEL.rawValue:
            errText = "ERR_CANCEL"
            break
        case EPOS2_BT_ERR_ALREADY_CONNECT.rawValue:
            errText = "ERR_ALREADY_CONNECT"
            break;
        case EPOS2_BT_ERR_ILLEGAL_DEVICE.rawValue:
            errText = "ERR_ILLEGAL_DEVICE"
            break
        case EPOS2_BT_ERR_FAILURE.rawValue:
            errText = "ERR_FAILURE"
            break
        default:
            errText = String(format:"%d", error)
            break
        }
        return errText
    }
    
    class fileprivate func getEposResultText(_ resultCode : Int32) -> String {
        var result = ""
        switch (resultCode) {
        case EPOS2_CODE_SUCCESS.rawValue:
            result = "PRINT_SUCCESS"
            break
        case EPOS2_CODE_PRINTING.rawValue:
            result = "Printing job is in progress."
            break
        case EPOS2_CODE_ERR_AUTORECOVER.rawValue:
            result = "Automatic recovery error occurred."
            break
        case EPOS2_CODE_ERR_COVER_OPEN.rawValue:
            result = "Please close roll paper cover."
            break
        case EPOS2_CODE_ERR_CUTTER.rawValue:
            result = "Please remove jammed paper and close roll paper cover.\nRemove any jammed paper or foreign substances in the printer, and then turn the printer off and turn the printer on again."
            break
        case EPOS2_CODE_ERR_MECHANICAL.rawValue:
            result = "Mechanical error occurred."
            break
        case EPOS2_CODE_ERR_EMPTY.rawValue:
            result = "Please check roll paper."
            break
        case EPOS2_CODE_ERR_UNRECOVERABLE.rawValue:
            result = "Please cycle the power switch of the printer.\nIf same errors occurred even power cycled, the printer may be out of order."
            break
        case EPOS2_CODE_ERR_FAILURE.rawValue:
            result = "Print job failed."
            break
        case EPOS2_CODE_ERR_NOT_FOUND.rawValue:
            result = "Printer not found."
            break
        case EPOS2_CODE_ERR_SYSTEM.rawValue:
            result = "System error occurred."
            break
        case EPOS2_CODE_ERR_PORT.rawValue:
            result = "Port error occurred. Please check the connection."
            break
        case EPOS2_CODE_ERR_TIMEOUT.rawValue:
            result = "Please check the connection of the printer and the mobile terminal.\nConnection timeout."
            break
        case EPOS2_CODE_ERR_JOB_NOT_FOUND.rawValue:
            result = "Print job not found."
            break
        case EPOS2_CODE_ERR_SPOOLER.rawValue:
            result = "Spooler error occurred."
            break
        case EPOS2_CODE_ERR_BATTERY_LOW.rawValue:
            result = "Battery of printer is almost empty."
            break
        default:
            result = String(format:"Print result error: %d", resultCode)
            break
        }
        
        return result;
    }
}
