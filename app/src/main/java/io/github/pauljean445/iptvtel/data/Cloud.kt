package io.github.pauljean445.iptvtel.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Compte du foyer (Firebase) : connexion par email / mot de passe et accès à la base Firestore (API REST),
 * portage de cloud.py : mêmes appels, mêmes données que l'appli PC.
 *
 * Seule la clé publique du projet est dans le code : ce qu'un compte peut lire ou écrire est fixé par les
 * règles de la base (chaque foyer n'accède qu'à households/<son uid>). La clé admin n'est jamais utilisée.
 * L'appareil garde l'email, l'uid, le jeton de renouvellement de la session et la clé de chiffrement de la source
 * IPTV (Vault), chiffrés par une clé du coffre-fort d'Android (Keystore, non extractible). Le mot de passe n'est
 * jamais enregistré.
 */
class Cloud(private val dir: File) {
    open class CloudError(message: String) : Exception(message)
    /** Identifiants refusés ou session terminée : il faut se reconnecter. */
    class AuthError(message: String) : CloudError(message)
    /** Le compte est connecté mais les règles de la base ne l'autorisent pas. */
    class Forbidden(message: String) : CloudError(message)

    private val file = File(dir, "account.json")
    var email = ""; private set
    var uid = ""; private set
    private var refresh = ""
    /** Clé de chiffrement de la source IPTV (Vault), calculée à la connexion. */
    var vaultKey: ByteArray? = null; private set
    private var idToken = ""
    private var expires = 0.0

    val signedIn get() = uid.isNotEmpty() && refresh.isNotEmpty()

    init {
        val d = readObj(file)
        email = d["email"].str(); uid = d["uid"].str()
        refresh = try { d["token"].str().takeIf { it.isNotEmpty() }?.let { unseal(it) } ?: "" } catch (e: Exception) { "" }
        vaultKey = try {
            d["vault"].str().takeIf { it.isNotEmpty() }?.let { Base64.decode(unseal(it), Base64.NO_WRAP) }
        } catch (e: Exception) { null }
    }

    private fun store() = writeObj(file, mapOf("email" to email, "uid" to uid,
        "token" to if (refresh.isNotEmpty()) seal(refresh) else "",
        "vault" to (vaultKey?.let { seal(Base64.encodeToString(it, Base64.NO_WRAP)) } ?: "")))

    // ---- Appels HTTP ------------------------------------------------------------- //
    /** Appel JSON ; réponse décodée, null pour un document absent (404). */
    private fun call(method: String, url: String, body: String?, token: String? = null, form: Boolean = false,
                     timeoutSec: Long = 15): Obj? {
        val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        val started = System.currentTimeMillis()
        val (code, text) = try {
            Http.json(method, url, body, headers, timeoutSec, form)
        } catch (e: Http.Transient) {
            android.util.Log.w("IPTV", "cloud $method ${url.substringBefore('?').substringAfterLast("/v1/")} : ${e.message}")
            throw CloudError(e.message ?: "serveur injoignable")
        }
        android.util.Log.i("IPTV", "cloud $method ${url.substringBefore('?').substringAfterLast("/v1/").takeLast(60)} -> $code " +
            "(${System.currentTimeMillis() - started} ms)")
        if (code in 200..299) return parseObj(text.ifEmpty { "{}" })
        if (code == 404) return null
        val err = parseObj(text)["error"].asObj() ?: LinkedHashMap()
        val msg = err["message"].str()
        val reason = msg.split(" ")[0].split(":")[0]
        MESSAGES[reason]?.let { throw AuthError(it) }
        if (code == 401) throw AuthError("la session a expiré, reconnecte-toi")
        if (code == 403) throw Forbidden("ce compte n'est pas autorisé sur la base de données")
        throw CloudError("le serveur a répondu « $code ${msg.ifEmpty { "" }} »")
    }

    @Synchronized
    fun signIn(email: String, password: String) {
        val r = call("POST", "$AUTH_URL?key=$API_KEY",
            mapOf("email" to email, "password" to password, "returnSecureToken" to true).dumps())!!
        this.email = r["email"].str().ifEmpty { email }
        uid = r["localId"].str(); refresh = r["refreshToken"].str()
        idToken = r["idToken"].str(); expires = now() + r["expiresIn"].int(3600)
        vaultKey = Vault.deriveKey(password, uid)
        try {
            getDoc("")  // document du foyer : absent au début, mais lisible si le compte est autorisé
        } catch (e: Forbidden) {
            signOut(); throw e
        }
        store()
    }

    /** Oublie la session (l'email reste, pour pré-remplir la prochaine connexion). */
    @Synchronized
    fun signOut() {
        uid = ""; refresh = ""; idToken = ""; expires = 0.0; vaultKey = null
        store()
    }

    /** Jeton d'accès valide (1 h), renouvelé au besoin avec le jeton de renouvellement. */
    @Synchronized
    private fun token(): String {
        if (refresh.isEmpty()) throw AuthError("cet appareil n'est pas connecté au compte du foyer")
        if (idToken.isNotEmpty() && now() < expires - 120) return idToken
        val r = try {
            call("POST", "$TOKEN_URL?key=$API_KEY",
                "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8"), form = true)!!
        } catch (e: AuthError) {
            signOut(); throw e  // mot de passe changé, compte désactivé ou supprimé
        }
        idToken = r["id_token"].str(); expires = now() + r["expires_in"].int(3600)
        val changed = r["refresh_token"].str() != refresh
        refresh = r["refresh_token"].str()
        if (changed) store()
        return idToken
    }

    // ---- Documents du foyer (chemins relatifs à households/<uid>) ---------------- //
    private fun docName(path: String) = "$DOC_NAME/households/$uid" + if (path.isNotEmpty()) "/$path" else ""

    /** Champs du document (décodés), null s'il n'existe pas. */
    fun getDoc(path: String, timeoutSec: Long = 15): Obj? {
        val r = call("GET", "$DB_BASE/${docName(path)}", null, token(), timeoutSec = timeoutSec) ?: return null
        val fields = r["fields"].asObj() ?: return LinkedHashMap()
        return fields.mapValuesTo(LinkedHashMap()) { decode(it.value.asObj()!!) }
    }

    /** Une écriture : seuls les champs donnés (chemins fieldPath -> valeur) sont écrits et ceux de delete effacés ;
     *  fields = null : le document est supprimé. */
    data class Write(val path: String, val fields: Map<String, Any?>?, val delete: List<String> = emptyList())

    /** Écritures groupées, appliquées ensemble ou pas du tout. */
    fun commit(writes: List<Write>, timeoutSec: Long = 15) {
        if (writes.isEmpty()) return
        val body = writes.map { w ->
            val name = docName(w.path)
            if (w.fields == null) mapOf("delete" to name)
            else {
                val nested = LinkedHashMap<String, Any?>()
                for ((fp, value) in w.fields) {
                    val parts = splitPath(fp)
                    var node: Obj = nested
                    for (part in parts.dropLast(1)) node = node.obj(part)
                    node[parts.last()] = value
                }
                mapOf("update" to mapOf("name" to name, "fields" to nested.mapValues { encode(it.value) }),
                    "updateMask" to mapOf("fieldPaths" to w.fields.keys.toList() + w.delete))
            }
        }
        call("POST", "$DB_BASE/$DOC_NAME:commit", mapOf("writes" to body).dumps(), token(), timeoutSec = timeoutSec)
    }

    companion object {
        const val API_KEY = "AIzaSyCJQAAhNf8j50_M3qt7jP7EKnz_4a2FVf0"  // clé publique du projet (pas un secret)
        const val PROJECT_ID = "iptv-sync-5039f"
        const val AUTH_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword"
        const val TOKEN_URL = "https://securetoken.googleapis.com/v1/token"
        const val DB_BASE = "https://firestore.googleapis.com/v1"
        const val DOC_NAME = "projects/$PROJECT_ID/databases/(default)/documents"

        private val MESSAGES = mapOf(
            "INVALID_LOGIN_CREDENTIALS" to "email ou mot de passe incorrect",
            "INVALID_PASSWORD" to "email ou mot de passe incorrect",
            "EMAIL_NOT_FOUND" to "email ou mot de passe incorrect",
            "INVALID_EMAIL" to "adresse email invalide",
            "MISSING_PASSWORD" to "mot de passe manquant",
            "USER_DISABLED" to "ce compte est désactivé",
            "TOO_MANY_ATTEMPTS_TRY_LATER" to "trop de tentatives, réessaie dans quelques minutes",
            "TOKEN_EXPIRED" to "la session a expiré, reconnecte-toi",
            "INVALID_REFRESH_TOKEN" to "la session a expiré, reconnecte-toi",
            "USER_NOT_FOUND" to "ce compte n'existe plus",
        )

        // ---- Valeurs Firestore ---------------------------------------------------- //
        fun encode(v: Any?): Map<String, Any?> = when (v) {
            null -> mapOf("nullValue" to null)
            is Boolean -> mapOf("booleanValue" to v)
            is Int, is Long -> mapOf("integerValue" to v.toString())
            is Number -> mapOf("doubleValue" to v.toDouble())
            is String -> mapOf("stringValue" to v)
            is Map<*, *> -> mapOf("mapValue" to mapOf("fields" to v.entries.associate { (k, x) -> k.toString() to encode(x) }))
            is Iterable<*> -> mapOf("arrayValue" to mapOf("values" to v.map { encode(it) }))
            else -> mapOf("stringValue" to v.toString())
        }

        fun decode(v: Obj): Any? {
            val (kind, x) = v.entries.first()
            return when (kind) {
                "integerValue" -> x.str().toLong()
                "doubleValue" -> x.num()
                "mapValue" -> (x.asObj()?.get("fields").asObj() ?: LinkedHashMap())
                    .mapValuesTo(LinkedHashMap()) { decode(it.value.asObj()!!) }
                "arrayValue" -> ((x.asObj()?.get("values") as? List<*>) ?: emptyList<Any?>())
                    .mapTo(ArrayList()) { decode(it.asObj()!!) }
                else -> x  // nullValue, booleanValue, stringValue, timestampValue…
            }
        }

        /** Chemin d'un champ (« progress.`x:ep:12` ») : les noms hors [A-Za-z_][A-Za-z0-9_]* entre accents graves. */
        fun fieldPath(vararg parts: String): String = parts.joinToString(".") { p ->
            if (Regex("[A-Za-z_][A-Za-z0-9_]*").matches(p)) p
            else "`" + p.replace("\\", "\\\\").replace("`", "\\`") + "`"
        }

        fun splitPath(path: String): List<String> {
            val parts = ArrayList<String>()
            val cur = StringBuilder()
            var quoted = false
            var i = 0
            while (i < path.length) {
                val ch = path[i]
                when {
                    quoted && ch == '\\' -> { cur.append(path[i + 1]); i += 2; continue }
                    ch == '`' -> quoted = !quoted
                    ch == '.' && !quoted -> { parts += cur.toString(); cur.clear() }
                    else -> cur.append(ch)
                }
                i++
            }
            parts += cur.toString()
            return parts
        }

        // ---- Chiffrement du jeton (Keystore d'Android) ---------------------------- //
        private const val KEY_ALIAS = "iptv-account"

        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            return gen.generateKey()
        }

        fun seal(text: String): String {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val out = c.iv + c.doFinal(text.toByteArray())
            return "ks:" + Base64.encodeToString(out, Base64.NO_WRAP)
        }

        fun unseal(value: String): String {
            val raw = Base64.decode(value.removePrefix("ks:"), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
            return String(c.doFinal(raw, 12, raw.size - 12))
        }
    }
}
