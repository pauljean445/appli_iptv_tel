package io.github.pauljean445.iptvtel.cast

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.pauljean445.iptvtel.data.Http
import io.github.pauljean445.iptvtel.data.Item
import io.github.pauljean445.iptvtel.data.XtreamClient
import io.github.pauljean445.iptvtel.player.Track
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import java.text.Normalizer

/** Appareil Google Cast trouvé sur le Wi-Fi (Chromecast, TV « Chromecast intégré »). */
data class CastTarget(val name: String, val ip: String, val port: Int, val model: String)

/**
 * Diffusion vers un appareil Cast, comme l'appli PC (cast.py) : VLC lit le flux sur le téléphone et l'envoie à
 * l'appareil par son module Chromecast, en convertissant ce que l'appareil ne sait pas lire (son Dolby / MP2 des
 * films et chaînes -> AAC). L'appareil reçoit un flux continu : pause et déplacements se font ici, sans rechargement
 * de son côté (réactif). Seules les pistes choisies partent (langue) ; sous-titres choisis : incrustés dans l'image.
 * Le téléphone doit rester sur le même Wi-Fi (CastService le garde éveillé écran éteint).
 */
object Caster {
    private const val TAG = "IPTV"
    private const val SEEK_GROUP_MS = 900L   // clics rapprochés sur ±10 s : un seul déplacement (comme le PC)
    private const val MAX_RETRIES = 3

    // Réglages repris de cast.py (appli PC)
    private const val AUDIO_AAC = "acodec=mp4a,ab=192,channels=2,samplerate=48000"
    private const val VIDEO_H264_720 = "vcodec=h264,vb=3500,height=720"

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val libVlc: LibVLC by lazy { LibVLC(app, arrayListOf("--http-reconnect", "--no-stats")) }
    private var player: MediaPlayer? = null

    // ---- État (lu par les écrans) ------------------------------------------------- //
    val targets = mutableStateListOf<CastTarget>()
    var target: CastTarget? = null; private set
    val device: String get() = target?.name.orEmpty()
    var connecting by mutableStateOf(false); private set    // flux lancé, pas encore lu par l'appareil
    var item by mutableStateOf<Item?>(null); private set
    var list: List<Item> = emptyList(); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var pos by mutableLongStateOf(0L); private set
    var len by mutableLongStateOf(0L); private set
    var error by mutableStateOf("")
    /** Pistes du flux (langue / sous-titres) et pistes envoyées ; changent à chaque lancement. */
    var tracks by mutableStateOf<List<Track>>(emptyList()); private set
    var audioId by mutableStateOf(-1); private set
    var spuId by mutableStateOf(-1); private set
    /** Mode compatible (image convertie) pour les chaînes qui ne passent pas, comme sur PC. */
    private val compat = HashSet<String>()

    var onFinished: (() -> Unit)? = null
    /** Choix de langue / sous-titres mémorisés pour l'entrée diffusée (film, série) : {"audio", "spu"}. */
    var prefsFor: ((Item) -> Map<String, Any?>?)? = null

    val available = true

    private var url = ""
    private var offsetMin = 0          // replay : le flux commence à cette minute du programme
    private var startedAt = 0L
    private var startPos = 0L
    private var retries = 0
    private var exactDone = false      // piste audio exacte (VFF / VFQ…) vérifiée pour ce lancement
    private var pendingSeek: Long? = null
    private var chosenAudio: Int? = null
    private var chosenSpu: Int? = null

    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    // ---- Recherche des appareils (mDNS « _googlecast._tcp », service d'Android) ----------- //
    private var nsd: NsdManager? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun startScan() {
        if (discovery != null) return
        val m = app.getSystemService(NsdManager::class.java) ?: return
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "recherche Cast : $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post { toResolve.addLast(info); resolveNext() }.let { }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        discovery = l
        try { m.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { Log.w(TAG, "recherche Cast", e) }
    }

    fun stopScan() {
        discovery?.let { try { nsd?.stopServiceDiscovery(it) } catch (e: Exception) { } }
        discovery = null
        toResolve.clear()
    }

    @Suppress("DEPRECATION")  // resolveService : simple et suffisant (un appareil à la fois)
    private fun resolveNext() {
        if (resolving) return
        val info = toResolve.removeFirstOrNull() ?: return
        val m = nsd ?: return
        resolving = true
        m.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { main.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val attrs = info.attributes
                fun attr(k: String) = attrs[k]?.let { String(it, Charsets.UTF_8) }.orEmpty()
                val ip = info.host?.hostAddress.orEmpty()
                val t = CastTarget(attr("fn").ifEmpty { info.serviceName }, ip, info.port, attr("md"))
                main.post {
                    resolving = false
                    if (ip.isNotEmpty() && ':' !in ip && targets.none { it.ip == t.ip }) targets += t
                    resolveNext()
                }
            }
        })
    }

    // ---- Diffusion ----------------------------------------------------------------- //
    /** Diffuse item sur l'appareil, à partir de startMs. */
    fun cast(t: CastTarget, it: Item, url: String, startMs: Long, items: List<Item>) {
        target = t
        list = items
        chosenAudio = null; chosenSpu = null
        CastService.start(app)
        load(it, url, startMs)
    }

    /** Lance une autre entrée sur le même appareil (épisode suivant, autre chaîne). */
    fun load(it: Item, url: String, startMs: Long) {
        // Autre film / épisode / programme : nouvelle connexion à l'appareil (comme le PC). Il relance brièvement son
        // lecteur, mais VLC attend qu'il soit prêt avant d'envoyer : l'épisode démarre à son vrai début. En gardant
        // la connexion, VLC envoyait pendant le rechargement de l'appareil, qui ratait ~2 s (épisode à « 0:02 »).
        // Chaîne en direct : connexion gardée (zapping sans relance, le début importe peu).
        if (item != null && item?.key != it.key && it.kind != "live") stopPlayer()
        if (item?.key != it.key) { chosenAudio = null; chosenSpu = null }
        item = it
        this.url = url
        retries = 0
        error = ""
        play(if (it.kind == "live") 0 else startMs, if (it.kind == "program") (startMs / 60_000).toInt() else 0)
    }

    private fun play(startMs: Long, replayMinute: Int) {
        val t = target ?: return
        val it = item ?: return
        main.removeCallbacks(tick)
        main.removeCallbacks(applySeek)
        pendingSeek = null
        offsetMin = replayMinute
        pos = startMs; len = if (it.kind == "program") (it.end - it.start) * 1000 else 0
        connecting = true; playing = false; buffering = true; exactDone = false
        val src = if (it.kind == "program" && replayMinute > 0) XtreamClient.shiftTimeshiftUrl(url, replayMinute) ?: url else url
        val media = Media(libVlc, Uri.parse(src))
        media.addOption(":http-user-agent=${Http.USER_AGENT}")
        // Réserve de VLC : l'appareil a la sienne ; direct : 3 s (stabilité)
        media.addOption(":network-caching=" + if (it.kind == "live") 3000 else 1000)
        if (startMs > 0 && it.kind != "program" && it.kind != "live") media.addOption(":start-time=${startMs / 1000.0}")
        // Pistes : choix fait pendant cette diffusion, sinon langue mémorisée pour le film / la série
        val pref = if (it.kind == "movie" || it.kind == "episode") prefsFor?.invoke(it) else null
        chosenAudio?.let { id -> media.addOption(":audio-track-id=$id") } ?: (pref?.get("audio") as? Map<*, *>)?.get("lang")
            ?.toString()?.takeIf { l -> l.isNotEmpty() }?.let { l -> media.addOption(":audio-language=$l") }
        val spuPref = pref?.get("spu")
        val burn = when {
            chosenSpu != null -> chosenSpu!! >= 0
            spuPref is Map<*, *> -> true
            else -> false
        }
        when {
            chosenSpu != null && chosenSpu!! >= 0 -> media.addOption(":sub-track-id=$chosenSpu")
            burn -> (spuPref as Map<*, *>)["lang"]?.toString()?.takeIf { l -> l.isNotEmpty() }?.let { l -> media.addOption(":sub-language=$l") }
            else -> media.addOption(":sub-language=none")  // sinon VLC remet la piste par défaut (souvent « Forcés »)
        }
        val live = it.kind == "live" || it.kind == "program"
        val convertVideo = live && it.key in compat
        var chain = "chromecast{ip=${t.ip},port=${t.port}}"
        if (burn || convertVideo) chain = "transcode{$VIDEO_H264_720${if (convertVideo) ",deinterlace" else ""}${if (burn) ",soverlay" else ""},$AUDIO_AAC}:$chain"
        else if (live) chain = "transcode{$AUDIO_AAC}:$chain"  // direct / replay : MP2 / E-AC3 illisibles par l'appareil
        media.addOption(":sout=#$chain")
        // Seules les pistes choisies partent vers l'appareil (sinon il lit la première : langue ignorée)
        media.addOption(":no-sout-all")
        media.addOption(":demux-filter=demux_chromecast")
        media.addOption(":sout-keep")
        if (!burn) media.addOption(":no-sout-spu")  // pistes de sous-titres ponctuelles : coupaient l'envoi
        // Même lecteur pour un changement de langue ou de chaîne : avec « sout-keep », VLC garde la connexion à
        // l'appareil, qui ne quitte pas sa lecture (refaite seulement si la chaîne d'envoi change : sous-titres
        // incrustés, mode compatible). Autre film / épisode : nouveau lecteur, voir load.
        val p = player ?: MediaPlayer(libVlc).also { np ->
            player = np
            np.setEventListener { ev -> main.post { onEvent(np, ev) } }
        }
        p.media = media
        media.release()
        startedAt = System.currentTimeMillis()
        startPos = startMs
        p.play()
        main.removeCallbacks(tick)
        main.post(tick)
        CastService.update(app)
    }

    private fun onEvent(p: MediaPlayer, ev: MediaPlayer.Event) {
        if (p !== player) return
        when (ev.type) {
            MediaPlayer.Event.Playing -> {
                connecting = false; playing = true; buffering = false
                refreshTracks(); CastService.update(app)
            }
            MediaPlayer.Event.Paused -> { playing = false; CastService.update(app) }
            MediaPlayer.Event.Buffering -> buffering = ev.buffering < 100f
            MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected -> refreshTracks()
            // Fin / erreur juste après un changement de flux : celle de l'ancien flux, ignorée
            MediaPlayer.Event.EndReached, MediaPlayer.Event.EncounteredError ->
                if (System.currentTimeMillis() - startedAt > 1500) failedOrEnded(ev.type == MediaPlayer.Event.EncounteredError)
        }
    }

    /** Fin du flux : échec de diffusion s'il s'arrête peu après le lancement sans avoir avancé (règle du PC). */
    private fun failedOrEnded(isError: Boolean) {
        val it = item ?: return
        val early = System.currentTimeMillis() - startedAt < 25_000 && kotlin.math.abs(pos - startPos) < 5000
        if (early || isError) {
            val live = it.kind == "live" || it.kind == "program"
            if (live && it.key !in compat) {  // nouvel essai, image convertie pour l'appareil
                compat += it.key
                error = "La chaîne ne passait pas : nouvel essai en mode compatible…"
                play(if (it.kind == "program") offsetMin * 60_000L else 0, offsetMin); return
            }
            if (!early && retries < MAX_RETRIES) { retries++; play(pos, offsetMin); return }  // coupure en cours de route
            error = "Impossible de diffuser sur « $device ». Vérifie que l'appareil est allumé et sur le même Wi-Fi que le téléphone."
            playing = false; connecting = false; buffering = false
            stopPlayer()
            CastService.update(app)
            return
        }
        if (it.kind == "live") { play(0, 0); return }  // flux coupé : reconnexion
        playing = false
        onFinished?.invoke()
    }

    private fun refreshTracks() {
        val p = player ?: return
        tracks = of(p.media)
        audioId = p.audioTrack
        spuId = p.spuTrack
        // Piste exacte mémorisée (VFF / VFQ… : même langue, libellé différent) : relance une fois avec la bonne
        val it = item ?: return
        if (exactDone || chosenAudio != null || !playing || tracks.none { t -> t.kind == "audio" }) return
        exactDone = true
        val pref = prefsFor?.invoke(it) ?: return
        val want = Track.match(tracks, "audio", pref["audio"])?.takeIf { id -> id >= 0 } ?: return
        if (want != audioId) { chosenAudio = want; play(pos, offsetMin) }
    }

    /** Changer de langue / de sous-titres : l'appareil reçoit les pistes choisies au lancement du flux, on relance
     *  donc à la même position (comme le PC). id -1 : sous-titres désactivés. */
    fun selectTrack(kind: String, id: Int) {
        if (kind == "audio") chosenAudio = id else chosenSpu = id
        play(if (item?.kind == "live") 0 else pos, offsetMin)
    }

    private val tick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val it = item
            if (playing && it != null) {
                pos = if (it.kind == "program") offsetMin * 60_000L + p.time else p.time
                if (it.kind != "program" && p.length > 0) len = p.length
            }
            main.postDelayed(this, 500)
        }
    }

    fun togglePause() {
        val p = player
        if (p == null) { item?.let { play(pos, offsetMin) }; return }  // après une erreur : relance
        if (p.isPlaying) p.pause() else p.play()
    }

    /** Déplacement à ms (clics rapprochés groupés). Replay : nouveau flux à la minute (découpage du serveur). */
    fun seekTo(ms: Long) {
        val it = item ?: return
        if (it.kind == "live") return
        val target = ms.coerceIn(0, if (len > 0) len - 3000 else ms)
        pos = target
        pendingSeek = target
        main.removeCallbacks(applySeek)
        main.postDelayed(applySeek, SEEK_GROUP_MS)
    }

    private val applySeek = Runnable {
        val ms = pendingSeek ?: return@Runnable
        pendingSeek = null
        val it = item ?: return@Runnable
        if (it.kind == "program") play(0, (ms / 60_000).toInt())
        else player?.let { p -> p.time = ms; startedAt = System.currentTimeMillis(); startPos = ms }
    }

    /** Arrête la diffusion (l'appareil revient à son écran d'accueil). */
    fun stop() {
        stopPlayer()
        item = null; target = null; tracks = emptyList()
        playing = false; connecting = false; buffering = false; pos = 0; len = 0; error = ""
        CastService.stop(app)
    }

    private fun stopPlayer() {
        main.removeCallbacks(tick)
        main.removeCallbacks(applySeek)
        pendingSeek = null
        val p = player ?: return
        player = null
        p.setEventListener(null)
        p.stop()
        p.release()
    }

    /** Pistes audio / sous-titres du flux lu par VLC (mêmes règles que le lecteur et l'appli TV). */
    private fun of(media: IMedia?): List<Track> {
        media ?: return emptyList()
        return (0 until media.trackCount).mapNotNull { i ->
            val t = media.getTrack(i) ?: return@mapNotNull null
            val kind = when (t.type) { IMedia.Track.Type.Audio -> "audio"; IMedia.Track.Type.Text -> "spu"; else -> null }
                ?: return@mapNotNull null
            Track(kind, t.id, Track.normLang(t.language), Normalizer.normalize(t.description ?: "", Normalizer.Form.NFC))
        }
    }
}
