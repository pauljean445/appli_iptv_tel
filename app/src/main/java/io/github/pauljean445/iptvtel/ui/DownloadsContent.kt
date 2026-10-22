package io.github.pauljean445.iptvtel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
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

/** Onglet Téléchargements : en cours, en attente, en erreur, puis les fichiers du téléphone (lecture sans réseau). */
@Composable
fun DownloadsContent(model: AppModel) {
    val context = LocalContext.current
    val jobs by Downloads.jobs.collectAsState()
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
    var deleting by remember { mutableStateOf<DlJob?>(null) }
    val active = jobs.filter { it.active }
    val failed = jobs.filter { it.state == "error" }
    val done = jobs.filter { it.state == "done" }.sortedWith(compareBy({ it.title.substringBefore(" · ").lowercase() }, { it.season }, { it.episode }))
    val free = remember(done.size) { try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (e: Exception) { 0L } }

    if (jobs.none { it.state != "cancelled" }) {
        Message("Aucun téléchargement", "Sur la fiche d'un film ou d'une série, touche « Télécharger » : les fichiers vont dans " +
            "le dossier Films/IPTV Player du téléphone et se regardent ici, même sans réseau.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("${fmtSize(Downloads.usedBytes())} utilisés · ${fmtSize(free)} libres · dossier Films/IPTV Player",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
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
            item { RowTitle("Sur le téléphone", "${done.size}", Modifier.padding(top = 10.dp)) }
            items(done, key = { it.id }) { j ->
                @Suppress("UNUSED_VARIABLE") val v = model.libVersion
                val pos = model.library.position(j.key)
                val len = model.library.length(j.key)
                JobRow(j, subtitle = listOf(fmtSize(j.total.takeIf { it > 0 } ?: j.done),
                    if (pos > 0) "reprendre à ${fmtTime(pos)}" else "").filter { it.isNotEmpty() }.joinToString(" · "),
                    progress = if (pos > 0 && len > 0) pos.toFloat() / len else -1f, onClick = { playDownload(model, j) },
                    onLongClick = { deleting = j }) {
                    IconButton({ playDownload(model, j) }) { Icon(Icons.Filled.PlayArrow, "Lire", tint = C.accentHi) }
                    IconButton({ deleting = j }) { Icon(Icons.Outlined.Delete, "Supprimer", tint = C.muted) }
                }
            }
        }
    }
    deleting?.let { j ->
        ConfirmDialog("Supprimer le fichier", "Supprimer « ${j.title} » du téléphone ? La reprise de lecture est conservée.",
            "Supprimer", onOk = { Downloads.remove(j.id); deleting = null }, onDismiss = { deleting = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JobRow(j: DlJob, subtitle: String, progress: Float, error: Boolean = false, onClick: () -> Unit,
                   onLongClick: (() -> Unit)? = null, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 46.dp, height = 69.dp).clip(RoundedCornerShape(6.dp))) {
            ItemImage(j.toItem(), Modifier.width(46.dp).height(69.dp))
            if (progress >= 0f) ProgressBar(progress, Modifier.align(Alignment.BottomCenter))
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(j.title, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = if (error) C.live else C.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}
