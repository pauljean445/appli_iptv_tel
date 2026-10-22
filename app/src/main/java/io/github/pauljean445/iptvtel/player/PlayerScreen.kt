package io.github.pauljean445.iptvtel.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.pauljean445.iptvtel.AppModel
import io.github.pauljean445.iptvtel.data.Item
import io.github.pauljean445.iptvtel.data.Library
import io.github.pauljean445.iptvtel.data.XtreamClient
import io.github.pauljean445.iptvtel.ui.AppButton
import io.github.pauljean445.iptvtel.ui.C
import io.github.pauljean445.iptvtel.ui.fmtTime
import kotlinx.coroutines.delay

private val RESUMABLE = setOf("movie", "episode")
private const val MAX_RETRIES = 5
private const val EARLY_END_MS = 120_000L

/**
 * Lecteur plein écran (Media3), en paysage. Toucher : affiche / masque les commandes ; double toucher à gauche /
 * à droite : -10 s / +10 s. Direct : chaîne précédente / suivante, pause = différé (comme sur PC et TV).
 * Position enregistrée toutes les 10 s (reprise synchronisée) ; fichiers téléchargés lus depuis le téléphone.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(model: AppModel, start: Item, list: List<Item>, resume: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    val lib = model.library
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(Track.mediaSourceFactory(context))
            .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(MC.USAGE_MEDIA)
                .setContentType(MC.AUDIO_CONTENT_TYPE_MOVIE).build(), true)  // pause si un appel / une autre appli
            .setHandleAudioBecomingNoisy(true)  // pause si les écouteurs sont débranchés
            .build()
    }
    var current by remember { mutableStateOf(start) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var pos by remember { mutableLongStateOf(0L) }
    var len by remember { mutableLongStateOf(0L) }
    var offsetMin by remember { mutableIntStateOf(0) }        // replay : le flux commence à cette minute du programme
    var overlay by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tracksPanel by remember { mutableStateOf(false) }
    var nextCountdown by remember { mutableIntStateOf(-1) }
    var retries by remember { mutableIntStateOf(0) }
    var prefsApplied by remember { mutableStateOf(false) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var liveDelayMs by remember { mutableLongStateOf(0L) }    // direct : retard accumulé par les pauses
    var pausedAt by remember { mutableLongStateOf(0L) }       // direct : début de la pause en cours
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var seekFlash by remember { mutableStateOf("") }          // « -10 s » / « +10 s » affiché un instant
    var castPicker by remember { mutableStateOf(false) }

    val isReplay = current.kind == "program"
    val isLive = current.kind == "live"
    fun prefKey(it: Item) = if (it.kind == "episode") it.parent?.key ?: it.key else it.key

    fun save(finished: Boolean? = null) {
        val it = current
        if (it.kind !in RESUMABLE || pos <= 0) return
        model.saveProgress(it, pos, len, finished ?: Library.isFinished(pos, len))
    }

    fun play(item: Item, startMs: Long, replayMinute: Int = 0) {
        current = item; error = ""; buffering = true; prefsApplied = false; nextCountdown = -1
        liveDelayMs = 0; pausedAt = 0
        offsetMin = replayMinute
        pos = startMs; len = if (item.kind == "program") (item.end - item.start) * 1000 else 0
        // Film / épisode téléchargé : lu depuis le téléphone (sans réseau, sans occuper de connexion IPTV)
        val url = model.localUri(item.key) ?: if (item.kind == "program" && replayMinute > 0)
            XtreamClient.shiftTimeshiftUrl(item.url, replayMinute) ?: item.url else item.url
        // Langue / sous-titres mémorisés : le lecteur part sur la bonne langue ; la piste exacte suit (applyPrefs)
        val params = player.trackSelectionParameters.buildUpon().clearOverrides()
            .setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, false).setPreferredAudioLanguage(null).setPreferredTextLanguage(null)
        if (item.kind in RESUMABLE) lib.pref(prefKey(item))?.let { pref ->
            (pref["audio"] as? Map<*, *>)?.get("lang")?.toString()?.takeIf { it.isNotEmpty() }
                ?.let { params.setPreferredAudioLanguage(Track.playerLang(it)) }
            when (val spu = pref["spu"]) {
                "off" -> params.setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true)
                is Map<*, *> -> spu["lang"]?.toString()?.takeIf { it.isNotEmpty() }?.let { params.setPreferredTextLanguage(Track.playerLang(it)) }
            }
        }
        player.trackSelectionParameters = params.build()
        startedAt = System.currentTimeMillis()
        player.setMediaItem(MediaItem.fromUri(url), if (startMs > 0 && item.kind in RESUMABLE) startMs else 0L)
        player.prepare()
        player.play()
    }

    fun nextItem(): Item? = when (current.kind) {
        "episode" -> list.getOrNull(list.indexOfFirst { it.key == current.key } + 1)
        "program" -> list.filter { it.start > current.start }.minByOrNull { it.start }
        else -> null
    }

    /** Impose la langue / les sous-titres mémorisés, dès que les pistes sont connues (15 premières secondes) ;
     *  ensuite on n'y touche plus (un choix fait pendant la lecture est respecté). */
    fun applyPrefs() {
        if (prefsApplied || current.kind !in RESUMABLE) return
        if (System.currentTimeMillis() - startedAt > 15_000) { prefsApplied = true; return }
        val pref = lib.pref(prefKey(current)) ?: run { prefsApplied = true; return }
        val list = Track.of(player.currentTracks)
        if (list.none { it.kind == "audio" }) return
        val (selAudio, selSpu) = Track.selected(player.currentTracks)
        val audio = Track.match(list, "audio", pref["audio"])?.takeIf { it >= 0 }
        val spu = Track.match(list, "spu", pref["spu"])
        if (audio != null && selAudio != audio) Track.select(player, "audio", audio)
        if (spu != null && selSpu != spu) Track.select(player, "spu", spu)
        prefsApplied = true
    }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying) {
                    retries = 0
                    if (pausedAt > 0) { liveDelayMs += System.currentTimeMillis() - pausedAt; pausedAt = 0 }
                } else if (current.kind == "live" && player.playbackState == Player.STATE_READY && !player.playWhenReady) {
                    pausedAt = System.currentTimeMillis()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_ENDED) {
                    if (current.kind == "live") { play(current, 0); return }  // flux coupé : reconnexion
                    // Fin bien avant la fin réelle : flux coupé par le fournisseur (ex. connexions simultanées
                    // dépassées), pas la fin du film / de l'épisode -> reconnexion à la position atteinte
                    if (current.kind in RESUMABLE && len > 0 && pos < len - EARLY_END_MS && retries < MAX_RETRIES) {
                        retries++; play(current, pos); return
                    }
                    if (current.kind in RESUMABLE) { pos = maxOf(pos, len); save(finished = true) }
                    if (nextItem() != null) nextCountdown = 5 else onExit()
                }
            }

            override fun onTracksChanged(t: Tracks) { tracks = t; applyPrefs() }

            override fun onPlayerError(e: PlaybackException) {
                if (retries < MAX_RETRIES) {  // coupure : reconnexion, à la position atteinte
                    retries++
                    play(current, if (current.kind in RESUMABLE) pos else 0, offsetMin)
                } else error = "Lecture impossible : ${describe(e)}"
            }
        }
        player.addListener(listener)
        val startMs = if (resume && start.kind in RESUMABLE) lib.resumePosition(start.key) else 0L
        play(start, startMs)
        // Plein écran en paysage, barres d'Android masquées (un glissement depuis le bord les fait revenir)
        val activity = context as Activity
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Image jusque dans la zone de l'encoche de la caméra (paysage)
        val cutout = activity.window.attributes.layoutInDisplayCutoutMode
        activity.window.attributes = activity.window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity.window.attributes = activity.window.attributes.apply { layoutInDisplayCutoutMode = cutout }
            save()
            player.removeListener(listener)
            player.release()
            bars.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Position, sauvegarde toutes les 10 s, masquage des commandes après 4 s sans toucher l'écran
    LaunchedEffect(Unit) {
        var lastSave = System.currentTimeMillis()
        while (true) {
            delay(500)
            if (playing) {
                pos = if (isReplayItem(current)) offsetMin * 60_000L + player.currentPosition else player.currentPosition
                if (!isReplayItem(current) && player.duration != MC.TIME_UNSET && player.duration > 0) len = player.duration
            }
            if (System.currentTimeMillis() - lastSave > 10_000 && playing) { save(); lastSave = System.currentTimeMillis() }
            if (overlay && playing && System.currentTimeMillis() - lastTouch > 4000 && !tracksPanel) overlay = false
        }
    }
    LaunchedEffect(nextCountdown) {
        if (nextCountdown > 0) { delay(1000); nextCountdown-- }
        else if (nextCountdown == 0) nextItem()?.let { n ->
            play(n, if (n.kind in RESUMABLE) lib.resumePosition(n.key) else 0)
        }
    }
    // Plein écran réappliqué : après la rotation et à chaque apparition des commandes (Android peut remettre la
    // barre d'état, qui gênait les boutons du haut)
    LaunchedEffect(overlay, tracksPanel, castPicker) {
        val activity = context as Activity
        repeat(2) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            delay(400)
        }
    }
    LaunchedEffect(seekFlash) { if (seekFlash.isNotEmpty()) { delay(700); seekFlash = "" } }

    /** Va à la position target (ms). Replay : à la minute (le serveur découpe les enregistrements par minute). */
    fun seekTo(target: Long) {
        if (isReplay) {
            val minute = (target / 60_000).toInt().coerceIn(0, ((len / 60_000) - 1).toInt().coerceAtLeast(0))
            play(current, 0, minute)
        } else if (!isLive && len > 0) {
            player.seekTo(target.coerceIn(0, len - 1000))
            pos = player.currentPosition
        }
    }

    fun seek(deltaMs: Long) {
        if (isLive) return
        seekTo(pos + deltaMs)
        seekFlash = (if (deltaMs < 0) "−" else "+") + if (isReplay) "${kotlin.math.abs(deltaMs) / 60_000} min" else "${kotlin.math.abs(deltaMs) / 1000} s"
    }

    fun zap(step: Int) {
        val i = list.indexOfFirst { it.key == current.key }
        if (i < 0 || list.size < 2) return
        play(list[(i + step + list.size) % list.size], 0)
    }

    fun togglePause() { if (player.isPlaying) player.pause() else { if (player.playbackState == Player.STATE_IDLE) player.prepare(); player.play() } }
    val step = if (isReplay) 60_000L else 10_000L

    Box(Modifier.fillMaxSize().background(Color.Black)
        .pointerInput(isLive, isReplay) {
            detectTapGestures(
                onTap = { overlay = !overlay; lastTouch = System.currentTimeMillis() },
                onDoubleTap = { o -> if (!isLive) seek(if (o.x < size.width / 2) -step else step) else overlay = !overlay },
            )
        }) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                this.player = player
            }
        }, modifier = Modifier.fillMaxSize())

        if (buffering && error.isEmpty()) CircularProgressIndicator(color = C.accentHi, modifier = Modifier.align(Alignment.Center))
        if (seekFlash.isNotEmpty()) Text(seekFlash, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(12.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp))
        if (error.isNotEmpty()) Column(Modifier.align(Alignment.Center).background(Color(0xCC100D22), RoundedCornerShape(16.dp))
            .padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error, color = C.live, fontSize = 16.sp)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Réessayer", { retries = 0; play(current, pos, offsetMin) }, primary = true)
                AppButton("Quitter", onExit)
            }
        }

        AnimatedVisibility(overlay && error.isEmpty(), enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                // Haut : retour, titre, langue et sous-titres
                Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        val title = if (current.parent != null && current.kind == "episode")
                            "${current.parent!!.name} · ${current.episodeCode}" else current.name
                        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (current.kind == "episode") Text(current.name, color = C.muted, fontSize = 13.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        if (isLive) {
                            val delayed = liveDelayMs + if (pausedAt > 0) System.currentTimeMillis() - pausedAt else 0
                            Text(if (delayed > 1000) "● Différé : ${fmtDelay(delayed)}" else "● En direct",
                                color = if (delayed > 1000) C.accentHi else C.live, fontSize = 13.sp)
                        }
                    }
                    if (isLive && (liveDelayMs > 1000 || pausedAt > 0)) AppButton("Revenir au direct", { play(current, 0) },
                        icon = Icons.Filled.LiveTv, modifier = Modifier.padding(end = 8.dp))
                    if (io.github.pauljean445.iptvtel.cast.Caster.available) IconButton({ castPicker = true; lastTouch = System.currentTimeMillis() }) {
                        Icon(Icons.Filled.Cast, "Diffuser sur un appareil Cast", tint = Color.White)
                    }
                    IconButton({ tracksPanel = true; lastTouch = System.currentTimeMillis() }) {
                        Icon(Icons.Filled.Subtitles, "Langue et sous-titres", tint = Color.White)
                    }
                }
                // Centre : précédent / -10 s, lecture / pause, +10 s / suivant
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (isLive) {
                        if (list.size > 1) RoundButton(Icons.Filled.SkipPrevious, "Chaîne précédente", 52) { zap(-1); lastTouch = System.currentTimeMillis() }
                    } else RoundButton(Icons.Filled.Replay10, "Reculer", 52) { seek(-step); lastTouch = System.currentTimeMillis() }
                    RoundButton(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Lecture", 72) {
                        togglePause(); lastTouch = System.currentTimeMillis()
                    }
                    if (isLive) {
                        if (list.size > 1) RoundButton(Icons.Filled.SkipNext, "Chaîne suivante", 52) { zap(1); lastTouch = System.currentTimeMillis() }
                    } else RoundButton(Icons.Filled.Forward10, "Avancer", 52) { seek(step); lastTouch = System.currentTimeMillis() }
                }
                // Bas : barre de temps, épisode / programme suivant
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 12.dp, top = 24.dp)) {
                    if (!isLive && len > 0) SeekBar(pos, len, onSeek = { seekTo(it) }, onTouch = { lastTouch = System.currentTimeMillis() })
                    nextItem()?.let { n ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            AppButton(if (n.kind == "episode") "Épisode suivant · ${n.episodeCode}" else "Programme suivant",
                                { save(); play(n, if (n.kind in RESUMABLE) lib.resumePosition(n.key) else 0) }, icon = Icons.Filled.SkipNext)
                        }
                    }
                }
            }
        }

        if (nextCountdown > 0) Column(Modifier.align(Alignment.Center).background(C.panel, RoundedCornerShape(16.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${if (current.kind == "episode") "Épisode" else "Programme"} suivant dans $nextCountdown s", color = C.text, fontSize = 18.sp)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppButton("Annuler", { nextCountdown = -1 })
                AppButton("Lire maintenant", { nextCountdown = 0 }, primary = true)
            }
        }
    }

    if (castPicker) io.github.pauljean445.iptvtel.ui.CastPicker(onPick = { route ->
        castPicker = false
        save()
        player.pause()
        model.startCast(route, current, list, if (current.kind in RESUMABLE) pos else 0)
    }, onDismiss = { castPicker = false; lastTouch = System.currentTimeMillis() })

    if (tracksPanel) TracksSheet(tracks, onPick = { kind, track ->
        Track.select(player, kind, track?.id ?: -1)
        if (current.kind in RESUMABLE) lib.setPref(prefKey(current), kind, track?.pref() ?: "off")
    }, onDismiss = { tracksPanel = false; lastTouch = System.currentTimeMillis() })
}

private fun isReplayItem(it: Item) = it.kind == "program"

private fun describe(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
        "le flux ne répond pas."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "le fournisseur refuse le flux (connexions simultanées dépassées ?)."
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
        "format vidéo non pris en charge par le téléphone."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
        "fichier illisible ou format non pris en charge."
    else -> e.errorCodeName.lowercase().removePrefix("error_code_").replace('_', ' ') + "."
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, size: Int, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).background(Color(0x55000000), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size((size * 0.6f).dp))
    }
}

/** Barre de temps : on fait glisser le curseur, la lecture va à la position au relâchement. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(pos: Long, len: Long, onSeek: (Long) -> Unit, onTouch: () -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) (value * len).toLong() else pos
    Column {
        Slider(
            value = if (dragging) value else (pos.toFloat() / len).coerceIn(0f, 1f),
            onValueChange = { dragging = true; value = it; onTouch() },
            onValueChangeFinished = { onSeek((value * len).toLong()); dragging = false },
            colors = SliderDefaults.colors(thumbColor = C.accentHi, activeTrackColor = C.accentHi, inactiveTrackColor = Color(0x55FFFFFF)),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmtTime(shown), color = if (dragging) C.accentHi else Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(fmtTime(len), color = C.muted, fontSize = 13.sp)
        }
    }
}

/** Langue et sous-titres pendant la lecture ; le choix est appliqué et mémorisé aussitôt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TracksSheet(tracks: Tracks, onPick: (String, Track?) -> Unit, onDismiss: () -> Unit) {
    val list = remember(tracks) { Track.of(tracks) }
    val (selAudio, selSpu) = remember(tracks) { Track.selected(tracks) }
    var audioId by remember(tracks) { mutableStateOf(selAudio) }
    var spuId by remember(tracks) { mutableIntStateOf(selSpu) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = C.panel,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Langue", color = C.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                val audio = list.filter { it.kind == "audio" }
                if (audio.size <= 1) Text("Une seule piste", color = C.muted, fontSize = 13.sp)
                audio.forEach { t -> TrackRow(t.label, audioId == t.id, t.supported) { audioId = t.id; onPick("audio", t) } }
            }
            Column(Modifier.weight(1f)) {
                Text("Sous-titres", color = C.muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                TrackRow("Aucun", spuId == -1, true) { spuId = -1; onPick("spu", null) }
                list.filter { it.kind == "spu" }.forEach { t -> TrackRow(t.label, spuId == t.id, t.supported) { spuId = t.id; onPick("spu", t) } }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(28.dp)) { if (selected) Icon(Icons.Filled.Check, null, tint = C.accentHi, modifier = Modifier.size(20.dp)) }
        Text(label, color = if (!enabled) C.muted else if (selected) C.accentHi else C.text, fontSize = 15.sp)
    }
}

private fun fmtDelay(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60} min %02d s".format(s % 60) else "$s s"
}
