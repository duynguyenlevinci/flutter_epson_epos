//
//  MessageHelper.swift
//  epson_epos
//
//  Created by Thomas on 08/08/2024.
//

import Foundation

/// In-memory replacement for the plugin's `.strings` table. The previous
/// `ePOS2Localizable.strings` file was both malformed (wrapped in `{}`) and
/// shipped under a non-default table, so every `NSLocalizedString(key, ...)`
/// call returned the key itself. Keep the translations here so the plugin is
/// self-contained and does not depend on the Pod resource bundle layout.
internal func eposLocalizedString(_ key: String) -> String {
    return EposLocalizedStrings.values[key] ?? key
}

internal enum EposLocalizedStrings {
    static let values: [String: String] = [
        "methoderr_errcode": "Error Code",
        "methoderr_method": "Method",
        "statusmsg_result": "Result",
        "statusmsg_description": "Description",

        "warn_receipt_near_end": "Roll paper is nearly end.\n",
        "warn_battery_near_end": "Battery level of printer is low.\n",
        "warn_detect_paper": "Please take the receipt.\n",
        "warn_detect_unknown": "Please check if no ambient light reaches the paper outlet and that the paper-taken sensor in the printer is enabled.\n",

        "err_offline": "Printer is offline.\n",
        "err_no_response": "Please check the connection of the printer and the mobile terminal. Connection lost.\n",
        "err_cover_open": "Please close the roll paper cover.\n",
        "err_paper_feed": "Please release the paper feed switch.\n",
        "err_autocutter": "Please remove jammed paper and close the roll paper cover. Remove any jammed paper or foreign substances in the printer, then power-cycle the printer.\n",
        "err_need_recover": "Then, if the printer doesn't recover from the error, please cycle the power switch.\n",
        "err_unrecover": "Please cycle the power switch of the printer. If the same error occurs after power-cycling, the printer may be out of order.",
        "err_receipt_end": "Please check the roll paper.\n",
        "err_battery": "Battery of the printer is hot.\n",
        "err_overheat": "Please wait until the error LED of the printer turns off.\n",
        "err_head": "Print head of the printer is hot.\n",
        "err_motor": "Motor Driver IC of the printer is hot.\n",
        "err_wrong_paper": "Please set the correct roll paper.\n",
        "err_battery_real_end": "Please connect AC adapter or change the battery. Battery of the printer is almost empty.\n",
        "err_wait_removal": "Please remove the paper.\n",
        "err_voltage": "Please check the voltage status.\n",
        "wait": "Please wait...",

        "error_missing_print_data": "Please provide all print data",
        "error_not_support_printer": "Printer not supported",
    ]
}

class MessageHelper {
    /// Epson error code name (e.g. `ERR_CONNECT`) for `content` / logging.
    class func apiErrorCodeName(_ resultCode: Int32) -> String {
        return getEposErrorCodeName(resultCode)
    }

    /// Human-readable API error for Flutter `message`. Raw SDK code lives in `code`.
    class func apiErrorMessage(_ resultCode: Int32) -> String {
        return getEposErrorDescription(resultCode)
    }

    class func btApiErrorMessage(_ resultCode: Int32) -> String {
        return getEposBtErrorDescription(resultCode)
    }

    class func errorEpos(_ resultCode: Int32, method: String) -> String {
        return String(
            format: "%@\n%@\n\n%@\n%@\n",
            eposLocalizedString("methoderr_errcode"),
            getEposErrorText(resultCode),
            eposLocalizedString("methoderr_method"),
            method
        )
    }

    class func errorEposBt(_ resultCode: Int32, method: String) -> String {
        return String(
            format: "%@\n%@\n\n%@\n%@\n",
            eposLocalizedString("methoderr_errcode"),
            getEposBtErrorText(resultCode),
            eposLocalizedString("methoderr_method"),
            method
        )
    }

    class func result(_ code: Int32, errMessage: String) -> String {
        if errMessage.isEmpty {
            return String(
                format: "%@\n%@\n",
                eposLocalizedString("statusmsg_result"),
                getEposResultText(code)
            )
        }
        return String(
            format: "%@\n%@\n\n%@\n%@\n",
            eposLocalizedString("statusmsg_result"),
            getEposResultText(code),
            eposLocalizedString("statusmsg_description"),
            errMessage
        )
    }

    fileprivate class func getEposErrorText(_ error: Int32) -> String {
        return getEposErrorDescription(error)
    }

    fileprivate class func getEposErrorCodeName(_ error: Int32) -> String {
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
        case EPOS2_ERR_DEVICE_BUSY.rawValue: return "ERR_DEVICE_BUSY"
        case EPOS2_ERR_RECOVERY_FAILURE.rawValue: return "ERR_RECOVERY_FAILURE"
        case EPOS2_ERR_FAILURE.rawValue: return "ERR_FAILURE"
        default: return "ERR_UNKNOWN"
        }
    }

    fileprivate class func getEposErrorDescription(_ error: Int32) -> String {
        switch error {
        case EPOS2_SUCCESS.rawValue:
            return "Operation completed successfully."
        case EPOS2_ERR_PARAM.rawValue:
            return "Invalid parameter. Check printer series, target address, or command data."
        case EPOS2_ERR_CONNECT.rawValue:
            return "Please check the connection of the printer."
        case EPOS2_ERR_TIMEOUT.rawValue:
            return "Connection timed out. The printer may be unreachable, offline, or busy."
        case EPOS2_ERR_MEMORY.rawValue:
            return "Not enough memory on the device to complete this operation."
        case EPOS2_ERR_ILLEGAL.rawValue:
            return "Operation is not allowed in the current printer or SDK state."
        case EPOS2_ERR_PROCESSING.rawValue:
            return "Another operation is still in progress. Wait and try again."
        case EPOS2_ERR_NOT_FOUND.rawValue:
            return "Printer not found. Run discovery again or verify the target address."
        case EPOS2_ERR_IN_USE.rawValue:
            return "Printer is already in use by another connection or application."
        case EPOS2_ERR_TYPE_INVALID.rawValue:
            return "Invalid printer type or connection type for this operation."
        case EPOS2_ERR_DISCONNECT.rawValue:
            return "Failed to disconnect from the printer. Check the connection status."
        case EPOS2_ERR_ALREADY_OPENED.rawValue:
            return "Printer connection is already open."
        case EPOS2_ERR_ALREADY_USED.rawValue:
            return "The requested printer resource is already in use."
        case EPOS2_ERR_BOX_COUNT_OVER.rawValue:
            return "Communication box count limit exceeded."
        case EPOS2_ERR_BOX_CLIENT_OVER.rawValue:
            return "Communication box client limit exceeded."
        case EPOS2_ERR_UNSUPPORTED.rawValue:
            return "This operation or feature is not supported on the selected printer."
        case EPOS2_ERR_DEVICE_BUSY.rawValue:
            return "Printer is busy. Retry after the current job finishes."
        case EPOS2_ERR_RECOVERY_FAILURE.rawValue:
            return "Automatic error recovery failed on the printer."
        case EPOS2_ERR_FAILURE.rawValue:
            return "An unknown printer error occurred."
        default:
            return "Unknown error (code \(error))."
        }
    }

    fileprivate class func getEposBtErrorText(_ error: Int32) -> String {
        return getEposBtErrorDescription(error)
    }

    fileprivate class func getEposBtErrorCodeName(_ error: Int32) -> String {
        switch error {
        case EPOS2_BT_SUCCESS.rawValue: return "SUCCESS"
        case EPOS2_BT_ERR_PARAM.rawValue: return "ERR_PARAM"
        case EPOS2_BT_ERR_UNSUPPORTED.rawValue: return "ERR_UNSUPPORTED"
        case EPOS2_BT_ERR_CANCEL.rawValue: return "ERR_CANCEL"
        case EPOS2_BT_ERR_ALREADY_CONNECT.rawValue: return "ERR_ALREADY_CONNECT"
        case EPOS2_BT_ERR_ILLEGAL_DEVICE.rawValue: return "ERR_ILLEGAL_DEVICE"
        case EPOS2_BT_ERR_FAILURE.rawValue: return "ERR_FAILURE"
        default: return "ERR_UNKNOWN"
        }
    }

    fileprivate class func getEposBtErrorDescription(_ error: Int32) -> String {
        switch error {
        case EPOS2_BT_SUCCESS.rawValue:
            return "Bluetooth operation completed successfully."
        case EPOS2_BT_ERR_PARAM.rawValue:
            return "Invalid Bluetooth parameter."
        case EPOS2_BT_ERR_UNSUPPORTED.rawValue:
            return "Bluetooth operation is not supported on this device."
        case EPOS2_BT_ERR_CANCEL.rawValue:
            return "Bluetooth pairing or connection was cancelled."
        case EPOS2_BT_ERR_ALREADY_CONNECT.rawValue:
            return "Bluetooth device is already connected."
        case EPOS2_BT_ERR_ILLEGAL_DEVICE.rawValue:
            return "The selected Bluetooth device is not a supported Epson printer."
        case EPOS2_BT_ERR_FAILURE.rawValue:
            return "Bluetooth connection failed."
        default:
            return "Unknown Bluetooth error (code \(error))."
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
