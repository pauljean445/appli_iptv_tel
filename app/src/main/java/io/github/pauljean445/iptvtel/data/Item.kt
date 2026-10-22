package io.github.pauljean445.iptvtel.data

/**
 * Une entrée affichable : chaîne en direct, film, série, épisode ou programme de replay.
 * Même modèle que l'appli PC (playlist.Item) ; les clés (« x:movie:123 »…) sont identiques, ce qui permet
 * de partager favoris et reprises entre les appareils.
 */
class Item(
    var name: String,
    var url: String = "",
    var group: String = "",
    var logo: String = "",
    var tvgId: String = "",
    var kind: String = "live",        // "live" | "movie" | "series" | "episode" | "program"
    var key: String = "",             // identifiant stable (favoris, reprise de lecture)
    var streamId: String = "",        // identifiant Xtream (stream_id, series_id ou id d'épisode)
    var ext: String = "",             // extension du fichier (films Xtream : mkv, mp4…)
    var plot: String = "",
    var rating: String = "",          // note du fournisseur (sur 10)
    var year: String = "",
    var genre: String = "",
    var duration: String = "",
    var cast: String = "",
    var director: String = "",
    var archiveDays: Int = 0,         // direct : profondeur du replay en jours (0 = pas de replay)
    var season: Int = 0,
    var episode: Int = 0,
    var start: Long = 0,              // programme : début / fin (secondes)
    var end: Long = 0,
    var parent: Item? = null,         // épisode -> série ; programme -> chaîne
    var episodes: List<Item>? = null, // série : épisodes (null = pas encore chargés)
    var infoLoaded: Boolean = false,  // descriptif détaillé déjà récupéré
) {
    val groupLabel: String get() = group.ifEmpty { "Sans groupe" }

    /** Nom en minuscules sans accents, pour la recherche (calculé une fois). */
    val norm: String by lazy { normalize(name) }

    /** Deux initiales du nom, affichées tant que l'image n'est pas là (ou s'il n'y en a pas). */
    val initials: String by lazy {
        name.split(' ', '|').filter { it.firstOrNull()?.isLetterOrDigit() == true }.take(2)
            .joinToString("") { it.take(1) }.uppercase()
    }

    /** « S01E02 » (épisode) ou le nom. */
    val episodeCode: String
        get() = if (season > 0 || episode > 0) "S%02dE%02d".format(season, episode) else name

    companion object {
        fun normalize(text: String): String =
            java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
    }
}
