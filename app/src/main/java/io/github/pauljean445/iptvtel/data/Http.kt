package io.github.pauljean445.iptvtel.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Accès réseau commun (fournisseur IPTV, Firebase, GitHub). */
object Http {
    // Certains fournisseurs bloquent les User-Agent inconnus : on se présente comme VLC, comme sur PC.
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    class HttpError(val code: Int, message: String, val body: String = "") : IOException(message)

    /** Erreur qui peut disparaître en réessayant (serveur surchargé, coupure, délai). */
    class Transient(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun get(url: String, timeoutSec: Long = 60, retries: Int = 0, headers: Map<String, String> = emptyMap()): ByteArray {
        var attempt = 0
        while (true) {
            try {
                return getOnce(url, timeoutSec, headers)
            } catch (e: Transient) {
                if (attempt >= retries) throw e
                Thread.sleep(1000L * (attempt + 1))  // 1 s, puis 2 s
                attempt++
            }
        }
    }

    private fun getOnce(url: String, timeoutSec: Long, headers: Map<String, String>): ByteArray {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> req.header(k, v) }
        val call = client.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build().newCall(req.build())
        try {
            call.execute().use { resp ->
                if (resp.code >= 500) throw Transient("Le serveur est indisponible (« ${resp.code} »).")
                if (!resp.isSuccessful) {
                    throw HttpError(resp.code, "Le serveur a répondu « ${resp.code} ».", resp.body?.string() ?: "")
                }
                return resp.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: java.net.SocketTimeoutException) {
            throw Transient("Délai dépassé : le serveur ne répond pas.", e)
        } catch (e: java.net.UnknownHostException) {
            throw Transient("Impossible de joindre le serveur (pas d'internet ?).", e)
        } catch (e: java.net.ConnectException) {
            throw Transient("Impossible de joindre le serveur.", e)
        }
    }

    /** Requête JSON (Firebase). Retourne (code, corps). Les erreurs réseau sont levées en Transient. */
    fun json(method: String, url: String, body: String?, headers: Map<String, String> = emptyMap(),
             timeoutSec: Long = 15, form: Boolean = false): Pair<Int, String> {
        val type = if (form) "application/x-www-form-urlencoded" else "application/json"
        val req = Request.Builder().url(url).header("User-Agent", "IPTVPlayerTel")
            .method(method, body?.toRequestBody(type.toMediaType()))
        headers.forEach { (k, v) -> req.header(k, v) }
        val c = client.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS)
            .connectTimeout(timeoutSec, TimeUnit.SECONDS).build()
        try {
            c.newCall(req.build()).execute().use { resp -> return resp.code to (resp.body?.string() ?: "") }
        } catch (e: IOException) {
            throw Transient("serveur injoignable (${e.message ?: e.javaClass.simpleName})", e)
        }
    }
}
