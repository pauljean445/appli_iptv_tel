package io.github.pauljean445.iptvtel.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.BuildConfig
import io.github.pauljean445.iptvtel.ui.C
import io.github.pauljean445.iptvtel.ui.ProgressBar

/** « Mise à jour disponible » : nouveautés, téléchargement (avec progression), puis installeur d'Android. */
@Composable
fun UpdateDialog(model: AppModel) {
    val info = model.update ?: return
    val context = LocalContext.current
    val progress = model.updateProgress
    // Autorisation « installer des applis inconnues » : revérifiée à chaque retour dans l'appli (réglage d'Android)
    var allowed by remember { mutableStateOf(Updater.canInstall(context)) }
    LifecycleResumeEffect(Unit) {
        allowed = Updater.canInstall(context)
        onPauseOrDispose { }
    }
    AlertDialog(
        onDismissRequest = { if (progress < 0f) model.update = null }, containerColor = C.panel,
        title = { Text("Mise à jour disponible", color = C.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("La version ${info.version} est disponible (tu as la ${BuildConfig.VERSION_NAME}).", color = C.text, fontSize = 15.sp)
                if (info.notes.isNotEmpty()) Text(info.notes, color = C.muted, fontSize = 13.sp,
                    modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()))
                when {
                    progress >= 0f -> {
                        Text(if (progress < 1f) "Téléchargement… ${(progress * 100).toInt()} %"
                            else "Installation… À la fin, choisis « Ouvrir » pour relancer l'appli.", color = C.text, fontSize = 14.sp)
                        ProgressBar(progress)
                    }
                    model.updateError.isNotEmpty() -> Text(model.updateError, color = C.live, fontSize = 14.sp)
                    !allowed -> Text("Une seule fois : autorise IPTV Player à installer ses mises à jour (« Autoriser » ouvre " +
                        "le réglage d'Android ; active-le puis reviens).", color = C.accentHi, fontSize = 14.sp)
                    else -> Text("Tes profils, favoris, reprises et téléchargements sont conservés.", color = C.muted, fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            when {
                progress >= 0f -> TextButton({ model.cancelUpdate() }) { Text("Annuler", color = C.text) }
                !allowed -> TextButton({ Updater.openInstallPermission(context) }) { Text("Autoriser", color = C.accentHi, fontWeight = FontWeight.SemiBold) }
                else -> TextButton({ model.startUpdate(context) }) { Text("Mettre à jour", color = C.accentHi, fontWeight = FontWeight.SemiBold) }
            }
        },
        dismissButton = { if (progress < 0f) TextButton({ model.update = null }) { Text("Plus tard", color = C.text) } },
    )
}
