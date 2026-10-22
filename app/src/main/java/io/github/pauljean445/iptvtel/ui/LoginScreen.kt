package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.LoginResult
import io.github.pauljean445.iptvtel.R
import kotlinx.coroutines.launch

/**
 * Connexion : compte du foyer (le même que sur le PC et la TV), puis abonnement IPTV seulement si le compte n'en a
 * pas encore (il y est stocké chiffré). iptvOnly : changer d'abonnement (Réglages).
 */
@Composable
fun LoginScreen(model: AppModel, notice: String, iptvOnly: Boolean) {
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf(if (iptvOnly) "iptv" else "account") }
    var info by rememberSaveable { mutableStateOf(notice) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf(model.cloud.email) }
    var password by remember { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf("xtream") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var iptvPassword by remember { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }

    fun handle(r: LoginResult) {
        busy = false
        when (r) {
            is LoginResult.Error -> error = r.message
            is LoginResult.AskIptv -> { step = "iptv"; info = r.message; error = "" }
            LoginResult.Done -> Unit
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppLogo(72)
        Text(if (step == "account") "Compte du foyer" else "Abonnement IPTV", color = C.text, fontSize = 24.sp,
            fontWeight = FontWeight.Bold)
        Text(if (step == "account") "Le même compte que sur le PC et la TV : profils, favoris et reprises sont partagés."
            else "Identifiants fournis par ton fournisseur IPTV. Vérifiés puis enregistrés chiffrés dans le compte du foyer.",
            color = C.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (info.isNotEmpty()) Text(info, color = C.accentHi, fontSize = 14.sp, textAlign = TextAlign.Center)

        if (step == "account") {
            Field(email, { email = it }, "Email", KeyboardType.Email)
            Field(password, { password = it }, "Mot de passe", KeyboardType.Password, secret = true)
            AppButton(if (busy) "Connexion…" else "Se connecter", {
                busy = true; error = ""
                scope.launch { handle(model.login(email, password)) }
            }, Modifier.fillMaxWidth(), primary = true, enabled = !busy)
            Text("Le mot de passe n'est pas enregistré : le téléphone garde seulement une session chiffrée.",
                color = C.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("xtream" to "Xtream Codes", "url" to "Lien M3U").forEachIndexed { i, (key, label) ->
                    SegmentedButton(selected = mode == key, onClick = { mode = key },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = C.accent, activeContentColor = androidx.compose.ui.graphics.Color.White,
                            inactiveContainerColor = C.surface, inactiveContentColor = C.muted)) { Text(label) }
                }
            }
            if (mode == "xtream") {
                Field(server, { server = it }, "Serveur (http://exemple.com:8080)", KeyboardType.Uri)
                Field(user, { user = it }, "Nom d'utilisateur", KeyboardType.Text)
                Field(iptvPassword, { iptvPassword = it }, "Mot de passe IPTV", KeyboardType.Password, secret = true)
            } else {
                Field(url, { url = it }, "Lien de la playlist M3U", KeyboardType.Uri)
            }
            AppButton(if (busy) "Vérification…" else "Valider", {
                busy = true; error = ""
                scope.launch { handle(model.setIptv(mode, server, user, iptvPassword, url)) }
            }, Modifier.fillMaxWidth(), primary = true, enabled = !busy)
            if (iptvOnly) AppButton("Annuler", { model.back() }, Modifier.fillMaxWidth())
        }
        if (error.isNotEmpty()) Text(error, color = C.live, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType, secret: Boolean = false) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.accent, unfocusedBorderColor = C.line,
            focusedLabelColor = C.accentHi, unfocusedLabelColor = C.muted, cursorColor = C.accentHi,
            focusedContainerColor = C.panel, unfocusedContainerColor = C.panel))
}

/** Logo de l'appli (calques de l'icône : dégradé « nuit » + logo). */
@Composable
fun AppLogo(size: Int) {
    androidx.compose.foundation.layout.Box(Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp))
        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(
            androidx.compose.ui.graphics.Color(0xFF1E1846), androidx.compose.ui.graphics.Color(0xFF0B0820))))) {
        // Calque de l'icône adaptative : 108 dp dont 72 visibles au centre
        Image(painterResource(R.mipmap.ic_launcher_fg), contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f))
    }
}
