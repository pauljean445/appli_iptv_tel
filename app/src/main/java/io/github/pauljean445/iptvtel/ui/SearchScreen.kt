package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.Screen
import io.github.pauljean445.iptvtel.data.Item
import kotlinx.coroutines.delay

/** Recherche (sans tenir compte des accents) : chaînes, films et séries, comme la page Recherche du PC et de la TV. */
@Composable
fun SearchScreen(model: AppModel) {
    val catalog = model.catalog
    var query by rememberSaveable { mutableStateOf("") }
    var searched by remember { mutableStateOf(query) }
    LaunchedEffect(query) { delay(300); searched = query }  // pas de recherche à chaque lettre tapée
    val q = Item.normalize(searched.trim())
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val results = remember(q, catalog) {
        if (q.length < 2 || catalog == null) null
        else Triple(catalog.byTab["live"].orEmpty().filter { q in it.norm }.take(50),
            catalog.byTab["movie"].orEmpty().filter { q in it.norm }.take(300),
            catalog.byTab["series"].orEmpty().filter { q in it.norm }.take(300))
    }
    fun sub(it: Item) = listOf(it.year, if (it.rating.isNotEmpty()) "★ ${it.rating}" else "")
        .filter { s -> s.isNotEmpty() }.joinToString(" · ").ifEmpty { it.groupLabel }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = C.text) }
            TextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                placeholder = { Text("Chaîne, film, série…") },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Effacer") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = C.surface, unfocusedContainerColor = C.surface,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = C.accentHi,
                    focusedPlaceholderColor = C.muted, unfocusedPlaceholderColor = C.muted))
        }
        if (results == null) {
            Text(if (catalog == null) "Catalogue pas encore chargé." else "Tape au moins 2 lettres.", color = C.muted, fontSize = 14.sp,
                modifier = Modifier.padding(16.dp))
        } else {
            val (live, films, series) = results
            if (live.isEmpty() && films.isEmpty() && series.isEmpty())
                Text("Aucun résultat.", color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            LazyVerticalGrid(GridCells.Adaptive(104.dp), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (live.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { RowTitle("Chaînes", "${live.size}", Modifier.padding(top = 8.dp)) }
                    items(live, key = { it.key }, span = { GridItemSpan(maxLineSpan) }) { c ->
                        ChannelRow(c, c.groupLabel, fav = model.library.isFavorite("live", c.key),
                            onClick = { model.open(Screen.Player(c, live, resume = false)) },
                            onStar = { model.toggleFavorite("live", c) }, onLongClick = {})
                    }
                }
                if (films.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { RowTitle("Films", "${films.size}", Modifier.padding(top = 8.dp)) }
                    items(films, key = { it.key }) { m -> PosterTile(m, sub(m), onClick = { model.open(Screen.Details(m)) }) }
                }
                if (series.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { RowTitle("Séries", "${series.size}", Modifier.padding(top = 8.dp)) }
                    items(series, key = { it.key }) { s -> PosterTile(s, sub(s), onClick = { model.open(Screen.Details(s)) }) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (query.isEmpty()) try { focus.requestFocus() } catch (e: Exception) { } }
}
