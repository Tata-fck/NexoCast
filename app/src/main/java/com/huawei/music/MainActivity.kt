package com.huawei.music

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pedir permiso de notificaciones en Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 120, 64, 64)
        }

        statusText = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, 48)
        }

        val btnPermission = Button(this).apply {
            text = "1. Activar Acceso a Notificaciones"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val btnRebind = Button(this).apply {
            text = "2. Reiniciar Servicio Puente"
            setOnClickListener {
                rebindNotificationListener()
            }
        }

        layout.addView(statusText)
        layout.addView(btnPermission)
        layout.addView(btnRebind)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        val enabled = isNotificationServiceEnabled()
        statusText.text = if (enabled) {
            "✅ Puente ACTIVO (Spoofed como com.huawei.music).\n\nReproduce algo en Poweramp o YouTube y baja el Centro de Control."
        } else {
            "❌ Permiso pendiente.\nPresiona el botón 1 y activa 'EMUI Media Bridge'."
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, MediaInterceptorService::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun rebindNotificationListener() {
        val cn = ComponentName(this, MediaInterceptorService::class.java)
        packageManager.setComponentEnabledSetting(
            cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
        packageManager.setComponentEnabledSetting(
            cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
    }
}