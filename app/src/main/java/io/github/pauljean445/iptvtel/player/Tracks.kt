package io.github.pauljean445.iptvtel.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.pauljean445.iptvtel.data.Http
import io.github.pauljean445.iptvtel.data.Obj
import io.github.pauljean445.iptvtel.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Pistes audio et sous-titres, portage de tracks.py (PC) / Tracks.kt (TV). Les numéros de pistes changent d'un
 * fichier à l'autre (VF en 1re piste ici, en 2e là) : un choix est mémorisé par langue + libellé
 * ({"lang": "fre", "desc": "VFF"}), puis retrouvé dans chaque fichier. Même format que sur PC et TV : les choix
 * sont partagés entre appareils. id = groupe * 1000 + piste (lecteur Media3).
 */
data class Track(val kind: String, val id: Int, val lang: String, val desc: String, val supported: Boolean = true) {
    val label: String
        get() {
            val name = NAMES[lang] ?: lang.uppercase()
            val d = desc.trim()
            val text = when {
                d.isEmpty() -> name.ifEmpty { "Piste ${id % 1000 + 1}" }
                name.isEmpty() || name.lowercase() in d.lowercase() -> d
                else -> "$name · $d"
            }
            return if (supported) text else "$text (non lisible ici)"
        }

    fun pref(): Obj = linkedMapOf("lang" to lang, "desc" to desc)

    companion object {
        private val ALIASES = mapOf("fr" to "fre", "fra" to "fre", "en" to "eng", "es" to "spa", "de" to "ger", "deu" to "ger",
            "it" to "ita", "pt" to "por", "nl" to "dut", "nld" to "dut", "sv" to "swe", "no" to "nor", "nb" to "nor",
            "da" to "dan", "fi" to "fin", "ru" to "rus", "pl" to "pol", "tr" to "tur", "ar" to "ara", "ja" to "jpn",
            "ko" to "kor", "zh" to "chi", "zho" to "chi", "hi" to "hin", "he" to "heb", "el" to "gre", "ell" to "gre",
            "ro" to "rum", "ron" to "rum", "cs" to "cze", "ces" to "cze", "hu" to "hun", "uk" to "ukr")
        private val NAMES = mapOf("fre" to "Français", "eng" to "Anglais", "spa" to "Espagnol", "ger" to "Allemand",
            "ita" to "Italien", "por" to "Portugais", "dut" to "Néerlandais", "swe" to "Suédois", "nor" to "Norvégien",
            "dan" to "Danois", "fin" to "Finnois", "rus" to "Russe", "pol" to "Polonais", "tur" to "Turc", "ara" to "Arabe",
            "jpn" to "Japonais", "kor" to "Coréen", "chi" to "Chinois", "hin" to "Hindi", "heb" to "Hébreu", "gre" to "Grec",
            "rum" to "Roumain", "cze" to "Tchèque", "hun" to "Hongrois", "ukr" to "Ukrainien")

        /** Code de langue commun aux appareils (VLC : « fre », Media3 : « fr » ou « fr-FR »). */
        fun normLang(code: String?): String {
            val c = (code ?: "").trim().lowercase().substringBefore('-').substringBefore('_')
            val n = ALIASES[c] ?: c
            return if (n in setOf("und", "unk", "mis", "zxx")) "" else n
        }

        /** Code à donner au lecteur pour préférer une langue (« fre » -> « fr »). */
        fun playerLang(lang: String): String = ALIASES.entries.firstOrNull { it.value == lang && it.key.length == 2 }?.key ?: lang

        private fun text(s: String?) = Normalizer.normalize(s ?: "", Normalizer.Form.NFC)

        /** Pistes audio / sous-titres d'un ensemble de pistes Media3. */
        @OptIn(UnstableApi::class)
        fun of(tracks: Tracks): List<Track> = tracks.groups.flatMapIndexed { g, group ->
            val kind = when (group.type) { MC.TRACK_TYPE_AUDIO -> "audio"; MC.TRACK_TYPE_TEXT -> "spu"; else -> null }
                ?: return@flatMapIndexed emptyList()
            (0 until group.length).map { i ->
                val f = group.getTrackFormat(i)
                Track(kind, g * 1000 + i, normLang(f.language), text(f.label), group.isTrackSupported(i))
            }
        }

        /** Pistes actuellement choisies par le lecteur : (audio, sous-titres) ; -1 = sous-titres désactivés. */
        fun selected(tracks: Tracks): Pair<Int?, Int> {
            var audio: Int? = null
            var spu = -1
            tracks.groups.forEachIndexed { g, group ->
                for (i in 0 until group.length) if (group.isTrackSelected(i)) {
                    if (group.type == MC.TRACK_TYPE_AUDIO) audio = g * 1000 + i
                    if (group.type == MC.TRACK_TYPE_TEXT) spu = g * 1000 + i
                }
            }
            return audio to spu
        }

        /** Choisit une piste (id) ; spu = -1 : sous-titres désactivés. */
        fun select(player: Player, kind: String, id: Int) {
            val type = if (kind == "audio") MC.TRACK_TYPE_AUDIO else MC.TRACK_TYPE_TEXT
            val b = player.trackSelectionParameters.buildUpon()
            if (id < 0) b.setTrackTypeDisabled(type, true)
            else {
                val group = player.currentTracks.groups.getOrNull(id / 1000) ?: return
                b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, id % 1000))
            }
            player.trackSelectionParameters = b.build()
        }

        private val probed = HashMap<String, List<Track>>()

        /** Pistes d'un fichier, lues avant la lecture (fiche d'un film / d'une série). */
        @OptIn(UnstableApi::class)
        suspend fun probe(context: Context, url: String): List<Track> {
            synchronized(probed) { probed[url] }?.let { return it }
            val tracks = withContext(Dispatchers.IO) {
                try {
                    val future = MetadataRetriever.retrieveMetadata(mediaSourceFactory(context), MediaItem.fromUri(url))
                    val groups = withTimeoutOrNull(12_000) { future.get(12, TimeUnit.SECONDS) } ?: return@withContext emptyList()
                    (0 until groups.length).flatMap { g ->
                        val group = groups[g]
                        val kind = when (group.type) { MC.TRACK_TYPE_AUDIO -> "audio"; MC.TRACK_TYPE_TEXT -> "spu"; else -> null }
                            ?: return@flatMap emptyList()
                        (0 until group.length).map { i ->
                            val f = group.getFormat(i)
                            Track(kind, g * 1000 + i, normLang(f.language), text(f.label))
                        }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (tracks.isNotEmpty()) synchronized(probed) { probed[url] = tracks }
            return tracks
        }

        /** Accès aux flux : User-Agent de VLC (certains fournisseurs refusent les autres), redirections http <-> https,
         *  et fichiers du téléphone (content://) pour les téléchargements. */
        @OptIn(UnstableApi::class)
        fun mediaSourceFactory(context: Context): DefaultMediaSourceFactory {
            val http = DefaultHttpDataSource.Factory().setUserAgent(Http.USER_AGENT).setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
            return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        }

        /** Identifiant de la piste qui correspond au choix mémorisé (null : rien à imposer, -1 : désactivé). */
        fun match(tracks: List<Track>, kind: String, pref: Any?): Int? {
            if (pref == null) return null
            if (pref == "off") return -1
            val p = pref as? Map<*, *> ?: return null
            val same = tracks.filter { it.kind == kind && it.supported && it.lang == p["lang"].str() }
            same.firstOrNull { it.desc.trim().equals(p["desc"].str().trim(), ignoreCase = true) }?.let { return it.id }
            return same.firstOrNull()?.id  // même langue, autre libellé (ex. « VF » au lieu de « VFF »)
        }

        fun isPref(t: Track, pref: Any?): Boolean {
            val p = pref as? Map<*, *> ?: return false
            return t.lang == p["lang"].str() && t.desc.trim().equals(p["desc"].str().trim(), ignoreCase = true)
        }
    }
}
