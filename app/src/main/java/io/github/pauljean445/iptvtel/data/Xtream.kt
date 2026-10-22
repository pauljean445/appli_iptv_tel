package io.github.pauljean445.iptvtel.data

import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Client de l'API Xtream Codes (player_api.php), portage de xtream.py de l'appli PC : catégories du
 * fournisseur pour le direct, les films et les séries, descriptifs, saisons et épisodes, replays.
 */
class XtreamClient(server: String, val username: String, val password: String, fmt: String = "ts") {
    val server = normalizeServer(server)
    val fmt = if (fmt == "ts" || fmt == "m3u8") fmt else "ts"
    private val u = enc(username)
    private val p = enc(password)

    class XtreamError(message: String) : Exception(message)

    // ---- Requêtes -------------------------------------------------------------- //
    fun raw(action: String = "", timeoutSec: Long = 120, params: Map<String, String> = emptyMap()): ByteArray {
        val sb = StringBuilder("$server/player_api.php?username=$u&password=$p")
        if (action.isNotEmpty()) sb.append("&action=").append(action)
        params.forEach { (k, v) -> sb.append('&').append(k).append('=').append(enc(v)) }
        return Http.get(sb.toString(), timeoutSec, retries = 2)
    }

    private fun parse(data: ByteArray): Any? = try {
        JSON.parseToJsonElement(String(data, Charsets.UTF_8)).toAny()
    } catch (e: Exception) {
        throw XtreamError("Réponse inattendue du serveur (API Xtream indisponible ?).")
    }

    fun api(action: String = "", timeoutSec: Long = 120, params: Map<String, String> = emptyMap()): Any? =
        parse(raw(action, timeoutSec, params))

    private fun checkAuth(info: Any?) {
        val user = info.asObj()?.get("user_info").asObj()
        if (user == null || user["auth"].str() != "1") throw XtreamError("Identifiants refusés par le serveur.")
        val status = user["status"].str()
        if (status.isNotEmpty() && status != "Active") throw XtreamError("Abonnement « $status ».")
    }

    // ---- URL des flux ---------------------------------------------------------- //
    fun liveUrl(streamId: String) = "$server/live/$u/$p/$streamId.$fmt"
    fun movieUrl(streamId: String, ext: String) = "$server/movie/$u/$p/$streamId.${ext.ifEmpty { "mp4" }}"
    fun episodeUrl(episodeId: String, ext: String) = "$server/series/$u/$p/$episodeId.${ext.ifEmpty { "mp4" }}"

    /** start : heure du serveur « AAAA-MM-JJ HH:MM:SS » (champ « start » de l'EPG). */
    fun timeshiftUrl(streamId: String, start: String, minutes: Int): String {
        val stamp = "${start.substring(0, 10)}:${start.substring(11, 13)}-${start.substring(14, 16)}"
        return "$server/timeshift/$u/$p/$minutes/$stamp/$streamId.ts"
    }

    // ---- Catalogue ------------------------------------------------------------- //
    /** Réponses brutes de l'API nécessaires au catalogue, téléchargées en parallèle. */
    suspend fun fetchCatalog(): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        coroutineScope {
            val auth = async { raw("") }
            val parts = CATALOG_ACTIONS.map { a -> async { a to raw(a) } }
            checkAuth(parse(auth.await()))
            parts.awaitAll().toMap()
        }
    }

    fun buildCatalog(raw: Map<String, ByteArray>): List<Item> {
        fun rows(action: String): List<Obj> =
            (parse(raw[action] ?: "[]".toByteArray()) as? List<*>)?.mapNotNull { it.asObj() } ?: emptyList()

        val (liveC, live, vodC, vod, serC, series) = CATALOG_ACTIONS.map { rows(it) }
        fun cats(rows: List<Obj>) = rows.associate { it["category_id"].str() to it["category_name"].str() }
        val liveCats = cats(liveC); val vodCats = cats(vodC); val serCats = cats(serC)
        // Ordre des groupes = ordre des catégories du fournisseur
        val order = HashMap<String, Int>()
        (liveCats.keys + vodCats.keys + serCats.keys).forEachIndexed { i, cid -> order.putIfAbsent(cid, i) }
        fun sorted(rows: List<Obj>) = rows.sortedBy { order[it["category_id"].str()] ?: Int.MAX_VALUE }

        val items = ArrayList<Item>(live.size + vod.size + series.size)
        for (s in sorted(live)) {
            val sid = s["stream_id"]?.str()?.takeIf { it.isNotEmpty() } ?: continue
            val archive = if (s["tv_archive"].str() == "1") s["tv_archive_duration"].int() else 0
            items += Item(
                name = s["name"].str().trim(), url = liveUrl(sid), group = liveCats[s["category_id"].str()] ?: "",
                logo = s["stream_icon"].str(), tvgId = s["epg_channel_id"].str(), kind = "live",
                key = "x:live:$sid", streamId = sid, archiveDays = archive,
            )
        }
        for (s in sorted(vod)) {
            val sid = s["stream_id"]?.str()?.takeIf { it.isNotEmpty() } ?: continue
            val ext = s["container_extension"].str()
            items += Item(
                name = s["name"].str().trim(), url = movieUrl(sid, ext), ext = ext,
                group = vodCats[s["category_id"].str()] ?: "", logo = s["stream_icon"].str(), kind = "movie",
                key = "x:movie:$sid", streamId = sid, rating = rating(s["rating"]),
                year = year(s["year"] ?: s["releasedate"]),
            )
        }
        for (s in sorted(series)) {
            val sid = s["series_id"]?.str()?.takeIf { it.isNotEmpty() } ?: continue
            items += Item(
                name = s["name"].str().trim(), group = serCats[s["category_id"].str()] ?: "",
                logo = s["cover"].str(), kind = "series", key = "x:series:$sid", streamId = sid,
                plot = s["plot"].str(), rating = rating(s["rating"]), genre = s["genre"].str(),
                year = year(s["releaseDate"] ?: s["release_date"]), cast = s["cast"].str(),
                director = s["director"].str(),
            )
        }
        if (items.isEmpty()) throw XtreamError("Le serveur n'a renvoyé aucune chaîne.")
        return items
    }

    // ---- Détails (à la demande) ------------------------------------------------ //
    fun fillMovieInfo(it: Item) {
        val info = api("get_vod_info", 30, mapOf("vod_id" to it.streamId)).asObj()?.get("info").asObj()
        if (info != null) {
            it.plot = info["plot"].str().ifEmpty { info["description"].str() }.ifEmpty { it.plot }
            it.genre = info["genre"].str().ifEmpty { it.genre }
            it.duration = info["duration"].str().ifEmpty { it.duration }
            it.cast = info["cast"].str().ifEmpty { info["actors"].str() }.ifEmpty { it.cast }
            it.director = info["director"].str().ifEmpty { it.director }
            it.year = year(info["releasedate"]).ifEmpty { it.year }
            it.rating = rating(info["rating"]).ifEmpty { it.rating }
            if (it.logo.isEmpty()) it.logo = info["cover_big"].str().ifEmpty { info["movie_image"].str() }
        }
        it.infoLoaded = true
    }

    fun loadEpisodes(show: Item): List<Item> {
        val data = api("get_series_info", 60, mapOf("series_id" to show.streamId)).asObj() ?: LinkedHashMap()
        data["info"].asObj()?.let { info ->
            if (show.plot.isEmpty()) show.plot = info["plot"].str()
            if (show.genre.isEmpty()) show.genre = info["genre"].str()
            if (show.rating.isEmpty()) show.rating = rating(info["rating"])
            if (show.logo.isEmpty()) show.logo = info["cover"].str()
        }
        val groups: Collection<Any?> = when (val raw = data["episodes"]) {
            is Map<*, *> -> raw.values
            is List<*> -> raw
            else -> emptyList()
        }
        val episodes = ArrayList<Item>()
        for (group in groups) {
            for (e in (group as? List<*>).orEmpty().mapNotNull { it.asObj() }) {
                val eid = e["id"]?.str()?.takeIf { it.isNotEmpty() } ?: continue
                val ei = e["info"].asObj() ?: LinkedHashMap()
                val season = e["season"].int()
                val num = e["episode_num"].int()
                val title = e["title"].str().trim()
                episodes += Item(
                    name = title.ifEmpty { "Épisode $num" }, url = episodeUrl(eid, e["container_extension"].str()),
                    group = "Saison $season", logo = ei["movie_image"].str().ifEmpty { show.logo },
                    kind = "episode", key = "x:ep:$eid", streamId = eid,
                    plot = ei["plot"].str().ifEmpty { ei["overview"].str() }, rating = rating(ei["rating"]),
                    duration = ei["duration"].str(), year = year(ei["air_date"] ?: ei["releasedate"]),
                    season = season, episode = num, parent = show, infoLoaded = true,
                )
            }
        }
        episodes.sortWith(compareBy({ it.season }, { it.episode }))
        show.episodes = episodes
        show.infoLoaded = true
        return episodes
    }

    /** Programmes passés de la chaîne disponibles en replay (du plus récent au plus ancien). */
    fun archivePrograms(channel: Item): List<Item> {
        val rows = (api("get_simple_data_table", 60, mapOf("stream_id" to channel.streamId))
            .asObj()?.get("epg_listings") as? List<*>).orEmpty().mapNotNull { it.asObj() }
        val nowSec = System.currentTimeMillis() / 1000
        val oldest = nowSec - max(1, channel.archiveDays) * 86400L
        var flagged = rows.filter { it["has_archive"].str() == "1" }
        if (flagged.isEmpty()) {  // certains serveurs ne remplissent pas has_archive
            flagged = rows.filter { it["start_timestamp"].int().toLong() in oldest until nowSec }
        }
        val hm = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val programs = ArrayList<Item>()
        for (r in flagged) {
            val start = r["start_timestamp"].str().toLongOrNull() ?: 0
            var end = r["stop_timestamp"].str().toLongOrNull() ?: 0
            val startText = r["start"].str()
            if (start == 0L || start >= nowSec || startText.length < 16) continue
            if (end <= start) end = start + 3600
            val minutes = max(1, ceil((end - start) / 60.0).toInt())
            val title = b64(r["title"]).ifEmpty { "Programme" }
            val hours = "${hm.format(start * 1000)}–${hm.format(end * 1000)}"
            programs += Item(
                name = "$hours  $title" + if (end > nowSec) "  (en cours)" else "",
                url = timeshiftUrl(channel.streamId, startText, minutes), group = dayLabel(start),
                logo = channel.logo, kind = "program", key = "x:prog:${channel.streamId}:$start",
                streamId = channel.streamId, plot = b64(r["description"]), start = start, end = end,
                parent = channel, duration = "$minutes min", infoLoaded = true,
            )
        }
        programs.sortByDescending { it.start }
        return programs
    }

    companion object {
        val CATALOG_ACTIONS = listOf(
            "get_live_categories", "get_live_streams", "get_vod_categories",
            "get_vod_streams", "get_series_categories", "get_series",
        )
        private val DAYS = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun normalizeServer(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.lowercase().startsWith("http://") && !s.lowercase().startsWith("https://")) s = "http://$s"
            return s
        }

        /** Empreinte des réponses brutes : identique = catalogue inchangé, inutile de le reconstruire. */
        fun signature(raw: Map<String, ByteArray>): String {
            val md = MessageDigest.getInstance("SHA-1")
            CATALOG_ACTIONS.forEach { md.update(raw[it] ?: ByteArray(0)) }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun rating(v: Any?): String {
            val d = v.str().toDoubleOrNull() ?: return ""
            return if (d > 0) "%.1f".format(Locale.US, d) else ""
        }

        fun year(v: Any?): String {
            val s = v.str()
            return if (s.length >= 4 && s.substring(0, 4).all { it.isDigit() }) s.substring(0, 4) else ""
        }

        fun b64(v: Any?): String {
            val s = v.str()
            if (s.isEmpty()) return ""
            return try {
                String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8).trim()
            } catch (e: IllegalArgumentException) {
                s
            }
        }

        fun dayLabel(ts: Long): String {
            val d = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
            val today = Calendar.getInstance()
            fun dayNum(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
            return when (dayNum(today) - dayNum(d)) {
                0 -> "Aujourd'hui"
                1 -> "Hier"
                else -> "${DAYS[(d.get(Calendar.DAY_OF_WEEK) + 5) % 7]} ${SimpleDateFormat("dd/MM", Locale.FRANCE).format(d.time)}"
            }
        }

        private val TIMESHIFT = Regex("""^(.*/timeshift/[^/]+/[^/]+/)(\d+)/(\d{4}-\d{2}-\d{2}):(\d{2})-(\d{2})/([^/]+)$""")

        /** Même replay commençant minutes plus tard (le serveur ne gère que la minute) : il démarre
         *  directement au bon endroit, sans que VLC ait à se déplacer dans le flux. */
        fun shiftTimeshiftUrl(url: String, minutes: Int): String? {
            val m = TIMESHIFT.matchEntire(url) ?: return null
            if (minutes <= 0) return null
            val (head, length, day, hh, mm, tail) = m.destructured
            if (length.toInt() - minutes < 1) return null
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val start = Calendar.getInstance().apply { time = fmt.parse("$day $hh:$mm")!!; add(Calendar.MINUTE, minutes) }
            val stamp = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(start.time)
            return "$head${length.toInt() - minutes}/$stamp/$tail"
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
