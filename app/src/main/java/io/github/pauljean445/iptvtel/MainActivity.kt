package io.github.pauljean445.iptvtel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.player.PlayerScreen
import io.github.pauljean445.iptvtel.ui.C
import io.github.pauljean445.iptvtel.ui.DetailsScreen
import io.github.pauljean445.iptvtel.ui.IptvTheme
import io.github.pauljean445.iptvtel.ui.LoginScreen
import io.github.pauljean445.iptvtel.ui.MainScreen
import io.github.pauljean445.iptvtel.ui.Message
import io.github.pauljean445.iptvtel.ui.ProfilesScreen
import io.github.pauljean445.iptvtel.ui.ProgramsScreen
import io.github.pauljean445.iptvtel.ui.SearchScreen
import io.github.pauljean445.iptvtel.ui.SettingsScreen
import io.github.pauljean445.iptvtel.update.UpdateDialog
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Barres d'Android transparentes, icônes claires (fond « nuit » de l'appli)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        // Démarrage une seule fois par modèle : aussi quand Android recrée l'appli qu'il avait fermée en arrière-plan
        model.startOnce()
        if (io.github.pauljean445.iptvtel.update.Updater.takeJustUpdated(this))
            model.toast = "IPTV Player est à jour : version ${BuildConfig.VERSION_NAME}"
        openTab(intent)
        setContent { IptvTheme { App(model) { finish() } } }
    }

    /** Notification des téléchargements touchée : onglet Téléchargements. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTab(intent)
    }

    private fun openTab(intent: Intent?) {
        model.showTab(intent?.getStringExtra(EXTRA_TAB) ?: return)
    }

    override fun onStart() {
        super.onStart()
        model.onAppShown()
    }

    override fun onStop() {
        super.onStop()
        model.onAppHidden()  // dernières positions envoyées au compte du foyer
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}

@Composable
fun App(model: AppModel, onQuit: () -> Unit) {
    BackHandler { if (!model.back()) onQuit() }
    val saved = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(C.bg)) {
        val s = model.screen
        // État de chaque écran de la pile (onglet, catégorie, défilement) gardé pendant qu'un autre est par-dessus
        saved.SaveableStateProvider(model.stack.size.toString() + ":" + s.javaClass.simpleName) {
            when (s) {
                Screen.Loading -> Message("IPTV Player", "Démarrage…", Modifier.statusBarsPadding())
                is Screen.Login -> LoginScreen(model, s.notice, s.iptvOnly)
                Screen.Profiles -> ProfilesScreen(model)
                Screen.Main -> MainScreen(model)
                Screen.Settings -> SettingsScreen(model)
                Screen.Search -> SearchScreen(model)
                is Screen.Details -> DetailsScreen(model, s.item)
                is Screen.Programs -> ProgramsScreen(model, s.channel)
                is Screen.Player -> PlayerScreen(model, s.item, s.list, s.resume, onExit = { model.back() })
            }
        }
        // Mise à jour proposée : jamais par-dessus une lecture (attend le retour aux menus)
        if (model.screen !is Screen.Player) UpdateDialog(model)
        if (model.toast.isNotEmpty()) {
            Text(model.toast, color = C.text, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = if (model.screen is Screen.Main) 92.dp else 24.dp)
                    .background(C.surface2, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 12.dp))
            LaunchedEffect(model.toast) { delay(3500); model.toast = "" }
        }
    }
}
