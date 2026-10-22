package io.github.pauljean445.iptvtel.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import io.github.pauljean445.iptvtel.data.Http
import io.github.pauljean445.iptvtel.data.Item
import io.github.pauljean445.iptvtel.data.Obj
import io.github.pauljean445.iptvtel.data.int
import io.github.pauljean445.iptvtel.data.num
import io.github.pauljean445.iptvtel.data.readObj
import io.github.pauljean445.iptvtel.data.str
import io.github.pauljean445.iptvtel.data.writeObj
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Un téléchargement. Le fichier est rangé dans Films/IPTV Player (dossier partagé du téléphone : il reste si on
 * désinstalle l'appli, et les autres applis vidéo le voient) ; pendant le téléchargement il est « en cours »
 * (MediaStore IS_PENDING : invisible ailleurs). key / kind / show… servent à le lire dans l'appli avec la reprise
 * de lecture synchronisée, comme le même film en streaming.
 */
data class DlJob(
    val id: String,
    val title: String,           // « Film (2024) », « Série · S01E02 · Titre »
    val url: String,
    val folder: String,          // « Movies/IPTV Player/Série/Saison 01 »
    val fileName: String,
    val mime: String,
    val key: String,
    val kind: String,            // movie | episode
    val name: String,
    val logo: String,
    val showKey: String = "",
    val showName: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val uri: String = "",        // fichier dans MediaStore (créé au premier octet)
    val state: String = "waiting", // waiting | running | done | error | cancelled
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,         // octets / s
    val error: String = "",
    val parts: String = "",      // morceaux déjà téléchargés (« 0-5,7 »), voir Downloads.inParts
    val added: Double = System.currentTimeMillis() / 1000.0,
) {
    val active get() = state == "waiting" || state == "running"
    val progress get() = if (total > 0) done.toFloat() / total else 0f

    /** Entrée lisible par le lecteur : le fichier du téléphone, avec la même clé que le film / l'épisode en ligne. */
    fun toItem(): Item {
        val parent = if (kind == "episode") Item(showName, kind = "series", key = showKey) else null
        return Item(name, url = uri, kind = kind, key = key, logo = logo, season = season, episode = episode, parent = parent)
    }

    fun toObj(): Obj = linkedMapOf("id" to id, "title" to title, "url" to url, "folder" to folder, "file" to fileName,
        "mime" to mime, "key" to key, "kind" to kind, "name" to name, "logo" to logo, "show_key" to showKey,
        "show_name" to showName, "season" to season, "episode" to episode, "uri" to uri, "state" to state,
        "done" to done, "total" to total, "error" to error, "parts" to parts, "added" to added)

    companion object {
        fun of(o: Obj) = DlJob(o["id"].str(), o["title"].str(), o["url"].str(), o["folder"].str(), o["file"].str(),
            o["mime"].str(), o["key"].str(), o["kind"].str(), o["name"].str(), o["logo"].str(), o["show_key"].str(),
            o["show_name"].str(), o["season"].int(), o["episode"].int(), o["uri"].str(),
            o["state"].str().let { if (it == "running") "waiting" else it }, o["done"].num().toLong(),
            o["total"].num().toLong(), 0, o["error"].str(), o["parts"].str(), o["added"].num())
    }
}

/**
 * File des téléchargements (comme downloads.py du PC) : un fichier à la fois, car chaque téléchargement occupe une
 * connexion de l'abonnement IPTV (souvent limité à une ou deux). Coupure : nouvel essai (4 fois) qui reprend là où
 * le fichier en était (requête HTTP « Range »), aussi après un redémarrage de l'appli ou du téléphone.
 * Les téléchargements tournent dans DownloadService (notification), même appli fermée ou écran éteint.
 */
object Downloads {
    private const val TAG = "IPTV"
    private const val RETRIES = 4
    private const val CHUNK = 256 * 1024
    private const val PART = 16L * 1024 * 1024   // morceau téléchargé par une connexion
    private const val CONNECTIONS = 6            // connexions en parallèle pour un fichier
    const val ROOT = "Movies/IPTV Player"
    private const val UNAVAILABLE = "le fournisseur n'envoie pas ce fichier (indisponible chez lui pour le moment)"

    private val _jobs = MutableStateFlow<List<DlJob>>(emptyList())
    val jobs: StateFlow<List<DlJob>> = _jobs
    private lateinit var file: File
    private lateinit var app: Context
    private val cancelled = HashSet<String>()
    private var lastSave = 0L

    class Transient(message: String) : IOException(message)
    private class Cancelled : Exception()

    /** Au lancement de l'appli : file relue ; les fichiers terminés effacés depuis (Galerie…) sont retirés. */
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        file = File(app.filesDir, "downloads.json")
        @Suppress("UNCHECKED_CAST")
        val list = (readObj(file)["jobs"] as? List<Any?>).orEmpty().mapNotNull { (it as? Obj)?.let(DlJob::of) }
        _jobs.value = list.filter { it.state != "done" || exists(it.uri) }
        if (_jobs.value.size != list.size) save()
    }

    @Synchronized
    private fun save() {
        lastSave = System.currentTimeMillis()
        try { writeObj(file, mapOf("jobs" to _jobs.value.map { it.toObj() })) } catch (e: Exception) { Log.w(TAG, "downloads.json", e) }
    }

    @Synchronized
    private fun edit(id: String, persist: Boolean = true, change: (DlJob) -> DlJob) {
        _jobs.value = _jobs.value.map { if (it.id == id) change(it) else it }
        if (persist || System.currentTimeMillis() - lastSave > 5000) save()
    }

    fun job(id: String): DlJob? = _jobs.value.firstOrNull { it.id == id }

    /** Film / épisode déjà téléchargé (lecture sans réseau, sans occuper de connexion). */
    fun local(key: String): DlJob? = _jobs.value.firstOrNull { it.key == key && it.state == "done" }

    fun status(key: String): DlJob? = _jobs.value.lastOrNull { it.key == key && it.state != "cancelled" }

    // ---- File d'attente ---------------------------------------------------------- //
    /** Ajoute des films / épisodes (déjà présents ou en file : ignorés). Retourne le nombre ajouté. */
    fun add(context: Context, items: List<Item>): Int {
        var added = 0
        synchronized(this) {
            val list = _jobs.value.toMutableList()
            for (it in items) {
                if (it.url.isEmpty() || list.any { j -> j.key == it.key && (j.active || j.state == "done") }) continue
                list.removeAll { j -> j.key == it.key }  // ancien essai en erreur / annulé : remplacé
                list += newJob(it)
                added++
            }
            _jobs.value = list
            save()
        }
        if (added > 0) start(context)
        return added
    }

    fun cancel(id: String) {
        val j = job(id) ?: return
        if (j.state == "running") synchronized(cancelled) { cancelled += id }
        else if (j.state == "waiting") { delete(j.uri); edit(id) { it.copy(state = "cancelled", uri = "", done = 0) } }
    }

    fun cancelAll() = _jobs.value.filter { it.active }.forEach { cancel(it.id) }

    fun retry(context: Context, id: String) {
        edit(id) { if (it.state == "error" || it.state == "cancelled") it.copy(state = "waiting", error = "") else it }
        start(context)
    }

    /** Retire de la liste (et efface le fichier, s'il est terminé ou commencé). */
    fun remove(id: String) {
        val j = job(id) ?: return
        if (j.state == "running") { cancel(id); return }
        delete(j.uri)
        synchronized(this) { _jobs.value = _jobs.value.filter { it.id != id }; save() }
    }

    fun clearFinished() = synchronized(this) {
        _jobs.value = _jobs.value.filter { it.state != "error" && it.state != "cancelled" }
        save()
    }

    /** Relance le service s'il reste des téléchargements en attente (appel au lancement de l'appli). */
    fun resume(context: Context) { if (_jobs.value.any { it.state == "waiting" }) start(context) }

    private fun start(context: Context) {
        context.startForegroundService(Intent(context, DownloadService::class.java))
    }

    private fun newJob(it: Item): DlJob {
        val ext = it.ext.ifEmpty { it.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "") }
            .lowercase().ifEmpty { "mp4" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { m -> m.startsWith("video/") }
            ?: if (ext == "mkv") "video/x-matroska" else "video/mp4"
        val show = it.parent
        return if (it.kind == "episode" && show != null) {
            val (title, _) = cleanTitle(show.name)
            val label = episodeLabel(it)
            DlJob(UUID.randomUUID().toString(), "$title · $label", it.url,
                "$ROOT/${safeName(title)}/Saison %02d".format(it.season), safeName("$title - ${label.replace(" · ", " - ")}") + ".$ext",
                mime, it.key, "episode", it.name, it.logo.ifEmpty { show.logo }, show.key, show.name, it.season, it.episode)
        } else {
            val (title, y) = cleanTitle(it.name)
            val year = y.ifEmpty { it.year }
            val label = if (year.isNotEmpty()) "$title ($year)" else title
            DlJob(UUID.randomUUID().toString(), label, it.url, ROOT, safeName(label) + ".$ext", mime, it.key, "movie",
                it.name, it.logo)
        }
    }

    // ---- Téléchargement (DownloadService) ------------------------------------------ //
    private fun next(): DlJob? = synchronized(this) {
        val j = _jobs.value.firstOrNull { it.state == "waiting" } ?: return null
        edit(j.id) { it.copy(state = "running", error = "", speed = 0) }
        job(j.id)
    }

    /** Traite la file jusqu'au bout (thread du service). onChange : notification à mettre à jour. */
    suspend fun process(onChange: () -> Unit) {
        while (true) {
            val j = next() ?: break
            onChange()
            for (attempt in 0..RETRIES) {
                try {
                    download(j.id, onChange)
                    edit(j.id) { it.copy(state = "done", speed = 0) }
                    break
                } catch (e: Cancelled) {
                    job(j.id)?.let { delete(it.uri) }
                    edit(j.id) { it.copy(state = "cancelled", uri = "", done = 0, speed = 0) }
                    break
                } catch (e: Transient) {
                    edit(j.id) { it.copy(error = e.message ?: "coupure", speed = 0) }
                    if (attempt == RETRIES) edit(j.id) { it.copy(state = "error") }
                    else { onChange(); delay(3000L * (attempt + 1)) }  // puis reprise à la position atteinte
                } catch (e: Exception) {
                    Log.w(TAG, "téléchargement", e)
                    edit(j.id) { it.copy(state = "error", error = e.message ?: e.javaClass.simpleName, speed = 0) }
                    break
                }
            }
            synchronized(cancelled) { cancelled -= j.id }
            onChange()
        }
    }

    private fun isCancelled(id: String) = synchronized(cancelled) { id in cancelled }

    private fun download(id: String, onChange: () -> Unit) {
        val resolver = app.contentResolver
        var j = job(id) ?: throw Cancelled()
        if (j.uri.isEmpty() || !exists(j.uri)) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, j.fileName)
                put(MediaStore.Video.Media.MIME_TYPE, j.mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, j.folder)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("impossible de créer le fichier dans Films (stockage plein ?)")
            edit(id) { it.copy(uri = uri.toString(), done = 0, parts = "") }
            j = job(id)!!
        }
        // Sonde (1 octet) : taille du fichier et téléchargement par morceaux possible (requêtes « Range »)
        val total = request(j.url, "bytes=0-0").use { r ->
            check(r)
            if (r.code == 206) r.header("Content-Range").orEmpty().substringAfterLast('/').toLongOrNull() ?: 0L else 0L
        }
        if (total > 0) inParts(id, Uri.parse(j.uri), total, onChange) else inOneGo(id, Uri.parse(j.uri), onChange)
    }

    private val client by lazy { Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build() }

    private fun request(url: String, range: String?): okhttp3.Response {
        val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT)
        if (range != null) req.header("Range", range)
        return try { client.newCall(req.build()).execute() } catch (e: IOException) {
            throw Transient("serveur injoignable (${e.message ?: e.javaClass.simpleName})")
        }
    }

    /** Réponse refusée : erreur passagère (serveur saturé, connexions simultanées dépassées) ou définitive. */
    private fun check(r: okhttp3.Response) {
        if (r.code >= 500 || r.code == 403 || r.code == 429) throw Transient("le serveur a répondu « ${r.code} »")
        if (!r.isSuccessful) throw IOException("le serveur a répondu « ${r.code} »")
        // Film indisponible chez le fournisseur : il répond « 200 » avec une page vide au lieu de la vidéo
        if (r.body?.contentType()?.type == "text") throw IOException(UNAVAILABLE)
    }

    private fun diskFull(e: IOException) = e.message.orEmpty().let { "ENOSPC" in it || "No space" in it }

    /**
     * Téléchargement par morceaux de 16 Mo, sur CONNECTIONS connexions à la fois : le fournisseur limite le débit de
     * chaque connexion (~2 Mo/s mesurés depuis le PC), pas leur nombre pour les films. Morceaux terminés notés dans
     * le job (reprise après une coupure ou un redémarrage). Une connexion refusée (limite du fournisseur) abandonne
     * après 3 essais, les autres continuent ; s'il ne reste plus rien qui avance, nouvel essai complet (process).
     */
    private fun inParts(id: String, uri: Uri, total: Long, onChange: () -> Unit) {
        val j = job(id) ?: throw Cancelled()
        val count = ((total + PART - 1) / PART).toInt()
        fun size(i: Int) = minOf(PART, total - i * PART)
        val doneParts = java.util.Collections.synchronizedSet(HashSet(if (j.total == total) decodeParts(j.parts) else emptySet()))
        val queue = java.util.ArrayDeque((0 until count).filter { it !in doneParts })
        val received = java.util.concurrent.atomic.AtomicLong(doneParts.sumOf { size(it) })
        edit(id) { it.copy(total = total, done = received.get(), parts = encodeParts(doneParts)) }
        val fatal = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val lastError = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
        val pfd = app.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("fichier inaccessible")
        pfd.use {
            val channel = java.io.FileOutputStream(pfd.fileDescriptor).channel  // écritures à une position donnée
            fun worker() {
                var fails = 0
                val buf = ByteArray(CHUNK)
                while (fatal.get() == null && !isCancelled(id)) {
                    val i = synchronized(queue) { queue.pollFirst() } ?: return
                    val from = i * PART
                    val to = from + size(i) - 1
                    var got = 0L
                    try {
                        request(j.url, "bytes=$from-$to").use { r ->
                            check(r)
                            if (r.code != 206) throw Transient("le serveur ne renvoie pas le morceau demandé")
                            r.body!!.byteStream().use { input ->
                                while (true) {
                                    if (isCancelled(id) || fatal.get() != null) throw Cancelled()
                                    // Bloc de 256 Ko avant d'écrire (les petites écritures dans Films/ sont lentes)
                                    var n = 0
                                    while (n < buf.size) { val k = input.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                                    if (n == 0) break
                                    val bb = java.nio.ByteBuffer.wrap(buf, 0, n)
                                    var pos = from + got
                                    while (bb.hasRemaining()) pos += channel.write(bb, pos)
                                    got += n; received.addAndGet(n.toLong())
                                    if (n < buf.size) break
                                }
                            }
                        }
                        if (got != to - from + 1) throw Transient("morceau incomplet")
                        doneParts += i
                        fails = 0
                    } catch (e: Exception) {
                        received.addAndGet(-got)  // morceau à refaire en entier
                        synchronized(queue) { queue.addFirst(i) }
                        when {
                            e is Cancelled -> return
                            e is Transient || e is java.net.SocketTimeoutException -> lastError.set(e)
                            e is IOException && diskFull(e) -> { fatal.set(IOException("stockage du téléphone plein")); return }
                            e is IOException && e.message == UNAVAILABLE -> { fatal.set(e); return }
                            e is IOException -> lastError.set(Transient("coupure (${e.message ?: e.javaClass.simpleName})"))
                            else -> { fatal.set(e); return }
                        }
                        if (++fails >= 3) return  // cette connexion abandonne, les autres continuent
                        Thread.sleep(2000L * fails)
                    }
                }
            }
            val workers = (0 until minOf(CONNECTIONS, queue.size)).map { kotlin.concurrent.thread(name = "dl-$it") { worker() } }
            var lastTick = System.currentTimeMillis()
            var lastDone = received.get()
            var lastLog = lastTick
            var logDone = lastDone
            while (workers.any { it.isAlive }) {
                workers.first { it.isAlive }.join(1000)
                val now = System.currentTimeMillis()
                if (now - lastTick >= 1000) {
                    val done = received.get()
                    val speed = (done - lastDone) * 1000 / (now - lastTick)
                    lastTick = now; lastDone = done
                    val parts = synchronized(doneParts) { encodeParts(doneParts) }
                    edit(id, persist = false) { it.copy(done = done, speed = speed, parts = parts, error = "") }
                    onChange()
                    if (now - lastLog >= 10_000) {
                        Log.i(TAG, "téléchargement : ${(done - logDone) / 1024 / ((now - lastLog) / 1000)} Ko/s, " +
                            "${workers.count { it.isAlive }} connexion(s)")
                        lastLog = now; logDone = done
                    }
                }
            }
        }
        val parts = synchronized(doneParts) { encodeParts(doneParts) }
        edit(id) { it.copy(done = received.get(), parts = parts) }
        if (isCancelled(id)) throw Cancelled()
        fatal.get()?.let { throw it }
        if (doneParts.size < count) throw (lastError.get() as? Transient) ?: Transient("téléchargement incomplet")
        finish(uri)
    }

    /** Serveur qui ne sait pas envoyer de morceaux : une seule connexion, du début à la fin (reprise si possible). */
    private fun inOneGo(id: String, uri: Uri, onChange: () -> Unit) {
        val resolver = app.contentResolver
        val j = job(id) ?: throw Cancelled()
        // Fichier tout juste créé : il n'existe vraiment qu'au premier octet écrit (lecture impossible avant)
        val have = if (j.parts.isNotEmpty()) 0L else try {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.coerceAtLeast(0) ?: 0L
        } catch (e: java.io.FileNotFoundException) { 0L }
        request(j.url, if (have > 0) "bytes=$have-" else null).use { r ->
            if (r.code == 416 && have > 0) { finish(uri); edit(id) { it.copy(done = have, total = have) }; return }
            check(r)
            val body = r.body ?: throw Transient("réponse vide")
            val length = body.contentLength().coerceAtLeast(0)
            val resumed = r.code == 206 && have > 0
            val start = if (resumed) have else 0L
            edit(id) { it.copy(done = start, total = if (length > 0) start + length else 0, parts = "") }
            var done = start
            var lastTick = System.currentTimeMillis()
            var lastDone = done
            try {
                // Écritures groupées par 1 Mo : le réseau livre de petits morceaux (~8 Ko) et chaque écriture dans
                // Films/ passe par la couche de stockage partagé d'Android (lente pour de petites écritures)
                (resolver.openOutputStream(uri, if (resumed) "wa" else "wt") ?: throw IOException("fichier inaccessible"))
                    .buffered(1024 * 1024).use { out ->
                    val buf = ByteArray(CHUNK)
                    body.byteStream().use { input ->
                        while (true) {
                            if (isCancelled(id)) throw Cancelled()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 1000) {
                                val speed = (done - lastDone) * 1000 / (now - lastTick)
                                lastTick = now; lastDone = done
                                edit(id, persist = false) { it.copy(done = done, speed = speed) }
                                onChange()
                            }
                        }
                    }
                }
            } catch (e: Cancelled) {
                throw e
            } catch (e: IOException) {
                edit(id) { it.copy(done = done) }
                if (diskFull(e)) throw IOException("stockage du téléphone plein")
                throw Transient("coupure (${e.message ?: e.javaClass.simpleName})")
            }
            edit(id) { it.copy(done = done) }
            if (done == 0L) throw IOException(UNAVAILABLE)
            if (length > 0 && done < start + length) throw Transient("téléchargement incomplet")
            finish(uri)
        }
    }

    /** Morceaux terminés, notés « 0-5,7,9-12 ». */
    private fun encodeParts(parts: Set<Int>): String {
        val sorted = parts.sorted()
        val out = StringBuilder()
        var i = 0
        while (i < sorted.size) {
            var k = i
            while (k + 1 < sorted.size && sorted[k + 1] == sorted[k] + 1) k++
            if (out.isNotEmpty()) out.append(',')
            out.append(if (k > i) "${sorted[i]}-${sorted[k]}" else "${sorted[i]}")
            i = k + 1
        }
        return out.toString()
    }

    private fun decodeParts(text: String): Set<Int> = text.split(',').filter { it.isNotBlank() }.flatMap { r ->
        val a = r.substringBefore('-').toIntOrNull() ?: return@flatMap emptyList()
        val b = r.substringAfter('-', r).toIntOrNull() ?: a
        (a..b).toList()
    }.toSet()

    /** Fichier complet : visible dans la Galerie et les autres applis vidéo. */
    private fun finish(uri: Uri) {
        app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
    }

    /** Le fichier est-il toujours dans MediaStore ? (aussi « en cours » et encore vide : il ne s'ouvre pas encore) */
    private fun exists(uri: String): Boolean = uri.isNotEmpty() && try {
        val args = android.os.Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE) }
        app.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.Video.Media._ID), args, null)?.use { it.count > 0 } ?: false
    } catch (e: Exception) { false }

    private fun delete(uri: String) {
        if (uri.isEmpty()) return
        try { app.contentResolver.delete(Uri.parse(uri), null, null) } catch (e: Exception) { Log.w(TAG, "suppression", e) }
    }

    /** Place prise par les fichiers terminés. */
    fun usedBytes(): Long = _jobs.value.filter { it.state == "done" }.sumOf { it.total.takeIf { t -> t > 0 } ?: it.done }

    // ---- Noms de fichiers (comme le PC : playlist.clean_title / episode_label, downloads.safe_name) ------------ //
    private val BAD = Regex("[<>:\"/\\\\|?*\\x00-\\x1f]")
    private val TAG_RE = Regex(
        "^\\s*(\\|[^|]*\\||\\[[^\\]]*\\]|[A-Z]{2,3}\\s*[-:|]\\s+)|" +
            "\\((?:MULTI|VF|VOSTFR|VO|VFF|VFQ|FR|EN|FRENCH|TRUEFRENCH)\\)|" +
            "\\b(?:4K|UHD|FHD|HD|SD|HEVC|H265|x265|1080p|720p|2160p|MULTI|VOSTFR|VFF|VFQ)\\b",
        RegexOption.IGNORE_CASE)
    private val YEAR_RE = Regex("[(\\[\\s-]*((?:19|20)\\d{2})[)\\]]?\\s*$")
    private val EPISODE_RE = Regex("\\bS(\\d{1,2})\\s*[ ._-]?\\s*E(\\d{1,4})\\b", RegexOption.IGNORE_CASE)

    fun safeName(text: String): String =
        BAD.replace(text, " ").trim().trimEnd('.', ' ').replace(Regex("\\s{2,}"), " ").take(150).ifEmpty { "sans titre" }

    /** « |FR| Titre (MULTI) FHD 2026 » -> (« Titre », « 2026 »). */
    fun cleanTitle(name: String): Pair<String, String> {
        var title = name
        repeat(3) { title = TAG_RE.replace(title, " ") }
        title = title.replace(Regex("\\s{2,}"), " ").trim(' ', '-', '_', '.', '|', ':')
        val m = YEAR_RE.find(title)
        if (m != null && m.range.first > 0) {
            return title.substring(0, m.range.first).trim(' ', '-', '_', '.', '|', ':', '(') to m.groupValues[1]
        }
        return title to ""
    }

    /** « Série (MULTI) FHD - S01E02 - Le titre » -> « S01E02 · Le titre ». */
    fun episodeLabel(it: Item): String {
        val m = EPISODE_RE.find(it.name)
        val (code, rest) = when {
            m != null -> "S%02dE%02d".format(m.groupValues[1].toInt(), m.groupValues[2].toInt()) to
                it.name.substring(m.range.last + 1).trim(' ', '-', '_', '.', '|', ':')
            it.season > 0 || it.episode > 0 -> "S%02dE%02d".format(it.season, it.episode) to it.name.trim()
            else -> return it.name
        }
        return if (rest.isNotEmpty()) "$code · $rest" else code
    }
}
