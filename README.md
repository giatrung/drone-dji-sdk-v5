# Module: DJI SDK v5 Android Application

## Tổng quan

Module này là ứng dụng Android sử dụng **DJI Mobile SDK V5** để điều khiển drone DJI và thu thập dữ liệu telemetry. Ứng dụng được phát triển dựa trên sample project chính thức của DJI và được tùy chỉnh để tích hợp với hệ thống quản lý drone fleet thông qua WebSocket.

## Công nghệ sử dụng

### Ngôn ngữ & Framework

- **Kotlin** - Ngôn ngữ lập trình chính
- **Android SDK** - Phiên bản tối thiểu: API 21 (Android 5.0)
- **DJI Mobile SDK V5** - Thư viện điều khiển drone DJI

### Thư viện chính

#### Communication

- **Socket.IO Client for Java** - WebSocket client để giao tiếp real-time với server
  - Auto-reconnection
  - Binary data support (video streaming)
  - Event-based communication
  - Namespace: `/drone`

#### DJI SDK Components

- **KeyManager** - Quản lý và truy xuất các thông số drone
- **FlightController** - Điều khiển bay (virtual stick, waypoint, RTH)
- **MediaDataCenter** - Quản lý video stream từ camera drone
- **IntelligentFlightManager** - Các tính năng bay thông minh (Fly-to, Follow-me)

## Kiến trúc & Các module tùy chỉnh

Hệ thống được thiết kế theo mô hình **Singleton Pattern** cho các manager để đảm bảo tính đồng bộ và quản lý tài nguyên hiệu quả.

### 1. SocketManager.kt

**Đường dẫn:** `android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/util/SocketManager.kt`

**Chức năng:**

- Quản lý kết nối Socket.IO với server backend
- Xử lý các command điều khiển từ server (take-off, land, move, start-mission, etc.)
- Gửi telemetry data real-time về server
- Xử lý tự động reconnect khi mất kết nối

**Các tính năng chính:**

```kotlin
// Connection management
fun connect()
fun disconnect()
fun isConnected(): Boolean

// Command handlers
- handleTakeOff()
- handleLand()
- handleMove(direction, speed)
- handleStartMission(waypoints)
- handlePauseMission()
- handleStopMission()
- handleReturnToHome()
- handleRequestVideoStream()

// Virtual Stick control
- enableVirtualStick()
- moveWithVirtualStick(pitch, roll, yaw, throttle)
```

**WebSocket Events:**

- `connect` - Kết nối thành công
- `disconnect` - Ngắt kết nối
- `ping` - Health check từ server
- `command` - Nhận lệnh điều khiển
- `telemetry` - Gửi dữ liệu telemetry
- `video-frame` - Gửi video stream

### 2. TelemetryLogger.kt

**Đường dẫn:** `android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/util/TelemetryLogger.kt`

**Chức năng:**

- Thu thập dữ liệu telemetry từ drone theo thời gian thực
- Lưu telemetry vào file local (storage/Download/Drone-Logs/)
- Gửi telemetry đến server qua WebSocket
- Logging chu kỳ 1 giây

**Dữ liệu thu thập:**

```json
{
  "location": {
    "lat": 21.0294498,
    "lon": 105.8544441
  },
  "altitude": 15.5,
  "heading": 45.2,
  "speed": 5.3,
  "battery": 85,
  "isFlying": true,
  "flightMode": "AUTO_FLY",
  "gpsSignalLevel": "LEVEL_5",
  "satelliteCount": 18,
  "timestamp": 1704672345678
}
```

**Các method chính:**

```kotlin
fun initialize()           // Khởi tạo logger
fun startLogging()         // Bắt đầu thu thập data
fun stopLogging()          // Dừng thu thập
fun setupTelemetryListeners() // Đăng ký listeners với DJI SDK
```

### 3. VideoStreamSender.kt

**Đường dẫn:** `android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/util/VideoStreamSender.kt`

**Chức năng:**

- Capture raw H.264 video stream từ camera drone
- Gửi video frames qua WebSocket dưới dạng binary data
- Tối ưu bandwidth bằng cách gửi raw stream (không encode lại)

**Cơ chế hoạt động:**

1. Đăng ký `ReceiveStreamListener` với `CameraStreamManager`
2. Nhận raw H.264 NAL units từ DJI SDK
3. Copy data và gửi qua Socket.IO event `video-frame`
4. Admin web nhận và decode bằng JMuxer (H.264 to Canvas)

**Các method chính:**

```kotlin
fun startStreaming(socketManager: SocketManager)
fun stopStreaming()
fun isStreaming(): Boolean
```

**Performance:**

- Không encode lại → Tiết kiệm CPU
- Sử dụng camera mặc định (ComponentIndexType.LEFT_OR_MAIN)
- Hỗ trợ streaming đến nhiều clients đồng thời

## Tài liệu tham khảo

### DJI Official Documentation

- **API Reference:** https://developer.dji.com/api-reference/android-api/Components/SDKManager/DJISDKManager.html
- **Developer Guide:** https://developer.dji.com/mobile-sdk/documentation/
- **Sample Code:** https://github.com/dji-sdk/Mobile-SDK-Android-V5

### Thư viện bên thứ ba

- **Socket.IO Client Java:** https://socket.io/docs/v4/client-api/
- **DJI Mobile SDK V5:** https://developer.dji.com/doc/mobile-sdk-tutorial/en/

## Cấu trúc thư mục

```
android-sdk-v5-sample/
├── src/main/java/dji/sampleV5/aircraft/
│   ├── util/
│   │   ├── SocketManager.kt         ← WebSocket management
│   │   ├── TelemetryLogger.kt       ← Telemetry collection
│   │   ├── VideoStreamSender.kt     ← Video streaming
│   │   ├── ToastUtils.kt            ← Helper utilities
│   │   └── ...
│   ├── pages/                        ← UI screens (sample app)
│   └── ...
└── ...
```

## Luồng hoạt động (Flow)

### 1. Khởi động ứng dụng

```
App Start → DJI SDK Registration → Socket Connection → TelemetryLogger Init
```

### 2. Mission execution

```
Admin Web (Create Mission)
  → Server (Save to DB)
  → WebSocket event "command: start-mission"
  → SocketManager.handleStartMission()
  → FlightController executes waypoints
  → TelemetryLogger sends progress
  → Admin Web updates UI
```

### 3. Video streaming

```
Admin Web (Request Stream)
  → WebSocket event "command: request-video-stream"
  → VideoStreamSender.startStreaming()
  → H.264 frames → Socket.IO
  → Admin Web (JMuxer decode → Canvas display)
```

## Yêu cầu hệ thống

- **Android Version:** 5.0 (API 21) trở lên
- **Drone Models:** DJI drones hỗ trợ SDK V5 (Mini 3 Pro, Mavic 3, Air 2S, etc.)
- **Internet Connection:** Cần kết nối mạng để giao tiếp với server
- **Permissions:** Location, Camera, Storage, Internet

## Cấu hình

### Server URL

Chỉnh sửa trong `SocketManager.kt`:

```kotlin
private const val SERVER_URL = "https://your-server-url.com"
private const val NAMESPACE = "/drone"
```