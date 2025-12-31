package dji.sampleV5.aircraft.util

import android.content.Context
import android.os.Environment
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.utils.common.LogUtils
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TelemetryLogger - Log telemetry data từ drone vào file
 * File được lưu trong Download/Drone-Logs với format: telemetry-dd-mm-YYYY.txt
 */
class TelemetryLogger private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TelemetryLogger"
        private const val LOG_DIR = "Drone-Logs"
        private const val FILE_PREFIX = "telemetry"
        private const val DATE_FORMAT = "dd-MM-yyyy"
        private const val TIME_FORMAT = "HH:mm:ss.SSS"
        
        @Volatile
        private var INSTANCE: TelemetryLogger? = null
        
        fun getInstance(context: Context): TelemetryLogger {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelemetryLogger(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
    private val timeFormat = SimpleDateFormat(TIME_FORMAT, Locale.getDefault())
    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    private val isInitialized = AtomicBoolean(false)
    private val isLogging = AtomicBoolean(false)

    // Telemetry data cache
    private var lastLocation: LocationCoordinate2D? = null
    private var lastAltitude: Double? = null
    private var lastBatteryPercent: Int? = null
    private var lastSpeed: Double? = null
    private var lastHeading: Double? = null
    private var lastIsFlying: Boolean? = null
    private var lastFlightMode: String? = null
    private var lastGPSSignalLevel: GPSSignalLevel? = null
    private var lastSatelliteCount: Int? = null

    /**
     * Khởi tạo logger và tạo file log mới
     */
    fun initialize() {
        if (isInitialized.getAndSet(true)) {
            LogUtils.d(TAG, "TelemetryLogger already initialized")
            return
        }

        try {
            createLogFile()
            startLogging()
            setupTelemetryListeners()
            LogUtils.i(TAG, "TelemetryLogger initialized successfully")
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to initialize TelemetryLogger: ${e.message}", e)
        }
    }

    /**
     * Tạo file log mới với format telemetry-dd-mm-YYYY.txt
     */
    private fun createLogFile() {
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val logDir = File(downloadDir, LOG_DIR)
            
            if (!logDir.exists()) {
                logDir.mkdirs()
            }

            val dateStr = dateFormat.format(Date())
            val fileName = "$FILE_PREFIX-$dateStr.txt"
            logFile = File(logDir, fileName)

            // Append mode - nếu file đã tồn tại, tiếp tục ghi vào file đó
            fileWriter = FileWriter(logFile, true)
            
            // Ghi header nếu file mới
            if (logFile?.length() == 0L) {
                writeHeader()
            }
            
            LogUtils.i(TAG, "Log file created: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to create log file: ${e.message}", e)
            throw e
        }
    }

    /**
     * Ghi header vào file log
     */
    private fun writeHeader() {
        val header = """
            |========================================
            |Telemetry Log Started: ${timeFormat.format(Date())}
            |========================================
            |
        """.trimMargin()
        writeToFile(header)
    }

    /**
     * Bắt đầu logging
     */
    private fun startLogging() {
        isLogging.set(true)
        logTelemetryEntry("Application started - Telemetry logging initialized")
    }

    /**
     * Thiết lập listeners cho các telemetry keys
     */
    private fun setupTelemetryListeners() {
        val keyManager = KeyManager.getInstance()

        // GPS Location
        KeyTools.createKey(FlightControllerKey.KeyAircraftLocation).listen(this) { location ->
            lastLocation = location
            logTelemetryData()
        }

        // Altitude
        KeyTools.createKey(FlightControllerKey.KeyAltitude).listen(this) { altitude ->
            lastAltitude = altitude
            logTelemetryData()
        }

        // Battery Percentage
        KeyTools.createKey(
            BatteryKey.KeyChargeRemainingInPercent,
            ComponentIndexType.LEFT_OR_MAIN
        ).listen(this) { percent ->
            lastBatteryPercent = percent
            logTelemetryData()
        }

        // Speed
        KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity).listen(this) { velocity ->
            velocity?.let {
                val x = it.x ?: 0.0
                val y = it.y ?: 0.0
                val z = it.z ?: 0.0
                lastSpeed = Math.sqrt(x * x + y * y + z * z)
            }
            logTelemetryData()
        }

        // Heading
        KeyTools.createKey(FlightControllerKey.KeyCompassHeading).listen(this) { heading ->
            lastHeading = heading
            logTelemetryData()
        }

        // Is Flying
        KeyTools.createKey(FlightControllerKey.KeyIsFlying).listen(this) { isFlying ->
            lastIsFlying = isFlying
            logTelemetryData()
        }

        // Flight Mode
        KeyTools.createKey(FlightControllerKey.KeyFlightModeString).listen(this) { flightMode ->
            lastFlightMode = flightMode
            logTelemetryData()
        }

        // GPS Signal Level
        KeyTools.createKey(FlightControllerKey.KeyGPSSignalLevel).listen(this) { signalLevel ->
            lastGPSSignalLevel = signalLevel
            logTelemetryData()
        }

        // Satellite Count
        KeyTools.createKey(FlightControllerKey.KeyGPSSatelliteCount).listen(this) { satelliteCount ->
            lastSatelliteCount = satelliteCount
            logTelemetryData()
        }

        LogUtils.i(TAG, "Telemetry listeners setup completed")
    }

    /**
     * Ghi telemetry data vào file
     */
    private fun logTelemetryData() {
        if (!isLogging.get()) return

        try {
            val json = JSONObject()
            json.put("timestamp", timeFormat.format(Date()))
            
            lastLocation?.let {
                json.put("latitude", it.latitude)
                json.put("longitude", it.longitude)
            }
            
            lastAltitude?.let { json.put("altitude_m", it) }
            lastBatteryPercent?.let { json.put("battery_percent", it) }
            lastSpeed?.let { json.put("speed_mps", it) }
            lastHeading?.let { json.put("heading_deg", it) }
            lastIsFlying?.let { json.put("is_flying", it) }
            lastFlightMode?.let { json.put("flight_mode", it) }
            lastGPSSignalLevel?.let { json.put("gps_signal_level", it.name) }
            lastSatelliteCount?.let { json.put("satellite_count", it) }

            writeToFile(json.toString())
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to log telemetry data: ${e.message}", e)
        }
    }

    /**
     * Ghi một entry vào file log
     */
    private fun logTelemetryEntry(message: String) {
        if (!isLogging.get()) return
        
        try {
            val entry = "[${timeFormat.format(Date())}] $message\n"
            writeToFile(entry)
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to log entry: ${e.message}", e)
        }
    }

    /**
     * Ghi dữ liệu vào file
     */
    private fun writeToFile(data: String) {
        try {
            fileWriter?.append(data)
            fileWriter?.append("\n")
            fileWriter?.flush()
        } catch (e: IOException) {
            LogUtils.e(TAG, "Failed to write to file: ${e.message}", e)
        }
    }

    /**
     * Dừng logging và đóng file
     */
    fun stop() {
        if (!isLogging.getAndSet(false)) return

        try {
            logTelemetryEntry("Application stopped - Telemetry logging ended")
            fileWriter?.flush()
            fileWriter?.close()
            fileWriter = null
            
            KeyManager.getInstance().cancelListen(this)
            LogUtils.i(TAG, "TelemetryLogger stopped")
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to stop TelemetryLogger: ${e.message}", e)
        }
    }
}

