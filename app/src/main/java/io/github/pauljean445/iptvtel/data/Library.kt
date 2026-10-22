package io.github.pauljean445.iptvtel.data

import java.io.File

/**
 * Favoris, positions de lecture, « Vu récemment », épisodes vus et langues choisies (library.json).
 * Portage exact de library.py (même format de fichier et mêmes règles) : chaque entrée porte l'heure de sa
 * dernière modification (« t ») et chaque suppression laisse une trace datée dans « deleted » ; pour la
 * synchro, la version la plus récente de chaque entrée gagne. Traces de plus de 3 mois effacées au chargement.
 *
 *  fav      : "onglet|clé" -> {"o": ordre, "t"}            (onglets : live, movie, series, replay)
 *  progress : clé film / épisode -> {"pos": ms, "len": ms, "t"}
 *  recent   : clé film / série -> {"kind", "name", "t", "last_ep", "last_label", "total"}
 *  watched  : clé épisode vu -> {"s": clé de la série, "t"}
 *  prefs    : clé film / série -> {"audio", "spu", "t"}    (langue et sous-titres choisis)
 *  hidden   : "onglet|catégorie" -> {"t"}                  (catégories masquées, rangées dans « Autres »)
 */
class Library(val folder: File) {
    val tables: Map<String, Obj>
    val deleted: Map<String, Obj>
    var dirty = false
    private val file = File(folder, "library.json")

    val fav get() = tables.getValue("fav")
    val progress get() = tables.getValue("progress")
    val recent get() = tables.getValue("recent")
    val watched get() = tables.getValue("watched")
    val prefs get() = tables.getValue("prefs")
    val hidden get() = tables.getValue("hidden")

    init {
        val data = readObj(file)
        dirty = data["v"].int() != VERSION && data.isNotEmpty()
        tables = TABLES.associateWith { data[it].asObj() ?: LinkedHashMap() }
        val del = data["deleted"].asObj() ?: LinkedHashMap()
        deleted = TABLES.associateWith { del[it].asObj() ?: LinkedHashMap() }
        purge()
        save()
    }

    @Synchronized
    fun save(force: Boolean = false) {
        if (dirty || force) {
            writeObj(file, LinkedHashMap<String, Any?>().apply {
                put("v", VERSION); putAll(tables); put("deleted", deleted)
            })
            dirty = false
        }
    }

    /** Heure de la modification, toujours après la précédente de cette entrée (sinon une suppression juste
     *  après un ajout aurait la même heure et ne gagnerait pas). */
    private fun stamp(table: String, key: String): Double {
        val old = tables.getValue(table)[key].asObj()?.get("t").num().takeIf { it > 0 } ?: deleted.getValue(table)[key].num()
        return maxOf(now(), old + 0.001)
    }

    private fun put(table: String, key: String, entry: Obj) {
        entry["t"] = stamp(table, key)
        tables.getValue(table)[key] = entry
        deleted.getValue(table).remove(key)
        dirty = true
    }

    private fun drop(table: String, key: String) {
        if (key in tables.getValue(table)) {
            deleted.getValue(table)[key] = stamp(table, key)
            tables.getValue(table).remove(key)
            dirty = true
        }
    }

    private fun purge() {
        val limit = now() - PURGE_DAYS * 86400
        for (marks in deleted.values) {
            val old = marks.filterValues { it.num() < limit }.keys
            if (old.isNotEmpty()) { old.forEach { marks.remove(it) }; dirty = true }
        }
    }

    /** Efface tout le profil (changement de fournisseur IPTV : les clés ne correspondent plus). */
    fun reset() {
        TABLES.forEach { t -> tables.getValue(t).keys.toList().forEach { drop(t, it) } }
        save()
    }

    // ---- Favoris (par onglet : live, movie, series, replay) --------------------- //
    private fun favRows(tab: String): List<Pair<Double, String>> {
        val prefix = "$tab|"
        return fav.entries.filter { it.key.startsWith(prefix) }
            .map { it.value.asObj()?.get("o").num() to it.key.substring(prefix.length) }
            .sortedWith(compareBy({ it.first }, { it.second }))
    }

    fun isFavorite(tab: String, key: String) = "$tab|$key" in fav

    fun toggleFavorite(tab: String, key: String): Boolean {
        val fk = "$tab|$key"
        val added = fk !in fav
        if (added) {
            val rows = favRows(tab)
            put("fav", fk, mutableMapOf("o" to if (rows.isEmpty()) 0.0 else rows.last().first + 1))
        } else drop("fav", fk)
        save()
        return added
    }

    fun favoriteKeys(tab: String): List<String> = favRows(tab).map { it.second }

    /** Change l'ordre des favoris : key passe juste avant before (à la fin si null). */
    fun moveFavorite(tab: String, key: String, before: String?) {
        val keys = favoriteKeys(tab).toMutableList()
        if (key !in keys || key == before) return
        keys.remove(key)
        val i = if (before != null && before in keys) keys.indexOf(before) else keys.size
        fun order(k: String) = fav["$tab|$k"].asObj()?.get("o").num()
        val lo = if (i > 0) order(keys[i - 1]) else null
        val hi = if (i < keys.size) order(keys[i]) else null
        val o = when {
            lo == null -> (hi ?: 1.0) - 1
            hi == null -> lo + 1
            else -> (lo + hi) / 2
        }
        if (lo != null && hi != null && !(lo < o && o < hi)) {
            keys.add(i, key)  // plus de place entre les deux voisins : on renumérote
            keys.forEachIndexed { n, k -> put("fav", "$tab|$k", mutableMapOf("o" to n.toDouble())) }
        } else put("fav", "$tab|$key", mutableMapOf("o" to o))
        save()
    }

    // ---- Reprise de lecture ----------------------------------------------------- //
    fun position(key: String): Long = progress[key].asObj()?.get("pos").num().toLong()
    fun length(key: String): Long = progress[key].asObj()?.get("len").num().toLong()

    fun resumePosition(key: String): Long {
        val pos = position(key)
        if (pos < MIN_RESUME_MS || isFinished(pos, length(key))) return 0
        return pos
    }

    fun watchedKeys(showKey: String): Set<String> =
        watched.filterValues { it.asObj()?.get("s") == showKey }.keys

    fun isWatched(episode: Item): Boolean {
        val parent = episode.parent ?: return false
        return parent.key in recent && episode.key in watched
    }

    fun setUnwatched(episode: Item) { drop("watched", episode.key); save() }

    /** Enregistre l'avancement d'un film ou d'un épisode. Dès que l'image a démarré (pos > 0), le film / la série
     *  entre dans « Vu récemment » ; la position de reprise n'est gardée qu'à partir de MIN_RESUME_MS (comme le PC). */
    fun update(item: Item, pos: Long, length: Long, finished: Boolean) {
        if (!finished && pos <= 0) return
        if (item.kind == "movie") {
            if (finished) {
                drop("progress", item.key); drop("recent", item.key)
            } else {
                if (pos >= MIN_RESUME_MS) put("progress", item.key, mutableMapOf("pos" to pos, "len" to length))
                put("recent", item.key, mutableMapOf("kind" to "movie", "name" to item.name))
            }
            return
        }
        val show = item.parent ?: return
        if (item.kind != "episode") return
        if (finished) {
            drop("progress", item.key)
            if (item.key !in watched) put("watched", item.key, mutableMapOf("s" to show.key))
        } else if (pos >= MIN_RESUME_MS) {
            put("progress", item.key, mutableMapOf("pos" to pos, "len" to length))
        }
        val label = if (item.season > 0 || item.episode > 0) "S%02dE%02d".format(item.season, item.episode) else item.name
        val entry: Obj = LinkedHashMap(recent[show.key].asObj() ?: mutableMapOf("kind" to "series"))
        entry["last_ep"] = item.key; entry["last_label"] = label; entry["name"] = show.name
        val all = show.episodes.orEmpty().map { it.key }.toSet()
        if (all.isNotEmpty()) entry["total"] = all.size.toLong()
        if (all.isNotEmpty() && watchedKeys(show.key).containsAll(all)) {
            drop("recent", show.key)  // toute la série a été vue : retirée de « Vu récemment »
            all.forEach { drop("progress", it); drop("watched", it) }
        } else put("recent", show.key, entry)
    }

    fun recentKeys(kind: String): List<String> =
        recent.entries.filter { it.value.asObj()?.get("kind") == kind }
            .sortedByDescending { it.value.asObj()?.get("t").num() }.map { it.key }

    fun removeRecent(key: String, episodeKeys: Collection<String> = emptyList()) {
        drop("recent", key); drop("progress", key)
        episodeKeys.forEach { drop("progress", it) }
        watchedKeys(key).forEach { drop("watched", it) }
        save()
    }

    // ---- Catégories masquées (par onglet) : rangées dans « Autres » --------------- //
    fun isHidden(tab: String, group: String) = "$tab|$group" in hidden

    fun setHidden(tab: String, group: String, hide: Boolean) {
        if (hide) put("hidden", "$tab|$group", LinkedHashMap()) else drop("hidden", "$tab|$group")
        save()
    }

    // ---- Langue et sous-titres mémorisés (par film ou par série) ---------------- //
    fun pref(key: String): Obj? = prefs[key].asObj()

    /** kind : "audio" ou "spu" ; value : piste choisie ({"lang", "desc"}) ou "off". */
    fun setPref(key: String, kind: String, value: Any) {
        val entry: Obj = LinkedHashMap(prefs[key].asObj() ?: LinkedHashMap())
        entry[kind] = value
        put("prefs", key, entry)
        save()
    }

    companion object {
        const val VERSION = 2
        const val MIN_RESUME_MS = 15_000L        // en dessous, on ne mémorise pas la position
        const val FINISHED_RATIO = 0.95          // au-delà, le film / l'épisode est considéré comme vu
        const val FINISHED_REMAINING_MS = 90_000L // … ou s'il reste moins de 1 min 30 (génériques)
        const val PURGE_DAYS = 90                // durée de conservation des traces de suppression
        val TABLES = listOf("fav", "progress", "recent", "watched", "prefs", "hidden")

        fun isFinished(pos: Long, length: Long) =
            length > 0 && (pos >= length * FINISHED_RATIO || length - pos <= FINISHED_REMAINING_MS)
    }
}
