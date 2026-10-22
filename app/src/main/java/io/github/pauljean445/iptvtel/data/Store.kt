package io.github.pauljean445.iptvtel.data

import java.io.File
import java.security.SecureRandom

/**
 * Réglages et profils sur le téléphone, rangés comme sur PC (config.py) :
 *   settings.json                  réglages de l'appareil, liste des profils, profil en cours
 *   profiles/<id>/profile.json     réglages du profil propres à l'appareil (source IPTV, dernière lecture)
 *   profiles/<id>/library.json     favoris, reprises… (synchronisés, voir Library)
 *   profiles/<id>/catalog.json.gz  catalogue en cache
 */
class Store(val dir: File) {
    val settingsFile = File(dir, "settings.json")

    fun profileDir(pid: String) = File(dir, "profiles/$pid")

    @Suppress("UNCHECKED_CAST")
    fun profiles(settings: Obj): MutableList<Obj> =
        (settings["profiles"] as? MutableList<Any?>)?.mapNotNull { it.asObj() }?.toMutableList()
            ?: ArrayList<Obj>().also { settings["profiles"] = it }

    fun setProfiles(settings: Obj, list: List<Obj>) {
        settings["profiles"] = list.toMutableList<Any?>()
    }

    /** Réglages de l'appareil ; crée un profil « Principal » s'il n'y en a aucun. */
    fun loadSettings(): Obj {
        val s = readObj(settingsFile)
        if (profiles(s).isEmpty()) {
            createProfile(s, "Principal", PROFILE_COLORS[0], null)
            s["profile"] = profiles(s)[0]["id"]
            saveSettings(s)
        }
        val ids = profiles(s).map { it["id"].str() }
        if (s["profile"].str() !in ids) s["profile"] = ids[0]
        return s
    }

    fun saveSettings(s: Obj) = writeObj(settingsFile, s)

    fun readProfile(pid: String): Obj = readObj(File(profileDir(pid), "profile.json"))

    fun writeProfile(pid: String, data: Obj) = writeObj(File(profileDir(pid), "profile.json"), data)

    /** Ajoute un profil (source IPTV copiée depuis le profil copyFrom, sinon vide). Retourne son id. */
    fun createProfile(settings: Obj, name: String, color: String, copyFrom: String?): String {
        val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val pid = "p" + bytes.joinToString("") { "%02x".format(it) }  // unique entre les appareils du foyer
        initProfileFolder(pid, copyFrom)
        profiles(settings).let { setProfiles(settings, it + mutableMapOf<String, Any?>("id" to pid, "name" to name, "color" to color, "t" to now())) }
        return pid
    }

    /** Dossier d'un profil, avec la source IPTV et le catalogue en cache de copyFrom (un foyer = un abonnement). */
    fun initProfileFolder(pid: String, copyFrom: String?) {
        val folder = profileDir(pid)
        folder.mkdirs()
        val own = LinkedHashMap<String, Any?>()
        if (copyFrom != null) {
            val src = readProfile(copyFrom)
            SOURCE_KEYS.forEach { k -> if (k in src) own[k] = src[k] }
            File(profileDir(copyFrom), CatalogCache.FILE).takeIf { it.exists() }
                ?.copyTo(File(folder, CatalogCache.FILE), overwrite = true)
        }
        writeProfile(pid, own)
    }

    /** Supprime un profil ; la trace datée (profiles_deleted) le fait disparaître des autres appareils. */
    fun deleteProfile(settings: Obj, pid: String) {
        setProfiles(settings, profiles(settings).filter { it["id"] != pid })
        settings.obj("profiles_deleted")[pid] = now()
        profileDir(pid).deleteRecursively()
    }

    companion object {
        val PROFILE_COLORS = listOf("#7B66FF", "#FF5470", "#2EC4B6", "#FFB020", "#3A86FF", "#8AC926")
        val SOURCE_KEYS = listOf("mode", "server", "username", "password", "stream_format", "url", "remember")
    }
}
