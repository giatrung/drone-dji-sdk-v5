package dji.sampleV5.aircraft.util

import android.util.Log
import dji.sampleV5.aircraft.util.ToastUtils
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.utils.common.LogUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoStreamSender - Gửi video stream từ drone qua WebSocket
 * Đơn giản: Lấy raw H.264 và gửi trực tiếp
 */
class VideoStreamSender private constructor() {
    
    companion object {
        private const val TAG = "VideoStreamSender"
        
        @Volatile
        private var INSTANCE: VideoStreamSender? = null
        
        fun getInstance(): VideoStreamSender {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VideoStreamSender().also { INSTANCE = it }
            }
        }
    }
    
    private var isStreaming = AtomicBoolean(false)
    private var streamListener: ICameraStreamManager.ReceiveStreamListener? = null
    private var socketManager: SocketManager? = null
    private var frameCount = 0L
    private var cameraIndex: ComponentIndexType? = null
    
    /**
     * Bắt đầu stream video
     */
    fun startStreaming(socketManager: SocketManager) {
        if (isStreaming.getAndSet(true)) {
            Log.w(TAG, "Video streaming already started")
            return
        }
        
        this.socketManager = socketManager
        frameCount = 0
        
        try {
            val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager
            
            // Check video channel status
            val videoStreamManager = MediaDataCenter.getInstance().videoStreamManager
            val primaryChannel = videoStreamManager.getAvailableVideoChannel(
                dji.v5.common.video.channel.VideoChannelType.PRIMARY_STREAM_CHANNEL
            )
            
            if (primaryChannel == null) {
                Log.w(TAG, "⚠️ Primary video channel not available")
                LogUtils.w(TAG, "Primary video channel not available")
                ToastUtils.showShortToast("⚠️ Video channel not available - please start video channel first")
                isStreaming.set(false)
                return
            }
            
            val channelState = primaryChannel.videoChannelStatus
            Log.i(TAG, "📹 Video channel status: $channelState")
            LogUtils.i(TAG, "Video channel status: $channelState")
            
            if (channelState != dji.v5.common.video.channel.VideoChannelState.ON) {
                Log.w(TAG, "⚠️ Video channel is not ON (status: $channelState). Stream will not work until channel is started.")
                LogUtils.w(TAG, "Video channel is not ON. Please start video channel first.")
                ToastUtils.showShortToast("⚠️ Video channel not started - please start channel first")
                // Don't return - still add listener in case channel starts later
            }
            
            // Dùng camera mặc định (LEFT_OR_MAIN) - camera chính của drone
            // Có thể thay đổi thành ComponentIndexType.RIGHT, FPV, etc. nếu cần
            cameraIndex = ComponentIndexType.LEFT_OR_MAIN
            
            streamListener = ICameraStreamManager.ReceiveStreamListener { data, offset, length, info ->
                // Gửi raw H.264 data qua WebSocket
                if (isStreaming.get() && socketManager?.isConnected() == true) {
                    try {
                        // Log first frame
                        if (frameCount == 0L) {
                            Log.i(TAG, "📹 First video frame received: $length bytes")
                            LogUtils.i(TAG, "First video frame received: $length bytes")
                        }
                        
                        // Copy data để tránh race condition
                        val frameData = ByteArray(length)
                        System.arraycopy(data, offset, frameData, 0, length)
                        
                        // Gửi qua Socket.IO dưới dạng binary
                        socketManager?.sendVideoFrame(frameData)
                        
                        frameCount++
                        if (frameCount % 30 == 0L) {
                            Log.d(TAG, "📹 Sent $frameCount frames (last: $length bytes)")
                            LogUtils.d(TAG, "Sent $frameCount frames")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending video frame: ${e.message}", e)
                        LogUtils.e(TAG, "Error sending video frame: ${e.message}", e)
                    }
                } else {
                    if (frameCount == 0L) {
                        Log.w(TAG, "⚠️ Frame received but streaming inactive (isStreaming: ${isStreaming.get()}, connected: ${socketManager?.isConnected()})")
                    }
                }
            }
            
            cameraStreamManager.addReceiveStreamListener(cameraIndex!!, streamListener!!)
            
            Log.i(TAG, "✅ Video streaming listener added (camera: $cameraIndex, channel state: $channelState)")
            LogUtils.i(TAG, "Video streaming listener added")
            ToastUtils.showShortToast("📹 Video streaming started")
            
            // Check after 2 seconds if no frames received
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (frameCount == 0L && isStreaming.get()) {
                    val currentState = primaryChannel?.videoChannelStatus
                    Log.w(TAG, "⚠️ No video frames received after 2 seconds. Channel state: $currentState")
                    LogUtils.w(TAG, "No video frames received. Channel state: $currentState")
                    ToastUtils.showShortToast("⚠️ No frames - check video channel")
                }
            }, 2000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting video stream: ${e.message}", e)
            LogUtils.e(TAG, "Error starting video stream: ${e.message}", e)
            ToastUtils.showShortToast("❌ Failed to start video stream")
            isStreaming.set(false)
        }
    }
    
    /**
     * Dừng stream video
     */
    fun stopStreaming() {
        if (!isStreaming.getAndSet(false)) {
            return
        }
        
        try {
            streamListener?.let { listener ->
                cameraIndex?.let { index ->
                    val cameraStreamManager = MediaDataCenter.getInstance().cameraStreamManager
                    cameraStreamManager.removeReceiveStreamListener(listener)
                }
            }
            
            streamListener = null
            cameraIndex = null
            socketManager = null
            
            Log.i(TAG, "⏹️ Video streaming stopped (total frames: $frameCount)")
            LogUtils.i(TAG, "Video streaming stopped")
            ToastUtils.showShortToast("⏹️ Video streaming stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping video stream: ${e.message}", e)
            LogUtils.e(TAG, "Error stopping video stream: ${e.message}", e)
        }
    }
    
    fun isStreaming(): Boolean = isStreaming.get()
}

