package com.huawei.music

import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.os.Bundle
import android.service.media.MediaBrowserService
import android.util.Log

class EmuiBrowserService : MediaBrowserService() {

    companion object {
        var instance: EmuiBrowserService? = null
        var pendingToken: MediaSession.Token? = null

        fun attachNativeToken(token: MediaSession.Token) {
            pendingToken = token
            instance?.let { service ->
                try {
                    if (service.sessionToken == null) {
                        service.sessionToken = token
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        pendingToken?.let {
            try {
                if (sessionToken == null) {
                    sessionToken = it
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.d("EMUI_BRIDGE", "Centro de Control de Huawei conectándose: $clientPackageName")
        val extras = Bundle().apply {
            putBoolean("android.service.media.extra.RECENT", true)
            putBoolean("android.service.media.extra.SUGGESTED", true)
        }
        return BrowserRoot("huawei_ROOT", extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        result.sendResult(mutableListOf())
    }
}