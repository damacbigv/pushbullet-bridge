package com.example.pushbridge

import android.app.Application
import android.util.Log
import com.example.pushbridge.data.PushBridgeFirebaseManager

class PushBridgeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("PushBridgeApp", "Application starting. Initializing Firebase Realtime sync engine...")
        PushBridgeFirebaseManager.initialize(this)
    }
}
