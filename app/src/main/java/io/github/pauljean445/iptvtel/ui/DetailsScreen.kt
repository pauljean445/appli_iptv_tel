package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.Screen
import io.github.pauljean445.iptvtel.data.Item
import io.github.pauljean445.iptvtel.data.asObj
import io.github.pauljean445.iptvtel.data.str
import io.github.pauljean445.iptvtel.download.DlJob
import io.github.pauljean445.iptvtel.download.Downloads
import io.github.pauljean445.iptvtel.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fiche d'un film ou d'une série (saisons et épisodes), avec lecture, favori, langue et téléchargement. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsScreen(model: AppModel, item: Item) {
    val lib = model.library
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
    val jobs by Downloads.jobs.collectAsState()
    val download = rememberDownloader(model)
    var loaded by remember { mutableIntStateOf(0) }  // redessine quand le descriptif / les épisodes arrivent
    var error by remember { mutableStateOf("") }
    LaunchedEffect(item.key) {
        val c = model.client ?: return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                if (item.kind == "movie" && !item.infoLoaded) c.fillMovieInfo(item)
                if (item.kind == "series" && item.episodes == null) c.loadEpisodes(item)
            }
        } catch (e: Exception) {
            error = "Détails indisponibles : ${e.message}"
        }
        loaded++
    }
    @Suppress("UNUSED_VARIABLE") val reload = loaded  // relu : la fiche se redessine à l'arrivée des détails
    var actionsFor by remember { mutableStateOf<Item?>(null) }
    var downloadMenu by remember { mutableStateOf(false) }
    var plotOpen by remember { mutableStateOf(false) }
    val tab = if (item.kind == "series") "series" else "movie"

    val eps = item.episodes.orEmpty()
    val seasons = remember(eps.size) { eps.map { it.season }.distinct() }
    var season by rememberSaveable(item.key, eps.size) {
        mutableIntStateOf(seriesTarget(model, item)?.season ?: seasons.firstOrNull() ?: 0)
    }
    fun job(key: String): DlJob? = jobs.lastOrNull { it.key == key && it.state != "cancelled" }

    LazyColumn(Modifier.fillMaxSize().background(C.bg), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                // En-tête : l'affiche floutée par l'assombrissement, puis l'affiche nette et le titre
                ItemImage(item, Modifier.fillMaxWidth().height(250.dp))
                Box(Modifier.fillMaxWidth().height(250.dp).background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xCC100D22), Color(0xF2100D22), C.bg))))
                Column(Modifier.statusBarsPadding()) {
                    IconButton({ model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = C.text) }
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        Box(Modifier.size(width = 112.dp, height = 168.dp).clip(RoundedCornerShape(10.dp))) { ItemImage(item, Modifier.fillMaxSize()) }
                        Column(Modifier.weight(1f).padding(start = 14.dp, top = 4.dp)) {
                            Text(item.name, color = C.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            val meta = listOf(item.year, if (item.rating.isNotEmpty()) "★ ${item.rating}" else "", item.duration)
                                .filter { it.isNotEmpty() }.joinToString("  ·  ")
                            if (meta.isNotEmpty()) Text(meta, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                            if (item.genre.isNotEmpty()) Text(item.genre, color = C.muted, fontSize = 13.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.kind == "movie") {
                        val pos = lib.resumePosition(item.key)
                        AppButton(if (pos > 0) "Reprendre à ${fmtTime(pos)}" else "Lire",
                            { model.open(Screen.Player(item, listOf(item), resume = true)) },
                            Modifier.weight(1f), primary = true, icon = Icons.Filled.PlayArrow)
                    } else {
                        val target = seriesTarget(model, item)
                        AppButton(when {
                            item.episodes == null -> "Chargement…"
                            target == null -> "Aucun épisode"
                            lib.resumePosition(target.key) > 0 -> "Reprendre ${target.episodeCode}"
                            else -> "Lire ${target.episodeCode}"
                        }, { target?.let { model.open(Screen.Player(it, eps, resume = true)) } },
                            Modifier.weight(1f), primary = true, enabled = target != null, icon = Icons.Filled.PlayArrow)
                    }
                    val fav = lib.isFavorite(tab, item.key)
                    AppButton("", { model.toggleFavorite(tab, item) }, icon = if (fav) Icons.Filled.Star else Icons.Filled.StarBorder)
                    val dl = if (item.kind == "movie") job(item.key) else null
                    AppButton("", {
                        if (item.kind == "series") downloadMenu = true
                        else if (dl == null || dl.state == "error") download(listOf(item))
                        else model.showTab("downloads")  // déjà en file / téléchargé : l'onglet le montre
                    }, enabled = item.kind == "movie" || eps.isNotEmpty(),
                        icon = if (dl?.state == "done") Icons.Filled.DownloadDone else Icons.Filled.Download)
                }
                if (item.kind == "movie" && lib.resumePosition(item.key) > 0)
                    AppButton("Lire depuis le début", { model.open(Screen.Player(item, listOf(item), resume = false)) },
                        Modifier.fillMaxWidth(), icon = Icons.Filled.Replay)
                if (item.kind == "movie") job(item.key)?.let { j -> DownloadState(j) }
                val plot = item.plot.ifEmpty { error }
                if (plot.isNotEmpty()) Text(plot, color = C.text, fontSize = 14.sp, maxLines = if (plotOpen) 40 else 4,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { plotOpen = !plotOpen })
                if (item.director.isNotEmpty() || item.cast.isNotEmpty()) Text(
                    listOf(if (item.director.isNotEmpty()) "Réalisation : ${item.director}" else "",
                        if (item.cast.isNotEmpty()) "Avec : ${item.cast}" else "").filter { it.isNotEmpty() }.joinToString("\n"),
                    color = C.muted, fontSize = 13.sp, maxLines = if (plotOpen) 10 else 2, overflow = TextOverflow.Ellipsis)
                val langTarget = if (item.kind == "movie") item else seriesTarget(model, item)
                if (langTarget != null && langTarget.url.isNotEmpty()) LanguageChoice(model, item, langTarget)
            }
        }
        if (item.kind == "series" && eps.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(seasons) { s ->
                        FilterChip(selected = s == season, onClick = { season = s }, label = { Text("Saison $s") },
                            colors = FilterChipDefaults.filterChipColors(containerColor = C.surface, labelColor = C.muted,
                                selectedContainerColor = C.accent, selectedLabelColor = Color.White),
                            border = FilterChipDefaults.filterChipBorder(true, s == season, borderColor = C.line, selectedBorderColor = C.accent))
                    }
                }
            }
            items(eps.filter { it.season == season }, key = { it.key }) { ep ->
                EpisodeRow(model, ep, job(ep.key), onClick = { model.open(Screen.Player(ep, eps, resume = true)) },
                    onLongClick = { actionsFor = ep }, onDownload = { download(listOf(ep)) })
            }
        }
    }

    if (downloadMenu) {
        val inSeason = eps.filter { it.season == season }
        ActionsSheet("Télécharger · ${item.name}", listOf(
            Action("Saison $season · ${inSeason.size} épisode${if (inSeason.size > 1) "s" else ""}", Icons.Filled.Download) { download(inSeason) },
            Action("Toute la série · ${eps.size} épisodes", Icons.Filled.Download) { download(eps) },
        ), onDismiss = { downloadMenu = false })
    }

    actionsFor?.let { ep ->
        val j = job(ep.key)
        ActionsSheet(ep.episodeCode + " · " + ep.name, buildList {
            add(if (lib.isWatched(ep)) Action("Marquer comme non vu", Icons.Outlined.RadioButtonUnchecked) { lib.setUnwatched(ep); model.libraryChanged() }
                else Action("Marquer comme vu", Icons.Outlined.CheckCircle) { model.saveProgress(ep, 0, 0, finished = true) })
            add(Action("Lire depuis le début", Icons.Filled.Replay) { model.open(Screen.Player(ep, item.episodes.orEmpty(), resume = false)) })
            if (j == null || j.state == "error") add(Action("Télécharger", Icons.Filled.Download) { download(listOf(ep)) })
            else if (j.state == "done") add(Action("Supprimer le fichier téléchargé", Icons.Outlined.Delete) { Downloads.remove(j.id) })
            else add(Action("Annuler le téléchargement", Icons.Outlined.Delete) { Downloads.cancel(j.id) })
        }, onDismiss = { actionsFor = null })
    }
}

/** État du téléchargement d'un film (sous les boutons de la fiche). */
@Composable
private fun DownloadState(j: DlJob) {
    val text = when (j.state) {
        "done" -> "Téléchargé sur le téléphone · lu sans réseau"
        "waiting" -> "Téléchargement en attente"
        "running" -> if (j.total > 0) "Téléchargement : ${(j.progress * 100).toInt()} %" else "Téléchargement : ${fmtSize(j.done)}"
        "error" -> "Téléchargement en erreur : ${j.error}"
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = if (j.state == "error") C.live else C.accentHi, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (j.active) Text("Annuler", color = C.muted, fontSize = 13.sp, modifier = Modifier.clickable { Downloads.cancel(j.id) }.padding(6.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeRow(model: AppModel, ep: Item, job: DlJob?, onClick: () -> Unit, onLongClick: () -> Unit, onDownload: () -> Unit) {
    val lib = model.library
    val pos = lib.resumePosition(ep.key)
    val len = lib.length(ep.key)
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(6.dp))) {
            ItemImage(ep, Modifier.fillMaxSize())
            if (pos > 0 && len > 0) ProgressBar(pos.toFloat() / len, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("${ep.episodeCode} · ${ep.name}", color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val status = when {
                lib.isWatched(ep) -> "✓ Vu"
                pos > 0 -> "En cours · ${fmtTime(pos)}"
                else -> ep.duration
            }
            if (status.isNotEmpty()) Text(status, color = if (lib.isWatched(ep) || pos > 0) C.accentHi else C.muted, fontSize = 12.sp)
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when (job?.state) {
                "done" -> Icon(Icons.Filled.DownloadDone, "Téléchargé", tint = C.accentHi)
                "running" -> if (job.total > 0) CircularProgressIndicator(progress = { job.progress }, Modifier.size(24.dp),
                    color = C.accentHi, trackColor = C.surface2, strokeWidth = 3.dp)
                    else CircularProgressIndicator(Modifier.size(24.dp), color = C.accentHi, strokeWidth = 3.dp)
                "waiting" -> CircularProgressIndicator(progress = { 0f }, Modifier.size(24.dp), color = C.accentHi,
                    trackColor = C.surface2, strokeWidth = 3.dp)
                else -> IconButton(onDownload) {
                    Icon(Icons.Filled.Download, "Télécharger", tint = if (job?.state == "error") C.live else C.muted)
                }
            }
        }
    }
}

/** Épisode à lancer : celui en cours, sinon le suivant non vu (comme « Reprendre » sur PC), sinon le premier. */
fun seriesTarget(model: AppModel, show: Item): Item? {
    val eps = show.episodes ?: return null
    if (eps.isEmpty()) return null
    val lib = model.library
    val entry = lib.recent[show.key].asObj() ?: return eps.first()
    val last = entry["last_ep"].str()
    val idx = eps.indexOfFirst { it.key == last }
    if (idx < 0) return eps.first()
    if (lib.resumePosition(last) > 0) return eps[idx]
    val watched = lib.watchedKeys(show.key)
    if (last !in watched) return eps[idx]  // lancé moins de 15 s : on le relance depuis le début
    return (eps.drop(idx + 1) + eps.take(idx)).firstOrNull { it.key !in watched } ?: eps[idx]
}

/**
 * Langue et sous-titres, choisis sur la fiche (pistes lues dans le fichier, comme sur PC et TV). Le choix est mémorisé
 * pour le film ou pour toute la série, appliqué à chaque lecture et partagé entre appareils.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageChoice(model: AppModel, item: Item, target: Item) {
    val context = LocalContext.current
    val lib = model.library
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
    val url = model.localUri(target.key) ?: target.url
    var tracks by remember(url) { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(url) { tracks = Track.probe(context, url) }
    val prefKey = item.key  // film : le film ; série : toute la série
    val pref = lib.pref(prefKey)
    val list = tracks
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            list == null -> Text("Recherche des langues disponibles…", color = C.muted, fontSize = 12.sp)
            list.isEmpty() -> Text("Langues non disponibles pour ce fichier.", color = C.muted, fontSize = 12.sp)
            else -> {
                val audio = list.filter { it.kind == "audio" }
                val subs = list.filter { it.kind == "spu" }
                if (audio.isNotEmpty()) {
                    Text("Langue", color = C.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        audio.forEach { t -> PrefChip(t.label, Track.isPref(t, pref?.get("audio"))) { lib.setPref(prefKey, "audio", t.pref()); model.libraryChanged() } }
                    }
                }
                if (subs.isNotEmpty()) {
                    Text("Sous-titres", color = C.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrefChip("Aucun", pref?.get("spu") == "off") { lib.setPref(prefKey, "spu", "off"); model.libraryChanged() }
                        subs.forEach { t -> PrefChip(t.label, Track.isPref(t, pref?.get("spu"))) { lib.setPref(prefKey, "spu", t.pref()); model.libraryChanged() } }
                    }
                }
                Text(if (item.kind == "series") "Choix mémorisé pour toute la série (modifiable aussi pendant la lecture)."
                    else "Choix mémorisé pour ce film (modifiable aussi pendant la lecture).", color = C.muted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PrefChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(containerColor = C.surface, labelColor = C.text,
            selectedContainerColor = C.accent, selectedLabelColor = Color.White),
        border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = C.line, selectedBorderColor = C.accent))
}

/** Replays d'une chaîne : programmes passés, du plus récent au plus ancien, filtrés par jour. */
@Composable
fun ProgramsScreen(model: AppModel, channel: Item) {
    var programs by remember { mutableStateOf<List<Item>?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(channel.key) {
        val c = model.client
        if (c == null) { error = "Replays disponibles seulement avec un abonnement Xtream."; return@LaunchedEffect }
        try { programs = withContext(Dispatchers.IO) { c.archivePrograms(channel) } } catch (e: Exception) {
            error = "Programmes indisponibles : ${e.message}"
        }
    }
    val list = programs
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = C.text) }
            Column {
                Text(channel.name, color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Replays · ${channel.archiveDays} jours", color = C.muted, fontSize = 12.sp)
            }
        }
        when {
            error.isNotEmpty() -> Text(error, color = C.live, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            list == null -> Text("Chargement des programmes…", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            list.isEmpty() -> Text("Aucun programme disponible en replay.", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            else -> {
                val days = list.map { it.group }.distinct()
                var day by rememberSaveable { mutableStateOf(days.first()) }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(days) { d ->
                        FilterChip(selected = d == day, onClick = { day = d }, label = { Text(d) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = C.surface, labelColor = C.muted,
                                selectedContainerColor = C.accent, selectedLabelColor = Color.White),
                            border = FilterChipDefaults.filterChipBorder(true, d == day, borderColor = C.line, selectedBorderColor = C.accent))
                    }
                }
                LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(list.filter { it.group == day }, key = { it.key }) { p ->
                        Column(Modifier.fillMaxWidth().clickable { model.open(Screen.Player(p, list, resume = false)) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(p.name, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (p.plot.isNotEmpty()) Text(p.plot, color = C.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
