package dji.sampleV5.aircraft.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.common.LocationCoordinate3D
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.sampleV5.aircraft.util.ToastUtils
import dji.v5.manager.aircraft.virtualstick.Stick
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import dji.v5.manager.intelligent.IntelligentFlightManager
import dji.v5.manager.intelligent.flyto.FlyToTarget
import dji.v5.utils.common.LogUtils
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SocketManager - Quản lý kết nối Socket.IO với server
 */
class SocketManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SocketManager"
        private const val SERVER_URL = "https://chorded-angelita-nonreticently.ngrok-free.dev"
        private const val NAMESPACE = "/drone"
        private const val NGROK_SKIP_WARNING = "true"
        private const val DEFAULT_DRONE_ID = "1" // Default drone ID, có thể config sau

        @Volatile
        private var INSTANCE: SocketManager? = null

        fun getInstance(context: Context): SocketManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SocketManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var socket: Socket? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var pongListener: ((JSONObject) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Mission state
    private var currentMission: MissionData? = null
    private var isMissionRunning = AtomicBoolean(false)
    private var currentWaypointIndex = 0
    
    // Virtual Stick state
    private var isVirtualStickEnabled = false
    private var movementStopRunnable: Runnable? = null

    /**
     * Kết nối đến server
     */
    fun connect() {
        if (socket?.connected() == true) {
            Log.w(TAG, "Socket already connected")
            return
        }

        try {
            val options = IO.Options()
            
            // Headers để bypass ngrok warning
            val headers = HashMap<String, List<String>>()
            headers["ngrok-skip-browser-warning"] = listOf(NGROK_SKIP_WARNING)
            options.extraHeaders = headers
            
            // Ép dùng WebSocket để ổn định hơn
            options.transports = arrayOf(WebSocket.NAME)
            
            // Cấu hình reconnection
            options.reconnection = true
            options.reconnectionAttempts = Int.MAX_VALUE
            options.reconnectionDelay = 1000
            options.reconnectionDelayMax = 5000
            options.timeout = 30000
            options.forceNew = false

            val url = "$SERVER_URL$NAMESPACE"
            Log.i(TAG, "Connecting to: $url")
            LogUtils.i(TAG, "Connecting to Socket.IO server: $url (using WebSocket transport)")
            socket = IO.socket(url, options)

            setupEventListeners()
            socket?.connect()

            Log.i(TAG, "Socket connection initiated")
            LogUtils.i(TAG, "Socket connection initiated")
            ToastUtils.showShortToast("✅ Socket connected")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect socket: ${e.message}", e)
            LogUtils.e(TAG, "Failed to connect socket: ${e.message}", e)
            ToastUtils.showShortToast("❌ Failed to connect socket")
        }
    }

    /**
     * Thiết lập event listeners
     */
    private fun setupEventListeners() {
        socket?.let { s ->
            s.on(Socket.EVENT_CONNECT) { args ->
                Log.i(TAG, "✅ Socket connected")
                LogUtils.i(TAG, "Socket connected")
                
                // Auto join drone room để nhận commands
                joinDroneRoom(DEFAULT_DRONE_ID)
                
                // Send app startup message
                sendAppStartupMessage()
            }

            s.on(Socket.EVENT_DISCONNECT) { args ->
                val reason = args.firstOrNull()?.toString() ?: "unknown"
                Log.w(TAG, "❌ Socket disconnected: $reason")
                LogUtils.w(TAG, "Socket disconnected: $reason")
            }

            s.on(Socket.EVENT_CONNECT_ERROR) { args ->
                val error = args.firstOrNull()
                val errorMessage = when (error) {
                    is Exception -> error.message ?: error.toString()
                    else -> error?.toString() ?: "unknown"
                }
                Log.e(TAG, "❌ Socket connection error: $errorMessage")
                LogUtils.e(TAG, "Socket connection error: $errorMessage")
                if (error is Exception) {
                    Log.e(TAG, "Error stack trace:", error)
                }
            }

            // Listen for PONG event
            s.on("pong") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject
                    if (data != null) {
                        val timestamp = data.optString("timestamp", "")
                        val message = data.optString("message", "")
                        
                        val logMessage = "🏓 PONG received - timestamp: $timestamp, message: $message"
                        Log.i(TAG, logMessage)
                        LogUtils.i(TAG, logMessage)
                        
                        // Log to TelemetryLogger
                        TelemetryLogger.getInstance(context).logPong(data)
                        
                        // Show Toast on main thread
                        mainHandler.post {
                            ToastUtils.showShortToast("🏓 PONG received! Message: $message")
                        }
                        
                        // Call custom listener if set
                        pongListener?.invoke(data)
                    } else {
                        Log.w(TAG, "PONG received but data is null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling PONG: ${e.message}", e)
                }
            }

            // Listen for drone commands
            s.on("drone:command") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject
                    if (data != null) {
                        val droneId = data.optString("droneId", "")
                        val command = data.optString("command", "")
                        val timestamp = data.optString("timestamp", "")
                        
                        val logMessage = "📨 Received command: $command for drone $droneId"
                        Log.i(TAG, logMessage)
                        LogUtils.i(TAG, logMessage)
                        
                        // Show Toast immediately when command received
                        mainHandler.post {
                            ToastUtils.showShortToast("📨 Command: $command")
                        }
                        
                        // Execute command on main thread
                        mainHandler.post {
                            executeDroneCommand(command, droneId)
                        }
                    } else {
                        Log.w(TAG, "Drone command received but data is null")
                        mainHandler.post {
                            ToastUtils.showShortToast("⚠️ Command received but data is null")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling drone command: ${e.message}", e)
                    LogUtils.e(TAG, "Error handling drone command: ${e.message}", e)
                    mainHandler.post {
                        ToastUtils.showShortToast("❌ Command error: ${e.message}")
                    }
                }
            }

            // Listen for mission start
            s.on("mission:start") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject
                    if (data != null) {
                        val droneId = data.optString("droneId", "")
                        
                        // Try to get waypoints directly from data (new format)
                        var waypointsArray = data.optJSONArray("waypoints")
                        
                        // If not found, try nested mission object (old format)
                        if (waypointsArray == null) {
                            val missionObj = data.optJSONObject("mission")
                            waypointsArray = missionObj?.optJSONArray("waypoints")
                        }
                        
                        val waypoints = mutableListOf<WaypointData>()
                        
                        if (waypointsArray != null && waypointsArray.length() > 0) {
                            for (i in 0 until waypointsArray.length()) {
                                val wp = waypointsArray.getJSONObject(i)
                                waypoints.add(WaypointData(
                                    latitude = wp.optDouble("latitude", 0.0),
                                    longitude = wp.optDouble("longitude", 0.0),
                                    altitude = wp.optDouble("altitude", 0.0),
                                    action = wp.optString("action", "fly_to")
                                ))
                            }
                            
                            val logMessage = "🎯 Received mission start for drone $droneId with ${waypoints.size} waypoints"
                            Log.i(TAG, logMessage)
                            LogUtils.i(TAG, logMessage)
                            
                            // Show Toast immediately
                            mainHandler.post {
                                ToastUtils.showShortToast("🎯 Mission start: ${waypoints.size} waypoints")
                            }
                            
                            // Execute mission on main thread
                            mainHandler.post {
                                startMission(droneId, waypoints)
                            }
                        } else {
                            val errorMsg = "Mission start received but no waypoints found"
                            Log.w(TAG, errorMsg)
                            LogUtils.w(TAG, errorMsg)
                            mainHandler.post {
                                ToastUtils.showShortToast("❌ Mission failed: No waypoints")
                            }
                        }
                    } else {
                        val errorMsg = "Mission start received but data is null"
                        Log.w(TAG, errorMsg)
                        LogUtils.w(TAG, errorMsg)
                        mainHandler.post {
                            ToastUtils.showShortToast("❌ Mission failed: No data")
                        }
                    }
                } catch (e: Exception) {
                    val errorMsg = "Error handling mission start: ${e.message}"
                    Log.e(TAG, errorMsg, e)
                    LogUtils.e(TAG, errorMsg, e)
                    mainHandler.post {
                        ToastUtils.showShortToast("❌ Mission error: ${e.message}")
                    }
                }
            }

            // Listen for mission end
            s.on("mission:end") { args ->
                try {
                    val data = args.firstOrNull() as? JSONObject
                    if (data != null) {
                        val droneId = data.optString("droneId", "")
                        
                        val logMessage = "🎯 Received mission end for drone $droneId"
                        Log.i(TAG, logMessage)
                        LogUtils.i(TAG, logMessage)
                        
                        // Show Toast immediately
                        mainHandler.post {
                            ToastUtils.showShortToast("⏹️ Mission end command received")
                        }
                        
                        // Execute mission end on main thread
                        mainHandler.post {
                            endMission()
                        }
                    } else {
                        Log.w(TAG, "Mission end received but data is null")
                        mainHandler.post {
                            ToastUtils.showShortToast("⚠️ Mission end: No data")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling mission end: ${e.message}", e)
                    LogUtils.e(TAG, "Error handling mission end: ${e.message}", e)
                    mainHandler.post {
                        ToastUtils.showShortToast("❌ Mission end error: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Data classes for mission
     */
    private data class WaypointData(
        val latitude: Double,      // Latitude in degrees (-90 to 90)
        val longitude: Double,     // Longitude in degrees (-180 to 180)
        val altitude: Double,      // Altitude in meters above ground
        val action: String        // Action type: "fly_to", "hover", "take_photo", etc.
    )

    private data class MissionData(
        val droneId: String,
        val waypoints: List<WaypointData>
    )

    /**
     * Execute drone command (takeoff/land)
     */
    private fun executeDroneCommand(command: String, droneId: String) {
        when (command.lowercase()) {
            "takeoff" -> {
                Log.i(TAG, "🛫 Executing TAKEOFF command")
                LogUtils.i(TAG, "Executing TAKEOFF command")
                
                FlightControllerKey.KeyStartTakeoff.create().action({ result: EmptyMsg ->
                    val message = "✅ Takeoff command executed successfully"
                    Log.i(TAG, message)
                    LogUtils.i(TAG, message)
                    
                    ToastUtils.showShortToast("🛫 Takeoff started!")
                    
                    // Log to telemetry
                    TelemetryLogger.getInstance(context).logCommand("TAKEOFF command executed successfully")
                }, { error: IDJIError ->
                    val message = "❌ Takeoff failed: ${error.description()}"
                    Log.e(TAG, message)
                    LogUtils.e(TAG, message)
                    
                    ToastUtils.showShortToast("❌ Takeoff failed: ${error.description()}")
                    
                    // Log to telemetry
                    TelemetryLogger.getInstance(context).logCommand("TAKEOFF command failed: ${error.description()}")
                })
            }
            
            "land" -> {
                Log.i(TAG, "🛬 Executing LAND command")
                LogUtils.i(TAG, "Executing LAND command")
                
                FlightControllerKey.KeyStartAutoLanding.create().action({ result: EmptyMsg ->
                    val message = "✅ Landing command executed successfully"
                    Log.i(TAG, message)
                    LogUtils.i(TAG, message)
                    
                    ToastUtils.showShortToast("🛬 Landing started!")
                    
                    // Log to telemetry
                    TelemetryLogger.getInstance(context).logCommand("LAND command executed successfully")
                }, { error: IDJIError ->
                    val message = "❌ Landing failed: ${error.description()}"
                    Log.e(TAG, message)
                    LogUtils.e(TAG, message)
                    
                    ToastUtils.showShortToast("❌ Landing failed: ${error.description()}")
                    
                    // Log to telemetry
                    TelemetryLogger.getInstance(context).logCommand("LAND command failed: ${error.description()}")
                })
            }
            
            "move_forward" -> {
                executeMoveCommand("forward")
            }
            
            "move_backward" -> {
                executeMoveCommand("backward")
            }
            
            "stop_movement" -> {
                executeStopMovement()
            }
            
            "start_video_stream" -> {
                val logMessage = "📹 Starting video stream"
                Log.i(TAG, logMessage)
                LogUtils.i(TAG, logMessage)
                ToastUtils.showShortToast("📹 Starting video stream...")
                try {
                    VideoStreamSender.getInstance().startStreaming(this)
                } catch (e: Exception) {
                    val errorMsg = "❌ Failed to start video stream: ${e.message}"
                    Log.e(TAG, errorMsg, e)
                    LogUtils.e(TAG, errorMsg, e)
                    ToastUtils.showShortToast("❌ Video stream error: ${e.message}")
                }
            }
            
            "stop_video_stream" -> {
                val logMessage = "⏹️ Stopping video stream"
                Log.i(TAG, logMessage)
                LogUtils.i(TAG, logMessage)
                ToastUtils.showShortToast("⏹️ Stopping video stream...")
                try {
                    VideoStreamSender.getInstance().stopStreaming()
                } catch (e: Exception) {
                    val errorMsg = "❌ Failed to stop video stream: ${e.message}"
                    Log.e(TAG, errorMsg, e)
                    LogUtils.e(TAG, errorMsg, e)
                    ToastUtils.showShortToast("❌ Stop video stream error: ${e.message}")
                }
            }
            
            else -> {
                val message = "⚠️ Unknown command: $command"
                Log.w(TAG, message)
                LogUtils.w(TAG, message)
                
                ToastUtils.showShortToast("⚠️ Unknown command: $command")
            }
        }
    }

    /**
     * Execute move command (forward/backward) using Virtual Stick
     */
    private fun executeMoveCommand(direction: String) {
        try {
            // Enable Virtual Stick nếu chưa enable
            if (!isVirtualStickEnabled) {
                VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        isVirtualStickEnabled = true
                        Log.i(TAG, "✅ Virtual Stick enabled")
                        LogUtils.i(TAG, "Virtual Stick enabled")
                        ToastUtils.showShortToast("✅ Virtual Stick enabled")
                        
                        // Sau khi enable, thực hiện move command
                        performMove(direction)
                    }
                    
                    override fun onFailure(error: IDJIError) {
                        val message = "❌ Failed to enable Virtual Stick: ${error.description()}"
                        Log.e(TAG, message)
                        LogUtils.e(TAG, message)
                        ToastUtils.showShortToast("❌ Failed to enable Virtual Stick")
                        TelemetryLogger.getInstance(context).logCommand(message)
                    }
                })
            } else {
                // Virtual Stick đã enable, thực hiện move ngay
                performMove(direction)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing move command: ${e.message}", e)
            LogUtils.e(TAG, "Error executing move command: ${e.message}", e)
            ToastUtils.showShortToast("❌ Error: ${e.message}")
        }
    }
    
    /**
     * Perform actual movement using Virtual Stick
     */
    private fun performMove(direction: String) {
        try {
            // Hủy stop movement nếu đang có
            movementStopRunnable?.let {
                mainHandler.removeCallbacks(it)
                movementStopRunnable = null
            }
            
            // Left stick: vertical position điều khiển pitch (tiến/lùi)
            // MAX_STICK_POSITION_ABS thường là 660
            val stickValue = (Stick.MAX_STICK_POSITION_ABS * 0.1).toInt() // 50% speed
            
            when (direction.lowercase()) {
                "forward" -> {
                    // Vertical position dương = tiến
                    VirtualStickManager.getInstance().leftStick.horizontalPosition = 0
                    VirtualStickManager.getInstance().leftStick.verticalPosition = stickValue
                    VirtualStickManager.getInstance().rightStick.horizontalPosition = 0
                    VirtualStickManager.getInstance().rightStick.verticalPosition = 0
                    
                    Log.i(TAG, "⬆️ Moving forward")
                    LogUtils.i(TAG, "Moving forward")
                    ToastUtils.showShortToast("⬆️ Moving forward")
                    TelemetryLogger.getInstance(context).logCommand("MOVE FORWARD command executed")
                }
                "backward" -> {
                    // Vertical position âm = lùi
                    VirtualStickManager.getInstance().leftStick.horizontalPosition = 0
                    VirtualStickManager.getInstance().leftStick.verticalPosition = -stickValue
                    VirtualStickManager.getInstance().rightStick.horizontalPosition = 0
                    VirtualStickManager.getInstance().rightStick.verticalPosition = 0
                    
                    Log.i(TAG, "⬇️ Moving backward")
                    LogUtils.i(TAG, "Moving backward")
                    ToastUtils.showShortToast("⬇️ Moving backward")
                    TelemetryLogger.getInstance(context).logCommand("MOVE BACKWARD command executed")
                }
            }
            
            // Tự động stop sau 2 giây để an toàn
            movementStopRunnable = Runnable {
                executeStopMovement()
            }
            mainHandler.postDelayed(movementStopRunnable!!, 2000) // Stop after 2 seconds
        } catch (e: Exception) {
            Log.e(TAG, "Error performing move: ${e.message}", e)
            LogUtils.e(TAG, "Error performing move: ${e.message}", e)
            ToastUtils.showShortToast("❌ Move error: ${e.message}")
        }
    }
    
    /**
     * Stop movement by resetting stick positions to center
     */
    private fun executeStopMovement() {
        try {
            // Hủy stop movement scheduled
            movementStopRunnable?.let {
                mainHandler.removeCallbacks(it)
                movementStopRunnable = null
            }
            
            // Reset stick positions về center (0, 0)
            VirtualStickManager.getInstance().leftStick.horizontalPosition = 0
            VirtualStickManager.getInstance().leftStick.verticalPosition = 0
            VirtualStickManager.getInstance().rightStick.horizontalPosition = 0
            VirtualStickManager.getInstance().rightStick.verticalPosition = 0
            
            Log.i(TAG, "⏸️ Movement stopped")
            LogUtils.i(TAG, "Movement stopped")
            ToastUtils.showShortToast("⏸️ Movement stopped")
            TelemetryLogger.getInstance(context).logCommand("STOP MOVEMENT command executed")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping movement: ${e.message}", e)
            LogUtils.e(TAG, "Error stopping movement: ${e.message}", e)
            ToastUtils.showShortToast("❌ Stop error: ${e.message}")
        }
    }

    /**
     * Gửi PING đến server
     */
    fun sendPing() {
        try {
            val pingData = JSONObject().apply {
                put("timestamp", dateFormat.format(Date()))
                put("message", "PING from Android")
            }
            
            socket?.emit("ping", pingData)
            Log.i(TAG, "📤 PING sent")
            LogUtils.i(TAG, "PING sent")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send PING: ${e.message}", e)
            LogUtils.e(TAG, "Failed to send PING: ${e.message}", e)
        }
    }

    /**
     * Gửi telemetry data đến server
     */
    fun sendTelemetryData(telemetryData: JSONObject) {
        if (!isConnected()) {
            Log.d(TAG, "Socket not connected, skipping telemetry data send")
            return
        }
        
        try {
            val payload = JSONObject().apply {
                put("droneId", DEFAULT_DRONE_ID)
                put("telemetry", telemetryData)
                put("timestamp", dateFormat.format(Date()))
            }
            
            socket?.emit("telemetry:data", payload)
            Log.d(TAG, "📤 Telemetry data sent to server: ${telemetryData.toString().take(100)}...")
            LogUtils.d(TAG, "Telemetry data sent to server")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send telemetry data: ${e.message}", e)
            LogUtils.w(TAG, "Failed to send telemetry data: ${e.message}", e)
        }
    }

    /**
     * Gửi app startup message đến server
     */
    private fun sendAppStartupMessage() {
        if (!isConnected()) {
            return
        }
        
        try {
            val payload = JSONObject().apply {
                put("droneId", DEFAULT_DRONE_ID)
                put("message", "🚁 Drone app started and connected")
                put("type", "app_startup")
                put("timestamp", dateFormat.format(Date()))
            }
            
            socket?.emit("app:message", payload)
            Log.i(TAG, "📤 App startup message sent to server")
            LogUtils.i(TAG, "App startup message sent to server")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send app startup message: ${e.message}", e)
            LogUtils.w(TAG, "Failed to send app startup message: ${e.message}", e)
        }
    }

    /**
     * Gửi video frame (H.264 raw data) đến server
     */
    fun sendVideoFrame(frameData: ByteArray) {
        if (!isConnected()) {
            return
        }
        
        try {
            // Gửi binary data qua Socket.IO
            socket?.emit("video:frame", frameData)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send video frame: ${e.message}", e)
        }
    }

    /**
     * Set listener cho PONG event
     */
    fun setPongListener(listener: (JSONObject) -> Unit) {
        this.pongListener = listener
    }

    /**
     * Kiểm tra trạng thái kết nối
     */
    fun isConnected(): Boolean {
        return socket?.connected() == true
    }

    /**
     * Join vào drone room để nhận commands
     */
    fun joinDroneRoom(droneId: String) {
        try {
            val payload = JSONObject().apply {
                put("droneId", droneId)
            }
            socket?.emit("join:drone", payload)
            Log.i(TAG, "🔌 Joined drone room: $droneId")
            LogUtils.i(TAG, "Joined drone room: $droneId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join drone room: ${e.message}", e)
            LogUtils.e(TAG, "Failed to join drone room: ${e.message}", e)
        }
    }

    /**
     * Start mission
     */
    private fun startMission(droneId: String, waypoints: List<WaypointData>) {
        if (isMissionRunning.get()) {
            Log.w(TAG, "Mission already running, stopping current mission first")
            endMission()
        }

        if (waypoints.isEmpty()) {
            Log.e(TAG, "Cannot start mission: no waypoints provided")
            ToastUtils.showShortToast("❌ Mission failed: No waypoints")
            return
        }

        currentMission = MissionData(droneId, waypoints)
        currentWaypointIndex = 0
        isMissionRunning.set(true)

        val logMessage = "🎯 Starting mission with ${waypoints.size} waypoints"
        Log.i(TAG, logMessage)
        LogUtils.i(TAG, logMessage)
        TelemetryLogger.getInstance(context).logCommand(logMessage)
        ToastUtils.showShortToast("🎯 Mission started: ${waypoints.size} waypoints")

        // Start executing waypoints
        executeNextWaypoint()
    }

    /**
     * Execute next waypoint in mission
     */
    private fun executeNextWaypoint() {
        if (!isMissionRunning.get() || currentMission == null) {
            Log.w(TAG, "Cannot execute waypoint: mission not running or null")
            ToastUtils.showShortToast("⚠️ Mission not running")
            return
        }

        if (currentWaypointIndex >= currentMission!!.waypoints.size) {
            // Mission completed
            val logMessage = "✅ Mission completed: all waypoints executed"
            Log.i(TAG, logMessage)
            LogUtils.i(TAG, logMessage)
            TelemetryLogger.getInstance(context).logCommand(logMessage)
            ToastUtils.showShortToast("✅ Mission completed!")
            endMission()
            return
        }

        val waypoint = currentMission!!.waypoints[currentWaypointIndex]
        val logMessage = "📍 Executing waypoint ${currentWaypointIndex + 1}/${currentMission!!.waypoints.size}: lat=${waypoint.latitude}°, lng=${waypoint.longitude}°, alt=${waypoint.altitude}m, action=${waypoint.action}"
        Log.i(TAG, logMessage)
        LogUtils.i(TAG, logMessage)
        TelemetryLogger.getInstance(context).logCommand(logMessage)
        
        // Show Toast for waypoint execution
        ToastUtils.showShortToast("📍 Waypoint ${currentWaypointIndex + 1}/${currentMission!!.waypoints.size}: ${waypoint.action}")

        when (waypoint.action.lowercase()) {
            "fly_to" -> {
                executeFlyTo(waypoint)
            }
            "hover" -> {
                // Hover for a few seconds, then continue
                ToastUtils.showShortToast("⏸️ Hovering at waypoint ${currentWaypointIndex + 1}...")
                mainHandler.postDelayed({
                    currentWaypointIndex++
                    executeNextWaypoint()
                }, 3000) // Hover for 3 seconds (3000 milliseconds)
            }
            "take_photo" -> {
                // TODO: Implement take photo
                Log.w(TAG, "Take photo action not implemented yet")
                currentWaypointIndex++
                executeNextWaypoint()
            }
            "start_recording" -> {
                // TODO: Implement start recording
                Log.w(TAG, "Start recording action not implemented yet")
                currentWaypointIndex++
                executeNextWaypoint()
            }
            "stop_recording" -> {
                // TODO: Implement stop recording
                Log.w(TAG, "Stop recording action not implemented yet")
                currentWaypointIndex++
                executeNextWaypoint()
            }
            else -> {
                // Default: fly to
                executeFlyTo(waypoint)
            }
        }
    }

    /**
     * Execute FlyTo to a waypoint
     */
    private fun executeFlyTo(waypoint: WaypointData) {
        try {
            val target = FlyToTarget().apply {
                targetLocation = LocationCoordinate3D(
                    waypoint.latitude,   // Latitude in degrees
                    waypoint.longitude,  // Longitude in degrees
                    waypoint.altitude   // Altitude in meters
                )
                maxSpeed = 14             // Maximum speed in m/s (meters per second)
                securityTakeoffHeight = 20 // Security takeoff height in meters
            }

            // Show Toast when starting FlyTo
            ToastUtils.showShortToast("✈️ Flying to waypoint ${currentWaypointIndex + 1}...")

            IntelligentFlightManager.getInstance().flyToMissionManager.startMission(
                target,
                null,
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        val logMessage = "✅ Waypoint ${currentWaypointIndex + 1} reached"
                        Log.i(TAG, logMessage)
                        LogUtils.i(TAG, logMessage)
                        TelemetryLogger.getInstance(context).logCommand(logMessage)
                        
                        // Show Toast when waypoint reached
                        ToastUtils.showShortToast("✅ Waypoint ${currentWaypointIndex + 1} reached!")

                        // Move to next waypoint after a short delay
                        mainHandler.postDelayed({
                            currentWaypointIndex++
                            executeNextWaypoint()
                        }, 2000) // Wait 2 seconds (2000 milliseconds) before next waypoint
                    }

                    override fun onFailure(error: IDJIError) {
                        val logMessage = "❌ Failed to reach waypoint ${currentWaypointIndex + 1}: ${error.description()}"
                        Log.e(TAG, logMessage)
                        LogUtils.e(TAG, logMessage)
                        TelemetryLogger.getInstance(context).logCommand(logMessage)
                        ToastUtils.showShortToast("❌ Waypoint ${currentWaypointIndex + 1} failed")

                        // Try next waypoint anyway, or end mission
                        mainHandler.postDelayed({
                            currentWaypointIndex++
                            if (currentWaypointIndex < currentMission!!.waypoints.size) {
                                executeNextWaypoint()
                            } else {
                                endMission()
                            }
                        }, 2000) // Wait 2 seconds (2000 milliseconds) before retry
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error executing FlyTo: ${e.message}", e)
            LogUtils.e(TAG, "Error executing FlyTo: ${e.message}", e)
            ToastUtils.showShortToast("❌ FlyTo error: ${e.message}")

            // Try next waypoint
            mainHandler.postDelayed({
                currentWaypointIndex++
                if (currentWaypointIndex < currentMission!!.waypoints.size) {
                    executeNextWaypoint()
                } else {
                    endMission()
                }
            }, 2000) // Wait 2 seconds (2000 milliseconds) before retry
        }
    }

    /**
     * End mission
     */
    private fun endMission() {
        if (!isMissionRunning.getAndSet(false)) {
            Log.d(TAG, "Mission end called but mission was not running")
            return
        }

        try {
            currentMission = null
            currentWaypointIndex = 0

            val logMessage = "⏹️ Mission ended"
            Log.i(TAG, logMessage)
            LogUtils.i(TAG, logMessage)
            TelemetryLogger.getInstance(context).logCommand(logMessage)
            ToastUtils.showShortToast("⏹️ Mission ended")

            // Stop FlyTo mission if running
            IntelligentFlightManager.getInstance().flyToMissionManager.stopMission(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "✅ Mission stopped successfully")
                        LogUtils.i(TAG, "Mission stopped successfully")
                        ToastUtils.showShortToast("✅ Mission stopped")
                    }

                    override fun onFailure(error: IDJIError) {
                        val warningMsg = "Warning: Failed to stop mission: ${error.description()}"
                        Log.w(TAG, warningMsg)
                        LogUtils.w(TAG, warningMsg)
                        ToastUtils.showShortToast("⚠️ Mission stop warning: ${error.description()}")
                    }
                }
            )
        } catch (e: Exception) {
            val errorMsg = "Error stopping mission: ${e.message}"
            Log.e(TAG, errorMsg, e)
            LogUtils.e(TAG, errorMsg, e)
            ToastUtils.showShortToast("❌ Mission stop error: ${e.message}")
        }

        currentMission = null
        currentWaypointIndex = 0
    }

    /**
     * Ngắt kết nối
     */
    fun disconnect() {
        // Stop mission if running
        if (isMissionRunning.get()) {
            endMission()
        }

        socket?.disconnect()
        socket = null
        Log.i(TAG, "Socket disconnected")
        LogUtils.i(TAG, "Socket disconnected")
        ToastUtils.showShortToast("❌ Websocket disconnect")
    }
}

