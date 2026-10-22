package io.github.pauljean445.iptvtel.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import io.github.pauljean445.iptvtel.MainActivity
import io.github.pauljean445.iptvtel.ui.fmtSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Service des téléchargements : tourne tant que la file n'est pas vide, avec une notification (progression), même
 * appli fermée ou écran éteint. Android limite ce type de service à 6 h par jour : au-delà, il s'arrête et la file
 * repart à la prochaine ouverture de l'appli, là où elle en était.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var lastNotify = 0L
    private val seen = HashSet<String>()  // téléchargements traités pendant ce passage

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Downloads.init(this)
        if (intent?.action == ACTION_CANCEL_ALL) {
            Downloads.cancelAll()
            if (worker?.isActive != true) { stopSelf(); return START_NOT_STICKY }
        }
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isActive != true) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iptvtel:downloads").apply { acquire(6 * 3600_000L) }
            worker = scope.launch {
                Downloads.process { update() }
                done()
            }
        }
        return START_NOT_STICKY
    }

    /** Limite de 6 h atteinte (Android 15) : arrêt propre, la file reprendra plus tard. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        worker?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun update() {
        Downloads.jobs.value.filter { it.state == "running" }.forEach { seen += it.id }
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    /** File terminée : notification « Téléchargements terminés » (si tout s'est bien passé), service arrêté. */
    private fun done() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        val jobs = Downloads.jobs.value.filter { it.id in seen }
        val errors = jobs.count { it.state == "error" }
        if (errors == 0 && jobs.none { it.state == "done" }) { stopSelf(); return }  // tout annulé
        val nm = getSystemService(NotificationManager::class.java)
        val text = if (errors > 0) "$errors téléchargement${if (errors > 1) "s" else ""} en erreur : ouvre l'appli pour réessayer."
            else "Les films et épisodes sont dans l'onglet Téléchargements."
        nm.notify(NOTIF_DONE_ID, Notification.Builder(this, channel(this))
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Téléchargements terminés")
            .setContentText(text).setContentIntent(openApp()).setAutoCancel(true).build())
        stopSelf()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "downloads")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val active = Downloads.jobs.value.filter { it.active }
        val running = active.firstOrNull { it.state == "running" } ?: active.firstOrNull()
        val b = Notification.Builder(this, channel(this))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp())
            .setContentTitle(running?.title ?: "Téléchargements")
            .addAction(Notification.Action.Builder(null, "Tout annuler", PendingIntent.getService(this, 1,
                Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_ALL), PendingIntent.FLAG_IMMUTABLE)).build())
        if (running != null) {
            val more = if (active.size > 1) " · ${active.size - 1} en attente" else ""
            val text = when {
                running.error.isNotEmpty() && running.speed == 0L -> "Nouvel essai… (${running.error})"
                running.total > 0 -> "${(running.progress * 100).toInt()} % · ${fmtSize(running.done)} / ${fmtSize(running.total)}" +
                    if (running.speed > 0) " · ${fmtSize(running.speed)}/s" else ""
                else -> fmtSize(running.done)
            }
            b.setContentText(text + more)
            if (running.total > 0) b.setProgress(1000, (running.progress * 1000).toInt(), false) else b.setProgress(0, 0, true)
        }
        return b.build()
    }

    companion object {
        const val NOTIF_ID = 1
        const val NOTIF_DONE_ID = 2
        const val ACTION_CANCEL_ALL = "cancel_all"

        fun channel(context: Context): String {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel("downloads") == null) nm.createNotificationChannel(
                NotificationChannel("downloads", "Téléchargements", NotificationManager.IMPORTANCE_LOW))
            return "downloads"
        }
    }
}
