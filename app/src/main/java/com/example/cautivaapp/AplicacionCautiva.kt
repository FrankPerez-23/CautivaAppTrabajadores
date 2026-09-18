package com.example.cautivaapp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class AplicacionCautiva : Application() {
    override fun onCreate() {
        super.onCreate()
        // Forzar tema Claro (Light) en toda la aplicación de forma global
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
    }
}
