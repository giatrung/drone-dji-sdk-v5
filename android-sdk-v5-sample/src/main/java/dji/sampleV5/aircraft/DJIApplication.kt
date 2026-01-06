package dji.sampleV5.aircraft

import android.app.Application
import android.util.Log
import dji.sampleV5.aircraft.models.MSDKManagerVM
import dji.sampleV5.aircraft.models.globalViewModels
import dji.sampleV5.aircraft.util.SocketManager
import dji.sampleV5.aircraft.util.TelemetryLogger

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/3/1
 *
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
open class DJIApplication : Application() {

    private val msdkManagerVM: MSDKManagerVM by globalViewModels()

    override fun onCreate() {
        super.onCreate()
        
        Log.i("DJIApplication", "=== onCreate() called ===")

        // Ensure initialization is called first
        Log.i("DJIApplication", "Initializing Mobile SDK...")
        msdkManagerVM.initMobileSDK(this)
        
        // Initialize telemetry logger
        Log.i("DJIApplication", "Initializing TelemetryLogger...")
        TelemetryLogger.getInstance(this).initialize()
        
        // Initialize Socket.IO connection (delay để đảm bảo app đã sẵn sàng)
        Log.i("DJIApplication", "Scheduling SocketManager connection...")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.i("DJIApplication", "Initializing SocketManager...")
            SocketManager.getInstance(this).connect()
        }, 2000) // Delay 2 giây
        
        Log.i("DJIApplication", "✅ onCreate() completed")
    }

}
