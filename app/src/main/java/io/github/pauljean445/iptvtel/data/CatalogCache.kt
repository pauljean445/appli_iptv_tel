package io.github.pauljean445.iptvtel.data

import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Cache disque du catalogue (catalog.json.gz) : au lancement, l'appli affiche le catalogue en cache aussitôt
 * puis l'actualise en arrière-plan. Les URL (qui contiennent les identifiants) ne sont pas stockées : elles
 * sont reconstruites à partir du client Xtream.
 */
object CatalogCache {
    const val FILE = "catalog.json.gz"
    private const val VERSION = 1
    private val KINDS = listOf("live", "movie", "series")

    fun save(folder: File, source: Source, signature: String, items: List<Item>) {
        val groups = LinkedHashMap<String, Int>()
        val rows = items.filter { it.kind in KINDS }.map {
            val g = groups.getOrPut(it.group) { groups.size }
            listOf(KINDS.indexOf(it.kind), it.name, g, it.logo, it.streamId, it.ext, it.tvgId, it.archiveDays,
                it.rating, it.year, it.plot, it.genre, it.cast, it.director)
        }
        val data = mapOf("v" to VERSION, "source" to source.id, "sig" to signature,
            "groups" to groups.keys.toList(), "rows" to rows)
        try {
            folder.mkdirs()
            val tmp = File(folder, "$FILE.tmp")
            GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { it.write(data.dumps()) }
            tmp.renameTo(File(folder, FILE)).also { if (!it) { File(folder, FILE).delete(); tmp.renameTo(File(folder, FILE)) } }
        } catch (e: Exception) {
            // cache facultatif
        }
    }

    /** (signature, entrées) si un cache valide existe pour cette source Xtream, sinon null. */
    fun load(folder: File, source: Source, client: XtreamClient): Pair<String, List<Item>>? = try {
        val text = GZIPInputStream(File(folder, FILE).inputStream()).bufferedReader().use { it.readText() }
        val data = parseObj(text)
        if (data["v"].int() != VERSION || data["source"] != source.id) null
        else {
            val groups = (data["groups"] as List<*>).map { it.str() }
            val items = (data["rows"] as List<*>).map { r ->
                val row = r as List<*>
                val kind = KINDS[row[0].int()]
                val sid = row[4].str()
                val ext = row[5].str()
                Item(
                    name = row[1].str(), group = groups[row[2].int()], logo = row[3].str(), streamId = sid, ext = ext,
                    tvgId = row[6].str(), archiveDays = row[7].int(), rating = row[8].str(), year = row[9].str(),
                    plot = row[10].str(), genre = row[11].str(), cast = row[12].str(), director = row[13].str(),
                    kind = kind, key = "x:$kind:$sid",
                    url = when (kind) { "live" -> client.liveUrl(sid); "movie" -> client.movieUrl(sid, ext); else -> "" },
                )
            }
            data["sig"].str() to items
        }
    } catch (e: Exception) {
        null
    }
}
