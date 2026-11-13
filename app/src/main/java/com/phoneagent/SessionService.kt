package com.phoneagent

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Foreground keep-alive so the system is less eager to reap the dsh engine. */
class SessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.ID_SESSION, Notifications.build(this, "DeepSeek Harness 运行中"))
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
