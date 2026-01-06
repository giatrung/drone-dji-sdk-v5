package dji.sampleV5.aircraft.util

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.GPSSignalLevel
import dji.v5.et.listen
import dji.v5.manager.KeyManager
import dji.v5.utils.common.LogUtils
import dji.sampleV5.aircraft.util.SocketManager
import dji.sampleV5.aircraft.util.ToastUtils
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TelemetryLogger - Log telemetry data từ drone vào file
 * File được lưu trong Download/Drone-Logs với format: telemetry-YYYY-MM-DD.txt
 */
class TelemetryLogger private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TelemetryLogger"
        private const val LOG_DIR = "Drone-Logs"
        private const val FILE_PREFIX = "telemetry"
        private const val DATE_FORMAT = "yyyy-MM-dd"
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var periodicLogRunnable: Runnable? = null
    private var logCount = 0 // Counter để hiển thị Toast định kỳ
    private var lastLoggedJson: String? = null // Lưu JSON cuối cùng đã log để tránh duplicate

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
        Log.i(TAG, "=== TelemetryLogger.initialize() called ===")
        LogUtils.i(TAG, "=== TelemetryLogger.initialize() called ===")
        
        if (isInitialized.getAndSet(true)) {
            Log.w(TAG, "TelemetryLogger already initialized")
            LogUtils.d(TAG, "TelemetryLogger already initialized")
            return
        }

        try {
            Log.i(TAG, "Creating log file...")
            createLogFile()
            Log.i(TAG, "Starting logging...")
            startLogging()
            
            // Delay setup listeners để đợi SDK sẵn sàng
            Log.i(TAG, "Scheduling telemetry listeners setup (delay 3 seconds)...")
            mainHandler.postDelayed({
                Log.i(TAG, "Setting up telemetry listeners...")
                setupTelemetryListeners()
                
                // Test log ngay sau khi setup
                mainHandler.postDelayed({
                    Log.i(TAG, "Testing telemetry logging...")
                    logTelemetryEntry("TelemetryLogger setup completed - Testing data collection")
                }, 1000)
            }, 3000) // Delay 3 giây để đợi SDK sẵn sàng
            
            Log.i(TAG, "✅ TelemetryLogger initialization scheduled")
            LogUtils.i(TAG, "TelemetryLogger initialization scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize TelemetryLogger: ${e.message}", e)
            LogUtils.e(TAG, "Failed to initialize TelemetryLogger: ${e.message}", e)
        }
    }

    /**
     * Tạo file log mới với format telemetry-YYYY-MM-DD.txt
     */
    private fun createLogFile() {
        Log.i(TAG, "=== createLogFile() called ===")
        
        try {
            // Dùng getExternalFilesDir() - không cần permission trên Android 10+
            val logDir = File(context.getExternalFilesDir(null), LOG_DIR)
            Log.i(TAG, "Using log directory: ${logDir.absolutePath}")
            
            // Tạo thư mục nếu chưa tồn tại
            if (!logDir.exists()) {
                val created = logDir.mkdirs()
                Log.i(TAG, "Created log directory: ${logDir.absolutePath}, success: $created")
            } else {
                Log.i(TAG, "Log directory already exists: ${logDir.absolutePath}")
            }
            
            // Tạo file log với format telemetry-YYYY-MM-DD.txt
            val dateStr = dateFormat.format(Date())
            logFile = File(logDir, "$FILE_PREFIX-$dateStr.txt")
            Log.i(TAG, "Target log file: ${logFile?.absolutePath}")
            
            // Mở FileWriter với append mode
            fileWriter = FileWriter(logFile, true)
            Log.i(TAG, "FileWriter opened successfully")
            
            // Ghi header nếu file mới
            if (logFile!!.length() == 0L) {
                Log.i(TAG, "File is empty, writing header...")
                writeHeader()
                fileWriter?.flush()
            }
            
            val finalSize = logFile?.length() ?: 0L
            Log.i(TAG, "✅ Log file ready: ${logFile?.absolutePath}, size: $finalSize bytes")
            Log.i(TAG, "✅ Log file exists: ${logFile?.exists()}, canWrite: ${logFile?.canWrite()}")
            LogUtils.i(TAG, "Log file ready: ${logFile?.absolutePath}, size: $finalSize bytes")
            
            // Test write ngay để đảm bảo file có thể ghi được
            try {
                fileWriter?.append("=== Test write at ${timeFormat.format(Date())} ===\n")
                fileWriter?.flush()
                val testSize = logFile?.length() ?: 0L
                Log.i(TAG, "✅ Test write successful, file size now: $testSize bytes")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Test write failed: ${e.message}", e)
                e.printStackTrace()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create log file: ${e.message}", e)
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
        
        // Start periodic logging to ensure data is written even if listeners don't trigger
        startPeriodicLogging()
    }
    
    /**
     * Start periodic logging every 5 seconds to ensure telemetry data is written
     * (Chỉ log khi có thay đổi hoặc sau một khoảng thời gian dài)
     */
    private fun startPeriodicLogging() {
        periodicLogRunnable = object : Runnable {
            override fun run() {
                if (isLogging.get()) {
                    // Force log current telemetry data (chỉ log nếu có thay đổi)
                    logTelemetryData(forceLog = false) // Không force, chỉ log nếu có thay đổi
                    
                    // Schedule next log
                    mainHandler.postDelayed(this, 5000) // Log every 5 seconds (giảm tần suất)
                }
            }
        }
        mainHandler.postDelayed(periodicLogRunnable!!, 5000) // Start after 5 seconds
        Log.i(TAG, "Periodic logging started (every 5 seconds, only on changes)")
        LogUtils.i(TAG, "Periodic logging started")
    }
    
    /**
     * Stop periodic logging
     */
    private fun stopPeriodicLogging() {
        periodicLogRunnable?.let {
            mainHandler.removeCallbacks(it)
            periodicLogRunnable = null
        }
        Log.i(TAG, "Periodic logging stopped")
        LogUtils.i(TAG, "Periodic logging stopped")
    }

    /**
     * Thiết lập listeners cho các telemetry keys
     */
    private fun setupTelemetryListeners() {
        Log.i(TAG, "Setting up telemetry listeners...")
        LogUtils.i(TAG, "Setting up telemetry listeners...")
        
        // GPS Location (2D - latitude, longitude)
        KeyTools.createKey(FlightControllerKey.KeyAircraftLocation).listen(this) { location ->
            lastLocation = location
            Log.d(TAG, "Location updated: lat=${location?.latitude}, lng=${location?.longitude}")
            logTelemetryData()
        }

        // GPS Location 3D (latitude, longitude, altitude) - Lấy altitude từ đây nếu KeyAltitude không hoạt động
        KeyTools.createKey(FlightControllerKey.KeyAircraftLocation3D).listen(this) { location3D ->
            location3D?.let {
                // Update location 2D từ location 3D
                lastLocation = LocationCoordinate2D(it.latitude, it.longitude)
                
                // Update altitude từ location 3D
                if (it.altitude != null && it.altitude > 0) {
                    lastAltitude = it.altitude
                    Log.d(TAG, "Altitude from Location3D: ${it.altitude} m")
                }
                
                Log.d(TAG, "Location3D updated: lat=${it.latitude}, lng=${it.longitude}, alt=${it.altitude}")
                logTelemetryData()
            }
        }

        // Altitude (relative altitude from barometer)
        KeyTools.createKey(FlightControllerKey.KeyAltitude).listen(this) { altitude ->
            altitude?.let {
                lastAltitude = it
                Log.d(TAG, "Altitude updated from KeyAltitude: $it m")
                logTelemetryData()
            } ?: run {
                Log.w(TAG, "Altitude is null from KeyAltitude")
            }
        }

        // Battery Percentage
        KeyTools.createKey(
            BatteryKey.KeyChargeRemainingInPercent,
            ComponentIndexType.LEFT_OR_MAIN
        ).listen(this) { percent ->
            lastBatteryPercent = percent
            logTelemetryData()
        }

               // Speed (calculated from velocity components in m/s)
               KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity).listen(this) { velocity ->
                   velocity?.let {
                       val x = it.x ?: 0.0  // Velocity X component in m/s
                       val y = it.y ?: 0.0  // Velocity Y component in m/s
                       val z = it.z ?: 0.0  // Velocity Z component in m/s
                       lastSpeed = Math.sqrt(x * x + y * y + z * z)  // Total speed in m/s
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

        Log.i(TAG, "✅ Telemetry listeners setup completed")
        LogUtils.i(TAG, "Telemetry listeners setup completed")
    }

    /**
     * Ghi telemetry data vào file
     * @param forceLog Nếu true, log ngay cả khi data không thay đổi (dùng cho listeners)
     */
    private fun logTelemetryData(forceLog: Boolean = true) {
        if (!isLogging.get()) {
            Log.d(TAG, "Logging is disabled, skipping telemetry data log")
            return
        }

        try {
            val json = JSONObject()
            json.put("timestamp", timeFormat.format(Date()))
            
            var hasData = false
            
            lastLocation?.let {
                json.put("latitude", it.latitude)      // Latitude in degrees
                json.put("longitude", it.longitude)     // Longitude in degrees
                hasData = true
            }
            
            lastAltitude?.let { 
                json.put("altitude_m", it)        // Altitude in meters
                hasData = true
            }
            lastBatteryPercent?.let { 
                json.put("battery_percent", it)  // Battery percentage (0-100)
                hasData = true
            }
            lastSpeed?.let { 
                json.put("speed_mps", it)            // Speed in m/s (meters per second)
                hasData = true
            }
            lastHeading?.let { 
                json.put("heading_deg", it)       // Heading in degrees (0-360)
                hasData = true
            }
            lastIsFlying?.let { 
                json.put("is_flying", it)
                hasData = true
            }
            lastFlightMode?.let { 
                json.put("flight_mode", it)
                hasData = true
            }
            lastGPSSignalLevel?.let { 
                json.put("gps_signal_level", it.name)
                hasData = true
            }
            lastSatelliteCount?.let { 
                json.put("satellite_count", it)
                hasData = true
            }

            // Only write if we have at least some data
            if (hasData) {
                val jsonString = json.toString()
                
                // Kiểm tra xem data có thay đổi không (tránh duplicate logs)
                if (!forceLog && lastLoggedJson == jsonString) {
                    Log.d(TAG, "Telemetry data unchanged, skipping log")
                    return
                }
                
                // Lưu JSON đã log (bỏ qua timestamp để so sánh)
                val jsonWithoutTimestamp = JSONObject(jsonString).apply {
                    remove("timestamp")
                }.toString()
                lastLoggedJson = jsonWithoutTimestamp
                
                writeToFile(jsonString)
                Log.d(TAG, "Telemetry data logged: $jsonString")
                
                // Send telemetry data to server via SocketManager
                try {
                    SocketManager.getInstance(context).sendTelemetryData(json)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send telemetry data to server: ${e.message}")
                    LogUtils.w(TAG, "Failed to send telemetry data to server: ${e.message}")
                }
            } else {
                Log.d(TAG, "No telemetry data available to log")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log telemetry data: ${e.message}", e)
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
     * Log PONG message từ Socket.IO
     */
    fun logPong(pongData: JSONObject) {
        if (!isLogging.get()) return

        try {
            val timestamp = pongData.optString("timestamp", "")
            val message = pongData.optString("message", "")
            val receivedAt = pongData.optString("receivedAt", "")

            val logMessage = "🏓 PONG - timestamp: $timestamp, receivedAt: $receivedAt, message: $message"
            logTelemetryEntry(logMessage)

            Log.i(TAG, "PONG logged to file: $logMessage")
            LogUtils.i(TAG, "PONG logged to file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log PONG: ${e.message}", e)
            LogUtils.e(TAG, "Failed to log PONG: ${e.message}", e)
        }
    }

    /**
     * Log command message (public method)
     */
    fun logCommand(message: String) {
        if (!isLogging.get()) return

        try {
            logTelemetryEntry(message)
            Log.i(TAG, "Command logged: $message")
            LogUtils.i(TAG, "Command logged: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log command: ${e.message}", e)
            LogUtils.e(TAG, "Failed to log command: ${e.message}", e)
        }
    }

    /**
     * Ghi dữ liệu vào file
     */
    private fun writeToFile(data: String) {
        try {
            if (fileWriter == null) {
                Log.e(TAG, "❌ FileWriter is null! Cannot write to file.")
                LogUtils.e(TAG, "FileWriter is null! Cannot write to file.")
                return
            }
            
            if (logFile == null) {
                Log.e(TAG, "❌ LogFile is null! Cannot write to file.")
                LogUtils.e(TAG, "LogFile is null! Cannot write to file.")
                return
            }
            
            fileWriter?.append(data)
            fileWriter?.append("\n")
            fileWriter?.flush()
            
            logCount++
            val fileSize = logFile?.length() ?: 0L
            Log.d(TAG, "✅ Data written to file: ${logFile?.absolutePath}, size: $fileSize bytes, count: $logCount")
            
            // Hiển thị Toast mỗi 10 lần ghi hoặc lần đầu tiên
            if (logCount == 1 || logCount % 10 == 0) {
                mainHandler.post {
                    val message = if (logCount == 1) {
                        "✅ Telemetry logging started"
                    } else {
                        "✅ Telemetry logged ($logCount entries, ${fileSize / 1024}KB)"
                    }
                    ToastUtils.showShortToast(message)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ Failed to write to file: ${e.message}", e)
            LogUtils.e(TAG, "Failed to write to file: ${e.message}", e)
            e.printStackTrace()
            
            // Hiển thị Toast lỗi
            mainHandler.post {
                ToastUtils.showShortToast("❌ Failed to write telemetry log")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error writing to file: ${e.message}", e)
            LogUtils.e(TAG, "Unexpected error writing to file: ${e.message}", e)
            e.printStackTrace()
            
            // Hiển thị Toast lỗi
            mainHandler.post {
                ToastUtils.showShortToast("❌ Error writing telemetry log")
            }
        }
    }

    /**
     * Dừng logging và đóng file
     */
    fun stop() {
        if (!isLogging.getAndSet(false)) return

        try {
            stopPeriodicLogging()
            logTelemetryEntry("Application stopped - Telemetry logging ended")
            fileWriter?.flush()
            fileWriter?.close()
            fileWriter = null
            
            KeyManager.getInstance().cancelListen(this)
            Log.i(TAG, "TelemetryLogger stopped")
            LogUtils.i(TAG, "TelemetryLogger stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop TelemetryLogger: ${e.message}", e)
            LogUtils.e(TAG, "Failed to stop TelemetryLogger: ${e.message}", e)
        }
    }
}

