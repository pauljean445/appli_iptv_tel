package io.github.pauljean445.iptvtel.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import io.github.pauljean445.iptvtel.MainActivity

/**
 * Pendant une diffusion, c'est le téléphone qui lit le flux et l'envoie à l'appareil : ce service le garde éveillé
 * (processeur et Wi-Fi) écran éteint, avec une notification (pause / arrêt) qui rouvre la télécommande.
 */
class CastService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> Caster.togglePause()
            ACTION_STOP -> { Caster.stop(); return START_NOT_STICKY }
        }
        startForeground(NOTIF_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (wake == null) wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iptvtel:cast").apply { acquire(8 * 3600_000L) }
        @Suppress("DEPRECATION")  // le mode « haute performance » garde le débit du Wi-Fi écran éteint
        if (wifi == null) wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iptvtel:cast").apply { acquire() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 3
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_STOP = "stop"
        private var running = false
        private var lastUpdate = 0L

        fun start(ctx: Context) {
            running = true
            ctx.startForegroundService(Intent(ctx, CastService::class.java))
        }

        fun stop(ctx: Context) {
            if (!running) return
            running = false
            ctx.stopService(Intent(ctx, CastService::class.java))
        }

        /** Notification mise à jour (titre, pause / lecture), au plus une fois par seconde. */
        fun update(ctx: Context) {
            if (!running || System.currentTimeMillis() - lastUpdate < 1000) return
            lastUpdate = System.currentTimeMillis()
            ctx.getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(ctx))
        }

        private fun notification(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel("cast") == null)
                nm.createNotificationChannel(NotificationChannel("cast", "Diffusion", NotificationManager.IMPORTANCE_LOW))
            fun action(a: String, req: Int) = PendingIntent.getService(ctx, req,
                Intent(ctx, CastService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CAST, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val it = Caster.item
            return Notification.Builder(ctx, "cast")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(it?.let { i -> if (i.kind == "episode") "${i.parent?.name.orEmpty()} · ${i.episodeCode}" else i.name } ?: "Diffusion")
                .setContentText(if (Caster.connecting) "Connexion à « ${Caster.device} »…" else "Sur « ${Caster.device} »")
                .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
                .addAction(Notification.Action.Builder(null, if (Caster.playing) "Pause" else "Lecture", action(ACTION_PAUSE, 11)).build())
                .addAction(Notification.Action.Builder(null, "Arrêter", action(ACTION_STOP, 12)).build())
                .build()
        }
    }
}
