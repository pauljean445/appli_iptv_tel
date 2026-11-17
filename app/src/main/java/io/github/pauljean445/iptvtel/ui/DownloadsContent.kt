package io.github.pauljean445.iptvtel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.Screen
import io.github.pauljean445.iptvtel.data.Item
import io.github.pauljean445.iptvtel.download.DlJob
import io.github.pauljean445.iptvtel.download.Downloads

/** Lit un fichier téléchargé ; épisode : les autres épisodes téléchargés de la série s'enchaînent. */
fun playDownload(model: AppModel, job: DlJob) {
    val list = if (job.kind == "episode") Downloads.jobs.value
        .filter { it.state == "done" && it.kind == "episode" && it.showKey == job.showKey }
        .sortedWith(compareBy({ it.season }, { it.episode })).map { it.toItem() }
    else listOf(job.toItem())
    val item = list.firstOrNull { it.key == job.key } ?: job.toItem()
    model.open(Screen.Player(item, list, resume = true))
}

/** Lance des téléchargements ; la première fois, Android demande l'autorisation d'afficher la notification de
 *  progression (sans elle, les téléchargements se font quand même). */
@Composable
fun rememberDownloader(model: AppModel): (List<Item>) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<List<Item>>(emptyList()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.download(pending); pending = emptyList()
    }
    return { items ->
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) { pending = items; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else model.download(items)
    }
}

/** Nom de la série d'un épisode téléchargé (« Série · S01E02 · Titre » -> « Série »). */
private fun showTitle(j: DlJob) = j.title.substringBefore(" · ")

/** Affiche de la série (pas l'image d'un épisode) : gardée avec le téléchargement, sinon celle du catalogue. */
private fun showImage(model: AppModel, j: DlJob) = Item(showTitle(j), kind = "series", key = j.showKey,
    logo = j.showLogo.ifEmpty { model.catalog?.byKey?.get(j.showKey)?.logo.orEmpty() }.ifEmpty { j.logo })

/**
 * Onglet Téléchargements : en cours, en attente, en erreur, puis les fichiers du téléphone (lecture sans réseau).
 * Filtre Tout / Films / Séries ; une série = une seule ligne (touchée : ses épisodes, par saison).
 */
@Composable
fun DownloadsContent(model: AppModel) {
    val context = LocalContext.current
    val jobs by Downloads.jobs.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
    var deleting by remember { mutableStateOf<List<DlJob>?>(null) }
    var filter by rememberSaveable { mutableStateOf("all") }        // all | movie | episode
    var openShow by rememberSaveable { mutableStateOf<String?>(null) }  // série ouverte (show_key)
    fun keep(j: DlJob) = filter == "all" || j.kind == filter
    val active = jobs.filter { it.active && keep(it) }
    val failed = jobs.filter { it.state == "error" && keep(it) }
    val done = jobs.filter { it.state == "done" && keep(it) }.sortedWith(compareBy({ showTitle(it).lowercase() }, { it.season }, { it.episode }))
    val free = remember(done.size) { try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (e: Exception) { 0L } }

    if (jobs.none { it.state != "cancelled" }) {
        Message("Aucun téléchargement", "Sur la fiche d'un film ou d'une série, touche « Télécharger » : les fichiers vont dans " +
            "le dossier Films/IPTV Player du téléphone et se regardent ici, même sans réseau.")
        return
    }
    // Une série ouverte : ses épisodes téléchargés, par saison
    openShow?.let { key ->
        val eps = jobs.filter { it.state == "done" && it.kind == "episode" && it.showKey == key }
            .sortedWith(compareBy({ it.season }, { it.episode }))
        if (eps.isEmpty()) { openShow = null; return }
        BackHandler { openShow = null }
        LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                IconButton({ openShow = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = C.text) }
                Row(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 72.dp, height = 108.dp).clip(RoundedCornerShape(8.dp))) {
                        ItemImage(showImage(model, eps.first()), Modifier.width(72.dp).height(108.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(showTitle(eps.first()), color = C.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val seasons = eps.map { it.season }.distinct().size
                        Text("${eps.size} épisode${if (eps.size > 1) "s" else ""}" + (if (seasons > 1) " · $seasons saisons" else "") +
                            " · ${fmtSize(eps.sumOf { sizeOf(it) })}", color = C.muted, fontSize = 13.sp)
                        AppButton("Tout supprimer", { deleting = eps }, icon = Icons.Outlined.Delete)
                    }
                }
            }
            eps.groupBy { it.season }.forEach { (s, list) ->
                item(key = "s$s") {
                    // Bandeau de saison : bien visible entre deux saisons
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)
                        .clip(RoundedCornerShape(10.dp)).background(C.surface2)
                        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 4.dp, height = 20.dp).clip(RoundedCornerShape(2.dp)).background(C.accentHi))
                        Text("Saison $s", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 10.dp).weight(1f))
                        Text("${list.size} épisode${if (list.size > 1) "s" else ""} · ${fmtSize(list.sumOf { sizeOf(it) })}",
                            color = C.muted, fontSize = 12.sp)
                    }
                }
                items(list, key = { it.id }) { j -> Box(Modifier.padding(start = 8.dp)) { DoneRow(model, j, onDelete = { deleting = listOf(j) }) } }
            }
        }
        DeleteDialog(deleting) { deleting = null }
        return
    }

    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("${fmtSize(Downloads.usedBytes())} utilisés · ${fmtSize(free)} libres · dossier Films/IPTV Player",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("all" to "Tout", "movie" to "Films", "episode" to "Séries")) { (k, label) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(containerColor = C.surface, labelColor = C.muted,
                            selectedContainerColor = C.accent, selectedLabelColor = Color.White),
                        border = FilterChipDefaults.filterChipBorder(true, filter == k, borderColor = C.line, selectedBorderColor = C.accent))
                }
            }
        }
        if (active.isEmpty() && failed.isEmpty() && done.isEmpty()) item {
            Text(if (filter == "movie") "Aucun film téléchargé." else "Aucune série téléchargée.", color = C.muted, fontSize = 14.sp,
                modifier = Modifier.padding(top = 16.dp))
        }
        if (active.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowTitle("En cours", "${active.size}", Modifier.weight(1f))
                    if (active.size > 1) AppButton("Tout annuler", { Downloads.cancelAll() })
                }
            }
            items(active, key = { it.id }) { j ->
                JobRow(j, subtitle = when {
                    j.state == "waiting" -> "En attente"
                    j.error.isNotEmpty() && j.speed == 0L -> "Nouvel essai… (${j.error})"
                    j.total > 0 -> "${(j.progress * 100).toInt()} % · ${fmtSize(j.done)} / ${fmtSize(j.total)}" +
                        if (j.speed > 0) " · ${fmtSize(j.speed)}/s" else ""
                    else -> fmtSize(j.done)
                }, progress = if (j.state == "running" && j.total > 0) j.progress else -1f, onClick = {}) {
                    IconButton({ Downloads.cancel(j.id) }) { Icon(Icons.Filled.Close, "Annuler", tint = C.muted) }
                }
            }
        }
        if (failed.isNotEmpty()) {
            item {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RowTitle("En erreur", "${failed.size}", Modifier.weight(1f))
                    AppButton("Effacer", { Downloads.clearFinished() })
                }
            }
            items(failed, key = { it.id }) { j ->
                JobRow(j, subtitle = j.error, progress = -1f, error = true, onClick = {}) {
                    IconButton({ Downloads.retry(context, j.id) }) { Icon(Icons.Filled.Refresh, "Réessayer", tint = C.accentHi) }
                    IconButton({ Downloads.remove(j.id) }) { Icon(Icons.Filled.Close, "Retirer", tint = C.muted) }
                }
            }
        }
        if (done.isNotEmpty()) {
            // Films : une ligne chacun ; séries : une ligne par série (épisodes regroupés)
            val entries = done.groupBy { if (it.kind == "episode") "show:" + it.showKey else it.id }.values.toList()
            item { RowTitle("Sur le téléphone", "${entries.size}", Modifier.padding(top = 10.dp)) }
            items(entries, key = { it.first().id }) { group ->
                val j = group.first()
                if (j.kind != "episode") DoneRow(model, j, onDelete = { deleting = listOf(j) })
                else ShowRow(model, group, onOpen = { openShow = j.showKey }, onDelete = { deleting = group })
            }
        }
    }
    DeleteDialog(deleting) { deleting = null }
}

private fun sizeOf(j: DlJob) = j.total.takeIf { it > 0 } ?: j.done

@Composable
private fun DeleteDialog(list: List<DlJob>?, onClose: () -> Unit) {
    val jobs = list ?: return
    val what = if (jobs.size == 1) "« ${jobs[0].title} »" else "les ${jobs.size} épisodes de « ${showTitle(jobs[0])} »"
    ConfirmDialog(if (jobs.size == 1) "Supprimer le fichier" else "Supprimer les fichiers",
        "Supprimer $what du téléphone ? La reprise de lecture est conservée.",
        "Supprimer", onOk = { jobs.forEach { Downloads.remove(it.id) }; onClose() }, onDismiss = onClose)
}

/** Film ou épisode téléchargé : lecture, suppression, reprise. */
@Composable
private fun DoneRow(model: AppModel, j: DlJob, onDelete: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
    val pos = model.library.position(j.key)
    val len = model.library.length(j.key)
    JobRow(j, subtitle = listOf(fmtSize(sizeOf(j)), if (pos > 0) "reprendre à ${fmtTime(pos)}" else "")
        .filter { it.isNotEmpty() }.joinToString(" · "),
        progress = if (pos > 0 && len > 0) pos.toFloat() / len else -1f, onClick = { playDownload(model, j) },
        onLongClick = onDelete, title = if (j.kind == "episode") j.title.substringAfter(" · ") else j.title) {
        IconButton({ playDownload(model, j) }) { Icon(Icons.Filled.PlayArrow, "Lire", tint = C.accentHi) }
        IconButton(onDelete) { Icon(Icons.Outlined.Delete, "Supprimer", tint = C.muted) }
    }
}

/** Série téléchargée : une ligne (affiche, nombre d'épisodes) ; touchée : ses épisodes ; ▶ : reprise. */
@Composable
private fun ShowRow(model: AppModel, eps: List<DlJob>, onOpen: () -> Unit, onDelete: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
    val first = eps.first()
    // Épisode à lancer : un épisode commencé, sinon le premier non vu, sinon le premier
    val next = eps.firstOrNull { model.library.position(it.key) > 0 }
        ?: eps.firstOrNull { !model.library.isWatched(it.toItem()) } ?: first
    val seasons = eps.map { it.season }.distinct().size
    JobRow(first, subtitle = "${eps.size} épisode${if (eps.size > 1) "s" else ""}" +
        (if (seasons > 1) " · $seasons saisons" else "") + " · ${fmtSize(eps.sumOf { sizeOf(it) })}",
        progress = -1f, onClick = onOpen, onLongClick = onDelete, title = showTitle(first), image = showImage(model, first)) {
        IconButton({ playDownload(model, next) }) { Icon(Icons.Filled.PlayArrow, "Lire", tint = C.accentHi) }
        IconButton(onOpen) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Épisodes", tint = C.muted) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JobRow(j: DlJob, subtitle: String, progress: Float, error: Boolean = false, onClick: () -> Unit,
                   onLongClick: (() -> Unit)? = null, title: String = j.title, image: Item? = null,
                   actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 46.dp, height = 69.dp).clip(RoundedCornerShape(6.dp))) {
            ItemImage(image ?: j.toItem(), Modifier.width(46.dp).height(69.dp))
            if (progress >= 0f) ProgressBar(progress, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = if (error) C.live else C.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}
