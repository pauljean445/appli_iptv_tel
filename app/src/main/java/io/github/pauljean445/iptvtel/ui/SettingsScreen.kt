package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.BuildConfig
import io.github.pauljean445.iptvtel.Screen
import io.github.pauljean445.iptvtel.data.Obj
import io.github.pauljean445.iptvtel.data.Store
import io.github.pauljean445.iptvtel.data.str

/** Réglages (avatar en haut à droite) : profils, catalogue, compte du foyer, version. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(model: AppModel) {
    var confirmSignOut by remember { mutableStateOf(false) }
    var resetPid by remember { mutableStateOf<String?>(null) }
    var deletePid by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Obj?>(null) }
    var editing by remember { mutableStateOf<Pair<String?, Obj?>?>(null) }  // (id, profil) ; id null : nouveau profil
    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ model.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = C.text) }
            Text("Réglages", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Section("Profils", "Un profil par personne du foyer : chacun a ses favoris, ses lectures en cours et ses langues. " +
                    "Les profils sont partagés avec le PC et la TV.") {
                    AppButton("Nouveau profil", { editing = null to null }, icon = Icons.Filled.PersonAdd)
                }
            }
            if (model.profiles.size > 1) item {
                Row(Modifier.fillMaxWidth().background(C.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable { model.setAskProfile(!model.askProfile) }
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Demander « Qui regarde ? » au démarrage", color = C.text, fontSize = 15.sp)
                        Text(if (model.askProfile) "Le choix du profil s'affiche à chaque ouverture de l'appli."
                            else "Le dernier profil choisi s'ouvre directement.", color = C.muted, fontSize = 12.sp)
                    }
                    Switch(checked = model.askProfile, onCheckedChange = { model.setAskProfile(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedTrackColor = C.accent,
                        uncheckedThumbColor = C.text, uncheckedTrackColor = C.surface2, uncheckedBorderColor = C.muted))
                }
            }
            items(model.profiles, key = { it["id"].str() }) { p ->
                val pid = p["id"].str()
                val current = pid == model.profileId
                Row(Modifier.fillMaxWidth().background(C.surface, RoundedCornerShape(14.dp))
                    .border(1.dp, if (current) C.accent else C.line, RoundedCornerShape(14.dp))
                    .clickable { if (!current) model.switchProfile(pid) }.padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p["name"].str(), p["color"].str(), 40.dp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(p["name"].str(), color = C.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (current) "Profil en cours" else "Toucher pour ouvrir", color = if (current) C.accentHi else C.muted, fontSize = 12.sp)
                    }
                    IconButton({ menuFor = p }) { Icon(Icons.Filled.MoreVert, "Actions", tint = C.muted) }
                }
            }
            item {
                Section("Catalogue", model.source?.let { if (it.type == "xtream") "Xtream Codes · ${it.server}" else "Playlist M3U" }
                    ?: "Aucune source IPTV") {
                    AppButton("Recharger", { model.loadCatalog(force = true); model.toast = "Rechargement du catalogue…" }, icon = Icons.Filled.Refresh)
                    AppButton("Changer d'abonnement", { model.open(Screen.Login(iptvOnly = true)) }, icon = Icons.Filled.SwapHoriz)
                }
            }
            item {
                Section("Compte du foyer", "Connecté au compte ${model.cloud.email}. Profils, favoris, reprises et langues " +
                    "sont partagés avec les autres appareils du foyer.") {
                    AppButton("Se déconnecter", { confirmSignOut = true }, icon = Icons.AutoMirrored.Filled.Logout)
                }
            }
            item {
                Section("Application", "IPTV Player (téléphone) · version ${BuildConfig.VERSION_NAME}. Les nouvelles versions " +
                    "sont proposées au lancement de l'appli. Téléchargements : dossier Films/IPTV Player du téléphone.") {
                    AppButton("Rechercher une mise à jour", { model.checkUpdate(silent = false) }, icon = Icons.Filled.SystemUpdate)
                }
            }
        }
    }
    fun name(pid: String) = model.profiles.firstOrNull { it["id"] == pid }?.get("name").str()
    menuFor?.let { p ->
        val pid = p["id"].str()
        ActionsSheet(p["name"].str(), buildList {
            add(Action("Modifier le nom et la couleur", Icons.Outlined.Edit) { editing = pid to p })
            add(Action("Réinitialiser (favoris, reprises…)", Icons.Outlined.RestartAlt) { resetPid = pid })
            if (model.profiles.size > 1) add(Action("Supprimer le profil", Icons.Outlined.Delete) { deletePid = pid })
        }, onDismiss = { menuFor = null })
    }
    if (confirmSignOut) ConfirmDialog("Se déconnecter",
        "Déconnecter le téléphone du compte du foyer ? Il faudra se reconnecter pour continuer à utiliser l'appli. " +
            "Les fichiers téléchargés restent sur le téléphone.",
        "Se déconnecter", onOk = { confirmSignOut = false; model.signOut() }, onDismiss = { confirmSignOut = false })
    resetPid?.let { pid ->
        ConfirmDialog("Réinitialiser « ${name(pid)} »",
            "Effacer les favoris, lectures en cours, « Vu récemment », épisodes vus et choix de langue de ce profil, sur " +
                "tous les appareils ? À faire après un changement de fournisseur IPTV. Le profil est conservé.",
            "Réinitialiser", onOk = { resetPid = null; model.resetProfile(pid) }, onDismiss = { resetPid = null })
    }
    deletePid?.let { pid ->
        ConfirmDialog("Supprimer « ${name(pid)} »",
            "Supprimer ce profil, ses favoris, lectures en cours et « Vu récemment », sur tous les appareils du foyer ? " +
                "Les autres profils ne sont pas touchés.",
            "Supprimer", onOk = { deletePid = null; model.deleteProfile(pid) }, onDismiss = { deletePid = null })
    }
    editing?.let { (pid, p) ->
        ProfileDialog(p?.get("name").str(), p?.get("color").str().ifEmpty {
            Store.PROFILE_COLORS.firstOrNull { c -> model.profiles.none { it["color"] == c } } ?: Store.PROFILE_COLORS[0]
        }, isNew = pid == null, onSave = { n, c ->
            editing = null
            if (pid == null) model.createProfile(n, c) else model.editProfile(pid, n, c)
        }, onDismiss = { editing = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, text: String, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(C.surface, RoundedCornerShape(14.dp))
        .border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(16.dp)) {
        Text(title, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(text, color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) { actions() }
    }
}

/** Nouveau profil / modifier un profil : nom et couleur. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileDialog(name: String, color: String, isNew: Boolean, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var n by remember { mutableStateOf(name) }
    var c by remember { mutableStateOf(color) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.panel,
        title = { Text(if (isNew) "Nouveau profil" else "Modifier le profil", color = C.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(n.ifEmpty { "?" }, c, 48.dp)
                    OutlinedTextField(n, { n = it.take(24) }, Modifier.weight(1f).padding(start = 12.dp), label = { Text("Prénom") },
                        singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent,
                            unfocusedBorderColor = C.line, focusedLabelColor = C.accentHi, cursorColor = C.accentHi))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Store.PROFILE_COLORS.forEach { col ->
                        Box(Modifier.size(36.dp).background(parseColor(col), CircleShape)
                            .border(if (col == c) 3.dp else 0.dp, Color.White, CircleShape).clickable { c = col })
                    }
                }
                if (isNew) Text("Le nouveau profil reprend l'abonnement IPTV du profil en cours.", color = C.muted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton({ if (n.isNotBlank()) onSave(n.trim(), c) }, enabled = n.isNotBlank()) {
                Text(if (isNew) "Créer" else "Enregistrer", color = C.accentHi, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Annuler", color = C.text) } },
    )
}
