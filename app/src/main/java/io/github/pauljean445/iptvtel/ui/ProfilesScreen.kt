package io.github.pauljean445.iptvtel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.data.str

/** « Qui regarde ? » : choix du profil au démarrage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(model: AppModel) {
    Column(Modifier.fillMaxSize().background(C.bg).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Qui regarde ?", color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        FlowRow(Modifier.padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            for (p in model.profiles) {
                val id = p["id"].str()
                Column(Modifier.width(110.dp).clip(RoundedCornerShape(16.dp)).clickable { model.openProfile(id) }.padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(p["name"].str(), p["color"].str(), 88.dp)
                    Text(p["name"].str(), color = C.text, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        // Se souvenir du choix : au prochain démarrage, le profil choisi s'ouvre directement (modifiable dans Réglages)
        Row(Modifier.padding(top = 36.dp).clip(RoundedCornerShape(12.dp)).clickable { model.setAskProfile(!model.askProfile) }
            .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = !model.askProfile, onCheckedChange = { model.setAskProfile(!it) },
                colors = SwitchDefaults.colors(checkedThumbColor = androidx.compose.ui.graphics.Color.White, checkedTrackColor = C.accent,
                        uncheckedThumbColor = C.text, uncheckedTrackColor = C.surface2, uncheckedBorderColor = C.muted))
            Text("Ouvrir directement ce profil la prochaine fois", color = C.muted, fontSize = 14.sp,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}
