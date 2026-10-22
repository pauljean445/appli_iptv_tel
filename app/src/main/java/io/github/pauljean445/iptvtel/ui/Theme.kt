package io.github.pauljean445.iptvtel.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Couleurs des applis PC et TV (logo « Néon ») : nuit profonde et violet lumineux. */
object C {
    val bg = Color(0xFF100D22)        // fond des pages
    val panel = Color(0xFF15122B)     // fond des panneaux, barre du bas
    val surface = Color(0xFF1B1735)   // blocs, cartes
    val surface2 = Color(0xFF262046)  // emplacement des images, boutons secondaires
    val line = Color(0xFF2F2959)
    val text = Color(0xFFEEEBFF)
    val muted = Color(0xFFA6A0CC)
    val accent = Color(0xFF7B66FF)
    val accentHi = Color(0xFF9D8CFF)
    val live = Color(0xFFFF5470)      // pastille « En direct », erreurs
}

@Composable
fun IptvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent,
            onPrimary = Color.White,
            primaryContainer = C.accent,
            onPrimaryContainer = Color.White,
            secondary = C.accentHi,
            onSecondary = Color.White,
            secondaryContainer = C.surface2,
            onSecondaryContainer = C.text,
            background = C.bg,
            onBackground = C.text,
            surface = C.bg,
            onSurface = C.text,
            surfaceVariant = C.surface2,
            onSurfaceVariant = C.muted,
            surfaceContainer = C.panel,
            surfaceContainerLow = C.panel,
            surfaceContainerHigh = C.surface,
            surfaceContainerHighest = C.surface2,
            surfaceContainerLowest = C.bg,
            outline = C.line,
            outlineVariant = C.line,
            error = C.live,
            onError = Color.White,
        ),
        content = content,
    )
}
