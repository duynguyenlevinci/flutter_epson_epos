//
//  EpsonStatusCode.swift
//  epson_epos
//
//  Unified status codes returned to the Flutter side.
//  MUST stay in sync with:
//   - android: EpsonStatusCode (Kotlin)
//   - dart:    EpsonStatusCode (lib/models.dart)
//

import Foundation

@objc public class EpsonStatusCode: NSObject {
    @objc public static let success: Int = 0
    @objc public static let printing: Int = 1

    // Connection (100-199)
    @objc public static let errConnect: Int = 100
    @objc public static let errDisconnect: Int = 101
    @objc public static let errOffline: Int = 102
    @objc public static let errNoResponse: Int = 103
    @objc public static let errTimeout: Int = 104
    @objc public static let errPort: Int = 105
    @objc public static let errNotFound: Int = 106

    // Paper (200-299)
    @objc public static let errReceiptEnd: Int = 200
    @objc public static let errPaperFeed: Int = 201
    @objc public static let errWrongPaper: Int = 202
    @objc public static let errEmpty: Int = 203

    // Cover / cutter (300-399)
    @objc public static let errCoverOpen: Int = 300
    @objc public static let errAutocutter: Int = 301
    @objc public static let errCutter: Int = 302
    @objc public static let errMechanical: Int = 303

    // Hardware health (400-499)
    @objc public static let errOverheatHead: Int = 400
    @objc public static let errOverheatMotor: Int = 401
    @objc public static let errOverheatBattery: Int = 402
    @objc public static let errBatteryEnd: Int = 403
    @objc public static let errUnrecover: Int = 404
    @objc public static let errAutorecover: Int = 405
    @objc public static let errVoltage: Int = 406
    @objc public static let errWaitRemoval: Int = 407

    // System (500-599)
    @objc public static let errFailure: Int = 500
    @objc public static let errSystem: Int = 501
    @objc public static let errParam: Int = 502
    @objc public static let errProcessing: Int = 503

    @objc public static let unknown: Int = 999

    /// Map an Epos2 generic API result (`EPOS2_SUCCESS`, `EPOS2_ERR_*`) to the unified status code.
    @objc public static func fromEposApi(_ resultCode: Int32) -> Int {
        switch resultCode {
        case EPOS2_SUCCESS.rawValue:        return success
        case EPOS2_ERR_PARAM.rawValue:      return errParam
        case EPOS2_ERR_CONNECT.rawValue:    return errConnect
        case EPOS2_ERR_TIMEOUT.rawValue:    return errTimeout
        case EPOS2_ERR_MEMORY.rawValue:     return errSystem
        case EPOS2_ERR_ILLEGAL.rawValue:    return errFailure
        case EPOS2_ERR_PROCESSING.rawValue: return errProcessing
        case EPOS2_ERR_NOT_FOUND.rawValue:  return errNotFound
        case EPOS2_ERR_IN_USE.rawValue:     return errFailure
        case EPOS2_ERR_TYPE_INVALID.rawValue: return errParam
        case EPOS2_ERR_DISCONNECT.rawValue: return errDisconnect
        case EPOS2_ERR_FAILURE.rawValue:    return errFailure
        default: return unknown
        }
    }

    /// Map an Epos2 print-job callback result (`EPOS2_CODE_*`) to the unified status code.
    @objc public static func fromEposPrintCode(_ resultCode: Int32) -> Int {
        switch resultCode {
        case EPOS2_CODE_SUCCESS.rawValue:           return success
        case EPOS2_CODE_PRINTING.rawValue:          return printing
        case EPOS2_CODE_ERR_AUTORECOVER.rawValue:   return errAutorecover
        case EPOS2_CODE_ERR_COVER_OPEN.rawValue:    return errCoverOpen
        case EPOS2_CODE_ERR_CUTTER.rawValue:        return errCutter
        case EPOS2_CODE_ERR_MECHANICAL.rawValue:    return errMechanical
        case EPOS2_CODE_ERR_EMPTY.rawValue:         return errEmpty
        case EPOS2_CODE_ERR_UNRECOVERABLE.rawValue: return errUnrecover
        case EPOS2_CODE_ERR_FAILURE.rawValue:       return errFailure
        case EPOS2_CODE_ERR_NOT_FOUND.rawValue:     return errNotFound
        case EPOS2_CODE_ERR_SYSTEM.rawValue:        return errSystem
        case EPOS2_CODE_ERR_PORT.rawValue:          return errPort
        case EPOS2_CODE_ERR_TIMEOUT.rawValue:       return errTimeout
        case EPOS2_CODE_ERR_BATTERY_LOW.rawValue:   return errBatteryEnd
        default: return unknown
        }
    }
}
