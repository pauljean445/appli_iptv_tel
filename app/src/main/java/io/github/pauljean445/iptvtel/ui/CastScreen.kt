package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.cast.CastTarget
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.Screen
import io.github.pauljean445.iptvtel.cast.Caster

/** Choix de l'appareil Cast (Chromecast, TV « Chromecast intégré ») sur le Wi-Fi où se trouve le téléphone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CastPicker(onPick: (CastTarget) -> Unit, onDismiss: () -> Unit) {
    DisposableEffect(Unit) {
        Caster.startScan()
        onDispose { Caster.stopScan() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Diffuser sur un appareil Cast", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            Text("Le téléphone lit le flux et l'envoie à l'appareil (son converti si besoin) : il doit rester sur ce Wi-Fi.",
                color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 24.dp))
            if (Caster.targets.isEmpty()) Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = C.accentHi, strokeWidth = 2.dp)
                Text("Recherche des appareils… (le téléphone et l'appareil doivent être sur le même Wi-Fi)", color = C.muted,
                    fontSize = 13.sp, modifier = Modifier.padding(start = 14.dp))
            }
            Caster.targets.forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (Caster.target == t) Icons.Filled.CastConnected else Icons.Filled.Cast, null, tint = C.accentHi)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(t.name, color = C.text, fontSize = 15.sp)
                        if (t.model.isNotEmpty()) Text(t.model, color = C.muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

/** Télécommande de la diffusion en cours : titre, appareil, barre de temps, lecture / pause, suivant, arrêt. */
@Composable
fun CastScreen(model: AppModel) {
    val item = Caster.item
    if (item == null) { Message("Diffusion terminée") { AppButton("Retour", { model.back() }, primary = true) }; return }
    val live = item.kind == "live"
    val step = if (item.kind == "program") 60_000L else 10_000L
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().navigationBarsPadding().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        var tracksOpen by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = C.text) }
            Text(if (Caster.connecting) "Connexion à ${Caster.device}…" else "Diffusion sur ${Caster.device}", color = C.accentHi,
                fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (!live || Caster.tracks.isNotEmpty()) IconButton({ tracksOpen = true }) {
                Icon(Icons.Filled.Subtitles, "Langue et sous-titres", tint = C.text)
            }
        }
        if (tracksOpen) CastTracksSheet(onDismiss = { tracksOpen = false })
        Spacer(Modifier.weight(0.4f))
        Box(Modifier.width(if (live) 220.dp else 180.dp).height(if (live) 124.dp else 270.dp).clip(RoundedCornerShape(14.dp))) {
            ItemImage(item, Modifier.fillMaxSize(), crop = !live, padding = if (live) 18.dp else 0.dp)
        }
        Text(if (item.kind == "episode") "${item.parent?.name.orEmpty()} · ${item.episodeCode}" else item.name, color = C.text,
            fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp))
        if (item.kind == "episode") Text(item.name, color = C.muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (Caster.error.isNotEmpty()) Text(Caster.error, color = C.live, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
        Spacer(Modifier.weight(0.6f))
        if (!live && Caster.len > 0) RemoteSeekBar(Caster.pos, Caster.len) { Caster.seekTo(it) }
        if (live) Text("● En direct", color = C.live, fontSize = 13.sp)
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) Round(Icons.Filled.SkipPrevious, "Chaîne précédente", 52) { model.castStep(-1) }
            else Round(Icons.Filled.Replay10, "Reculer", 52) { Caster.seekTo(Caster.pos - step) }
            Box(contentAlignment = Alignment.Center) {
                Round(if (Caster.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture / pause", 72) { Caster.togglePause() }
                if (Caster.buffering || Caster.connecting) CircularProgressIndicator(Modifier.size(78.dp), color = C.accentHi, strokeWidth = 2.dp)
            }
            if (live) Round(Icons.Filled.SkipNext, "Chaîne suivante", 52) { model.castStep(1) }
            else Round(Icons.Filled.Forward10, "Avancer", 52) { Caster.seekTo(Caster.pos + step) }
        }
        if (item.kind == "episode" || item.kind == "program") model.castNextItem()?.let { n ->
            AppButton(if (n.kind == "episode") "Épisode suivant · ${n.episodeCode}" else "Programme suivant", { model.castStep(1) },
                icon = Icons.Filled.SkipNext)
        }
        AppButton("Arrêter la diffusion", { model.stopCast() }, Modifier.padding(top = 10.dp), icon = Icons.Filled.StopCircle)
    }
}

/** Bandeau « Diffusion sur … » au-dessus de la barre du bas : rouvre la télécommande. */
@Composable
fun CastBar(model: AppModel) {
    val item = Caster.item ?: return
    if (Caster.device.isEmpty() && !Caster.connecting) return
    Row(Modifier.fillMaxWidth().background(C.surface).clickable { model.open(Screen.Cast) }
        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CastConnected, null, tint = C.accentHi)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (Caster.connecting) "Connexion…" else "Sur ${Caster.device}", color = C.muted, fontSize = 11.sp)
        }
        IconButton({ Caster.togglePause() }) { Icon(if (Caster.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Lecture / pause", tint = C.text) }
    }
}

/** Langue et sous-titres de la diffusion : une liste, comme dans le lecteur ; un choix relance le flux à la même
 *  position (l'appareil reçoit les pistes choisies au lancement). Sous-titres : incrustés dans l'image. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CastTracksSheet(onDismiss: () -> Unit) {
    val list = Caster.tracks
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            if (list.isEmpty()) Text("Pistes pas encore connues : attends le début de la lecture.", color = C.muted, fontSize = 13.sp)
            val audio = list.filter { it.kind == "audio" }
            if (audio.size > 1) {
                Text("Langue", color = C.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp))
                audio.forEach { t -> TrackLine(t.label, Caster.audioId == t.id) { onDismiss(); Caster.selectTrack("audio", t.id) } }
            }
            val subs = list.filter { it.kind == "spu" }
            if (subs.isNotEmpty()) {
                Text("Sous-titres (incrustés dans l'image)", color = C.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                TrackLine("Aucun", Caster.spuId < 0) { onDismiss(); Caster.selectTrack("spu", -1) }
                subs.forEach { t -> TrackLine(t.label, Caster.spuId == t.id) { onDismiss(); Caster.selectTrack("spu", t.id) } }
            }
            if (list.isNotEmpty() && audio.size <= 1 && subs.isEmpty()) Text("Une seule langue, pas de sous-titres.", color = C.muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun TrackLine(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp)) { if (selected) Icon(Icons.Filled.Check, null, tint = C.accentHi, modifier = Modifier.size(20.dp)) }
        Text(label, color = if (selected) C.accentHi else C.text, fontSize = 15.sp)
    }
}

@Composable
private fun Round(icon: ImageVector, label: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).background(C.surface2, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@Composable
private fun RemoteSeekBar(pos: Long, len: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    Column(Modifier.padding(horizontal = 24.dp)) {
        Slider(value = if (dragging) value else (pos.toFloat() / len).coerceIn(0f, 1f),
            onValueChange = { dragging = true; value = it },
            onValueChangeFinished = { onSeek((value * len).toLong()); dragging = false },
            colors = SliderDefaults.colors(thumbColor = C.accentHi, activeTrackColor = C.accentHi, inactiveTrackColor = C.surface2))
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(if (dragging) (value * len).toLong() else pos), color = C.text, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(len), color = C.muted, fontSize = 13.sp)
        }
    }
}
