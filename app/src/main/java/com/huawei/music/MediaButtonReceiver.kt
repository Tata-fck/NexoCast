package com.huawei.mediasession.mediasession.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import com.huawei.music.MediaInterceptorService

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MEDIA_BUTTON) {
            val serviceIntent = Intent(context, MediaInterceptorService::class.java).apply {
                action = Intent.ACTION_MEDIA_BUTTON
                putExtra(Intent.EXTRA_KEY_EVENT, intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT))
            }
            context.startService(serviceIntent)
        }
    }
}