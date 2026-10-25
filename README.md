# mod_1  (Minecraft 26.2 - Fabric)

Lecteur MP3 invisible. Rien n'apparait tant que tu n'appuies pas sur **Ctrl + Maj + Alt + P**.

## Compiler
1. Installer JDK 25 (ex. Temurin) et IntelliJ IDEA.
2. Ouvrir ce dossier dans IntelliJ (il importe le projet Gradle tout seul).
3. Panneau Gradle > Tasks > build > build.
4. Le mod est dans `build/libs/mod_1-1.0.0.jar`  (pas le *-sources*).
   Alternative en ligne de commande (si Gradle est installe) : `gradle wrapper --gradle-version 9.4.0` puis `./gradlew build`.

## Installer
Mettre le jar dans `.minecraft/mods/` avec Fabric Loader >= 0.19.3 et Fabric API pour 26.2.

## Musique
Un dossier par playlist, avec les .mp3 dedans :
`.minecraft/mod_1/playlists/MaPlaylist/morceau.mp3`

## Utilisation
- Ctrl+Maj+Alt+P : ouvre / ferme l'interface (Echap ferme aussi).
- Touche media Play/Pause : pause / reprise.
- Touche media Stop : arrete, mais la position est retenue (Play/Pause ou bouton "Lire" reprend au meme endroit).
- Touches media Suivant / Precedent : piste suivante / precedente.
- Les touches media fonctionnent sous Windows (meme si Minecraft n'est pas au premier plan).
- La derniere piste, la position et le volume sont retenus entre deux lancements du jeu.
