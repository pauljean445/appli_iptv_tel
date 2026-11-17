package io.github.pauljean445.iptvtel.data

import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Base commune des notes IMDb : imdb/s00 … s15 (hors des foyers, lue par tous, écrite seulement par la tâche
 * planifiée du dépôt app_iptv_pc, tools/imdb_job.py, chaque nuit), une entrée par titre : "m1185344" (film TMDB) /
 * "s1396" (série TMDB) -> "tt29256561|47|101|2026" (identifiant IMDb | note × 10 | durée en minutes | année ;
 * vide = inconnu ; "?AAAAMMJJ" = pas encore relié). Les titres du catalogue absents de la base sont inscrits dans
 * households/<uid>/imdb_req/sNN (champ vide) : la tâche les relie la nuit suivante.
 * Titre présent dans la base : année, note et durée viennent toutes d'elle (même source, cohérentes).
 * Copie locale imdb_base.json : notes affichées dès le lancement, aussi hors ligne.
 */
object ImdbBase {
    private const val SHARDS = 16
    private const val PULL_EVERY_MS = 6 * 3600_000L
    private var file: File? = null
    private var reqFile: File? = null
    private val entries = ConcurrentHashMap<String, String>()
    private val requested = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var lastPull = 0L

    fun init(dir: File) {
        if (file != null) return
        File(dir, "imdb_base.json").delete()  // ancienne copie (base rangée dans le foyer, abandonnée le 17/11/2026)
        val f = File(dir, "imdb_base2.json")
        file = f
        reqFile = File(dir, "imdb_req.json")
        try { readObj(f).forEach { (k, v) -> if (v is String) entries[k] = v } } catch (e: Exception) { }
        try { (readObj(reqFile!!)["keys"] as? List<*>)?.forEach { k -> if (k is String) requested += k } } catch (e: Exception) { }
    }

    /** Clé d'une entrée du catalogue (« m123 » film, « s456 » série), "" sans numéro TMDB. */
    fun keyOf(it: Item): String {
        val t = it.tmdb
        if (t.isEmpty() || !t.all { c -> c.isDigit() }) return ""
        return when (it.kind) { "movie" -> "m$t"; "series" -> "s$t"; else -> "" }
    }

    fun pullDue() = System.currentTimeMillis() - lastPull > PULL_EVERY_MS

    /** Lit la base commune (thread d'arrière-plan), puis inscrit pour la tâche de la nuit les titres du catalogue
     *  qui n'y sont pas. true si des notes ont changé. */
    fun pull(cloud: Cloud, catalogKeys: Collection<String>): Boolean {
        var changed = false
        // Copie locale = miroir exact de la base commune (lue en entier ; sinon on garde l'ancienne copie)
        val fresh = HashMap<String, String>()
        var complete = true
        for (i in 0 until SHARDS) {
            // base commune pas encore lisible (règle) ou hors ligne : on inscrit quand même les titres
            val doc = try { cloud.getRootDoc("imdb/s%02d".format(i)) } catch (e: Exception) { complete = false; break } ?: continue
            for ((k, v) in doc) if (v is String) fresh[k] = v
        }
        if (complete && fresh != HashMap(entries)) {
            entries.clear()
            entries.putAll(fresh)
            changed = true
        }
        lastPull = System.currentTimeMillis()
        if (changed) file?.let { f -> try { writeObj(f, LinkedHashMap<String, Any?>(entries)) } catch (e: Exception) { } }
        val missing = catalogKeys.filter { it.isNotEmpty() && !entries.containsKey(it) && it !in requested }.distinct()
        try {
            for ((shard, keys) in missing.groupBy { "s%02d".format(it.drop(1).toLong() % SHARDS) }) {
                for (chunk in keys.chunked(1000)) {
                    cloud.commit(listOf(Cloud.Write("imdb_req/$shard", chunk.associateWith { "" })), timeoutSec = 30)
                    requested += chunk
                }
            }
        } catch (e: Exception) {
            lastPull = 0L  // coupure : on reprend au passage suivant (synchro, 30 s), là où on en était
        } finally {
            if (missing.isNotEmpty()) reqFile?.let { f ->
                try { writeObj(f, mapOf("keys" to synchronized(requested) { requested.toList() })) } catch (e: Exception) { }
            }
        }
        return changed
    }

    /** Notes IMDb et durées sur les entrées du catalogue. */
    fun apply(items: Collection<Item>) {
        for (it in items) {
            val e = entries[keyOf(it)] ?: continue
            if (!e.startsWith("tt")) continue  // pas encore relié à IMDb
            val p = e.split("|")
            it.imdbId = p.getOrElse(0) { "" }
            it.imdbRating = p.getOrNull(1)?.toIntOrNull()?.let { r -> String.format(Locale.US, "%.1f", r / 10.0) } ?: ""
            it.runtime = p.getOrNull(2)?.toIntOrNull() ?: 0
            it.imdbYear = p.getOrNull(3)?.toIntOrNull() ?: 0
        }
    }

    /** Note affichée partout : IMDb seulement (la note du fournisseur n'est plus montrée, comme sur PC). */
    fun ratingText(it: Item) = if (it.imdbRating.isNotEmpty()) "IMDb ★ ${it.imdbRating}" else ""

    private val YEAR_END = Regex("""(?:^|[\s(\[-])((?:19|20)\d{2})[)\]]?\s*$""")

    /** Année : champ du fournisseur, sinon celle qui termine souvent le nom (« Titre (MULTI) FHD 2026 »). */
    fun yearOf(it: Item): String =
        if (it.imdbYear > 0) it.imdbYear.toString()
        else it.year.ifEmpty { YEAR_END.find(it.name)?.groupValues?.get(1).orEmpty() }

    /** Texte sous une vignette : film « année · ★ note · durée », série « ★ note » seulement. */
    fun tileText(it: Item): String =
        (if (it.kind == "movie") listOf(yearOf(it), shortRating(it), runtimeText(it)) else listOf(shortRating(it)))
            .filter { s -> s.isNotEmpty() }.joinToString(" · ")

    /** Mosaïques et listes : « ★ 4.7 » (la mention IMDb est sur la fiche). */
    fun shortRating(it: Item) = if (it.imdbRating.isNotEmpty()) "★ ${it.imdbRating}" else ""

    /** Durée lisible (« 1 h 41 ») : base commune, sinon durée du fichier donnée par le fournisseur. */
    fun runtimeText(it: Item): String {
        var m = it.runtime
        if (m == 0) {  // durée inconnue de la base : celle du fichier (fournisseur)
            val parts = it.duration.split(":").mapNotNull { p -> p.trim().toIntOrNull() }
            if (parts.size == 3) m = parts[0] * 60 + parts[1]
        }
        if (m == 0) return if (it.kind == "series") it.duration else ""
        return if (m >= 60) String.format(Locale.US, "%d h %02d", m / 60, m % 60) else "$m min"
    }
}
