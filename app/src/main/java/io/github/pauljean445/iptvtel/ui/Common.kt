package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.pauljean445.iptvtel.data.Item

/** Bouton principal (violet) ou secondaire (contour), icône facultative. */
@Composable
fun AppButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
              danger: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null) {
    val padding = PaddingValues(horizontal = if (text.isEmpty()) 12.dp else 18.dp, vertical = 10.dp)
    val content: @Composable () -> Unit = {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        if (icon != null && text.isNotEmpty()) Spacer(Modifier.width(8.dp))
        if (text.isNotEmpty()) Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
    if (primary || danger) Button(onClick, modifier, enabled = enabled, contentPadding = padding,
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (danger) C.live else C.accent, contentColor = Color.White)) { content() }
    else OutlinedButton(onClick, modifier, enabled = enabled, contentPadding = padding, shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = C.surface2, contentColor = C.text),
        border = androidx.compose.foundation.BorderStroke(1.dp, C.line)) { content() }
}

/** Pastille ronde d'un profil (initiale sur sa couleur). */
@Composable
fun Avatar(name: String, color: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(parseColor(color), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: Exception) {
    C.accent
}

/** Message plein écran (chargement, erreur), avec des boutons facultatifs. */
@Composable
fun Message(title: String, text: String = "", modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (text.isNotEmpty()) Text(text, color = C.muted, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp))
        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally) { actions() }
    }
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f Go".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "${bytes shr 20} Mo"
    else -> "${bytes shr 10} Ko"
}

/** Image d'une entrée (affiche, logo) ; initiales du nom tant qu'elle n'est pas chargée (ou s'il n'y en a pas). */
@Composable
fun ItemImage(item: Item, modifier: Modifier = Modifier, crop: Boolean = true, padding: Dp = 0.dp) {
    var loaded by remember(item.logo) { mutableStateOf(false) }
    Box(modifier.background(C.surface2), contentAlignment = Alignment.Center) {
        if (!loaded) Text(item.initials, color = C.muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (item.logo.isNotEmpty()) AsyncImage(
            model = item.logo, contentDescription = null,
            contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(padding),
            onSuccess = { loaded = true },
        )
    }
}

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color(0x3CFFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(C.accentHi))
    }
}

/** Affiche de film / série (grilles, bandes) : image 2:3, progression, titre et sous-titre. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterTile(item: Item, subtitle: String, modifier: Modifier = Modifier, progress: Float = -1f, badge: ImageVector? = null,
               onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
            ItemImage(item, Modifier.fillMaxSize())
            if (progress >= 0f) ProgressBar(progress, Modifier.align(Alignment.BottomCenter))
            if (badge != null) Icon(badge, contentDescription = null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x99100D22), CircleShape)
                    .padding(3.dp).size(14.dp))
        }
        Text(item.name, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 2.dp, end = 2.dp))
        if (subtitle.isNotEmpty()) Text(subtitle, color = C.muted, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 2.dp))
    }
}

/** Logo de chaîne (bandes de l'accueil). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelTile(item: Item, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(modifier.width(128.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().height(76.dp).clip(RoundedCornerShape(10.dp))) {
            ItemImage(item, Modifier.fillMaxSize(), crop = false, padding = 10.dp)
        }
        Text(item.name, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 2.dp))
        if (subtitle.isNotEmpty()) Text(subtitle, color = C.muted, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

/** Confirmation (suppression, déconnexion…). */
@Composable
fun ConfirmDialog(title: String, text: String, ok: String, onOk: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.panel,
        title = { Text(title, color = C.text, fontWeight = FontWeight.Bold) },
        text = { Text(text, color = C.muted) },
        confirmButton = { TextButton(onOk) { Text(ok, color = if (danger) C.live else C.accentHi, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onDismiss) { Text("Annuler", color = C.text) } },
    )
}

/** Une action d'une feuille d'actions (appui long). */
data class Action(val label: String, val icon: ImageVector? = null, val run: () -> Unit)

/** Feuille d'actions qui monte du bas de l'écran (appui long sur une entrée). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionsSheet(title: String, actions: List<Action>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(title, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            actions.forEach { a ->
                Row(Modifier.fillMaxWidth().clickable { onDismiss(); a.run() }.padding(horizontal = 24.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (a.icon != null) Icon(a.icon, contentDescription = null, tint = C.muted, modifier = Modifier.padding(end = 18.dp))
                    Text(a.label, color = C.text, fontSize = 15.sp)
                }
            }
        }
    }
}

/** Titre d'une bande (accueil, recherche). */
@Composable
fun RowTitle(title: String, sub: String = "", modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotEmpty()) Text("  $sub", color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
    }
}
