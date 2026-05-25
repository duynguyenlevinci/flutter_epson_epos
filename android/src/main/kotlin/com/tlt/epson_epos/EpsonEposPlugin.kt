package com.tlt.epson_epos

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.epson.epos2.Epos2Exception
import com.epson.epos2.discovery.Discovery
import com.epson.epos2.discovery.DiscoveryListener
import com.epson.epos2.discovery.FilterOption
import com.epson.epos2.printer.Printer
import com.epson.epos2.printer.PrinterSettingListener
import com.epson.epos2.printer.PrinterStatusInfo
import com.epson.epos2.printer.ReceiveListener
import com.google.gson.Gson
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import com.epson.epos2.Log as PrintLog


interface JSONConvertable {
    fun toJSON(): String = Gson().toJson(this)
}

inline fun <reified T : JSONConvertable> String.toObject(): T = Gson().fromJson(this, T::class.java)

class EpsonEposPrinterInfo(
    var ipAddress: String? = null,
    var bdAddress: String? = null,
    var macAddress: String? = null,
    var model: String? = null,
    var type: String? = null,
    var printType: String? = null,
    var target: String? = null
) : JSONConvertable

data class EpsonEposPrinterResult(
    var type: String,
    var success: Boolean,
    var message: String? = null,
    var code: Int? = null,
    var content: Any? = null
) : JSONConvertable

/** EpsonEposPlugin */
class EpsonEposPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private val logTag: String = "Epson_ePOS"
    private lateinit var context: Context
    private var activity: Activity? = null
    private var mPrinter: Printer? = null
    private var mTarget: String? = null
    private val printers: MutableList<EpsonEposPrinterInfo> = mutableListOf()

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "epson_epos")
        channel.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
        PrintLog.setLogSettings(
            context,
            PrintLog.PERIOD_TEMPORARY,
            PrintLog.OUTPUT_STORAGE,
            null,
            0,
            1,
            PrintLog.LOGLEVEL_LOW
        )
    }

    override fun onMethodCall(call: MethodCall, rawResult: Result) {
        val result = MethodResultWrapper(rawResult)
        Thread(MethodRunner(call, result)).start()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    inner class MethodRunner(
        private val call: MethodCall,
        private val result: Result
    ) : Runnable, ReceiveListener {
        private var printType: String = ""

        fun setPrintType(type: String) {
            this.printType = type
        }

        override fun onPtrReceive(p0: Printer?, p1: Int, p2: PrinterStatusInfo?, p3: String?) {
            val resp = EpsonEposPrinterResult("onPrint${printType}", false)
            resp.code = p1

            // Always prioritize checking printer status for specific hardware errors
            // (out of paper, cover open, etc.)
            val errorFromStatus = if (p2 != null) printerStatusError(p2) else null
            val hasSpecificError = errorFromStatus != null && errorFromStatus["code"] != "ERR_UNKNOWN"

            if (p1 == 0 && !hasSpecificError) {
                resp.success = true
                resp.message = "Success"
            } else {
                resp.success = false

                if (hasSpecificError) {
                    resp.message = errorFromStatus!!["message"] as String
                    resp.content = errorFromStatus["code"] as String
                } else {
                    val callbackErrorKey = getCallbackErrorCode(p1)
                    resp.message = getErrorMessage(callbackErrorKey.lowercase())
                    resp.content = callbackErrorKey
                }
            }

            Log.d(logTag, "onPtrReceive: Code $p1, Status $p2, Message ${resp.message}")
            result.success(resp.toJSON())
            disconnectPrinter()
        }

        private fun getCallbackErrorCode(code: Int): String {
            return when (code) {
                0 -> "CODE_SUCCESS"
                1 -> "CODE_PRINTING"
                2 -> "ERR_AUTORECOVER"
                3 -> "ERR_COVER_OPEN"
                4 -> "ERR_CUTTER"
                5 -> "ERR_MECHANICAL"
                6 -> "ERR_EMPTY"
                7 -> "ERR_UNRECOVERABLE"
                8 -> "ERR_FAILURE"
                9 -> "ERR_NOT_FOUND"
                10 -> "ERR_SYSTEM"
                11 -> "ERR_PORT"
                12 -> "ERR_TIMEOUT"
                else -> "ERR_UNKNOWN"
            }
        }

        override fun run() {
            Log.d(logTag, "Method Called: ${call.method}")
            when (call.method) {
                "onDiscovery" -> onDiscovery(call, result)
                "onPrint" -> onPrint(call, result, this)
                "onGetPrinterInfo" -> onGetPrinterInfo(call, result)
                "isPrinterConnected" -> isPrinterConnected(call, result)
                "getPrinterSetting" -> getPrinterSetting(call, result)
                "setPrinterSetting" -> setPrinterSetting(call, result)
                "requestRuntimePermission" -> requestRuntimePermission(call, result)
                else -> {
                    Log.d(logTag, "Method: ${call.method} is not supported yet")
                    result.notImplemented()
                }
            }
        }
    }

    class MethodResultWrapper(private val methodResult: Result) : Result {
        private val handler: Handler = Handler(Looper.getMainLooper())

        override fun success(result: Any?) {
            handler.post { methodResult.success(result) }
        }

        override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
            handler.post { methodResult.error(errorCode, errorMessage, errorDetails) }
        }

        override fun notImplemented() {
            handler.post { methodResult.notImplemented() }
        }
    }

    /**
     * Stop discovery printer
     */
    private fun stopDiscovery() {
        try {
            Discovery.stop()
        } catch (e: Epos2Exception) {
            if (e.errorStatus != Epos2Exception.ERR_PROCESSING) {
                Log.w(logTag, "stopDiscovery error", e)
            }
        }
    }

    /**
     * Discovery printers
     */
    private fun onDiscovery(call: MethodCall, result: Result) {
        val printType: String = call.argument<String>("type") ?: ""
        Log.d(logTag, "onDiscovery type: $printType")
        when (printType) {
            "TCP" -> onDiscoveryPrinter(call, Discovery.PORTTYPE_TCP, result)
            "USB" -> onDiscoveryPrinter(call, Discovery.PORTTYPE_USB, result)
            "BT" -> onDiscoveryPrinter(call, Discovery.PORTTYPE_BLUETOOTH, result)
            "ALL" -> onDiscoveryPrinter(call, Discovery.TYPE_ALL, result)
            else -> result.notImplemented()
        }
    }

    /**
     * Discovery Printers GENERIC
     */
    private fun onDiscoveryPrinter(
        call: MethodCall,
        portType: Int,
        result: Result
    ) {
        val delay: Long = if (portType == Discovery.PORTTYPE_USB) 1000 else 7000
        printers.clear()
        val filter = FilterOption().apply {
            this.portType = portType
        }
        Log.d(logTag, "onDiscoveryPrinter: filter = $portType")

        val resp = EpsonEposPrinterResult("onDiscoveryPrinter", false)
        try {
            Discovery.start(context, filter, mDiscoveryListener)
            Handler(Looper.getMainLooper()).postDelayed({
                resp.success = true
                resp.message = "Successfully!"
                resp.content = printers
                result.success(resp.toJSON())
                stopDiscovery()
            }, delay)
        } catch (e: Exception) {
            Log.e(logTag, "onDiscoveryPrinter: start not working for ${call.method}", e)
            resp.success = false
            resp.message = "Error while search printer"
            result.success(resp.toJSON())
        }
    }


    private fun onGetPrinterInfo(call: MethodCall, result: Result) {
        Log.d(logTag, "onGetPrinterInfo $call $result")
    }

    private fun isPrinterConnected(call: MethodCall, result: Result) {
        Log.d(logTag, "isPrinterConnected $call $result")
    }

    private fun getPrinterSetting(call: MethodCall, result: Result) {
        Log.d(logTag, "getPrinterSetting $call $result")

        val type: String = call.argument<String>("type") ?: ""
        val series: String = call.argument<String>("series") ?: ""
        val target: String = call.argument<String>("target") ?: ""

        val resp = EpsonEposPrinterResult("onPrint${type}", false)
        try {
            if (!connectPrinter(target, series)) {
                val error = printerStatusError()
                resp.success = false
                resp.message = error["message"] as String
                resp.content = error["code"] as String
                result.success(resp.toJSON())
                mPrinter?.clearCommandBuffer()
            } else {
                mPrinter?.clearCommandBuffer()
            }
        } catch (e: Exception) {
            Log.e(logTag, "getPrinterSetting error", e)
            resp.success = false
            resp.message = "Print error"
            result.success(resp.toJSON())
        }
    }

    private fun setPrinterSetting(call: MethodCall, result: Result) {
        Log.d(logTag, "setPrinterSetting $call $result")

        val type: String = call.argument<String>("type") ?: ""
        val series: String = call.argument<String>("series") ?: ""
        val target: String = call.argument<String>("target") ?: ""

        val paperWidth: Int? = call.argument<Int>("paper_width")
        val printDensity: Int? = call.argument<Int>("print_density")
        val printSpeed: Int? = call.argument<Int>("print_speed")

        val resp = EpsonEposPrinterResult("onPrint${type}", false)
        try {
            if (!connectPrinter(target, series)) {
                val error = printerStatusError()
                resp.success = false
                resp.message = error["message"] as String
                resp.content = error["code"] as String
                result.success(resp.toJSON())
                mPrinter?.clearCommandBuffer()
            } else {
                val settingList = HashMap<Int, Int>()
                settingList[Printer.SETTING_PRINTSPEED] = printSpeed ?: Printer.PARAM_DEFAULT
                settingList[Printer.SETTING_PRINTDENSITY] = printDensity ?: Printer.PARAM_DEFAULT
                val pw = when (paperWidth) {
                    58, 60, 80 -> paperWidth
                    else -> 80
                }
                settingList[Printer.SETTING_PAPERWIDTH] = pw
                try {
                    mPrinter?.setPrinterSetting(30000, settingList, mPrinterSettingListener)
                } catch (ex: Exception) {
                    Log.e(logTag, "setPrinterSetting error", ex)
                    resp.success = false
                    resp.message = "Print error"
                    result.success(resp.toJSON())
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "setPrinterSetting outer error", e)
            resp.success = false
            resp.message = "Print error"
            result.success(resp.toJSON())
        }
    }

    /**
     * Print
     */
    private fun onPrint(call: MethodCall, result: Result, runner: MethodRunner) {
        val type: String = call.argument<String>("type") ?: ""
        val series: String = call.argument<String>("series") ?: ""
        val target: String = call.argument<String>("target") ?: ""
        runner.setPrintType(type)

        @Suppress("UNCHECKED_CAST")
        val commands: ArrayList<Map<String, Any>> =
            call.argument<ArrayList<Map<String, Any>>>("commands") ?: ArrayList()
        val resp = EpsonEposPrinterResult("onPrint${type}", false)
        try {
            if (!connectPrinter(target, series)) {
                resp.success = false
                resp.message = "Can not connect to the printer."
                result.success(resp.toJSON())
                Log.e(logTag, "Cannot ConnectPrinter $resp")
                mPrinter?.clearCommandBuffer()
            } else {
                commands.forEach { onGenerateCommand(it) }
                try {
                    val statusInfo: PrinterStatusInfo? = mPrinter?.status
                    Log.d(
                        logTag,
                        "Printing $target $series Connection: ${statusInfo?.connection} online: ${statusInfo?.online} cover: ${statusInfo?.coverOpen} Paper: ${statusInfo?.paper} ErrorSt: ${statusInfo?.errorStatus} Battery Level: ${statusInfo?.batteryLevel}, drawer: ${statusInfo?.drawer}"
                    )
                    Log.d(logTag, "BEGIN transaction")
                    mPrinter?.setReceiveEventListener(runner)
                    mPrinter?.beginTransaction()

                    Log.d(logTag, "send data")
                    mPrinter?.sendData(30000)

                    Log.d(logTag, "Sent data to printer $target $series")
                } catch (ex: Epos2Exception) {
                    if (ex.errorStatus == Epos2Exception.ERR_CONNECT) {
                        disconnectPrinter()
                    }
                    Log.e(logTag, "sendData Error ${ex.errorStatus}", ex)
                    resp.success = false
                    resp.message = "Send data error: ${ex.errorStatus}"
                    result.success(resp.toJSON())
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "onPrint outer error", e)
            resp.success = false
            resp.message = "Print error"
            result.success(resp.toJSON())
        }
    }

    /// FUNCTIONS

    private val mDiscoveryListener = DiscoveryListener { deviceInfo ->
        Log.d(logTag, "Found: ${deviceInfo?.deviceName}")
        if (deviceInfo?.deviceName != null && deviceInfo.deviceName.isNotEmpty()) {
            val printer = EpsonEposPrinterInfo(
                deviceInfo.ipAddress,
                deviceInfo.bdAddress,
                deviceInfo.macAddress,
                deviceInfo.deviceName,
                deviceInfo.deviceType.toString(),
                deviceInfo.deviceType.toString(),
                deviceInfo.target
            )
            val printerIndex = printers.indexOfFirst { it.ipAddress == deviceInfo.ipAddress }
            if (printerIndex > -1) {
                printers[printerIndex] = printer
            } else {
                printers.add(printer)
            }
        }
    }

    private val mPrinterSettingListener = object : PrinterSettingListener {
        override fun onGetPrinterSetting(p0: Int, p1: Int, p2: Int) {
            Log.d(logTag, "onGetPrinterSetting type: $p0 $p1 $p2")
        }

        override fun onSetPrinterSetting(p0: Int) {
            Log.d(logTag, "onSetPrinterSetting Code: $p0")
        }
    }

    private fun connectPrinter(target: String, series: String): Boolean {
        try {
            mPrinter?.let {
                try {
                    it.disconnect()
                } catch (_: Exception) {
                }
                mPrinter = null
            }

            val printConst = getPrinterConstant(series)
            mPrinter = Printer(printConst, Printer.MODEL_ANK, context)
            mTarget = target

            mPrinter?.connect(target, Printer.PARAM_DEFAULT)
            mPrinter?.clearCommandBuffer()

        } catch (e: Epos2Exception) {
            Log.e(logTag, "Connect Error ${e.errorStatus}", e)
            return false
        }
        return true
    }

    private fun disconnectPrinter() {
        if (mPrinter == null) {
            Log.d(logTag, "disconnectPrinter mPrinter null")
            return
        }

        try {
            try {
                mPrinter?.endTransaction()
            } catch (_: Exception) {
            }

            try {
                mPrinter?.clearCommandBuffer()
            } catch (_: Exception) {
            }

            try {
                mPrinter?.setReceiveEventListener(null)
            } catch (_: Exception) {
            }

            try {
                mPrinter?.disconnect()
            } catch (_: Exception) {
            }

            Log.d(logTag, "disconnectPrinter success")

        } catch (e: Exception) {
            Log.e(logTag, "disconnectPrinter fatal error ${e.message}", e)
        } finally {
            mPrinter = null
            mTarget = null
        }
    }

    private fun onGenerateCommand(command: Map<String, Any>) {
        val printer = mPrinter ?: return
        Log.d(logTag, "onGenerateCommand: $command")

        val commandId: String = command["id"] as? String ?: return
        if (commandId.isEmpty()) return
        val commandValue = command["value"]

        when (commandId) {

            "appendText" -> {
                Log.d(logTag, "appendText: $commandValue")
                printer.addText(commandValue.toString())
            }

            "printRawData" -> {
                try {
                    Log.d(logTag, "printRawData")
                    printer.addCommand(commandValue as ByteArray)
                } catch (e: Exception) {
                    Log.e(logTag, "onGenerateCommand Error ${e.localizedMessage}", e)
                }
            }

            "addImage" -> {
                try {
                    val width: Int = command["width"] as Int
                    val height: Int = command["height"] as Int
                    val posX: Int = command["posX"] as Int
                    val posY: Int = command["posY"] as Int
                    val bitmap: Bitmap? = convertBase64toBitmap(commandValue as String)
                    Log.d(logTag, "appendBitmap: $width x $height $posX $posY bitmap $bitmap")
                    printer.addImage(
                        bitmap,
                        posX,
                        posY,
                        width,
                        height,
                        Printer.PARAM_DEFAULT,
                        Printer.PARAM_DEFAULT,
                        Printer.PARAM_DEFAULT,
                        1.0,
                        Printer.COMPRESS_AUTO
                    )
                } catch (e: Exception) {
                    Log.e(logTag, "onGenerateCommand Error ${e.localizedMessage}", e)
                }
            }

            "addFeedLine" -> printer.addFeedLine(commandValue as Int)

            "addCut" -> {
                when (commandValue.toString()) {
                    "CUT_FEED" -> printer.addCut(Printer.CUT_FEED)
                    "CUT_NO_FEED" -> printer.addCut(Printer.CUT_NO_FEED)
                    "CUT_RESERVE" -> printer.addCut(Printer.CUT_RESERVE)
                    else -> printer.addCut(Printer.PARAM_DEFAULT)
                }
            }

            "addLineSpace" -> printer.addFeedLine(commandValue as Int)

            "addPageBegin" -> printer.addPageBegin()

            "addPageArea" -> {
                val v = commandValue as Map<*, *>
                val x = v["x"] as Int
                val y = v["y"] as Int
                val w = v["w"] as Int
                val h = v["h"] as Int
                printer.addPageArea(x, y, w, h)
            }

            "addPageEnd" -> printer.addPageEnd()

            "addPagePosition" -> {
                val v = commandValue as Map<*, *>
                val x = v["x"] as Int
                val y = v["y"] as Int
                printer.addPagePosition(x, y)
            }

            "addTextAlign" -> {
                when (commandValue.toString()) {
                    "LEFT" -> printer.addTextAlign(Printer.ALIGN_LEFT)
                    "CENTER" -> printer.addTextAlign(Printer.ALIGN_CENTER)
                    "RIGHT" -> printer.addTextAlign(Printer.ALIGN_RIGHT)
                    else -> printer.addTextAlign(Printer.PARAM_DEFAULT)
                }
            }

            "addTextFont" -> {
                when (commandValue.toString()) {
                    "FONT_A" -> printer.addTextFont(Printer.FONT_A)
                    "FONT_B" -> printer.addTextFont(Printer.FONT_B)
                    "FONT_C" -> printer.addTextFont(Printer.FONT_C)
                    "FONT_D" -> printer.addTextFont(Printer.FONT_D)
                    "FONT_E" -> printer.addTextFont(Printer.FONT_E)
                }
            }

            "addTextSmooth" -> {
                if (commandValue as Boolean) {
                    printer.addTextSmooth(Printer.TRUE)
                } else {
                    printer.addTextSmooth(Printer.FALSE)
                }
            }

            "addTextSize" -> {
                val width = command["width"] as Int
                val height = command["height"] as Int
                Log.d(logTag, "setTextSize: width: $width, height: $height")
                printer.addTextSize(width, height)
            }

            "addKick" -> printer.addPulse(Printer.DRAWER_2PIN, Printer.PULSE_500)

            "addTextStyle" -> {
                val reverse = command["reverse"] as Boolean?
                val ul = command["ul"] as Boolean?
                val em = command["em"] as Boolean?
                val color = command["color"] as String?

                val reverseValue = reverse?.let { if (it) Printer.TRUE else Printer.FALSE }
                    ?: Printer.PARAM_DEFAULT
                val ulValue = ul?.let { if (it) Printer.TRUE else Printer.FALSE }
                    ?: Printer.PARAM_DEFAULT
                val emValue = em?.let { if (it) Printer.TRUE else Printer.FALSE }
                    ?: Printer.PARAM_DEFAULT

                val colorValue = when (color) {
                    "COLOR_NONE" -> Printer.COLOR_NONE
                    "COLOR_1" -> Printer.COLOR_1
                    "COLOR_2" -> Printer.COLOR_2
                    "COLOR_3" -> Printer.COLOR_3
                    "COLOR_4" -> Printer.COLOR_4
                    else -> Printer.PARAM_DEFAULT
                }

                printer.addTextStyle(reverseValue, ulValue, emValue, colorValue)
            }

            "addBarcode" -> {
                val barcodeWidth = command["width"] as? Int ?: 2
                val barcodeHeight = command["height"] as? Int ?: 100
                val barcode = command["barcode"] as? String ?: ""
                val type = command["type"] as? Int ?: Printer.BARCODE_EAN13
                val textPosition = command["position"] as? Int ?: Printer.HRI_BELOW

                val font = when (command["font"] as? String) {
                    "FONT_A" -> Printer.FONT_A
                    "FONT_B" -> Printer.FONT_B
                    "FONT_C" -> Printer.FONT_C
                    "FONT_D" -> Printer.FONT_D
                    "FONT_E" -> Printer.FONT_E
                    else -> Printer.FONT_A
                }

                Log.d(
                    logTag,
                    "addBarcode: $barcode $barcodeWidth $barcodeHeight $type $textPosition $font"
                )
                printer.addBarcode(
                    barcode,
                    type,
                    textPosition,
                    font,
                    barcodeWidth,
                    barcodeHeight
                )
            }
        }
    }

    private fun getPrinterConstant(series: String): Int {
        return when (series) {
            "TM_M10" -> Printer.TM_M10
            "TM_M30" -> Printer.TM_M30
            "TM_M30II" -> Printer.TM_M30II
            "TM_M50" -> Printer.TM_M50
            "TM_P20" -> Printer.TM_P20
            "TM_P60" -> Printer.TM_P60
            "TM_P60II" -> Printer.TM_P60II
            "TM_P80" -> Printer.TM_P80
            "TM_T20" -> Printer.TM_T20
            "TM_T60" -> Printer.TM_T60
            "TM_T70" -> Printer.TM_T70
            "TM_T81" -> Printer.TM_T81
            "TM_T82" -> Printer.TM_T82
            "TM_T83" -> Printer.TM_T83
            "TM_T83III" -> Printer.TM_T83III
            "TM_T88" -> Printer.TM_T88
            "TM_T90" -> Printer.TM_T90
            "TM_T100" -> Printer.TM_T100
            "TM_U220" -> Printer.TM_U220
            "TM_U330" -> Printer.TM_U330
            "TM_L90" -> Printer.TM_L90
            "TM_H6000" -> Printer.TM_H6000
            else -> 0
        }
    }

    private fun convertBase64toBitmap(base64Str: String): Bitmap? {
        val decodedBytes: ByteArray = Base64.decode(base64Str, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    }

    private fun printerStatusError(printerStatus: PrinterStatusInfo? = null): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (mPrinter == null && printerStatus == null) {
            result["message"] = getErrorMessage("")
            result["code"] = "ERR_UNKNOWN"
            return result
        }
        var errorMes = ""
        var errorCode = ""
        val status: PrinterStatusInfo? = printerStatus ?: mPrinter?.status

        if (status?.online == Printer.FALSE) {
            errorMes = getErrorMessage("err_offline")
            errorCode = "ERR_OFFLINE"
        }

        if (status?.connection == Printer.FALSE) {
            errorMes = getErrorMessage("err_no_response")
            errorCode = "ERR_NO_RESPONSE"
        }

        if (status?.coverOpen == Printer.TRUE) {
            errorMes = getErrorMessage("err_cover_open")
            errorCode = "ERR_COVER_OPEN"
        }

        if (status?.paper == Printer.PAPER_EMPTY) {
            errorMes = getErrorMessage("err_receipt_end")
            errorCode = "ERR_RECEIPT_END"
        }

        if (status?.paperFeed == Printer.TRUE || status?.panelSwitch == Printer.SWITCH_ON) {
            errorMes = getErrorMessage("err_paper_feed")
            errorCode = "ERR_PAPER_FEED"
        }

        if (status?.errorStatus == Printer.UNRECOVER_ERR) {
            errorMes = getErrorMessage("err_unrecover")
            errorCode = "ERR_UNRECOVER"
        }

        if (status?.errorStatus == Printer.MECHANICAL_ERR || status?.errorStatus == Printer.AUTOCUTTER_ERR) {
            errorMes = getErrorMessage("err_autocutter") + getErrorMessage("err_need_recover")
            errorCode = "ERR_AUTOCUTTER"
        }

        if (status?.errorStatus == Printer.AUTORECOVER_ERR) {
            when (status.autoRecoverError) {
                Printer.HEAD_OVERHEAT -> {
                    errorMes = getErrorMessage("err_head") + getErrorMessage("err_overheat")
                    errorCode = "ERR_OVERHEAT_HEAD"
                }
                Printer.MOTOR_OVERHEAT -> {
                    errorMes = getErrorMessage("err_motor") + getErrorMessage("err_overheat")
                    errorCode = "ERR_OVERHEAT_MOTOR"
                }
                Printer.BATTERY_OVERHEAT -> {
                    errorMes = getErrorMessage("err_battery") + getErrorMessage("err_overheat")
                    errorCode = "ERR_OVERHEAT_BATTERY"
                }
                Printer.WRONG_PAPER -> {
                    errorMes = getErrorMessage("err_wrong_paper")
                    errorCode = "ERR_WRONG_PAPER"
                }
            }
        }
        if (status?.batteryLevel == Printer.BATTERY_LEVEL_0) {
            errorMes = getErrorMessage("err_battery_real_end")
            errorCode = "ERR_BATTERY_END"
        }

        if (errorMes.isEmpty()) {
            result["message"] = getErrorMessage("")
            result["code"] = "ERR_UNKNOWN"
        } else {
            result["message"] = errorMes
            result["code"] = errorCode
        }
        return result
    }

    private fun getErrorMessage(errorKey: String, withNewLine: Boolean = true): String {
        val errorMes = when (errorKey.lowercase()) {
            "warn_receipt_near_end" -> "Roll paper is nearly end."
            "warn_battery_near_end" -> "Battery level of printer is low."
            "err_no_response", "err_timeout" ->
                "Please check the connection of the printer and the mobile terminal.\nConnection get lost or timeout."
            "err_cover_open" -> "Please close roll paper cover."
            "err_receipt_end", "err_empty" -> "Please check roll paper."
            "err_paper_feed" -> "Please release a paper feed switch."
            "err_autocutter", "err_cutter" ->
                "Please remove jammed paper and close roll paper cover.\nRemove any jammed paper or foreign substances in the printer, and then turn the printer off and turn the printer on again."
            "err_need_recover" ->
                "Then, If the printer doesn\'t recover from error, please cycle the power switch."
            "err_unrecover", "err_unrecoverable" ->
                "Please cycle the power switch of the printer.\nIf same errors occurred even power cycled, the printer may be out of order."
            "err_overheat" -> "Please wait until error LED of the printer turns off. "
            "err_head" -> "Print head of printer is hot."
            "err_motor" -> "Motor Driver IC of printer is hot."
            "err_battery" -> "Battery of printer is hot."
            "err_wrong_paper" -> "Please set correct roll paper."
            "err_battery_real_end", "err_battery_end" ->
                "Please connect AC adapter or change the battery.\nBattery of printer is almost empty."
            "err_offline" -> "Printer is offline."
            "err_mechanical" -> "Mechanical error occurred."
            "err_failure" -> "Print job failed."
            "err_system" -> "System error occurred."
            else -> "Unknown error. Please check the power and communication status of the printer."
        }
        return if (withNewLine) "$errorMes\n" else errorMes
    }

    private val _REQUEST_PERMISSION = 100
    private fun requestRuntimePermission(call: MethodCall, result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.notImplemented()
            return
        }
        val currentActivity = activity ?: run {
            result.error("NO_ACTIVITY", "Activity is not attached", null)
            return
        }
        val requestPermissions: ArrayList<String> = ArrayList()
        if (Build.VERSION_CODES.S <= Build.VERSION.SDK_INT) {
            // Android 12+ requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT
            val permissionBluetoothScan: Int =
                ContextCompat.checkSelfPermission(currentActivity, Manifest.permission.BLUETOOTH_SCAN)
            val permissionBluetoothConnect: Int =
                ContextCompat.checkSelfPermission(currentActivity, Manifest.permission.BLUETOOTH_CONNECT)
            if (permissionBluetoothScan == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (permissionBluetoothConnect == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (Build.VERSION_CODES.Q <= Build.VERSION.SDK_INT && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            val permissionLocationFine: Int =
                ContextCompat.checkSelfPermission(currentActivity, Manifest.permission.ACCESS_FINE_LOCATION)
            if (permissionLocationFine == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            val permissionLocationCoarse: Int =
                ContextCompat.checkSelfPermission(currentActivity, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (permissionLocationCoarse == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (requestPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                currentActivity,
                requestPermissions.toTypedArray(),
                _REQUEST_PERMISSION
            )
        }
    }
}
