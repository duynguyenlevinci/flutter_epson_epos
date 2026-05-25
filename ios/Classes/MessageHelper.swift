//
//  MessageHelper.swift
//  epson_epos
//
//  Created by Thomas on 08/08/2024.
//

import Foundation

class MessageHelper {
    class func errorEpos(_ resultCode: Int32, method: String) -> String {
        return String(
            format: "%@\n%@\n\n%@\n%@\n",
            NSLocalizedString("methoderr_errcode", comment: ""),
            getEposErrorText(resultCode),
            NSLocalizedString("methoderr_method", comment: ""),
            method
        )
    }

    class func errorEposBt(_ resultCode: Int32, method: String) -> String {
        return String(
            format: "%@\n%@\n\n%@\n%@\n",
            NSLocalizedString("methoderr_errcode", comment: ""),
            getEposBtErrorText(resultCode),
            NSLocalizedString("methoderr_method", comment: ""),
            method
        )
    }

    class func result(_ code: Int32, errMessage: String) -> String {
        if errMessage.isEmpty {
            return String(
                format: "%@\n%@\n",
                NSLocalizedString("statusmsg_result", comment: ""),
                getEposResultText(code)
            )
        }
        return String(
            format: "%@\n%@\n\n%@\n%@\n",
            NSLocalizedString("statusmsg_result", comment: ""),
            getEposResultText(code),
            NSLocalizedString("statusmsg_description", comment: ""),
            errMessage
        )
    }

    fileprivate class func getEposErrorText(_ error: Int32) -> String {
        switch error {
        case EPOS2_SUCCESS.rawValue: return "SUCCESS"
        case EPOS2_ERR_PARAM.rawValue: return "ERR_PARAM"
        case EPOS2_ERR_CONNECT.rawValue: return "ERR_CONNECT"
        case EPOS2_ERR_TIMEOUT.rawValue: return "ERR_TIMEOUT"
        case EPOS2_ERR_MEMORY.rawValue: return "ERR_MEMORY"
        case EPOS2_ERR_ILLEGAL.rawValue: return "ERR_ILLEGAL"
        case EPOS2_ERR_PROCESSING.rawValue: return "ERR_PROCESSING"
        case EPOS2_ERR_NOT_FOUND.rawValue: return "ERR_NOT_FOUND"
        case EPOS2_ERR_IN_USE.rawValue: return "ERR_IN_USE"
        case EPOS2_ERR_TYPE_INVALID.rawValue: return "ERR_TYPE_INVALID"
        case EPOS2_ERR_DISCONNECT.rawValue: return "ERR_DISCONNECT"
        case EPOS2_ERR_ALREADY_OPENED.rawValue: return "ERR_ALREADY_OPENED"
        case EPOS2_ERR_ALREADY_USED.rawValue: return "ERR_ALREADY_USED"
        case EPOS2_ERR_BOX_COUNT_OVER.rawValue: return "ERR_BOX_COUNT_OVER"
        case EPOS2_ERR_BOX_CLIENT_OVER.rawValue: return "ERR_BOX_CLIENT_OVER"
        case EPOS2_ERR_UNSUPPORTED.rawValue: return "ERR_UNSUPPORTED"
        case EPOS2_ERR_FAILURE.rawValue: return "ERR_FAILURE"
        default: return String(format: "%d", error)
        }
    }

    fileprivate class func getEposBtErrorText(_ error: Int32) -> String {
        switch error {
        case EPOS2_BT_SUCCESS.rawValue: return "SUCCESS"
        case EPOS2_BT_ERR_PARAM.rawValue: return "ERR_PARAM"
        case EPOS2_BT_ERR_UNSUPPORTED.rawValue: return "ERR_UNSUPPORTED"
        case EPOS2_BT_ERR_CANCEL.rawValue: return "ERR_CANCEL"
        case EPOS2_BT_ERR_ALREADY_CONNECT.rawValue: return "ERR_ALREADY_CONNECT"
        case EPOS2_BT_ERR_ILLEGAL_DEVICE.rawValue: return "ERR_ILLEGAL_DEVICE"
        case EPOS2_BT_ERR_FAILURE.rawValue: return "ERR_FAILURE"
        default: return String(format: "%d", error)
        }
    }

    fileprivate class func getEposResultText(_ resultCode: Int32) -> String {
        switch resultCode {
        case EPOS2_CODE_SUCCESS.rawValue:
            return "PRINT_SUCCESS"
        case EPOS2_CODE_PRINTING.rawValue:
            return "Printing job is in progress."
        case EPOS2_CODE_ERR_AUTORECOVER.rawValue:
            return "Automatic recovery error occurred."
        case EPOS2_CODE_ERR_COVER_OPEN.rawValue:
            return "Please close roll paper cover."
        case EPOS2_CODE_ERR_CUTTER.rawValue:
            return "Please remove jammed paper and close roll paper cover.\nRemove any jammed paper or foreign substances in the printer, and then turn the printer off and turn the printer on again."
        case EPOS2_CODE_ERR_MECHANICAL.rawValue:
            return "Mechanical error occurred."
        case EPOS2_CODE_ERR_EMPTY.rawValue:
            return "Please check roll paper."
        case EPOS2_CODE_ERR_UNRECOVERABLE.rawValue:
            return "Please cycle the power switch of the printer.\nIf same errors occurred even power cycled, the printer may be out of order."
        case EPOS2_CODE_ERR_FAILURE.rawValue:
            return "Print job failed."
        case EPOS2_CODE_ERR_NOT_FOUND.rawValue:
            return "Printer not found."
        case EPOS2_CODE_ERR_SYSTEM.rawValue:
            return "System error occurred."
        case EPOS2_CODE_ERR_PORT.rawValue:
            return "Port error occurred. Please check the connection."
        case EPOS2_CODE_ERR_TIMEOUT.rawValue:
            return "Please check the connection of the printer and the mobile terminal.\nConnection timeout."
        case EPOS2_CODE_ERR_JOB_NOT_FOUND.rawValue:
            return "Print job not found."
        case EPOS2_CODE_ERR_SPOOLER.rawValue:
            return "Spooler error occurred."
        case EPOS2_CODE_ERR_BATTERY_LOW.rawValue:
            return "Battery of printer is almost empty."
        default:
            return String(format: "Print result error: %d", resultCode)
        }
    }
}
