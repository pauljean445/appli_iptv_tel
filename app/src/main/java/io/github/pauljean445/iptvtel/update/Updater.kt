package io.github.pauljean445.iptvtel.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.pauljean445.iptvtel.BuildConfig
import io.github.pauljean445.iptvtel.data.JSON
import io.github.pauljean445.iptvtel.data.asObj
import io.github.pauljean445.iptvtel.data.str
import io.github.pauljean445.iptvtel.data.toAny
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Mise à jour depuis l'appli : dernière version publiée sur GitHub (page « Releases » du dépôt), comme l'appli PC.
 *
 * Publier une version : augmenter la version dans version.properties, lancer build_release.bat, puis
 * créer sur GitHub une release avec le tag « v<versionName> » (ex. v0.3.0) et y joindre dist\IPTVPlayerTel-<version>.apk.
 * Le texte de la release est montré dans l'appli. Le dépôt doit être public (sinon l'API ne répond pas sans identifiants).
 * L'APK doit être signé avec la même clé que la version installée (keystore.properties), sinon Android le refuse.
 */
object Updater {
    const val REPO = "pauljean445/appli_iptv_tel"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Info(val version: String, val notes: String, val url: String, val name: String, val size: Long)

    class Cancelled : IOException("téléchargement annulé")

    private fun version(text: String): List<Int> = Regex("\\d+").findAll(text).take(3).map { it.value.toInt() }.toList()

    private fun newer(a: String, b: String): Boolean {
        val x = version(a); val y = version(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** La version publiée si elle est plus récente que celle installée, sinon null. Réseau : hors interface. */
    fun check(): Info? {
        val req = Request.Builder().url(API).header("User-Agent", "IPTVPlayerTel/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/vnd.github+json").build()
        val rel = io.github.pauljean445.iptvtel.data.Http.client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 -> throw IOException("aucune version publiée n'est accessible (dépôt privé ou sans release)")
                !resp.isSuccessful -> throw IOException("GitHub a répondu « ${resp.code} »")
            }
            JSON.parseToJsonElement(resp.body!!.string()).toAny().asObj() ?: return null
        }
        val tag = rel["tag_name"].str().trimStart('v', 'V')
        if (tag.isEmpty() || !newer(tag, BuildConfig.VERSION_NAME)) return null
        val asset = (rel["assets"] as? List<*>).orEmpty().mapNotNull { it.asObj() }
            .firstOrNull { it["name"].str().lowercase().endsWith(".apk") } ?: return null
        return Info(tag, rel["body"].str().trim(), asset["browser_download_url"].str(), File(asset["name"].str()).name,
            asset["size"].str().toLongOrNull() ?: 0L)
    }

    /** Télécharge l'APK (dans le cache de l'appli). progress(reçu, total) ; cancelled() arrête le téléchargement. */
    fun download(context: Context, info: Info, progress: (Long, Long) -> Unit, cancelled: () -> Boolean): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // anciennes mises à jour : inutile de les garder
        // L'APK et son installation prennent environ deux fois sa taille, et Android garde une marge de sécurité
        val free = StatFs(dir.path).availableBytes
        if (info.size > 0 && free < info.size * 2 + 250L * 1024 * 1024) throw IOException(
            "pas assez de place sur le téléphone (${free / 1_048_576} Mo libres) : libère de l'espace (applis, caches) et réessaie")
        val part = File(dir, info.name + ".part")
        val req = Request.Builder().url(info.url).header("User-Agent", "IPTVPlayerTel/${BuildConfig.VERSION_NAME}").build()
        io.github.pauljean445.iptvtel.data.Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("téléchargement impossible (« ${resp.code} »)")
            val body = resp.body ?: throw IOException("réponse vide")
            val total = body.contentLength().takeIf { it > 0 } ?: info.size
            var done = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled()) { part.delete(); throw Cancelled() }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) { part.delete(); throw IOException("téléchargement incomplet") }
        }
        return File(dir, info.name).also { part.renameTo(it) }
    }

    /** L'appli a-t-elle le droit d'installer des APK ? (réglage Android « Installer des applis inconnues ») */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Ouvre le réglage Android qui autorise cette appli à installer ses mises à jour. */
    fun openInstallPermission(context: Context) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun prefs(context: Context) = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Mise à jour lancée depuis l'appli : la nouvelle version affichera « à jour » (pas après un « adb install »). */
    private fun markPending(context: Context) {
        prefs(context).edit().putLong("pending", System.currentTimeMillis()).commit()
    }

    /** Appelé par la nouvelle version : vrai si la mise à jour venait de l'appli (marque de moins de 30 min, ou
     *  installée par l'appli elle-même selon Android : marche aussi depuis une version qui ne posait pas la marque).
     *  Note aussi « à afficher : mise à jour installée » pour le prochain démarrage. */
    fun takePending(context: Context): Boolean {
        val p = prefs(context)
        val t = p.getLong("pending", 0L)
        val bySelf = Build.VERSION.SDK_INT >= 30 && try {
            context.packageManager.getInstallSourceInfo(context.packageName).initiatingPackageName == context.packageName
        } catch (e: Exception) { false }
        val ours = (t > 0 && System.currentTimeMillis() - t < 30 * 60_000L) || bySelf
        p.edit().remove("pending").putBoolean("just_updated", ours).commit()
        return ours
    }

    /** Vrai une seule fois après une mise à jour faite depuis l'appli (message « IPTV Player est à jour »). */
    fun takeJustUpdated(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean("just_updated", false).also { if (it) p.edit().remove("just_updated").commit() }
    }

    /** Confie l'APK à l'installeur d'Android : confirmation, installation (l'appli est fermée), puis écran
     *  « Application installée » avec un bouton « Ouvrir ». Android interdit à une appli de se rouvrir toute seule
     *  après sa mise à jour (lancement depuis l'arrière-plan bloqué) : ce bouton la relance. */
    fun install(context: Context, apk: File) {
        markPending(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.update", apk)
        @Suppress("DEPRECATION")  // ACTION_INSTALL_PACKAGE : l'installeur standard, avec son écran de fin
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** Nouvelle version installée (Android l'envoie à la nouvelle version, l'ancienne a été fermée) : si la mise à jour
 *  venait de l'appli, le prochain démarrage affiche « IPTV Player est à jour ». (Rouvrir l'appli d'ici est bloqué
 *  par Android : c'est le bouton « Ouvrir » de l'installeur qui la relance.) */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updater.takePending(context)
    }
}
