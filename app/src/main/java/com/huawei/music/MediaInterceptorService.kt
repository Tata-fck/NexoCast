package com.huawei.music

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.media.AudioFocusRequest
import android.media.AudioManager

class MediaInterceptorService : NotificationListenerService() {

    private lateinit var mediaSessionManager: MediaSessionManager
    private lateinit var audioManager: AudioManager
    private var nativeSession: MediaSession? = null
    private var activeController: MediaController? = null

    private var lastNotifiedTitle: String? = null
    private var lastNotifiedArtist: String? = null
    private var lastNotifiedPlayingState: Boolean? = null

    private val CHANNEL_ID = "com.proxy.music.NowPlayingChannel"
    private val NOTIFICATION_ID = 7070

    companion object {
        const val ACTION_PLAY_PAUSE = "com.proxy.music.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.proxy.music.ACTION_NEXT"
        const val ACTION_PREV = "com.proxy.music.ACTION_PREV"
    }

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener { _ ->
        refreshActivePlayer()
    }

    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        refreshActivePlayer()
    }

    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        refreshActivePlayer()
    }

    private fun refreshActivePlayer() {
        val componentName = ComponentName(this, MediaInterceptorService::class.java)
        try {
            val sessions = mediaSessionManager.getActiveSessions(componentName).toMutableList()
            val notificationControllers = getControllersFromNotifications()
            val allControllers = (sessions + notificationControllers).distinctBy { it.sessionToken }
            hookIntoExternalPlayer(allControllers)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getControllersFromNotifications(): List<MediaController> {
        val controllers = mutableListOf<MediaController>()
        try {
            val sbnList = activeNotifications ?: return controllers
            for (sbn in sbnList) {
                val extras = sbn.notification.extras
                val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
                }
                if (token != null) {
                    try {
                        val controller = MediaController(this, token)
                        if (controller.packageName == sbn.packageName) {
                            controllers.add(controller)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
        return controllers
    }

    private val externalCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            cloneMetadata(metadata)
            updateForegroundNotificationIfNeeded()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            clonePlaybackState(state)
            updateForegroundNotificationIfNeeded()
        }

        override fun onSessionDestroyed() {
            activeController = null
            val cn = ComponentName(this@MediaInterceptorService, MediaInterceptorService::class.java)
            try {
                hookIntoExternalPlayer(mediaSessionManager.getActiveSessions(cn))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                val state = activeController?.playbackState?.state
                if (state == PlaybackState.STATE_PLAYING) {
                    activeController?.transportControls?.pause()
                } else {
                    activeController?.transportControls?.play()
                }
            }
            ACTION_NEXT -> activeController?.transportControls?.skipToNext()
            ACTION_PREV -> activeController?.transportControls?.skipToPrevious()
        }
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        val mbrIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply { 
            setPackage(packageName) 
        }
        val mbrPendingIntent = PendingIntent.getBroadcast(
            this, 0, mbrIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        nativeSession = MediaSession(this, "TidalProxy").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setMediaButtonReceiver(mbrPendingIntent)

            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { activeController?.transportControls?.play() }
                override fun onPause() { activeController?.transportControls?.pause() }
                override fun onSkipToNext() { activeController?.transportControls?.skipToNext() }
                override fun onSkipToPrevious() { activeController?.transportControls?.skipToPrevious() }
                override fun onSeekTo(pos: Long) { activeController?.transportControls?.seekTo(pos) }
                override fun onStop() { activeController?.transportControls?.stop() }
            })
            isActive = true
        }

        val componentName = ComponentName(this, MediaInterceptorService::class.java)
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName)
            refreshActivePlayer()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun pulseAudioFocus() {
        try {
            val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest)
            // Abandona inmediatamente; solo queremos despertar el pipeline de Huawei
            audioManager.abandonAudioFocusRequest(audioFocusRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun hookIntoExternalPlayer(controllers: List<MediaController>?) {
        if (controllers == null) return

        val candidates = controllers.filter {
            it.packageName != packageName &&
                    it.packageName != "com.android.server.telecom" &&
                    it.packageName != "com.huawei.camera"
        }

        val target = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull()

        if (target == null) {
            activeController?.unregisterCallback(externalCallback)
            activeController = null
            return
        }

        if (activeController?.sessionToken == target.sessionToken) return

        activeController?.unregisterCallback(externalCallback)
        activeController = target
        target.registerCallback(externalCallback)

        val launchIntent = packageManager.getLaunchIntentForPackage(target.packageName)
            ?: Intent(this, MainActivity::class.java)
        val sessionActivityPi = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nativeSession?.setSessionActivity(sessionActivityPi)

        cloneMetadata(target.metadata)
        clonePlaybackState(target.playbackState)
        updateForegroundNotificationIfNeeded()
    }

    private fun cloneMetadata(meta: MediaMetadata?) {
        val session = nativeSession ?: return
        val builder = MediaMetadata.Builder()

        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta?.description?.title?.toString() ?: "Reproduciendo"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.description?.subtitle?.toString() ?: "Interceptando..."
        val album = meta?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 180000L

        val art: Bitmap? = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta?.description?.iconBitmap

        builder.putString(MediaMetadata.METADATA_KEY_TITLE, title)
        builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
        builder.putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
        builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist)
        builder.putString(MediaMetadata.METADATA_KEY_ALBUM, album)
        builder.putLong(MediaMetadata.METADATA_KEY_DURATION, if (duration > 0) duration else 180000L)

        if (art != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, art)
            builder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, art)
        }

        session.setMetadata(builder.build())
    }

    private fun clonePlaybackState(state: PlaybackState?) {
        val session = nativeSession ?: return
        val currentState = state?.state ?: PlaybackState.STATE_PAUSED
        val position = state?.position ?: 0L
        val speed = state?.playbackSpeed ?: 1.0f

        val stateBuilder = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
            )
            .setState(currentState, position, speed, SystemClock.elapsedRealtime())

        session.setPlaybackState(stateBuilder.build())
    }

    private fun updateForegroundNotificationIfNeeded() {
        val session = nativeSession ?: return
        val controller = session.controller
        val meta = controller.metadata
        val playbackState = controller.playbackState

        val isPlaying = playbackState?.state == PlaybackState.STATE_PLAYING
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Reproduciendo"
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "Conectado"

        if (title == lastNotifiedTitle && artist == lastNotifiedArtist && isPlaying == lastNotifiedPlayingState) {
            return
        }

        lastNotifiedTitle = title
        lastNotifiedArtist = artist
        lastNotifiedPlayingState = isPlaying

        if (isPlaying) {
            pulseAudioFocus()
        }

        val albumArt = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)

        val launchIntent = activeController?.packageName?.let {
            packageManager.getLaunchIntentForPackage(it)
        } ?: Intent(this, MainActivity::class.java)

        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val prevAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_media_previous),
            "Anterior", buildServiceActionIntent(ACTION_PREV, 1)
        ).build()

        val playPauseAction = Notification.Action.Builder(
            Icon.createWithResource(this, if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play),
            if (isPlaying) "Pausa" else "Reproducir", buildServiceActionIntent(ACTION_PLAY_PAUSE, 2)
        ).build()

        val nextAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_media_next),
            "Siguiente", buildServiceActionIntent(ACTION_NEXT, 3)
        ).build()

        val nativeMediaStyle = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val extras = Bundle().apply {
            putParcelable(Notification.EXTRA_MEDIA_SESSION, session.sessionToken)
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(R.drawable.ic_media_play)
            .setContentIntent(contentPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(isPlaying)
            .setStyle(nativeMediaStyle)
            .addExtras(extras)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)

        if (albumArt != null) {
            builder.setLargeIcon(albumArt)
        }

        val notification = builder.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceActionIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MediaInterceptorService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "NexoCast", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Controles multimedia para Centro de Control EMUI"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {}
        activeController?.unregisterCallback(externalCallback)
        nativeSession?.release()
        super.onDestroy()
    }
}