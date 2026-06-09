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
import com.google.gson.annotations.SerializedName
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import com.epson.epos2.Log as PrintLog
import java.net.Inet4Address
import java.net.NetworkInterface


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
    @SerializedName("status_code")
    var statusCode: Int = EpsonStatusCode.UNKNOWN,
    var code: Int? = null,
    var content: Any? = null
) : JSONConvertable

/**
 * Unified status codes returned to Flutter side.
 * Keep in sync with the Dart-side constants in lib/const.dart.
 */
object EpsonStatusCode {
    const val SUCCESS = 0
    const val PRINTING = 1

    // Connection (100-199)
    const val ERR_CONNECT = 100
    const val ERR_DISCONNECT = 101
    const val ERR_OFFLINE = 102
    const val ERR_NO_RESPONSE = 103
    const val ERR_TIMEOUT = 104
    const val ERR_PORT = 105
    const val ERR_NOT_FOUND = 106

    // Paper (200-299)
    const val ERR_RECEIPT_END = 200
    const val ERR_PAPER_FEED = 201
    const val ERR_WRONG_PAPER = 202
    const val ERR_EMPTY = 203

    // Cover / cutter (300-399)
    const val ERR_COVER_OPEN = 300
    const val ERR_AUTOCUTTER = 301
    const val ERR_CUTTER = 302
    const val ERR_MECHANICAL = 303

    // Hardware health (400-499)
    const val ERR_OVERHEAT_HEAD = 400
    const val ERR_OVERHEAT_MOTOR = 401
    const val ERR_OVERHEAT_BATTERY = 402
    const val ERR_BATTERY_END = 403
    const val ERR_UNRECOVER = 404
    const val ERR_AUTORECOVER = 405

    // System (500-599)
    const val ERR_FAILURE = 500
    const val ERR_SYSTEM = 501
    const val ERR_PARAM = 502
    const val ERR_PROCESSING = 503

    const val UNKNOWN = 999
}

/** EpsonEposPlugin */
class EpsonEposPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {
    private lateinit var channel: MethodChannel
    private val logTag: String = "Epson_ePOS"
    private lateinit var context: Context
    private var activity: Activity? = null
    private var mPrinter: Printer? = null
    private var mTarget: String? = null
    private val printers: MutableList<EpsonEposPrinterInfo> = mutableListOf()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var discoveryFinishRunnable: Runnable? = null

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
            val hasSpecificError =
                errorFromStatus != null && errorFromStatus.statusCode != EpsonStatusCode.UNKNOWN

            if (p1 == 0 && !hasSpecificError) {
                resp.success = true
                resp.statusCode = EpsonStatusCode.SUCCESS
                resp.message = "Success"
            } else {
                resp.success = false

                if (hasSpecificError) {
                    resp.statusCode = errorFromStatus!!.statusCode
                    resp.message = errorFromStatus.message
                    resp.content = errorFromStatus.legacyCode
                } else {
                    val callbackErrorKey = getCallbackErrorCode(p1)
                    resp.statusCode = callbackCodeToStatus(p1)
                    resp.message = getErrorMessage(callbackErrorKey.lowercase())
                    resp.content = callbackErrorKey
                }
            }

            Log.d(
                logTag,
                "onPtrReceive: Code $p1, status_code ${resp.statusCode}, Status $p2, Message ${resp.message}"
            )
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
     * Stop discovery printer. Loops while SDK reports ERR_PROCESSING so a new
     * [Discovery.start] is not rejected with ERR_ILLEGAL.
     */
    private fun stopDiscovery() {
        discoveryFinishRunnable?.let { mainHandler.removeCallbacks(it) }
        discoveryFinishRunnable = null
        while (true) {
            try {
                Discovery.stop()
                break
            } catch (e: Epos2Exception) {
                if (e.errorStatus != Epos2Exception.ERR_PROCESSING) {
                    Log.w(logTag, "stopDiscovery error: ${e.errorStatus}", e)
                    break
                }
            }
        }
    }

    /**
     * Epson TCP discovery sends UDP broadcast to find printers. On many Android
     * POS devices (including Sunmi), 255.255.255.255 is blocked; the subnet
     * broadcast (e.g. 192.168.1.255) must be used instead.
     */
    private fun resolveSubnetBroadcast(): String {
        try {
            val candidates = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                for (address in networkInterface.interfaceAddresses) {
                    val broadcast = address.broadcast ?: continue
                    if (broadcast !is Inet4Address) continue
                    val host = broadcast.hostAddress ?: continue
                    if (host != "255.255.255.255") {
                        candidates.add(host)
                    }
                }
            }
            if (candidates.isNotEmpty()) {
                Log.d(logTag, "resolveSubnetBroadcast: $candidates")
                return candidates.first()
            }
        } catch (e: Exception) {
            Log.w(logTag, "resolveSubnetBroadcast failed", e)
        }
        return "255.255.255.255"
    }

    private fun getDiscoveryErrorMessage(errorStatus: Int): String = when (errorStatus) {
        Epos2Exception.ERR_PARAM -> "Invalid discovery parameters."
        Epos2Exception.ERR_ILLEGAL ->
            "Discovery is already running. Wait for the current search to finish."
        Epos2Exception.ERR_PROCESSING -> "Discovery is still processing."
        Epos2Exception.ERR_FAILURE -> "Discovery failed. Check permissions and connection type."
        Epos2Exception.ERR_NOT_FOUND -> "No printer found."
        else -> "Discovery error (code $errorStatus). Check Bluetooth/Location permissions and network."
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
        stopDiscovery()
        printers.clear()
        val broadcastArg: String? = call.argument<String>("broadcast")
        val filter = FilterOption().apply {
            this.portType = portType
            if (portType == Discovery.PORTTYPE_TCP || portType == Discovery.TYPE_ALL) {
                broadcast = broadcastArg ?: resolveSubnetBroadcast()
            }
        }
        Log.d(
            logTag,
            "onDiscoveryPrinter: portType=$portType broadcast=${filter.broadcast}"
        )

        val resp = EpsonEposPrinterResult("onDiscovery", false)
        mainHandler.post {
            try {
                Discovery.start(context, filter, mDiscoveryListener)
                val finishRunnable = Runnable {
                    resp.success = true
                    resp.statusCode = EpsonStatusCode.SUCCESS
                    resp.message = "Successfully!"
                    resp.content = printers
                    Log.d(logTag, "onDiscoveryPrinter: found ${printers.size} printer(s)")
                    result.success(resp.toJSON())
                    stopDiscovery()
                }
                discoveryFinishRunnable = finishRunnable
                mainHandler.postDelayed(finishRunnable, delay)
            } catch (e: Epos2Exception) {
                Log.e(
                    logTag,
                    "onDiscoveryPrinter: Epos2Exception errorStatus=${e.errorStatus}",
                    e
                )
                resp.success = false
                resp.statusCode = epos2ExceptionToStatus(e.errorStatus)
                resp.code = e.errorStatus
                resp.message = getDiscoveryErrorMessage(e.errorStatus)
                result.success(resp.toJSON())
            } catch (e: Exception) {
                Log.e(logTag, "onDiscoveryPrinter: start not working for ${call.method}", e)
                resp.success = false
                resp.statusCode = EpsonStatusCode.ERR_FAILURE
                resp.message = e.localizedMessage ?: "Error while search printer"
                result.success(resp.toJSON())
            }
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
                resp.statusCode = if (error.statusCode != EpsonStatusCode.UNKNOWN)
                    error.statusCode else EpsonStatusCode.ERR_CONNECT
                resp.message = error.message
                resp.content = error.legacyCode
                result.success(resp.toJSON())
                mPrinter?.clearCommandBuffer()
            } else {
                mPrinter?.clearCommandBuffer()
            }
        } catch (e: Exception) {
            Log.e(logTag, "getPrinterSetting error", e)
            resp.success = false
            resp.statusCode = EpsonStatusCode.ERR_FAILURE
            resp.message = e.localizedMessage ?: "Print error"
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
                resp.statusCode = if (error.statusCode != EpsonStatusCode.UNKNOWN)
                    error.statusCode else EpsonStatusCode.ERR_CONNECT
                resp.message = error.message
                resp.content = error.legacyCode
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
                    resp.statusCode = EpsonStatusCode.ERR_FAILURE
                    resp.message = ex.localizedMessage ?: "Print error"
                    result.success(resp.toJSON())
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "setPrinterSetting outer error", e)
            resp.success = false
            resp.statusCode = EpsonStatusCode.ERR_FAILURE
            resp.message = e.localizedMessage ?: "Print error"
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
                // Try to read printer status to provide a meaningful error
                val statusError = printerStatusError()
                resp.success = false
                resp.statusCode = if (statusError.statusCode != EpsonStatusCode.UNKNOWN)
                    statusError.statusCode else EpsonStatusCode.ERR_CONNECT
                resp.message = if (statusError.statusCode != EpsonStatusCode.UNKNOWN)
                    statusError.message else "Can not connect to the printer."
                resp.content = statusError.legacyCode
                Log.e(logTag, "Cannot ConnectPrinter $resp")
                result.success(resp.toJSON())
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
                    val statusError = printerStatusError()
                    resp.success = false
                    if (statusError.statusCode != EpsonStatusCode.UNKNOWN) {
                        resp.statusCode = statusError.statusCode
                        resp.message = statusError.message
                        resp.content = statusError.legacyCode
                    } else {
                        resp.statusCode = epos2ExceptionToStatus(ex.errorStatus)
                        resp.message = "Send data error: ${ex.errorStatus}"
                    }
                    resp.code = ex.errorStatus
                    result.success(resp.toJSON())
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "onPrint outer error", e)
            resp.success = false
            resp.statusCode = EpsonStatusCode.ERR_FAILURE
            resp.message = e.localizedMessage ?: "Print error"
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

    /**
     * Translates a PrinterStatusInfo into a unified (status_code, message, legacyCode) tuple.
     * legacyCode is kept only for backward compatibility with previous response payload.
     */
    private data class PrinterStatusError(
        val statusCode: Int,
        val message: String,
        val legacyCode: String
    )

    private fun printerStatusError(printerStatus: PrinterStatusInfo? = null): PrinterStatusError {
        if (mPrinter == null && printerStatus == null) {
            return PrinterStatusError(
                EpsonStatusCode.UNKNOWN,
                getErrorMessage(""),
                "ERR_UNKNOWN"
            )
        }
        var errorMes = ""
        var errorCode = ""
        var statusCode: Int = EpsonStatusCode.UNKNOWN
        val status: PrinterStatusInfo? = printerStatus ?: mPrinter?.status

        if (status?.online == Printer.FALSE) {
            errorMes = getErrorMessage("err_offline")
            errorCode = "ERR_OFFLINE"
            statusCode = EpsonStatusCode.ERR_OFFLINE
        }

        if (status?.connection == Printer.FALSE) {
            errorMes = getErrorMessage("err_no_response")
            errorCode = "ERR_NO_RESPONSE"
            statusCode = EpsonStatusCode.ERR_NO_RESPONSE
        }

        if (status?.coverOpen == Printer.TRUE) {
            errorMes = getErrorMessage("err_cover_open")
            errorCode = "ERR_COVER_OPEN"
            statusCode = EpsonStatusCode.ERR_COVER_OPEN
        }

        if (status?.paper == Printer.PAPER_EMPTY) {
            errorMes = getErrorMessage("err_receipt_end")
            errorCode = "ERR_RECEIPT_END"
            statusCode = EpsonStatusCode.ERR_RECEIPT_END
        }

        if (status?.paperFeed == Printer.TRUE || status?.panelSwitch == Printer.SWITCH_ON) {
            errorMes = getErrorMessage("err_paper_feed")
            errorCode = "ERR_PAPER_FEED"
            statusCode = EpsonStatusCode.ERR_PAPER_FEED
        }

        if (status?.errorStatus == Printer.UNRECOVER_ERR) {
            errorMes = getErrorMessage("err_unrecover")
            errorCode = "ERR_UNRECOVER"
            statusCode = EpsonStatusCode.ERR_UNRECOVER
        }

        if (status?.errorStatus == Printer.MECHANICAL_ERR || status?.errorStatus == Printer.AUTOCUTTER_ERR) {
            errorMes = getErrorMessage("err_autocutter") + " " + getErrorMessage("err_need_recover")
            errorCode = "ERR_AUTOCUTTER"
            statusCode = EpsonStatusCode.ERR_AUTOCUTTER
        }

        if (status?.errorStatus == Printer.AUTORECOVER_ERR) {
            when (status.autoRecoverError) {
                Printer.HEAD_OVERHEAT -> {
                    errorMes = getErrorMessage("err_head") + " " + getErrorMessage("err_overheat")
                    errorCode = "ERR_OVERHEAT_HEAD"
                    statusCode = EpsonStatusCode.ERR_OVERHEAT_HEAD
                }
                Printer.MOTOR_OVERHEAT -> {
                    errorMes = getErrorMessage("err_motor") + " " + getErrorMessage("err_overheat")
                    errorCode = "ERR_OVERHEAT_MOTOR"
                    statusCode = EpsonStatusCode.ERR_OVERHEAT_MOTOR
                }
                Printer.BATTERY_OVERHEAT -> {
                    errorMes = getErrorMessage("err_battery") + " " + getErrorMessage("err_overheat")
                    errorCode = "ERR_OVERHEAT_BATTERY"
                    statusCode = EpsonStatusCode.ERR_OVERHEAT_BATTERY
                }
                Printer.WRONG_PAPER -> {
                    errorMes = getErrorMessage("err_wrong_paper")
                    errorCode = "ERR_WRONG_PAPER"
                    statusCode = EpsonStatusCode.ERR_WRONG_PAPER
                }
            }
        }
        if (status?.batteryLevel == Printer.BATTERY_LEVEL_0) {
            errorMes = getErrorMessage("err_battery_real_end")
            errorCode = "ERR_BATTERY_END"
            statusCode = EpsonStatusCode.ERR_BATTERY_END
        }

        return if (errorMes.isEmpty()) {
            PrinterStatusError(EpsonStatusCode.UNKNOWN, getErrorMessage(""), "ERR_UNKNOWN")
        } else {
            PrinterStatusError(statusCode, errorMes.trim(), errorCode)
        }
    }

    /**
     * Map an Epson SDK callback `code` (0..12) to the unified [EpsonStatusCode].
     */
    private fun callbackCodeToStatus(code: Int): Int = when (code) {
        0 -> EpsonStatusCode.SUCCESS
        1 -> EpsonStatusCode.PRINTING
        2 -> EpsonStatusCode.ERR_AUTORECOVER
        3 -> EpsonStatusCode.ERR_COVER_OPEN
        4 -> EpsonStatusCode.ERR_CUTTER
        5 -> EpsonStatusCode.ERR_MECHANICAL
        6 -> EpsonStatusCode.ERR_EMPTY
        7 -> EpsonStatusCode.ERR_UNRECOVER
        8 -> EpsonStatusCode.ERR_FAILURE
        9 -> EpsonStatusCode.ERR_NOT_FOUND
        10 -> EpsonStatusCode.ERR_SYSTEM
        11 -> EpsonStatusCode.ERR_PORT
        12 -> EpsonStatusCode.ERR_TIMEOUT
        else -> EpsonStatusCode.UNKNOWN
    }

    /**
     * Map an Epos2Exception.errorStatus to the unified [EpsonStatusCode].
     */
    private fun epos2ExceptionToStatus(errorStatus: Int): Int = when (errorStatus) {
        Epos2Exception.ERR_PARAM -> EpsonStatusCode.ERR_PARAM
        Epos2Exception.ERR_CONNECT -> EpsonStatusCode.ERR_CONNECT
        Epos2Exception.ERR_TIMEOUT -> EpsonStatusCode.ERR_TIMEOUT
        Epos2Exception.ERR_MEMORY -> EpsonStatusCode.ERR_SYSTEM
        Epos2Exception.ERR_ILLEGAL -> EpsonStatusCode.ERR_FAILURE
        Epos2Exception.ERR_PROCESSING -> EpsonStatusCode.ERR_PROCESSING
        Epos2Exception.ERR_NOT_FOUND -> EpsonStatusCode.ERR_NOT_FOUND
        Epos2Exception.ERR_IN_USE -> EpsonStatusCode.ERR_FAILURE
        Epos2Exception.ERR_TYPE_INVALID -> EpsonStatusCode.ERR_PARAM
        Epos2Exception.ERR_DISCONNECT -> EpsonStatusCode.ERR_DISCONNECT
        Epos2Exception.ERR_FAILURE -> EpsonStatusCode.ERR_FAILURE
        else -> EpsonStatusCode.UNKNOWN
    }

    private fun getErrorMessage(errorKey: String, withNewLine: Boolean = false): String {
        val errorMes = when (errorKey.lowercase()) {
            "warn_receipt_near_end" -> "Roll paper is nearly end."
            "warn_battery_near_end" -> "Battery level of printer is low."
            "err_no_response", "err_timeout" ->
                "Please check the connection of the printer."
            "err_cover_open" -> "Please close roll paper cover."
            "err_receipt_end", "err_empty" -> "Please check roll paper."
            "err_paper_feed" -> "Please release a paper feed switch."
            "err_autocutter", "err_cutter" ->
                "Please remove jammed paper and close roll paper cover. Remove any jammed paper or foreign substances in the printer, and then turn the printer off and turn the printer on again."
            "err_need_recover" ->
                "Then, If the printer doesn\'t recover from error, please cycle the power switch."
            "err_unrecover", "err_unrecoverable" ->
                "Please cycle the power switch of the printer. If same errors occurred even power cycled, the printer may be out of order."
            "err_overheat" -> "Please wait until error LED of the printer turns off."
            "err_head" -> "Print head of printer is hot."
            "err_motor" -> "Motor Driver IC of printer is hot."
            "err_battery" -> "Battery of printer is hot."
            "err_wrong_paper" -> "Please set correct roll paper."
            "err_battery_real_end", "err_battery_end" ->
                "Please connect AC adapter or change the battery. Battery of printer is almost empty."
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
            result.success(true)
        } else {
            result.success(false)
        }
    }
}
