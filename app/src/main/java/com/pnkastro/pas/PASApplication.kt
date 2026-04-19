package com.pnkastro.pas

import android.app.Application
import android.content.Intent
import kotlin.system.exitProcess

class PASApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Global crash protection
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
            exitProcess(1)
        }
    }
}

