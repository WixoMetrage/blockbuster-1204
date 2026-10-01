# Blockbuster (fork Wixo) — aide-mémoire Claude Code

**La référence est [CDC.md](CDC.md).** Avant de coder, relis la section du chantier en cours. Si le CDC et le code ne sont pas d'accord, ne devine pas : demande à Wixo, puis mets le CDC à jour.

## Principes d'architecture (CDC §2)
- **Formats intouchables** : `.dat`, scènes, profils caméra, `model.json`, configs. Toute évolution passe par un tag de version et une migration automatique, et aucun champ legacy n'est supprimé.
- **Une fonctionnalité = un module** (package ou classe dédiée). On n'alourdit pas les grosses classes existantes.
- **Registres** (`register(id, factory)`) plutôt que des `switch` pour les actions, fixtures, modifiers, morphs et formats vidéo.
- **Tout réglage passe par la config** : aucune valeur codée en dur, un nouveau comportement a une option avec un défaut raisonnable, visible dans l'UI.
- **Mixins** : `@Inject`, `@WrapOperation`, `@ModifyExpressionValue` plutôt que `@Redirect`/`@Overwrite`. Pour le cosmétique : `require = 0` et un log.
- **Diagnostic** : une catégorie de log par système et un mode debug en config. Une erreur utilisateur s'affiche en jeu (chat ou écran).
- **Tests** dans `src/test` pour toute logique sans OpenGL, et un test de non-régression par bug corrigé quand c'est possible.
- **Performance** : aucune allocation ni accès disque dans les boucles de rendu ou de tick. Les I/O se font hors du thread de jeu.

## Méthode (CDC §3)
- Une branche par chantier (`fix/video`, `fix/camera-fov`…), des petits commits explicites. `master` compile toujours.
- Avant de coder : proposer un plan (fichiers, approche, ambiguïtés) et **attendre la validation de Wixo**.
- Après chaque chantier : `gradlew build` et les tests passent, le jar est dans `build/libs/`, une checklist de test en jeu est fournie (critères d'acceptation du CDC) et `CHANGELOG.md` est à jour.
- Version `2.7.3-wixo.N`, incrémentée à chaque livraison testable.
- Messages de commit sur une ligne au format `type(portée): description`, par exemple `fix(video): …`, `feat(camera): …`, `chore(build): …`.
- **`local.properties` est propre à la machine** : ne jamais le committer ni le réécrire.

## Toolchain
- Gradle 8.6 / Loom tournent sous **JDK 21** (`org.gradle.java.home` dans `~/.gradle/gradle.properties`, parce que `JAVA_HOME` pointe sur le JDK 25). Le mod compile en **Java 17**.
- `_JAVA_OPTIONS=-Xmx4G` est global sur le PC : il écrase le `-Xmx` de Gradle **et de Minecraft**.
- `./gradlew build` · `./gradlew test` · `./gradlew installToInstance` (build, sauvegarde de l'ancien jar dans `mods_backup/`, copie dans l'instance ; refuse si Minecraft tourne).
- Lancer le jeu après l'installation : `E:\PrismLauncher\prismlauncher.exe --launch Blockbuster_Dev`. On enchaîne donc build, installation et lancement.

## Chemins
| Quoi | Où |
|---|---|
| Projet | `E:\Claude_Projets\Dev_Blockbuster` |
| Instance de test (Prism) | `E:\PrismLauncher\instances\Blockbuster_Dev` (jeu dans `minecraft\`) — chemin dans `local.properties` (non versionné) |
| Logs | `…\minecraft\logs\latest.log` |
| Crash-reports | `…\minecraft\crash-reports\` |
| Config Blockbuster | `…\minecraft\config\blockbuster\` (`config.json`, `movies\video.log`) |
| Modèles | `…\minecraft\config\blockbuster\models\` (`model.json`, OBJ/VOX, Blockbench) |
| Prism | `E:\PrismLauncher\prismlauncher.exe` (`--launch Blockbuster_Dev`) |
| ffmpeg | dans le PATH (winget, `Gyan.FFmpeg`) |

Quand Wixo signale un bug : `powershell -NoProfile -ExecutionPolicy Bypass -File tools/collect-logs.ps1` copie `latest.log`, le dernier crash-report et les logs vidéo dans `build/diag/<horodatage>/`, puis on les analyse.

Instance : Minecraft 1.20.4, Fabric Loader 0.19.5, Fabric API 0.91.1, Sodium 0.5.8, Iris 1.7.2 (aussi dépendance de compilation `modClientCompileOnly` du mod, version dans `gradle.properties` : `iris_version`), Complementary Reimagined r5.9.3.
