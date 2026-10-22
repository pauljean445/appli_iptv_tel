package io.github.pauljean445.iptvtel.data

import android.net.Uri

/**
 * Playlists M3U / M3U8 (URL) et description de la source IPTV d'un profil, portage de playlist.py.
 * Avec une URL de playlist Xtream (get.php?username=…&password=…), on passe par l'API Xtream, plus complète.
 */
object Playlist {
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
    // URL de flux Xtream : http://hôte/[live|movie|series/]user/mdp/123.ext
    private val XT_URL = Regex("""^https?://[^/]+/(?:(live|movie|series)/)?[^/]+/[^/]+/(\d+)(?:\.(\w+))?$""", RegexOption.IGNORE_CASE)
    private val XT_KINDS = mapOf("" to "live", "live" to "live", "movie" to "movie", "series" to "ep")
    // "Ma série S01E02", "Ma série S1 E2", "Ma série - S01.E02"
    private val EPISODE = Regex("""\bS(\d{1,2})\s*[ ._-]?\s*E(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val VOD_EXT = listOf(".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm", ".m4v", ".mpg", ".mpeg", ".divx")

    /** (clé, stream_id, extension) : pour une URL Xtream, même clé que l'API (x:movie:123…). */
    fun stableKey(url: String): Triple<String, String, String> {
        val m = XT_URL.matchEntire(url.trim()) ?: return Triple("m3u:$url", "", "")
        val kind = XT_KINDS[m.groupValues[1].lowercase()] ?: "live"
        return Triple("x:$kind:${m.groupValues[2]}", m.groupValues[2], m.groupValues[3])
    }

    fun parse(text: String): List<Item> {
        val items = ArrayList<Item>()
        var pending: Map<String, Any>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
                    val attrs = ATTR.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val quote = line.lastIndexOf('"')
                    val comma = line.indexOf(',', if (quote >= 0) quote else 0)
                    val name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    val days = attrs["catchup-days"] ?: attrs["tvg-rec"] ?: attrs["timeshift"] ?: "0"
                    pending = mapOf(
                        "name" to name.ifEmpty { attrs["tvg-name"] ?: "" },
                        "group" to (attrs["group-title"] ?: ""), "logo" to (attrs["tvg-logo"] ?: ""),
                        "tvg_id" to (attrs["tvg-id"] ?: ""), "archive_days" to (days.toIntOrNull() ?: 0),
                    )
                }
                line.startsWith("#EXTGRP:") -> pending?.let {
                    if ((it["group"] as String).isEmpty()) pending = it + ("group" to line.substring(8).trim())
                }
                line.startsWith("#") -> Unit  // #EXTM3U, #EXTVLCOPT, commentaires…
                else -> {
                    val info = pending ?: emptyMap()
                    val (key, sid, ext) = stableKey(line)
                    items += Item(
                        name = (info["name"] as? String).orEmpty().ifEmpty { line }, url = line,
                        group = (info["group"] as? String).orEmpty(), logo = (info["logo"] as? String).orEmpty(),
                        tvgId = (info["tvg_id"] as? String).orEmpty(), archiveDays = (info["archive_days"] as? Int) ?: 0,
                        key = key, streamId = sid, ext = ext,
                    )
                    pending = null
                }
            }
        }
        return items
    }

    private fun episodeInfo(name: String): Triple<String, Int, Int>? {
        val m = EPISODE.find(name) ?: return null
        val show = name.substring(0, m.range.first).trim(' ', '-', '_', '.', '|', ':')
        return Triple(show.ifEmpty { name.trim() }, m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** Classe les entrées (direct, films, épisodes regroupés en séries), comme classify_all. */
    fun classify(items: List<Item>, seriesKeywords: List<String>): List<Item> {
        val kw = seriesKeywords.map { it.lowercase() }.filter { it.isNotEmpty() }
        val xtreamStyle = items.any { i -> listOf("/movie/", "/series/", "/live/").any { i.url.lowercase().contains(it) } }
        val out = ArrayList<Item>()
        val shows = LinkedHashMap<String, Item>()
        for (it in items) {
            val path = (Uri.parse(it.url).path ?: "").lowercase()
            val kind = when {
                "/movie/" in path -> "movie"
                "/series/" in path -> "episode"
                xtreamStyle -> "live"
                VOD_EXT.none { path.endsWith(it) } -> "live"
                episodeInfo(it.name) != null || kw.any { k -> k in it.group.lowercase() } -> "episode"
                else -> "movie"
            }
            if (kind != "episode") {
                it.kind = kind
                out += it
                continue
            }
            val (showName, season, ep) = episodeInfo(it.name) ?: Triple(it.groupLabel, 0, 0)
            val skey = "m3u-series:" + showName.lowercase()
            val show = shows.getOrPut(skey) {
                Item(name = showName, group = it.group, logo = it.logo, kind = "series", key = skey,
                    episodes = ArrayList(), infoLoaded = true).also { s -> out += s }
            }
            it.kind = "episode"; it.parent = show; it.season = season; it.episode = ep
            (show.episodes as ArrayList<Item>) += it
        }
        shows.values.forEach { s -> (s.episodes as ArrayList<Item>).sortWith(compareBy({ it.season }, { it.episode })) }
        return out
    }

    fun loadFromUrl(url: String, seriesKeywords: List<String>): List<Item> {
        val items = parse(String(Http.get(url, 120, retries = 1), Charsets.UTF_8).removePrefix("﻿"))
        if (items.isEmpty()) throw XtreamClient.XtreamError(
            "Aucune chaîne trouvée : la réponse n'est pas une playlist M3U valide (identifiants, abonnement ou URL ?).")
        return classify(items, seriesKeywords)
    }

    /** Si l'URL est une playlist Xtream (get.php?username=…&password=…) : (serveur, user, mdp). */
    fun xtreamFromM3uUrl(url: String): Triple<String, String, String>? {
        val u = Uri.parse(url.trim())
        if (!(u.path ?: "").lowercase().endsWith("get.php")) return null
        val user = u.getQueryParameter("username").orEmpty()
        val pwd = u.getQueryParameter("password").orEmpty()
        if (user.isEmpty() || pwd.isEmpty() || u.authority.isNullOrEmpty()) return null
        return Triple("${u.scheme ?: "http"}://${u.authority}", user, pwd)
    }
}

/** Source IPTV d'un profil (réglages de profile.json, propres à l'appareil). */
data class Source(
    val type: String,            // "xtream" | "url"
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val format: String = "ts",
    val url: String = "",
) {
    /** Identifie le catalogue en cache (sans le mot de passe). */
    val id: String get() = listOf(type, server, username, format, url.substringBefore('?')).joinToString("|")

    companion object {
        /** D'après les réglages du profil ; null si incomplets (les mêmes clés que sur PC). */
        fun fromProfile(p: Obj): Source? {
            val fmt = p["stream_format"].str().ifEmpty { "ts" }
            return when (p["mode"].str()) {
                "xtream" -> {
                    val (s, u, pw) = Triple(p["server"].str(), p["username"].str(), p["password"].str())
                    if (s.isNotEmpty() && u.isNotEmpty() && pw.isNotEmpty())
                        Source("xtream", XtreamClient.normalizeServer(s), u, pw, fmt) else null
                }
                "url" -> {
                    val url = p["url"].str()
                    if (url.isEmpty()) null
                    else Playlist.xtreamFromM3uUrl(url)?.let { (s, u, pw) -> Source("xtream", s, u, pw, fmt, url) }
                        ?: Source("url", url = url)
                }
                else -> null  // "file" : fichier local du PC, sans objet sur le téléphone
            }
        }
    }
}
