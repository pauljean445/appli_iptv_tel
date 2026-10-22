package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.Screen
import io.github.pauljean445.iptvtel.data.Item

const val FAV = "\u0000fav"
const val RECENT = "\u0000recent"
const val ALL = ""

/** Onglets Direct (avec Replays) / Films / Séries : catégories en puces, puis liste (chaînes) ou grille (affiches). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseContent(model: AppModel, mainTab: String) {
    val catalog = model.catalog ?: return
    val lib = model.library
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
    var liveMode by rememberSaveable { mutableStateOf("live") }  // onglet Direct : « En direct » ou « Replays »
    val tab = if (mainTab == "live") liveMode else mainTab
    var group by rememberSaveable(tab) { mutableStateOf(if (lib.favoriteKeys(tab).isNotEmpty()) FAV else ALL) }
    var actionsFor by remember { mutableStateOf<Item?>(null) }
    var groupActions by remember { mutableStateOf<String?>(null) }  // catégorie : masquer / réafficher
    var allGroups by remember { mutableStateOf(false) }
    val download = rememberDownloader(model)
    // Catégories masquées (par profil, synchronisées) : hors des puces et de « Toutes », rangées dans « Autres »
    val hiddenGroups = remember(catalog, tab, model.libVersion) { catalog.groups[tab].orEmpty().filter { lib.isHidden(tab, it) } }

    val personal = group == FAV || group == RECENT
    val items = remember(catalog, tab, group, if (personal || group == ALL) model.libVersion else 0) {
        val all = catalog.byTab[tab].orEmpty()
        when (group) {
            FAV -> lib.favoriteKeys(tab).mapNotNull { catalog.byKey[it] }
            RECENT -> lib.recentKeys(tab).mapNotNull { catalog.byKey[it] }
            ALL -> hiddenGroups.toSet().let { h -> if (h.isEmpty()) all else all.filter { it.groupLabel !in h } }
            else -> all.filter { it.groupLabel == group }
        }
    }
    val groups = remember(catalog, tab, hiddenGroups) {
        buildList {
            add(FAV to "★ Favoris")
            if (tab == "movie" || tab == "series") add(RECENT to "Vu récemment")
            add(ALL to "Toutes")
            catalog.groups[tab].orEmpty().filter { it !in hiddenGroups }.forEach { add(it to it) }
        }
    }
    val chips = remember(groups, group) {
        // La catégorie choisie dans la liste complète reste visible en puce, juste après « Toutes »
        if (groups.any { it.first == group }) groups
        else groups.take(if (tab == "movie" || tab == "series") 3 else 2) + (group to group) + groups.drop(if (tab == "movie" || tab == "series") 3 else 2)
    }

    fun open(it: Item) = when (tab) {
        "live" -> model.open(Screen.Player(it, items, resume = false))
        "replay" -> model.open(Screen.Programs(it))
        else -> model.open(Screen.Details(it))
    }

    Column(Modifier.fillMaxSize()) {
        if (mainTab == "live") SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
            listOf("live" to "En direct", "replay" to "Replays").forEachIndexed { i, (key, label) ->
                SegmentedButton(selected = liveMode == key, onClick = { liveMode = key }, shape = SegmentedButtonDefaults.itemShape(i, 2),
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = C.accent, activeContentColor = Color.White,
                        inactiveContainerColor = C.bg, inactiveContentColor = C.muted)) { Text(label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ allGroups = true }, Modifier.padding(start = 6.dp)) {
                Icon(Icons.AutoMirrored.Filled.List, "Toutes les catégories", tint = C.text)
            }
            LazyRow(contentPadding = PaddingValues(end = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips, key = { it.first }) { (key, label) ->
                    GroupChip(label, key == group, onClick = { group = key },
                        onLongClick = if (key != FAV && key != RECENT && key != ALL) ({ groupActions = key }) else null)
                }
            }
        }
        val title = (if (group in hiddenGroups) "Catégorie masquée (dans « Autres ») · " else "") +
            "${items.size} ${if (tab == "movie") "film" else if (tab == "series") "série" else "chaîne"}${if (items.size > 1) "s" else ""}"
        Text(title, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (items.isEmpty()) {
            Text(when (group) {
                FAV -> if (tab == "live" || tab == "replay") "Aucun favori. Touche l'étoile d'une chaîne pour l'ajouter."
                    else "Aucun favori. Appui long sur une affiche pour l'ajouter aux favoris."
                RECENT -> "Rien en cours pour le moment."
                else -> "Aucune entrée dans cette catégorie."
            }, color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        } else if (tab == "live" || tab == "replay") {
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 4.dp, bottom = 16.dp)) {
                items(items, key = { it.key }) { it ->
                    ChannelRow(it, if (tab == "replay") "${it.groupLabel} · replay ${it.archiveDays} j" else it.groupLabel,
                        fav = lib.isFavorite(tab, it.key), onClick = { open(it) },
                        onStar = { model.toggleFavorite(tab, it) }, onLongClick = { actionsFor = it })
                }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.key }) { it ->
                    @Suppress("UNUSED_VARIABLE") val v = model.libVersion  // progression redessinée
                    val pos = if (tab == "movie") lib.resumePosition(it.key) else 0L
                    val len = if (tab == "movie") lib.length(it.key) else 0L
                    val sub = listOf(it.year, if (it.rating.isNotEmpty()) "★ ${it.rating}" else "").filter { s -> s.isNotEmpty() }.joinToString(" · ")
                    PosterTile(it, sub, progress = if (pos > 0 && len > 0) pos.toFloat() / len else -1f,
                        onClick = { open(it) }, onLongClick = { actionsFor = it })
                }
            }
        }
    }

    if (allGroups) GroupsSheet(groups, hiddenGroups, group, onPick = { group = it; allGroups = false },
        onLongClick = { groupActions = it }, onDismiss = { allGroups = false })

    groupActions?.let { g ->
        val hidden = lib.isHidden(tab, g)
        ActionsSheet(g, listOf(
            if (hidden) Action("Réafficher la catégorie", Icons.Outlined.Visibility) {
                lib.setHidden(tab, g, false); model.libraryChanged(); model.toast = "« $g » réaffichée"
            } else Action("Masquer la catégorie (rangée dans « Autres »)", Icons.Outlined.VisibilityOff) {
                lib.setHidden(tab, g, true); model.libraryChanged()
                if (group == g) group = ALL
                model.toast = "« $g » rangée dans « Autres »"
            },
        ), onDismiss = { groupActions = null })
    }

    actionsFor?.let { item ->
        val fav = lib.isFavorite(tab, item.key)
        ActionsSheet(item.name, buildList {
            add(Action(if (fav) "Retirer des favoris" else "Ajouter aux favoris", if (fav) Icons.Filled.Star else Icons.Filled.StarBorder) {
                val added = model.toggleFavorite(tab, item)
                model.toast = "${item.name} : ${if (added) "ajouté aux" else "retiré des"} favoris"
            })
            if (item.kind == "movie") add(Action("Télécharger", Icons.Filled.Download) { download(listOf(item)) })
            if (item.key in lib.recent) add(Action("Retirer de « Vu récemment »", Icons.Outlined.Delete) { model.removeRecent(item) })
        }, onDismiss = { actionsFor = null })
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    // FilterChip ne gère pas l'appui long : la zone de la puce le reçoit par-dessus
    Box {
        FilterChip(selected = selected, onClick = onClick, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = FilterChipDefaults.filterChipColors(containerColor = C.surface, labelColor = C.muted,
                selectedContainerColor = C.accent, selectedLabelColor = Color.White),
            border = FilterChipDefaults.filterChipBorder(true, selected, borderColor = C.line, selectedBorderColor = C.accent))
        if (onLongClick != null) Box(Modifier.matchParentSize().padding(vertical = 8.dp).clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick))
    }
}

/** Liste complète des catégories (bouton à gauche des puces) ; « Autres » : catégories masquées. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupsSheet(groups: List<Pair<String, String>>, hidden: List<String>, current: String, onPick: (String) -> Unit,
                        onLongClick: (String) -> Unit, onDismiss: () -> Unit) {
    var othersOpen by remember { mutableStateOf(current in hidden) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel, sheetState = rememberModalBottomSheetState()) {
        Text("Catégories", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
        Text("Appui long sur une catégorie pour la masquer ou la réafficher.", color = C.muted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(groups, key = { it.first }) { (key, label) ->
                GroupLine(label, key == current, muted = false, onClick = { onPick(key) },
                    onLongClick = if (key != FAV && key != RECENT && key != ALL) ({ onLongClick(key) }) else null)
            }
            if (hidden.isNotEmpty()) {
                item(key = "\u0000others") {
                    GroupLine((if (othersOpen) "▾  " else "▸  ") + "Autres · ${hidden.size}", false, muted = false,
                        onClick = { othersOpen = !othersOpen }, onLongClick = null)
                }
                if (othersOpen) items(hidden, key = { "\u0000h:$it" }) { g ->
                    GroupLine("     $g", g == current, muted = true, onClick = { onPick(g) }, onLongClick = { onLongClick(g) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupLine(label: String, selected: Boolean, muted: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    Text(label, color = if (selected) C.accentHi else if (muted) C.muted else C.text, fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 24.dp, vertical = 13.dp))
}

/** Une chaîne : logo, nom, catégorie, étoile (favori). Appui long : actions. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(item: Item, subtitle: String, fav: Boolean, onClick: () -> Unit, onStar: () -> Unit, onLongClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 68.dp, height = 42.dp).clip(RoundedCornerShape(6.dp))) {
            ItemImage(item, Modifier.fillMaxSize(), crop = false, padding = 4.dp)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.name, color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = C.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onStar) {
            Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, if (fav) "Retirer des favoris" else "Ajouter aux favoris",
                tint = if (fav) C.accentHi else C.muted)
        }
    }
}
