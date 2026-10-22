# IPTV Player (téléphone)

Appli Android pour téléphone du lecteur IPTV, compagnon des applis PC (`app_iptv_pc`) et TV (`app_iptv_tv`) :
même catalogue Xtream, mêmes profils, favoris, reprises de lecture, « Vu récemment », épisodes vus, langues et
catégories masquées, synchronisés par le compte du foyer (Firebase). En plus de la TV : **téléchargement des films et
épisodes** pour les regarder sans réseau.
Kotlin + Jetpack Compose (Material 3) + Media3 (lecteur intégré d'Android) : APK d'environ 4,5 Mo.

## Organisation du projet
```
appli_iptv_tel/
├─ version.properties       version de l'appli (une ligne)
├─ build_release.bat        créer l'APK publié dist\IPTVPlayerTel-<version>.apk (signé)
├─ keystore.properties      mots de passe de la clé de signature (HORS dépôt, voir plus bas)
├─ app/build.gradle.kts     dépendances, signature (lit version.properties)
└─ app/src/main/java/io/github/pauljean445/iptvtel/
   ├─ MainActivity.kt       écrans (pile, touche Retour), mise à jour proposée, messages
   ├─ AppModel.kt           état de l'appli : compte, profils, catalogue, favoris, synchro toutes les 30 s
   ├─ IptvApp.kt            chargeur d'images (cache disque limité à 40 Mo)
   ├─ data/                 copié de l'appli TV (mêmes formats) : Item, Xtream, Playlist, CatalogCache, Store,
   │                        Library, Cloud (Firebase REST), Sync (fusion), Vault (chiffrement de la source IPTV)
   ├─ download/             Downloads (file, téléchargement par morceaux), DownloadService (notification)
   ├─ player/               PlayerScreen (lecteur Media3 plein écran), Tracks (langues et sous-titres)
   ├─ ui/                   écrans : connexion, « Qui regarde ? », accueil, onglets, fiche, replays, recherche,
   │                        téléchargements, réglages
   └─ update/               Updater (releases GitHub, installation), UpdateDialog
```
Les formats de données (library.json, documents Firestore, chiffrement de la source IPTV) sont **exactement ceux des
applis PC et TV** : toute évolution doit être faite partout. `data/` est une copie de celle de la TV (package
renommé) : une correction dans l'une est à reporter dans l'autre.

## Prérequis (PC de développement)
Les mêmes que pour l'appli TV : dans `%LOCALAPPDATA%\Android`, JDK 17 (`jdk-17`) et SDK Android (`Sdk` : plateforme 35,
build-tools, platform-tools pour `adb`) ; variables `JAVA_HOME` et `ANDROID_HOME` ; `local.properties` (hors dépôt)
avec `sdk.dir=…`. Gradle est téléchargé par `gradlew.bat`.

## Compiler, installer sur le téléphone
- Version publiée (optimisée, à utiliser pour les essais) : `gradlew.bat assembleRelease`
  → `app\build\outputs\apk\release\app-release.apk`.
- Téléphone : Paramètres → À propos du téléphone → Informations sur le logiciel → 7 appuis sur « Numéro de version »,
  puis Options de développement → « Débogage USB ». Brancher le câble, accepter « Autoriser le débogage USB ».
  `adb install -r app-release.apk`.
- Journal : `adb logcat -s IPTV:* ExoPlayerImpl:* AndroidRuntime:E`. Capture : `adb exec-out screencap -p > tel.png`.

## Publier une version (mise à jour proposée dans l'appli)
1. augmenter la version dans `version.properties` (ex. `version=1.0.1`) : le numéro interne d'Android
   (`versionCode`) en est déduit (1.0.1 → 10001), toujours croissant ;
2. double-cliquer sur `build_release.bat` → `dist\IPTVPlayerTel-1.0.1.apk` ;
3. commiter / pousser, puis sur GitHub : *Releases* > *Draft a new release*, tag `v1.0.1`, joindre l'APK, décrire les
   nouveautés (affichées dans l'appli), *Publish release*.

Le dépôt GitHub `pauljean445/appli_iptv_tel` doit être **public** (constante `REPO` dans `update/Updater.kt`).
L'appli cherche une nouvelle version 8 s après son ouverture (et via Réglages → « Rechercher une mise à jour »),
jamais pendant une lecture ; la première fois, Android demande d'autoriser l'appli à « installer des applis
inconnues ». L'installeur d'Android se termine par « Ouvrir », qui relance l'appli.

## Clé de signature (à sauvegarder !)
L'APK publié est signé par `%USERPROFILE%\.android-keys\iptvtel-release.jks` (alias `iptvtel`), dont les mots de passe
sont dans `keystore.properties` à la racine du projet. **Ces deux fichiers ne sont pas dans le dépôt** et doivent être
sauvegardés ailleurs : sans eux, aucune mise à jour ne peut s'installer sur les téléphones existants (il faudrait
désinstaller l'appli, et perdre la liste des téléchargements). Une version de développement (`assembleDebug`)
ne peut pas non plus remplacer une version publiée sans désinstallation.

## Connexion au compte du foyer
Au premier lancement : email et mot de passe du compte du foyer (le même que sur le PC et la TV). Le mot de passe
n'est pas enregistré (seulement une session, chiffrée par le Keystore d'Android) ; il sert aussi à calculer la clé
qui déchiffre l'abonnement IPTV du foyer, stocké chiffré dans le compte. Si le compte n'a pas encore d'abonnement,
l'appli le demande (Xtream ou lien M3U), le vérifie et l'enregistre chiffré. Même règle que PC et TV : un profil qui a
déjà son abonnement sur l'appareil le garde. Réglages → « Changer d'abonnement » rouvre cette étape seule.

## Synchronisation
Comme sur PC et TV : liste des profils et, pour chaque profil, favoris, lectures en cours, « Vu récemment »,
épisodes vus, langues et catégories masquées. La modification la plus récente gagne (suppressions comprises).
Toutes les 30 s (liste des profils toutes les 2 min), au retour dans l'appli, au changement de profil et quand
l'appli passe en arrière-plan. Sans réseau, l'appli s'ouvre quand même (catalogue en cache, téléchargements).

## Téléchargements
- Fiche d'un film : bouton ⬇ ; série : ⬇ puis « Saison n » ou « Toute la série », ou ⬇ sur un épisode ; appui long sur
  une affiche de film : « Télécharger ». La première fois, Android demande d'autoriser les notifications.
- Fichiers dans **Films/IPTV Player** du téléphone (`Titre (année).mkv`, `Série/Saison 01/Série - S01E02 - Titre.mkv`,
  comme sur PC) : ils restent si on désinstalle l'appli et les autres applis vidéo les voient. Pendant le
  téléchargement ils sont « en cours » (invisibles ailleurs).
- Un fichier à la fois, découpé en morceaux de 16 Mo téléchargés sur **6 connexions** en parallèle : le fournisseur
  limite le débit de chaque connexion (~2 à 6 Mo/s), pas leur nombre pour les films (mesuré le 22/10/2026 :
  3 Mo/s sur une connexion, 48 Mo/s sur six). Une connexion refusée abandonne, les autres continuent ; serveur sans
  morceaux (« Range ») : une seule connexion.
- Continue appli fermée / écran éteint (service avec notification, « Tout annuler ») ; coupure : nouvel essai
  (4 fois) qui repart des morceaux terminés, aussi après un redémarrage. Android limite ce service à 6 h par jour.
- Onglet « Téléchargés » : en cours (annuler), en erreur (réessayer), sur le téléphone (lire, supprimer). Un film /
  épisode téléchargé est lu depuis le téléphone partout dans l'appli (sans réseau, sans occuper de connexion) et sa
  reprise de lecture est synchronisée comme en streaming.

## Utilisation
- Barre du bas : Accueil, Direct (« En direct » / « Replays »), Films, Séries, Téléchargés ; en haut la loupe
  (chaînes, films, séries) et l'avatar du profil (réglages : profils, catalogue, abonnement, compte, mise à jour).
- Catégories en puces ; le bouton liste à gauche ouvre toutes les catégories. Appui long sur une catégorie : la
  masquer (rangée dans « Autres ») ou la réafficher.
- Chaînes : étoile = favori. Appui long sur une affiche : favoris, téléchargement, « Vu récemment ».
- Lecteur (paysage, plein écran) : toucher = commandes ; double toucher à gauche / à droite = -10 s / +10 s
  (replay : 1 min) ; barre de temps à faire glisser ; en direct : chaîne précédente / suivante, pause = différé,
  « Revenir au direct » ; langue et sous-titres (choix mémorisé par film / par série, partagé avec PC et TV).
- Lecteur intégré d'Android : le téléphone de test (Samsung S25 FE) n'a **pas de décodeur DTS** ; une piste DTS
  apparaît « (non lisible ici) ». Les pistes Dolby (AC3, E-AC3) fonctionnent.
