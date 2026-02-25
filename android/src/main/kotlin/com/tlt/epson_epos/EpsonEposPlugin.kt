package com.tlt.epson_epos

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.annotation.NonNull
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
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private var logTag: String = "Epson_ePOS"
    private lateinit var context: Context
    private lateinit var activity: Activity
    private var mPrinter: Printer? = null
    private var mTarget: String? = null
    private var printers: MutableList<EpsonEposPrinterInfo> = ArrayList()

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {

    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {

    }

    override fun onDetachedFromActivity() {

    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
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
        );
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull rawResult: Result) {
        val result = MethodResultWrapper(rawResult)
        Thread(MethodRunner(call, result)).start()
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
    }

    inner class MethodRunner(call: MethodCall, result: Result) : Runnable, ReceiveListener {
        private val call: MethodCall = call
        private val result: Result = result
        private var printType: String = ""

        fun setPrintType(type: String) {
            this.printType = type
        }

        override fun onPtrReceive(p0: Printer?, p1: Int, p2: PrinterStatusInfo?, p3: String?) {
            var resp = EpsonEposPrinterResult("onPrint${printType}", false)
            resp.code = p1
            
            // Luôn ưu tiên kiểm tra trạng thái máy in (status) xem có lỗi cụ thể nào không (hết giấy, mở nắp...)
            val errorFromStatus = if (p2 != null) printerStatusError(p2) else null
            val hasSpecificError = errorFromStatus != null && errorFromStatus["code"] != "ERR_UNKNOWN"

            if (p1 == 0 && !hasSpecificError) {
                // Thành công tuyệt đối
                resp.success = true
                resp.message = "Success"
            } else {
                // Có lỗi xảy ra (hoặc từ callback code p1, hoặc từ status p2)
                resp.success = false
                
                if (hasSpecificError) {
                    // Ưu tiên báo lỗi cụ thể từ phần cứng (Hết giấy, Mở nắp...)
                    resp.message = errorFromStatus!!["message"] as String
                    resp.content = errorFromStatus["code"] as String
                    
                    // Nếu p1 cũng có lỗi khác, thêm vào detail
                    if (p1 != 0) {
                        val callbackErrorKey = getCallbackErrorCode(p1)
                        resp.message = "${resp.message}"
                    }
                } else {
                    // Nếu status không báo lỗi gì đặc biệt, dùng lỗi từ callback code p1
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
                "onDiscovery" -> {
                    onDiscovery(call, result)
                }

                "onPrint" -> {
                    onPrint(call, result, this)
                }

                "onGetPrinterInfo" -> {
                    onGetPrinterInfo(call, result)
                }

                "isPrinterConnected" -> {
                    isPrinterConnected(call, result)
                }

                "getPrinterSetting" -> {
                    getPrinterSetting(call, result)
                }

                "setPrinterSetting" -> {
                    setPrinterSetting(call, result)
                }

                "requestRuntimePermission" -> {
                    requestRuntimePermission(call, result)
                }

                else -> {
                    Log.d(logTag, "Method: ${call.method} is not supported yet")
                    result.notImplemented()
                }
            }
        }
    }

    class MethodResultWrapper(methodResult: Result) : Result {

        private val methodResult: Result = methodResult
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

            }
        }
    }

    /**
     * Discovery printers
     */
    private fun onDiscovery(@NonNull call: MethodCall, @NonNull result: Result) {
        val printType: String = call.argument<String>("type") as String
        Log.d(logTag, "onDiscovery type: $printType")
        when (printType) {
            "TCP" -> {
                onDiscoveryPrinter(call, Discovery.PORTTYPE_TCP, result)
            }

            "USB" -> {
                onDiscoveryPrinter(call, Discovery.PORTTYPE_USB, result)
            }

            "BT" -> {
                onDiscoveryPrinter(call, Discovery.PORTTYPE_BLUETOOTH, result)
            }

            "ALL" -> {
                onDiscoveryPrinter(call, Discovery.TYPE_ALL, result)
            }

            else -> result.notImplemented()
        }
    }

    /**
     * Discovery Printers GENERIC
     */
    private fun onDiscoveryPrinter(
        @NonNull call: MethodCall,
        portType: Int,
        @NonNull result: Result
    ) {
        var delay: Long = 7000;
        if (portType == Discovery.PORTTYPE_USB) {
            delay = 1000;
        }
        printers.clear()
        var filter = FilterOption()
        filter.portType = portType;
        Log.e("onDiscoveryPrinter", "Filter = $portType");

        var resp = EpsonEposPrinterResult("onDiscoveryPrinter", false)
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
            Log.e("onDiscoveryPrinter", "Start not working ${call.method}");
            resp.success = false
            resp.message = "Error while search printer"
            e.printStackTrace()
            result.success(resp.toJSON())
        }
    }


    private fun onGetPrinterInfo(@NonNull call: MethodCall, @NonNull result: Result) {
        Log.d(logTag, "onGetPrinterInfo $call $result")
    }

    private fun isPrinterConnected(call: MethodCall, result: Result) {
        Log.d(logTag, "isPrinterConnected $call $result")
    }

    private fun getPrinterSetting(call: MethodCall, result: Result) {
        Log.d(logTag, "getPrinterSetting $call $result")

        val type: String = call.argument<String>("type") as String
        val series: String = call.argument<String>("series") as String
        val target: String = call.argument<String>("target") as String

        var resp = EpsonEposPrinterResult("onPrint${type}", false)
        try {
            if (!connectPrinter(target, series)) {
                val error = printerStatusError()
                resp.success = false
                resp.message = error["message"] as String
                resp.content = error["code"] as String
                result.success(resp.toJSON())
                mPrinter!!.clearCommandBuffer()
            } else {
                if (mPrinter != null) {
                    mPrinter!!.clearCommandBuffer()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            resp.success = false
            resp.message = "Print error"
            result.success(resp.toJSON())
        }
    }

    private fun setPrinterSetting(call: MethodCall, result: Result) {
        Log.d(logTag, "setPrinterSetting $call $result")

        val type: String = call.argument<String>("type") as String
        val series: String = call.argument<String>("series") as String
        val target: String = call.argument<String>("target") as String

        val paperWidth: Int? = call.argument<String>("paper_width") as? Int
        val printDensity: Int? = call.argument<String>("print_density") as? Int
        val printSpeed: Int? = call.argument<String>("print_speed") as? Int

        var resp = EpsonEposPrinterResult("onPrint${type}", false)
        try {
            if (!connectPrinter(target, series)) {
                val error = printerStatusError()
                resp.success = false
                resp.message = error["message"] as String
                resp.content = error["code"] as String
                result.success(resp.toJSON())
                mPrinter!!.clearCommandBuffer()
            } else {
                val settingList = HashMap<Int, Int>()
                settingList[Printer.SETTING_PRINTSPEED] = printSpeed ?: Printer.PARAM_DEFAULT
                settingList[Printer.SETTING_PRINTDENSITY] = printDensity ?: Printer.PARAM_DEFAULT
                var pw = 80
                if (paperWidth != null) {
                    pw = if (paperWidth != 80 || paperWidth != 58 || paperWidth != 60) {
                        80
                    } else {
                        paperWidth
                    }
                }
                settingList[Printer.SETTING_PAPERWIDTH] = pw
                try {
                    mPrinter!!.setPrinterSetting(30000, settingList, mPrinterSettingListener)
                } catch (ex: Exception) {
                    Log.e(logTag, "sendData Error", ex)
                    ex.printStackTrace()
                    resp.success = false
                    resp.message = "Print error"
                    result.success(resp.toJSON())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            resp.success = false
            resp.message = "Print error"
            result.success(resp.toJSON())
        }
    }

    /**
     * Print
     */
    private fun onPrint(@NonNull call: MethodCall, @NonNull result: Result, runner: MethodRunner ) {
        val type: String = call.argument<String>("type") as String
        val series: String = call.argument<String>("series") as String
        val target: String = call.argument<String>("target") as String
        runner.setPrintType(type)

        val commands: ArrayList<Map<String, Any>> =
            call.argument<ArrayList<Map<String, Any>>>("commands") as ArrayList<Map<String, Any>>
        var resp = EpsonEposPrinterResult("onPrint${type}", false)
        try {
            if (!connectPrinter(target, series)) {
                resp.success = false
                resp.message = "Can not connect to the printer."
                result.success(resp.toJSON())
                Log.e("logTag", "Cannot ConnectPrinter $resp")
                if (mPrinter != null) {
                    mPrinter!!.clearCommandBuffer()
                }
            } else {
                commands.forEach {
                    onGenerateCommand(it)
                }
                mPrinter!!.addPulse(Printer.DRAWER_2PIN, Printer.PULSE_500)

                try {
                    val statusInfo: PrinterStatusInfo? = mPrinter!!.status;
                    Log.d(
                        logTag,
                        "Printing $target $series Connection: ${statusInfo?.connection} online: ${statusInfo?.online} cover: ${statusInfo?.coverOpen} Paper: ${statusInfo?.paper} ErrorSt: ${statusInfo?.errorStatus} Battery Level: ${statusInfo?.batteryLevel}, drawer: ${statusInfo?.drawer}"
                    )
                    Log.d(logTag, "BEGIN transaction")
                    mPrinter!!.setReceiveEventListener(runner)
                    mPrinter!!.beginTransaction()
                    Log.d(logTag, "add kick drawer")

                    Log.d(logTag, "send data")
                    mPrinter!!.sendData(30000)

                    Log.d(logTag, "Sent data to printer $target $series")
                    // result.success(resp.toJSON()); // Removed: wait for onPtrReceive
                } catch (ex: Epos2Exception) {
                    if (ex.errorStatus == Epos2Exception.ERR_CONNECT) {
                        disconnectPrinter()
                    }
                    ex.printStackTrace()
                    Log.e(logTag, "sendData Error" + ex.errorStatus, ex)
                    resp.success = false
                    resp.message = "Send data error: ${ex.errorStatus}"
                    result.success(resp.toJSON())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            resp.success = false
            resp.message = "Print error"
            result.success(resp.toJSON())
        }
    }

    /// FUNCTIONS

    private val mDiscoveryListener = DiscoveryListener { deviceInfo ->
        Log.d(logTag, "Found: ${deviceInfo?.deviceName}")
        if (deviceInfo?.deviceName != null && deviceInfo?.deviceName != "") {
            var printer = EpsonEposPrinterInfo(
                deviceInfo.ipAddress,
                deviceInfo.bdAddress,
                deviceInfo.macAddress,
                deviceInfo.deviceName,
                deviceInfo.deviceType.toString(),
                deviceInfo.deviceType.toString(),
                deviceInfo.target
            )
            var printerIndex = printers.indexOfFirst { e -> e.ipAddress == deviceInfo.ipAddress }
            if (printerIndex > -1) {
                printers[printerIndex] = printer
            } else {
                printers.add(printer)
            }
        }

    }

    private val mPrinterSettingListener = object : PrinterSettingListener {
        override fun onGetPrinterSetting(p0: Int, p1: Int, p2: Int) {
            Log.e("logTag", "onGetPrinterSetting type: $p0 $p1 $p2")
        }

        override fun onSetPrinterSetting(p0: Int) {
            Log.e("logTag", "onSetPrinterSetting Code: $p0")
        }
    }

    private fun connectPrinter(target: String, series: String): Boolean {
        try {
            if (mPrinter != null) {
                try {
                    mPrinter!!.disconnect()
                } catch (e: Exception) {}
                mPrinter = null
            }

            val printConst = getPrinterConstant(series)
            mPrinter = Printer(printConst, Printer.MODEL_ANK, context)
            mTarget = target

            mPrinter!!.connect(target, Printer.PARAM_DEFAULT)
            mPrinter!!.clearCommandBuffer()

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
                // Nếu đang transaction thì kết thúc
                mPrinter?.endTransaction()
            } catch (_: Exception) {}

            try {
                // Xóa buffer nếu còn lệnh
                mPrinter?.clearCommandBuffer()
            } catch (_: Exception) {}

            try {
                // Bỏ listener trước khi disconnect
                mPrinter?.setReceiveEventListener(null)
            } catch (_: Exception) {}

            try {
                mPrinter?.disconnect()
            } catch (_: Exception) {}

            Log.d(logTag, "disconnectPrinter success")

        } catch (e: Exception) {
            Log.e(logTag, "disconnectPrinter fatal error ${e.message}", e)
        } finally {
            mPrinter = null
            mTarget = null
        }
    }

    private fun onGenerateCommand(command: Map<String, Any>) {
        if (mPrinter == null) {
            return
        }
        Log.d(logTag, "onGenerateCommand: $command")
        val textData = StringBuilder()

        val commandId: String = command["id"] as String
        if (commandId.isNotEmpty()) {
            val commandValue = command["value"]

            when (commandId) {

                "appendText" -> {
                    Log.d(
                        logTag,
                        "appendText: $commandValue"
                    )
                    mPrinter!!.addText(commandValue.toString());
                }

                "printRawData" -> {
                    try {
                        Log.d(logTag, "printRawData")
                        mPrinter!!.addCommand(commandValue as ByteArray)
                    } catch (e: Exception) {
                        Log.e(
                            logTag,
                            "onGenerateCommand Error" + e.localizedMessage
                        )
                    }
                }

                "addImage" -> {
                    try {
                        val width: Int =
                            command["width"] as Int
                        val height: Int =
                            command["height"] as Int
                        val posX: Int = command["posX"] as Int
                        val posY: Int = command["posY"] as Int
                        val bitmap: Bitmap? =
                            convertBase64toBitmap(commandValue as String)
                        Log.d(
                            logTag,
                            "appendBitmap: $width x $height $posX $posY bitmap $bitmap"
                        )
//            Printer.SETTING_PAPERWIDTH_80_0
                        mPrinter!!.addImage(
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
                        Log.e(
                            logTag,
                            "onGenerateCommand Error" + e.localizedMessage
                        )
                    }
                }

                "addFeedLine" -> {
                    mPrinter!!.addFeedLine(commandValue as Int)
                }

                "addCut" -> {
                    when (commandValue.toString()) {
                        "CUT_FEED" -> {
                            mPrinter!!.addCut(Printer.CUT_FEED)
                        }

                        "CUT_NO_FEED" -> {
                            mPrinter!!.addCut(Printer.CUT_NO_FEED)
                        }

                        "CUT_RESERVE" -> {
                            mPrinter!!.addCut(Printer.CUT_RESERVE)
                        }

                        else -> {
                            mPrinter!!.addCut(Printer.PARAM_DEFAULT)
                        }
                    }
                }

                "addLineSpace" -> {
                    mPrinter!!.addFeedLine(commandValue as Int)
                }

                "addPageBegin" -> {
                    mPrinter!!.addPageBegin()
                }

                "addPageArea" -> {
                    val v = commandValue as Map<*,*>
                    val x = v["x"] as Int
                    val y = v["y"] as Int
                    val w = v["w"] as Int
                    val h = v["h"] as Int
                    mPrinter!!.addPageArea(x,y,w,h)
                }

                "addPageEnd" -> {
                    mPrinter!!.addPageEnd()
                }
                "addPagePosition" -> {
                    val v = commandValue as Map<*,*>
                    val x = v["x"] as Int
                    val y = v["y"] as Int
                    mPrinter!!.addPagePosition(x,y)
                }

                "addTextAlign" -> {
                    when (commandValue.toString()) {
                        "LEFT" -> {
                            mPrinter!!.addTextAlign(Printer.ALIGN_LEFT)
                        }

                        "CENTER" -> {
                            mPrinter!!.addTextAlign(Printer.ALIGN_CENTER)
                        }

                        "RIGHT" -> {
                            mPrinter!!.addTextAlign(Printer.ALIGN_RIGHT)
                        }

                        else -> {
                            mPrinter!!.addTextAlign(Printer.PARAM_DEFAULT)
                        }
                    }
                }

                "addTextFont" -> {
                    when (commandValue.toString()) {
                        "FONT_A" -> {
                            mPrinter!!.addTextFont(Printer.FONT_A)
                        }

                        "FONT_B" -> {
                            mPrinter!!.addTextFont(Printer.FONT_B)
                        }

                        "FONT_C" -> {
                            mPrinter!!.addTextFont(Printer.FONT_C)
                        }

                        "FONT_D" -> {
                            mPrinter!!.addTextFont(Printer.FONT_D)
                        }

                        "FONT_E" -> {
                            mPrinter!!.addTextFont(Printer.FONT_E)
                        }
                    }
                }

                "addTextSmooth" -> {
                    if (commandValue as Boolean) {
                        mPrinter!!.addTextSmooth(Printer.TRUE)
                    } else {
                        mPrinter!!.addTextSmooth(Printer.FALSE)
                    }
                }

                "addTextSize" -> {
                    val width = command["width"] as Int
                    val height = command["height"] as Int
                    Log.d(
                        logTag,
                        "setTextSize: width: $width, height: $height"
                    )
                    mPrinter!!.addTextSize(width, height)
                }

                "addKick" -> {
                    mPrinter!!.addCommand(
                        byteArrayOf(
                            27.toByte(), // ESC
                            112.toByte(),
                            0.toByte(),
                            100.toByte(),
                            250.toByte()
                        )
                    )
                }

                "addTextStyle" -> {
                    val reverse =
                        command["reverse"] as Boolean?
                    val ul = command["ul"] as Boolean?
                    val em = command["em"] as Boolean?
                    val color = command["color"] as String?

                    val reverseValue =
                        if (reverse != null) {
                            if (reverse) {
                                Printer.TRUE
                            } else
                                Printer.FALSE
                        } else {
                            Printer.PARAM_DEFAULT
                        }

                    val ulValue = if (ul != null) {
                        if (ul) {
                            Printer.TRUE
                        } else
                            Printer.FALSE
                    } else {
                        Printer.PARAM_DEFAULT
                    }

                    val emValue = if (em != null) {
                        if (em) {
                            Printer.TRUE
                        } else
                            Printer.FALSE
                    } else {
                        Printer.PARAM_DEFAULT
                    }

                    val colorValue = when (color) {
                        "COLOR_NONE" -> Printer.COLOR_NONE
                        "COLOR_1" -> Printer.COLOR_1
                        "COLOR_2" -> Printer.COLOR_2
                        "COLOR_3" -> Printer.COLOR_3
                        "COLOR_4" -> Printer.COLOR_4
                        else -> Printer.PARAM_DEFAULT
                    }

                    mPrinter!!.addTextStyle(
                        reverseValue,
                        ulValue,
                        emValue,
                        colorValue
                    )
                }

                "addBarcode" -> {
                    var barcodeWidth = 2
                    val width = command["width"] as? Int
                    if (width != null) {
                        barcodeWidth = width
                    }

                    var barcodeHeight = 100
                    val height = command["height"] as? Int
                    if (height != null) {
                        barcodeHeight = height
                    }

                    var barcode = ""
                    val code = command["barcode"] as? String
                    if (code != null) {
                        barcode = code
                    }

                    var type = Printer.BARCODE_EAN13
                    val codeType = command["type"] as? Int
                    if (codeType != null) {
                        type = codeType
                    }

                    var textPosition = Printer.HRI_BELOW
                    val position =
                        command["position"] as? Int
                    if (position != null) {
                        textPosition = position
                    }

                    var font =
                        Printer.FONT_A // Use the correct font value for Kotlin
                    val fontValue =
                        command["font"] as? String
                    when (fontValue) {
                        "FONT_A" -> font = Printer.FONT_A
                        "FONT_B" -> font = Printer.FONT_B
                        "FONT_C" -> font = Printer.FONT_C
                        "FONT_D" -> font = Printer.FONT_D
                        "FONT_E" -> font = Printer.FONT_E
                    }

                    Log.d(
                        logTag,
                        "addBarcode: $barcode $barcodeWidth $barcodeHeight $type $textPosition $font"
                    )
                    mPrinter?.addBarcode(
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
        val status: PrinterStatusInfo? = printerStatus ?: mPrinter!!.status

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
            if (status?.autoRecoverError == Printer.HEAD_OVERHEAT) {
                errorMes = getErrorMessage("err_head") + getErrorMessage("err_overheat")
                errorCode = "ERR_OVERHEAT_HEAD"
            }
            if (status?.autoRecoverError == Printer.MOTOR_OVERHEAT) {
                errorMes = getErrorMessage("err_motor") + getErrorMessage("err_overheat")
                errorCode = "ERR_OVERHEAT_MOTOR"
            }
            if (status?.autoRecoverError == Printer.BATTERY_OVERHEAT) {
                errorMes = getErrorMessage("err_battery") + getErrorMessage("err_overheat")
                errorCode = "ERR_OVERHEAT_BATTERY"
            }
            if (status?.autoRecoverError == Printer.WRONG_PAPER) {
                errorMes = getErrorMessage("err_wrong_paper")
                errorCode = "ERR_WRONG_PAPER"
            }
        }
        if (status?.batteryLevel == Printer.BATTERY_LEVEL_0) {
            errorMes = getErrorMessage("err_battery_real_end")
            errorCode = "ERR_BATTERY_END"
        }

        if (errorMes == "") {
            result["message"] = getErrorMessage("")
            result["code"] = "ERR_UNKNOWN"
        } else {
            result["message"] = errorMes
            result["code"] = errorCode
        }
        return result
    }

    private fun getErrorMessage(errorKey: String, withNewLine: Boolean = true): String {
        var errorMes = when (errorKey.lowercase()) {
            "warn_receipt_near_end" -> {
                "Roll paper is nearly end."
            }

            "warn_battery_near_end" -> {
                "Battery level of printer is low."
            }

            "err_no_response", "err_timeout" -> {
                "Please check the connection of the printer and the mobile terminal.\nConnection get lost or timeout."
            }

            "err_cover_open" -> {
                "Please close roll paper cover."
            }

            "err_receipt_end", "err_empty" -> {
                "Please check roll paper."
            }

            "err_paper_feed" -> {
                "Please release a paper feed switch."
            }

            "err_autocutter", "err_cutter" -> {
                "Please remove jammed paper and close roll paper cover.\nRemove any jammed paper or foreign substances in the printer, and then turn the printer off and turn the printer on again."
            }

            "err_need_recover" -> {
                "Then, If the printer doesn\'t recover from error, please cycle the power switch."
            }

            "err_unrecover", "err_unrecoverable" -> {
                "Please cycle the power switch of the printer.\nIf same errors occurred even power cycled, the printer may be out of order."
            }

            "err_overheat" -> {
                "Please wait until error LED of the printer turns off. "
            }

            "err_head" -> {
                "Print head of printer is hot."
            }

            "err_motor" -> {
                "Motor Driver IC of printer is hot."
            }

            "err_battery" -> {
                "Battery of printer is hot."
            }

            "err_wrong_paper" -> {
                "Please set correct roll paper."
            }

            "err_battery_real_end", "err_battery_end" -> {
                "Please connect AC adapter or change the battery.\nBattery of printer is almost empty."
            }

            "err_offline" -> {
                "Printer is offline."
            }
            
            "err_mechanical" -> {
                "Mechanical error occurred."
            }
            
            "err_failure" -> {
                "Print job failed."
            }
            
            "err_system" -> {
                "System error occurred."
            }

            else -> "Unknown error. Please check the power and communication status of the printer."
        }
        if (withNewLine) {
            return "$errorMes\n"
        }
        return errorMes
    }

    private val _REQUEST_PERMISSION = 100
    private fun requestRuntimePermission(@NonNull call: MethodCall, @NonNull result: Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            result.notImplemented()
            return
        }
        val requestPermissions: ArrayList<String> = ArrayList()
        if (Build.VERSION_CODES.S <= Build.VERSION.SDK_INT) {
            // If your app targets Android 12 (API level 31) and higher, it's recommended that you declare BLUETOOTH permission.
            val permissionBluetoothScan: Int =
                ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN)
            val permissionBluetoothConnect: Int =
                ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
            if (permissionBluetoothScan == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (permissionBluetoothConnect == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (Build.VERSION_CODES.Q <= Build.VERSION.SDK_INT && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
            // If your app targets Android 11 (API level 30) or lower, it's necessary that you declare ACCESS_FINE_LOCATION permission.
            val permissionLocationFine: Int =
                ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
            if (permissionLocationFine == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        } else {
            // If your app targets Android 9 (API level 28) or lower, you can declare the ACCESS_COARSE_LOCATION permission instead of the ACCESS_FINE_LOCATION permission.
            val permissionLocationCoarse: Int =
                ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (permissionLocationCoarse == PackageManager.PERMISSION_DENIED) {
                requestPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (requestPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                requestPermissions.toArray(arrayOfNulls<String>(requestPermissions.size)),
                _REQUEST_PERMISSION
            )
        }
    }

//  @Override
//  fun onRequestPermissionsResult(
//    requestCode: Int,
//    @NonNull permissions: Array<String>,
//    @NonNull grantResults: IntArray
//  ) {
//    if (requestCode != _REQUEST_PERMISSION || grantResults.isEmpty()) {
//      return
//    }
//    val requestPermissions: ArrayList<String> = ArrayList()
//    for (i in permissions.indices) {
//      if (Build.VERSION_CODES.S <= Build.VERSION.SDK_INT) {
//        // If your app targets Android 12 (API level 31) and higher, it's recommended that you declare BLUETOOTH permission.
//        if (permissions[i] == Manifest.permission.BLUETOOTH_SCAN
//          && grantResults[i] == PackageManager.PERMISSION_DENIED
//        ) {
//          requestPermissions.add(permissions[i])
//        }
//        if (permissions[i] == Manifest.permission.BLUETOOTH_CONNECT
//          && grantResults[i] == PackageManager.PERMISSION_DENIED
//        ) {
//          requestPermissions.add(permissions[i])
//        }
//      } else if (Build.VERSION_CODES.Q <= Build.VERSION.SDK_INT && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
//        // If your app targets Android 11 (API level 30) or lower, it's necessary that you declare ACCESS_FINE_LOCATION permission.
//        if (permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION
//          && grantResults[i] == PackageManager.PERMISSION_DENIED
//        ) {
//          requestPermissions.add(permissions[i])
//        }
//      } else {
//        // If your app targets Android 9 (API level 28) or lower, you can declare the ACCESS_COARSE_LOCATION permission instead of the ACCESS_FINE_LOCATION permission.
//        if (permissions[i] == Manifest.permission.ACCESS_COARSE_LOCATION
//          && grantResults[i] == PackageManager.PERMISSION_DENIED
//        ) {
//          requestPermissions.add(permissions[i])
//        }
//      }
//    }
//    if (!requestPermissions.isEmpty()) {
//      ActivityCompat.requestPermissions(
//        activity,
//        requestPermissions.toArray(arrayOfNulls<String>(requestPermissions.size)),
//        _REQUEST_PERMISSION
//      )
//    }
//  }
//
//  //When searching for a device running on Android 10 or later as a Bluetooth-capable device, enable access to location information of the device.
//  private fun enableLocationSetting() {
//    val locationRequest: LocationRequest = LocationRequest.create()
//    locationRequest.setInterval(10000)
//    locationRequest.setFastestInterval(5000)
//    locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
//    val builder: LocationSettingsRequest.Builder = Builder()
//      .addLocationRequest(locationRequest)
//    val client: SettingsClient = LocationServices.getSettingsClient(this)
//    val task: Task<LocationSettingsResponse> = client.checkLocationSettings(builder.build())
//    task.addOnSuccessListener(this, object : OnSuccessListener<LocationSettingsResponse?>() {
//      @Override
//      fun onSuccess(locationSettingsResponse: LocationSettingsResponse?) {
//        // All location settings are satisfied. The client can initialize
//        // location requests here.
//        // ...
//      }
//    })
//    task.addOnFailureListener(this, object : OnFailureListener() {
//      @Override
//      fun onFailure(@NonNull e: Exception) {
//        if (e is ResolvableApiException) {
//          // Location settings are not satisfied, but this can be fixed
//          // by showing the user a dialog.
//          try {
//            // Show the dialog by calling startResolutionForResult(),
//            // and check the result in onActivityResult().
//            val resolvable: ResolvableApiException = e as ResolvableApiException
//            resolvable.startResolutionForResult(
//              this@MainActivity,
//              CommonStatusCodes.RESOLUTION_REQUIRED
//            )
//          } catch (sendEx: IntentSender.SendIntentException) {
//            // Ignore the error.
//          }
//        }
//      }
//    })
//  }
}