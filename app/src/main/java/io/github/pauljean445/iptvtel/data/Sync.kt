package io.github.pauljean445.iptvtel.data

import io.github.pauljean445.iptvtel.data.Cloud.Companion.fieldPath

/**
 * Synchronisation avec le compte du foyer, portage exact de sync.py (même format, mêmes règles que le PC) :
 *
 *  households/<uid>                  profiles : {pid: {name, color, t}} ; deleted : {profiles: {pid: t}}
 *  households/<uid>/profiles/<pid>   fav, progress, recent, watched, prefs (comme library.json) ; deleted
 *
 * Pour chaque entrée, la version la plus récente (« t ») gagne, valeur ou trace de suppression. Une synchro :
 * lire le document, appliquer ici ce que le serveur a de plus récent, puis envoyer seulement ce que cet
 * appareil a de plus récent, champ par champ. Serveur injoignable : on continue, rien n'est perdu.
 * La source IPTV du profil voyage chiffrée (champ secret.source : {"v": bloc Vault, "t"}), voir sourceWrites ;
 * les autres réglages de profile.json restent propres à chaque appareil.
 */
object Sync {
    private const val PURGE_SECONDS = Library.PURGE_DAYS * 86400.0

    class Merge(val changed: Boolean, val fields: Map<String, Any?>, val drop: List<String>)

    /** Fusionne remote (document du serveur) dans tables / deleted (données d'ici, modifiées sur place). */
    fun merge(names: List<String>, tables: Map<String, Obj>, deleted: Map<String, Obj>, remote: Obj?): Merge {
        val rdeleted = remote?.get("deleted").asObj() ?: LinkedHashMap()
        val limit = now() - PURGE_SECONDS
        var changed = false
        val fields = LinkedHashMap<String, Any?>()
        val drop = ArrayList<String>()
        for (name in names) {
            val live = tables.getValue(name)
            val dead = deleted.getValue(name)
            val rlive = remote?.get(name).asObj() ?: LinkedHashMap()
            val rdead = rdeleted[name].asObj() ?: LinkedHashMap()
            for (key in live.keys + dead.keys + rlive.keys + rdead.keys) {
                if (key in dead && dead[key].num() < limit) dead.remove(key)  // trace de plus de 3 mois
                var rgone = key !in rlive && key in rdead  // trace de suppression sur le serveur
                val rt: Double? = when {
                    key in rlive -> rlive[key].asObj()?.get("t").num()
                    rgone && rdead[key].num() >= limit -> rdead[key].num()
                    else -> {
                        if (rgone) {  // trace de plus de 3 mois : effacée aussi sur le serveur
                            drop += fieldPath("deleted", name, key); rgone = false
                        }
                        null
                    }
                }
                val lt: Double? = if (key in live) live[key].asObj()?.get("t").num() else dead[key]?.num()
                if (rt != null && (lt == null || rt > lt)) {
                    if (key in rlive) { live[key] = rlive[key]; dead.remove(key) }
                    else { live.remove(key); dead[key] = rt }
                    changed = true
                } else if (lt != null && (rt == null || lt > rt)) {
                    if (key in live) {
                        fields[fieldPath(name, key)] = live[key]
                        if (rgone) drop += fieldPath("deleted", name, key)
                    } else {
                        fields[fieldPath("deleted", name, key)] = dead[key]
                        if (key in rlive) drop += fieldPath(name, key)
                    }
                }
            }
        }
        return Merge(changed, fields, drop)
    }

    // ---- Données d'un profil ------------------------------------------------------ //
    /** Fusionne le document du profil dans lib (enregistrée si elle change). Retourne (changée, écritures). */
    fun libraryWrites(lib: Library, remote: Obj?, pid: String): Pair<Boolean, List<Cloud.Write>> {
        val m = merge(Library.TABLES, lib.tables, lib.deleted, remote)
        if (m.changed) { lib.dirty = true; lib.save() }
        val writes = if (m.fields.isNotEmpty() || m.drop.isNotEmpty())
            listOf(Cloud.Write("profiles/$pid", m.fields, m.drop)) else emptyList()
        return m.changed to writes
    }

    /** Synchro complète d'un profil, sans attendre le passage suivant (fermeture, changement de profil). */
    fun syncLibraryNow(cloud: Cloud, lib: Library, pid: String, store: Store? = null, timeoutSec: Long = 8): Boolean {
        val remote = cloud.getDoc("profiles/$pid", timeoutSec)
        var (changed, writes) = libraryWrites(lib, remote, pid)
        if (store != null) {
            val (srcChanged, fields) = sourceWrites(store, pid, remote, cloud.vaultKey)
            writes = withFields(writes, pid, fields)
            changed = changed || srcChanged
        }
        cloud.commit(writes, timeoutSec)
        return changed
    }

    // ---- Source IPTV du profil (chiffrée) --------------------------------------------- //
    val VAULT_KEYS = listOf("mode", "server", "username", "password", "stream_format", "url")

    /** Source IPTV du profil (même règle que sync.source_writes côté PC). Le compte sert à configurer les appareils
     *  qui n'en ont pas : un profil SANS abonnement sur cet appareil prend celui du compte (déchiffré dans
     *  profile.json). Un profil qui en a un le garde toujours (chaque appareil peut avoir le sien, ex. un lien « TV »
     *  différent de celui du PC) ; il l'envoie au compte s'il n'y en a pas, s'il y est indéchiffrable (mot de passe
     *  changé) ou après un changement fait ici (source_t plus récent). Retourne (source d'ici modifiée, champs à écrire). */
    fun sourceWrites(store: Store, pid: String, remote: Obj?, key: ByteArray?): Pair<Boolean, Map<String, Any?>> {
        key ?: return false to emptyMap()
        val own = store.readProfile(pid)
        val lt = own["source_t"].num()
        val r = remote?.get("secret").asObj()?.get("source").asObj()
        val rt = r?.get("t").num() ?: 0.0
        val data = r?.let { Vault.decrypt(key, pid, it["v"].str()) }
        if (Source.fromProfile(own) == null) {
            data ?: return false to emptyMap()
            VAULT_KEYS.forEach { k -> own[k] = data[k] }
            own["remember"] = true
            own["source_t"] = rt
            store.writeProfile(pid, own)
            return true to emptyMap()
        }
        if (r == null || data == null || lt > rt) {
            val t = if (r == null || lt > rt) lt else now()
            if (t != lt) { own["source_t"] = t; store.writeProfile(pid, own) }
            val path = fieldPath("secret", "source")
            return false to mapOf(path to mapOf("v" to Vault.encrypt(key, pid, VAULT_KEYS.associateWith { own[it] }), "t" to t))
        }
        return false to emptyMap()
    }

    /** Ajoute des champs à l'écriture du document du profil (ou en crée une). */
    fun withFields(writes: List<Cloud.Write>, pid: String, fields: Map<String, Any?>): List<Cloud.Write> {
        if (fields.isEmpty()) return writes
        val path = "profiles/$pid"
        val existing = writes.firstOrNull { it.path == path && it.fields != null }
            ?: return writes + Cloud.Write(path, fields)
        return writes.map { if (it === existing) Cloud.Write(path, it.fields!! + fields, it.delete) else it }
    }

    // ---- Liste des profils ---------------------------------------------------------- //
    class ProfilesResult(val changed: Boolean, val writes: List<Cloud.Write>, val removed: List<String>, val added: List<String>)

    /** Fusionne la liste des profils du foyer dans les réglages (settings.json chargé). */
    fun profilesWrites(store: Store, settings: Obj, remote: Obj?, uid: String): ProfilesResult {
        val old = store.profiles(settings).associateBy { it["id"].str() }
        var live: Obj = old.mapValuesTo(LinkedHashMap()) { (_, p) ->
            mutableMapOf<String, Any?>("name" to p["name"], "color" to p["color"], "t" to p["t"].num())
        }
        var dead: Obj = LinkedHashMap(settings["profiles_deleted"].asObj() ?: LinkedHashMap())
        val synced = settings["sync_uid"].str()  // compte avec lequel cet appareil s'est déjà synchronisé
        if (synced != uid && (synced.isNotEmpty() || !remote?.get("profiles").asObj().isNullOrEmpty())) {
            // Cet appareil rejoint un foyer : il prend ses profils, ceux d'ici sont laissés de côté (sans trace).
            // Seul un appareil jamais synchronisé, face à un foyer vide, envoie ses profils.
            live = LinkedHashMap(); dead = LinkedHashMap()
        }
        val m = merge(listOf("profiles"), mapOf("profiles" to live), mapOf("profiles" to dead), remote)
        val added = live.keys.filter { it !in old }.sortedBy { live[it].asObj()?.get("t").num() }
        val removed = old.keys.filter { it !in live }
        store.setProfiles(settings,
            old.keys.filter { it in live }.map { pid -> LinkedHashMap(old.getValue(pid)).apply { putAll(live[pid].asObj()!!) } } +
                added.map { pid -> LinkedHashMap(live[pid].asObj()!!).apply { put("id", pid) } })
        settings["profiles_deleted"] = dead
        settings["sync_uid"] = uid
        val writes = ArrayList<Cloud.Write>()
        if (m.fields.isNotEmpty() || m.drop.isNotEmpty()) writes += Cloud.Write("", m.fields, m.drop)
        // Profil supprimé ici : ses données sont effacées du serveur avec lui
        dead.keys.filter { fieldPath("deleted", "profiles", it) in m.fields }.forEach { writes += Cloud.Write("profiles/$it", null) }
        return ProfilesResult(m.changed || removed.isNotEmpty() || added.isNotEmpty(), writes, removed, added)
    }

    /** Dossiers des profils arrivés d'un autre appareil (source IPTV de sourcePid : un foyer = un abonnement) ;
     *  suppression de ceux des profils retirés. Il reste toujours au moins un profil. */
    fun applyProfiles(store: Store, settings: Obj, removed: List<String>, added: List<String>, sourcePid: String?) {
        added.forEach { store.initProfileFolder(it, sourcePid) }
        if (store.profiles(settings).isEmpty()) store.createProfile(settings, "Principal", Store.PROFILE_COLORS[0], sourcePid)
        removed.forEach { store.profileDir(it).deleteRecursively() }
    }

    /** Au lancement, avant « Qui regarde ? » : met à jour la liste des profils du foyer. Serveur injoignable :
     *  on continue avec ceux de l'appareil. Lève AuthError si la session n'est plus valable. */
    fun startup(store: Store, cloud: Cloud) {
        val settings = store.loadSettings()
        val current = settings["profile"].str()
        val fresh = settings["sync_uid"].str().isEmpty()
        try {
            val remote = cloud.getDoc("", 8)
            val r = profilesWrites(store, settings, remote, cloud.uid)
            applyProfiles(store, settings, r.removed, r.added, current.takeIf { store.profileDir(it).exists() })
            val ids = store.profiles(settings).map { it["id"].str() }
            settings["profile"] = if (current in ids) current else ids[0]
            store.saveSettings(settings)
            cloud.commit(r.writes, 8)
            if (fresh && r.added.isEmpty()) {
                // Premier passage, foyer encore vide : on envoie aussi les données des profils
                ids.forEach { syncLibraryNow(cloud, Library(store.profileDir(it)), it, store) }
            }
        } catch (e: Cloud.Forbidden) {
            cloud.signOut()
            throw Cloud.AuthError("l'accès de ce compte a été retiré")
        } catch (e: Cloud.AuthError) {
            throw e
        } catch (e: Cloud.CloudError) {
            // serveur injoignable : on continue avec les profils de l'appareil
        }
    }
}
