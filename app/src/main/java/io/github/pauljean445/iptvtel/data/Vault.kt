package io.github.pauljean445.iptvtel.data

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement de bout en bout de la source IPTV des profils, stockée dans le compte du foyer : seuls les
 * appareils qui ont saisi le mot de passe du compte peuvent la lire (pas Google / Firebase).
 * Même format que l'appli PC (vault.py) :
 *   clé  = PBKDF2-HMAC-SHA256(mot de passe, sel = "iptv-player/" + uid, 200 000 itérations, 32 octets)
 *   bloc = base64(nonce 12 octets || AES-256-GCM(JSON de la source), données associées = "source/" + id du profil)
 * La clé est calculée à la connexion (le mot de passe n'est pas gardé) puis conservée chiffrée sur l'appareil.
 */
object Vault {
    private const val ITERATIONS = 200_000

    fun deriveKey(password: String, uid: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), "iptv-player/$uid".toByteArray(), ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun encrypt(key: ByteArray, pid: String, data: Map<String, Any?>): String {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD("source/$pid".toByteArray())
        return Base64.encodeToString(nonce + c.doFinal(data.dumps().toByteArray()), Base64.NO_WRAP)
    }

    /** null si le bloc ne se déchiffre pas avec cette clé (mot de passe changé depuis, données abîmées). */
    fun decrypt(key: ByteArray, pid: String, blob: String): Obj? = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw, 0, 12))
        c.updateAAD("source/$pid".toByteArray())
        parseObj(String(c.doFinal(raw, 12, raw.size - 12)))
    } catch (e: Exception) {
        null
    }
}
