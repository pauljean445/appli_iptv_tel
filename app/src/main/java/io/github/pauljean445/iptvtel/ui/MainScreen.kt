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

private val TABS = listOf(Tab("home", "Accueil", Icons.Filled.Home), Tab("live", "Direct", Icons.Filled.LiveTv),
    Tab("movie", "Films", Icons.Filled.Movie), Tab("series", "Séries", Icons.Filled.VideoLibrary),
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
            NavigationBar(containerColor = C.panel) {
                TABS.forEach { t ->
                    NavigationBarItem(
                        selected = model.tab == t.key, onClick = { model.tab = t.key },
                        icon = {
                            if (t.key == "downloads" && active > 0) BadgedBox(badge = { Badge(containerColor = C.accent) { Text("$active") } }) {
                                Icon(t.icon, null)
                            } else Icon(t.icon, null)
                        },
                        label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = C.text,
                            indicatorColor = C.accent, unselectedIconColor = C.muted, unselectedTextColor = C.muted),
                    )
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
                else -> BrowseContent(model, model.tab)
            }
        }
    }
}

/** Accueil : films et séries en cours, chaînes favorites, replays favoris, derniers téléchargements. */
@Composable
fun HomeContent(model: AppModel) {
    val catalog = model.catalog ?: return
    val lib = model.library
    @Suppress("UNUSED_VARIABLE") val v = model.libVersion  // redessine après une modification
    val jobs by Downloads.jobs.collectAsState()
    var removing by remember { mutableStateOf<Pair<String, Item>?>(null) }

    fun channels(tab: String) = lib.favoriteKeys(tab).mapNotNull { catalog.byKey[it] }
    val movies = lib.recentKeys("movie").mapNotNull { catalog.byKey[it] }
    val series = lib.recentKeys("series").mapNotNull { catalog.byKey[it] }
    val downloaded = jobs.filter { it.state == "done" }.sortedByDescending { it.added }.take(20)

    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            HomeRow("Films", "en cours", movies.size,
                empty = "Aucun film en cours. Les films commencés apparaîtront ici.") {
                items(movies, key = { it.key }) { m ->
                    val pos = lib.position(m.key)
                    PosterTile(m, if (pos > 0) "Reprendre à ${fmtTime(pos)}" else "Commencé", Modifier.width(112.dp),
                        progress = lib.length(m.key).let { if (it > 0) pos.toFloat() / it else -1f },
                        onClick = { model.open(Screen.Details(m)) }, onLongClick = { removing = "movie" to m })
                }
            }
        }
        item {
            HomeRow("Séries", "en cours", series.size,
                empty = "Aucune série en cours. Les séries commencées apparaîtront ici.") {
                items(series, key = { it.key }) { s ->
                    val e = lib.recent[s.key].asObj()
                    val last = e?.get("last_ep").str()
                    val label = e?.get("last_label").str()
                    val pos = lib.position(last)
                    PosterTile(s, when {
                        label.isEmpty() -> "En cours"
                        pos > 0 -> "$label · ${fmtTime(pos)}"
                        last in lib.watchedKeys(s.key) -> "Après $label"
                        else -> "$label · commencé"
                    }, Modifier.width(112.dp), progress = lib.length(last).let { if (it > 0) pos.toFloat() / it else -1f },
                        onClick = { model.open(Screen.Details(s)) }, onLongClick = { removing = "series" to s })
                }
            }
        }
        item {
            val live = channels("live")
            HomeRow("Direct", "chaînes favorites", live.size,
                empty = "Aucune chaîne favorite. Dans l'onglet Direct, touche l'étoile d'une chaîne pour l'ajouter.") {
                items(live, key = { it.key }) { c ->
                    ChannelTile(c, c.groupLabel, onClick = { model.open(Screen.Player(c, live, resume = false)) },
                        onLongClick = { removing = "live" to c })
                }
            }
        }
        val replays = channels("replay")
        if (replays.isNotEmpty()) item {
            HomeRow("Replays", "chaînes favorites", replays.size, empty = "") {
                items(replays, key = { it.key }) { c ->
                    ChannelTile(c, "replay ${c.archiveDays} j", onClick = { model.open(Screen.Programs(c)) },
                        onLongClick = { removing = "replay" to c })
                }
            }
        }
        if (downloaded.isNotEmpty()) item {
            HomeRow("Téléchargés", "sur le téléphone", jobs.count { it.state == "done" }, empty = "") {
                items(downloaded, key = { it.id }) { j ->
                    val it = j.toItem()
                    val pos = lib.position(j.key)
                    PosterTile(it, j.title.substringAfter(" · ", ""), Modifier.width(112.dp),
                        progress = lib.length(j.key).let { l -> if (l > 0) pos.toFloat() / l else -1f },
                        badge = Icons.Filled.DownloadDone, onClick = { playDownload(model, j) })
                }
            }
        }
    }

    removing?.let { (kind, item) ->
        val fav = kind == "live" || kind == "replay"
        ActionsSheet(item.name, listOf(
            if (fav) Action("Retirer des favoris", Icons.Outlined.Delete) { if (lib.isFavorite(kind, item.key)) model.toggleFavorite(kind, item) }
            else Action("Retirer des lectures en cours", Icons.Outlined.Delete) { model.removeRecent(item) },
        ), onDismiss = { removing = null })
    }
}

/** Une bande de l'accueil : titre, puis entrées qui défilent vers la droite (ou un texte si elle est vide). */
@Composable
private fun HomeRow(title: String, sub: String, count: Int, empty: String,
                    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        RowTitle(title, if (count > 0) "$sub · $count" else sub, Modifier.padding(horizontal = 16.dp))
        if (count == 0) Text(empty, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top, content = content)
    }
}
