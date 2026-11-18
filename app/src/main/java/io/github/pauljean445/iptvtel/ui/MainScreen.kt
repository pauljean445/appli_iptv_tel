package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.pauljean445.iptvtel.data.ImdbBase
import io.github.pauljean445.iptvtel.data.num
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.Screen
import io.github.pauljean445.iptvtel.data.Item
import io.github.pauljean445.iptvtel.data.asObj
import io.github.pauljean445.iptvtel.data.str
import io.github.pauljean445.iptvtel.download.Downloads

private data class Tab(val key: String, val label: String, val icon: ImageVector)

// TV : direct et replays (sélecteur) ; VOD : films et séries (sélecteur) ; Favoris : les quatre
private val TABS = listOf(Tab("home", "Accueil", Icons.Filled.Home), Tab("fav", "Favoris", Icons.Filled.Star),
    Tab("live", "TV", Icons.Filled.LiveTv), Tab("vod", "VOD", Icons.Filled.Movie),
    Tab("downloads", "Téléchargés", Icons.Filled.Download))

/** Écran principal : titre, recherche et profil en haut ; onglets dans la barre du bas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(model: AppModel) {
    val jobs by Downloads.jobs.collectAsState()
    val active = jobs.count { it.active }
    Scaffold(
        containerColor = C.bg,
        topBar = {
            TopAppBar(
                title = { Text(if (model.tab == "home") "IPTV Player" else TABS.first { it.key == model.tab }.label.let {
                    if (it == "Téléchargés") "Téléchargements" else it }, fontWeight = FontWeight.Bold) },
                actions = {
                    if (model.catalogLoading) CircularProgressIndicator(Modifier.padding(end = 8.dp).width(18.dp), color = C.muted, strokeWidth = 2.dp)
                    if (model.tab != "downloads") IconButton({ model.open(Screen.Search) }) { Icon(Icons.Filled.Search, "Rechercher") }
                    model.currentProfile?.let { p ->
                        Box(Modifier.padding(end = 12.dp, start = 4.dp).clip(CircleShape).clickable { model.open(Screen.Settings) }) {
                            Avatar(p["name"].str(), p["color"].str(), 32.dp)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = C.bg, titleContentColor = C.text, actionIconContentColor = C.text),
            )
        },
        bottomBar = {
            Column {
            CastBar(model)
            NavigationBar(containerColor = C.panel) {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = model.tab == t.key, onClick = { model.tab = t.key },
                        icon = {
                            if (t.key == "downloads" && active > 0) BadgedBox(badge = { Badge(containerColor = C.accent) { Text("$active") } }) {
                                Icon(t.icon, null)
                            } else Icon(t.icon, null)
                        },
                        label = { Text(t.label, fontSize = 10.sp, maxLines = 1, softWrap = false) },  // « Téléchargés » tient sur 5 onglets
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = C.text,
                            indicatorColor = C.accent, unselectedIconColor = C.muted, unselectedTextColor = C.muted),
                    )
                }
            }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val catalog = model.catalog
            when {
                model.tab == "downloads" -> DownloadsContent(model)
                catalog == null && model.catalogError.isNotEmpty() -> Message("Catalogue indisponible", model.catalogError) {
                    AppButton("Réessayer", { model.loadCatalog(force = true) }, primary = true)
                    AppButton("Saisir l'abonnement IPTV", { model.open(Screen.Login(iptvOnly = true)) })
                    if (jobs.any { it.state == "done" }) AppButton("Voir les téléchargements", { model.tab = "downloads" },
                        icon = Icons.Filled.DownloadDone)
                }
                catalog == null -> Message("Chargement du catalogue…")
                model.tab == "home" -> HomeContent(model)
                model.tab == "fav" -> FavoritesContent(model)
                else -> BrowseContent(model, model.tab)
            }
        }
    }
}

/** Vignettes au plus par bande de l'accueil (« Tout voir » pour le reste), comme le PC. */
const val HOME_MAX = 12

/** Série « finie pour l'instant » : son dernier épisode disponible est vu (ou tous ses épisodes connus) ; elle
 *  quitte « En cours de lecture » (toujours dans « Vu récemment »), de nouveaux épisodes l'y refont apparaître. */
fun seriesDone(lib: io.github.pauljean445.iptvtel.data.Library, show: Item): Boolean {
    val e = lib.recent[show.key].asObj() ?: return false
    val last = e["last_ep"].str()
    if (last.isEmpty() || last !in lib.watched) return false
    val eps = show.episodes.orEmpty()
    if (eps.isNotEmpty()) return eps.last().key in lib.watched
    val total = e["total"].num().toLong()
    return total > 0 && lib.watchedKeys(show.key).size >= total
}

/** Accueil : les lectures en cours seulement, films puis séries (12 au plus, « Tout voir » ouvre l'onglet VOD sur
 *  « Vu récemment »). Les favoris ont leur onglet. */
@Composable
fun HomeContent(model: AppModel) {
    val catalog = model.catalog ?: return
    val lib = model.library
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion  // redessine après une modification
    var removing by remember { mutableStateOf<Item?>(null) }

    // Films finis / séries entièrement vues : dans « Vu récemment » seulement
    val movies = lib.recentKeys("movie").filterNot { lib.isDone(it) }.mapNotNull { catalog.byKey[it] }
    val series = lib.recentKeys("series").filterNot { lib.isDone(it) }.mapNotNull { catalog.byKey[it] }
        .filterNot { seriesDone(lib, it) }
    fun seeAll(tab: String) { model.pendingGroup = tab to RECENT; model.tab = "vod" }

    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "title") { BlockTitle("En cours de lecture", Icons.Filled.PlayCircle) }
        item(key = "movie") {
            HomeRow("Films", movies.size, empty = "Aucun film en cours. Les films commencés apparaîtront ici.",
                icon = Icons.Filled.Movie, onSeeAll = { seeAll("movie") }.takeIf { movies.size > HOME_MAX }) {
                items(movies.take(HOME_MAX), key = { it.key }) { m ->
                    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
                    val pos = lib.position(m.key)
                    PosterTile(m, if (pos > 0) "Reprendre à ${fmtTime(pos)}" else "Commencé", Modifier.width(112.dp),
                        progress = lib.length(m.key).let { if (it > 0) pos.toFloat() / it else -1f },
                        onClick = { model.open(Screen.Details(m)) }, onLongClick = { removing = m })
                }
            }
        }
        item(key = "series") {
            HomeRow("Séries", series.size, empty = "Aucune série en cours. Les séries commencées apparaîtront ici.",
                icon = Icons.Filled.VideoLibrary, onSeeAll = { seeAll("series") }.takeIf { series.size > HOME_MAX }) {
                items(series.take(HOME_MAX), key = { it.key }) { s ->
                    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
                    val e = lib.recent[s.key].asObj()
                    val last = e?.get("last_ep").str()
                    val label = e?.get("last_label").str()
                    val pos = lib.position(last)
                    PosterTile(s, when {
                        label.isEmpty() -> "En cours"
                        pos > 0 -> "$label · ${fmtTime(pos)}"
                        last in lib.watched -> "Après $label"
                        else -> "$label · commencé"
                    }, Modifier.width(112.dp), progress = lib.length(last).let { if (it > 0) pos.toFloat() / it else -1f },
                        onClick = { model.open(Screen.Details(s)) }, onLongClick = { removing = s })
                }
            }
        }
    }

    removing?.let { item ->
        ActionsSheet(item.name, listOf(
            Action("Retirer des lectures en cours", Icons.Outlined.Delete) { model.removeRecent(item) },
        ), onDismiss = { removing = null })
    }
}

/** Onglet Favoris : bloc VOD (films, séries ; les derniers ajoutés d'abord), puis bloc TV (direct, replays ; dans
 *  l'ordre des favoris, celui du zapping). */
@Composable
fun FavoritesContent(model: AppModel) {
    val catalog = model.catalog ?: return
    val lib = model.library
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion
    var removing by remember { mutableStateOf<Pair<String, Item>?>(null) }

    fun channels(tab: String) = lib.favoriteKeys(tab).mapNotNull { catalog.byKey[it] }
    fun vod(tab: String) = lib.favoriteKeys(tab)
        .sortedByDescending { lib.fav["$tab|$it"].asObj()?.get("t").num() }.mapNotNull { catalog.byKey[it] }
    val movies = vod("movie")
    val series = vod("series")
    val live = channels("live")
    val replays = channels("replay")

    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "vod") { BlockTitle("VOD", Icons.Filled.Movie) }
        item(key = "movie") {
            HomeRow("Films", movies.size, icon = Icons.Filled.Movie,
                empty = "Aucun film favori. Sur la fiche d'un film : bouton Favori.") {
                items(movies, key = { it.key }) { m ->
                    PosterTile(m, ImdbBase.tileText(m), Modifier.width(112.dp), seen = lib.isDone(m.key),
                        onClick = { model.open(Screen.Details(m)) }, onLongClick = { removing = "movie" to m })
                }
            }
        }
        item(key = "series") {
            HomeRow("Séries", series.size, icon = Icons.Filled.VideoLibrary,
                empty = "Aucune série favorite. Sur la fiche d'une série : bouton Favori.") {
                items(series, key = { it.key }) { s ->
                    PosterTile(s, ImdbBase.tileText(s), Modifier.width(112.dp), seen = lib.isDone(s.key),
                        onClick = { model.open(Screen.Details(s)) }, onLongClick = { removing = "series" to s })
                }
            }
        }
        item(key = "tv") { BlockTitle("TV", Icons.Filled.LiveTv, Modifier.padding(top = 8.dp)) }
        item(key = "live") {
            HomeRow("Direct", live.size, icon = Icons.Filled.Tv,
                empty = "Aucune chaîne favorite. Dans l'onglet TV, touche l'étoile d'une chaîne pour l'ajouter.") {
                items(live, key = { it.key }) { c ->
                    ChannelTile(c, c.groupLabel, onClick = { model.open(Screen.Player(c, live, resume = false)) },
                        onLongClick = { removing = "live" to c })
                }
            }
        }
        item(key = "replay") {
            HomeRow("Replays", replays.size, icon = Icons.Filled.Replay,
                empty = "Aucune chaîne favorite. Dans l'onglet TV › Replays, touche l'étoile d'une chaîne pour l'ajouter.") {
                items(replays, key = { it.key }) { c ->
                    ChannelTile(c, "replay ${c.archiveDays} j", onClick = { model.open(Screen.Programs(c)) },
                        onLongClick = { removing = "replay" to c })
                }
            }
        }
    }

    removing?.let { (tab, item) ->
        ActionsSheet(item.name, listOf(
            Action("Retirer des favoris", Icons.Outlined.Delete) {
                if (lib.isFavorite(tab, item.key)) model.toggleFavorite(tab, item)
            },
        ), onDismiss = { removing = null })
    }
}

/** Titre d'un bloc (Accueil, Favoris) : icône violette, titre et trait. */
@Composable
private fun BlockTitle(title: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = C.accentHi, modifier = Modifier.size(22.dp))
        Text(title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp, end = 12.dp))
        Box(Modifier.weight(1f).height(1.dp).background(C.line))
    }
}

/** Une bande : petit titre gris en capitales (+ nombre, lien « Tout voir » à droite), puis entrées qui défilent vers
 *  la droite (ou un texte si elle est vide). */
@Composable
private fun HomeRow(title: String, count: Int, empty: String, icon: ImageVector? = null, onSeeAll: (() -> Unit)? = null,
                    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = C.muted, modifier = Modifier.padding(end = 8.dp).size(16.dp))
            Text(title.uppercase(), color = C.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            if (count > 0) Text("  ·  $count", color = C.muted, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            if (onSeeAll != null) Text("Tout voir ›", color = C.accentHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }
        if (count == 0) Text(empty, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top, content = content)
    }
}
