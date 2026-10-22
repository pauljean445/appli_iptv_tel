# Media3 et Compose fournissent leurs propres règles R8.
# libVLC (diffusion) : classes appelées depuis le code natif (JNI), à ne pas renommer ni supprimer
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**
