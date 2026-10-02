# Cahier des charges — Blockbuster (fork perso) — V1

> **Statut :** V1, document de travail. Seule la section 4 (Vidéo) est rédigée en détail. Les sections 5 à 8 consignent les décisions déjà prises et les bugs relevés, et seront détaillées une par une.
> **Lecteur principal :** Claude Code, qui code à partir de ce document. **Propriétaire :** Wixo.
> **Règle d'or :** quand ce document et le code ne sont pas d'accord, on ne devine pas. On demande, puis on met à jour le CDC.

---

## 1. Contexte et vision

- **Base de travail :** portage Fabric 1.20.4 de Blockbuster 2.7.2, par NotSafe/McHorse, dépôt `mchorse/blockbuster-1204`, version 2.7.3, licence GPLv3. Il intègre McLib, Metamorph, Aperture, Chameleon et la capture vidéo façon Minema.
- **Usage :** personnel, en **solo**, pour produire des machinimas (montage dans Adobe Premiere Pro).
- **Ambition :** à long terme, ce mod deviendra **l'outil de travail principal** de Wixo. Des outils et fonctionnalités s'y ajouteront en continu. Le code doit donc rester **facile à faire évoluer**, ce qui compte autant que la correction des bugs.
- **Mission 1 :** corriger tous les bugs, dans cet ordre : Vidéo → Caméra/FOV → Replay (blocs, explosions) → Morphs → Optimisation.
- **Mission 2 :** améliorer les fonctionnalités : éditeur caméra, export vidéo, éditeur d'enregistrements/timeline, morphs. Ce sera l'objet d'un CDC V2 et suivants.

### Environnement cible
| Élément | Valeur |
|---|---|
| Minecraft | 1.20.4, Fabric Loader ≥ 0.15.0, Fabric API 0.91.1+1.20.4 |
| Shaders | **Iris + Complementary Reimagined : compatibilité obligatoire** |
| PC | Windows 11, RTX 3060 Ti, 32 Go de RAM, écrans de résolutions différentes (dont un 4K) |
| Encodage | ffmpeg installé sur le PC |
| Données legacy | Les modèles 1.12.2 (Blockbench, modèles Blockbuster, OBJ/VOX) doivent continuer à se charger tels quels |

---

## 2. Principes d'architecture (évolutivité)

Ces règles s'appliquent à **chaque** modification, y compris aux corrections de bugs.

1. **Ne pas casser les formats.** Les fichiers `.dat`, les scènes, les profils caméra, les `model.json` et les configs actuels doivent toujours se charger. Toute évolution de format passe par un **tag de version** dans les données, avec une migration automatique des anciens fichiers. On ne supprime jamais un champ lu par l'ancien format.
2. **Une fonctionnalité = un module identifiable.** Une nouvelle fonctionnalité va dans son propre package ou sa propre classe, avec un point d'entrée clair. On évite de disperser de la logique dans des classes existantes déjà énormes.
3. **Des registres plutôt que des `switch`.** Les actions d'enregistrement, les fixtures et modifiers caméra, les types de morphs et les formats vidéo s'ajoutent par **registre** (`register(id, factory)`). Ajouter un type ne doit jamais demander de modifier dix fichiers. Si un registre manque, on le crée au moment où on en a besoin.
4. **Tout réglage passe par la config.** Pas de valeur codée en dur. Chaque nouveau comportement qui remplace l'ancien a une **option** (on/off ou valeur), avec un défaut raisonnable, et apparaît dans l'interface de configuration.
5. **Mixins prudents.** On préfère `@Inject`, `@WrapOperation` et `@ModifyExpressionValue` (MixinExtras) aux `@Redirect` et `@Overwrite`. Une fonction cosmétique ne doit jamais empêcher le jeu de démarrer (`require = 0` et un log).
6. **Diagnostic intégré.** Chaque système (vidéo, caméra, replay) a une **catégorie de log** dédiée et un mode debug activable dans la config. Une erreur visible par l'utilisateur s'affiche **dans le chat ou à l'écran**, pas seulement dans `latest.log`.
7. **Tests.** On remet en place `src/test` (le build désactive actuellement les tests). Toute logique qui ne dépend pas d'OpenGL (calcul de résolution, alignement des ticks, sérialisation, restauration du monde) a des tests unitaires. Chaque bug corrigé reçoit si possible un test de non-régression.
8. **Pas de régression de performance.** Pas d'allocation ni d'accès disque dans les boucles de rendu ou de tick. Toute lecture ou écriture de fichier se fait hors du thread de jeu.

---

## 3. Méthode de travail avec Claude Code

- **Git :** une branche par chantier (`fix/video`, `fix/camera-fov`…). Des commits petits et explicites. `main` doit toujours compiler.
- **Avant de coder :** Claude Code relit la section concernée, propose son plan (fichiers touchés, approche) et signale tout ce qui est ambigu.
- **Après chaque chantier :**
  1. le build passe (`gradlew build`) ;
  2. les tests passent ;
  3. le jar est produit dans `build/libs/` ;
  4. une **checklist de test en jeu** est fournie à Wixo, reprenant les critères d'acceptation de la section ;
  5. `CHANGELOG.md` est mis à jour.
- **Retours de Wixo :** les bugs constatés en jeu sont ajoutés dans la section « Défauts constatés » du chantier concerné, ce qui produit une nouvelle version du CDC.
- **Version du mod :** `2.7.3-wixo.N`, incrémentée à chaque livraison testable.
- **Toolchain :** compilation en Java 17 (cible du mod). Gradle 8.6 et Loom 1.6 **ne tournent pas sous Java 25**. Il faut donc un JDK 17 ou 21 pour Gradle (voir l'annexe A). Loom doit être fixé sur une version précise, pas sur `-SNAPSHOT`.

---

## 4. Chantier 1 : enregistrement vidéo  ✍️ (rédigé, à valider)

### 4.1 Problème constaté
L'enregistrement ne fonctionne correctement que sur l'écran 4K. Sur les autres écrans, en fenêtre, la vidéo est noire ou figée.

### 4.2 Causes identifiées par l'audit
1. **Dimensions impaires.** Une fenêtre (avec barre de titre) a souvent une hauteur impaire, par exemple 1920×1009. ffmpeg exige des dimensions paires. Le mod bascule alors sur le chemin « résolution personnalisée ». Ce chemin est **interdit quand Iris est actif**, donc le mod se replie sur la taille brute (impaire) de la fenêtre. `VideoParams` arrondit à une valeur paire, la taille lue ne correspond plus à la taille annoncée, et **chaque image est ignorée**. On obtient une vidéo noire ou figée sur la dernière bonne image, sans aucun message.
   - Fichiers : `CaptureResolution.java` (l.145-164), `MinemaBackend.java` (l.150-156, 260-283), `VideoParams.java` (l.38-39), `FramebufferFrameSource.java` (l.96-130).
2. **Résolution personnalisée impossible avec Iris.** Le rendu hors écran (`CustomResolutionCapture`) est bloqué volontairement sous Iris et en Fabulous.
   - *Constat de Claude Code, à la relecture (V1.1) :* ce blocage reposait sur une hypothèse fausse. Iris 1.7.2 relit `MinecraftClient.getFramebuffer()` à chaque image. `IrisRenderingPipeline.beginLevelRendering` redimensionne ses cibles de rendu à cette taille, et `FinalPassRenderer` rattache la texture couleur quand elle change. Le framebuffer de capture est donc suivi nativement par Iris : c'est la piste (a) qui a été retenue.
3. **Une taille saisie une fois reste en config.** Par exemple, du 3840×2160 saisi sur l'écran 4K s'applique ensuite à tous les écrans. Aucune taille effective n'est affichée.
4. **Taille modifiée pendant l'enregistrement** (redimensionnement, F11, alt-tab) : la vidéo se fige pour tout le reste de la prise, sans message.
5. **Performance.** La lecture GPU est synchrone, sans PBO, avec une copie mémoire de trop par image. La finalisation de ffmpeg peut **figer le jeu jusqu'à 60 s**. Les erreurs de ffmpeg ne sont pas remontées.
6. Le tracking exporté (Blender/AE) annonce la taille de la fenêtre et non la taille réelle de la vidéo.

### 4.3 Comportement attendu

**R1. Résolution choisie, indépendante de la fenêtre** (comportement 1.12.2/Minema)
- Wixo choisit la résolution dans les réglages vidéo : des préréglages **1080p, 1440p, 4K**, plus une saisie libre (largeur × hauteur).
- La vidéo est rendue **exactement** à cette résolution, que la fenêtre soit plus petite, plus grande, d'une autre proportion, ou **réduite (minimisée)**.
- La valeur `0` (bouton « Taille de la fenêtre ») conserve l'ancien comportement : taille de la fenêtre arrondie au pair.
- La taille de la fenêtre ne change jamais la taille de sortie pendant une prise.

**R2. Compatibilité Iris / Complementary Reimagined (obligatoire)**
- R1 doit fonctionner **avec les shaders actifs**. C'est le cas d'usage principal, pas un cas limite.
- Pistes, à évaluer par Claude Code, qui choisit et justifie :
  - (a) redimensionner temporairement le framebuffer principal et notifier Iris par son chemin de redimensionnement normal (`onResolutionChanged`, via les hooks prévus par Iris) pendant l'enregistrement, puis restaurer à la fin ;
  - (b) redimensionner réellement la fenêtre OS (`glfwSetWindowSize`), comme l'option `aaFastRenderFix` de Minema ; il faudra vérifier les limites de Windows quand la taille dépasse l'écran ;
  - (c) en dernier recours : rendu natif à la taille de la fenêtre, puis **mise à l'échelle** vers la taille demandée, avec un avertissement clair.
- La solution retenue doit être testée avec Complementary Reimagined. Les effets dépendants de la résolution (bloom, flou, AO) doivent avoir le même aspect qu'en jeu.

**R3. Rendu fenêtre réduite / fenêtre cachée**
- L'enregistrement continue quand la fenêtre est minimisée ou recouverte par une autre application. Aujourd'hui, Minecraft ne rend plus rien quand la fenêtre est minimisée (taille 0×0) : il faut forcer le rendu pendant une capture.
- *À valider en jeu ; si c'est impossible avec Iris, le signaler et proposer une alternative.*

**R4. Jamais de vidéo noire ou figée en silence**
- Les dimensions sont toujours paires. Pour une taille impaire, on rogne d'un pixel, ou on met à l'échelle avec des bandes noires si nécessaire.
- Au démarrage de l'enregistrement, un message s'affiche dans le chat. Exemple : « 🎥 Enregistrement 3840×2160 — H.264 — shaders actifs ».
- Si la qualité est dégradée (mise à l'échelle, rognage, repli), un message en **jaune** l'explique.
- En cas d'erreur (ffmpeg absent, ffmpeg arrêté), un message en **rouge** s'affiche avec la vraie cause : les dernières lignes du log ffmpeg.

**R5. Sortie vidéo pour Premiere Pro**
- Par défaut : **MP4 H.264 haute qualité**, `yuv420p`, couleurs étiquetées **BT.709** (pas de décalage de couleurs dans Premiere). Qualité visuellement sans perte (de l'ordre de CRF 16-18 ; valeur exacte à proposer).
- En option dans les réglages : un préréglage **NVENC** (encodage GPU, plus rapide) et un préréglage « quasi sans perte » pour le montage lourd. Le gabarit de commande ffmpeg reste modifiable, comme aujourd'hui.
- Si ffmpeg est introuvable, un message clair s'affiche au démarrage. Le repli PNG doit rester utilisable : écriture optimisée, sans conversion pixel par pixel.

**R6. Performance**
- Lecture GPU asynchrone : anneau de 2 ou 3 PBO, format BGRA, copie directe dans le buffer d'encodage, sans copie intermédiaire.
- La finalisation de ffmpeg (fermeture, attente) ne bloque **jamais** le jeu. Un message « vidéo terminée : <chemin> » s'affiche à la fin.
- Le code de sortie de ffmpeg est vérifié.
- Objectif : enregistrer en 4K avec Complementary Reimagined sans plantage. La capture image par image (temps figé) reste la norme ; le framerate en jeu peut baisser pendant la capture, mais pas la vidéo produite.

**R7. Interface**
- Dans le panneau de capture (Shift+F4 / panneau Minema), afficher :
  - les préréglages et la saisie libre ;
  - le bouton « Taille de la fenêtre » ;
  - la **résolution effective calculée en direct** et le mode utilisé (natif / mis à l'échelle) ;
  - le préréglage d'encodage.
- Corriger les textes de config obsolètes et ajouter la traduction **fr_fr**.

**R8. Tracking**
- Le JSON de tracking exporté annonce la résolution réelle de la vidéo.

### 4.4 Critères d'acceptation (tests en jeu par Wixo)
| # | Test | Résultat attendu |
|---|---|---|
| V1 | Écran 1080p, **fenêtre** (hauteur impaire), shaders actifs, réglage 1080p | Vidéo 1920×1080 correcte, ni noire ni figée |
| V2 | Même écran, réglage **4K** | Vidéo 3840×2160 correcte, shaders identiques à l'aperçu |
| V3 | Écran 4K plein écran, réglage 1080p | Vidéo 1920×1080 correcte |
| V4 | Pendant une prise : redimensionner, F11, alt-tab | La vidéo continue, aucune image figée, taille de sortie inchangée |
| V5 | Fenêtre **minimisée** pendant la prise | La vidéo continue normalement *(si techniquement impossible : message clair)* |
| V6 | Import dans Premiere Pro | Lecture fluide, couleurs identiques au jeu |
| V7 | Fin d'une prise 4K de 2 minutes | Le jeu ne gèle pas, message « vidéo terminée » |
| V8 | ffmpeg renommé (introuvable) | Message rouge explicite, pas de crash |
| V9 | Mêmes tests sans shaders | Tout fonctionne |

### 4.5 Défauts constatés après livraison
*(à remplir par Wixo après les tests)*

*Remarque de Claude Code (livraison wixo.1) :* le préréglage NVENC exige un pilote NVIDIA 610 ou plus récent avec ffmpeg 9.0.1. Le pilote 596.36 installé est refusé, et le message rouge en jeu l'indique.

**Retours de Wixo pendant les tests (2026-10-02), tous corrigés dans wixo.1 :**
- Le choix de la résolution et de l'encodage doit se faire dans les **réglages vidéo Blockbuster** (panneau de config), pas seulement dans Maj+F4. → Ajout d'un bloc Résolution et d'un sélecteur d'encodage dans les réglages.
- Maj+F4 lançait une prise au lieu d'ouvrir le panneau (Maj lu trop tard). → Maj est lu au moment de l'appui.
- **Capture trop lente** (4K : 8 images/s, 15 min pour 2 min de vidéo). Cause mesurée : l'envoi à ffmpeg par blocs de 8 Ko plafonnait à 300 Mo/s. → Envoi par blocs de 4 Mo : 4K à environ 40 images/s.
- Wixo filme en **vertical 9:16**, jusqu'ici en 4K paysage avec le letterbox d'Aperture (seuls 1215×2160 pixels étaient utiles). → Préréglages Vertical 1080×1920 et 2160×3840 : rendu direct, même cadrage.
- Le message « ffmpeg introuvable » n'apparaissait pas à l'entrée dans un monde après un changement de chemin. → Nouvelle vérification quand le chemin change.

**Résultat des tests en jeu (2026-10-02) :** V1 ✅ · V2 ✅ · V4 ✅ · V5 ✅ · V6 ✅ · V7 ✅ · V8 ✅ · V9 ✅, plus une prise caméra Aperture ✅.

---

## 5. Chantier 2 : caméra, FOV et coupes  ✅ (2.7.3-wixo.2, validé en jeu le 2026-10-02)

**Problème :** entre deux plans de FOV différents, on voit 1 ou 2 images de transition. Visible en prévisualisation **et** dans la vidéo.

**Cause trouvée (certaine) :** le runner Aperture est évalué dans `Camera.update` (`CameraMixin`), **après** que `GameRenderer.renderWorld` a déjà lu le FOV (`getFov`) et le roll (`tiltViewWhenHurt`). Le FOV et le roll affichés ont donc toujours **une image de retard** sur la position.
> **Correction (2026-10-02) :** ce retard est propre à la 1.12.2. En 1.20.4, `renderWorld` appelle `Camera.update` **avant** `getFov` et `tiltViewWhenHurt` : FOV, roll et position sont lus sur la même image. Wixo n'a pas reproduit le bug dans le fork. Rien à corriger sur ce point.

**Décisions de Wixo :**
- La coupe doit être **nette sur l'image exacte** : position, rotation, FOV et roll changent ensemble.
- Wixo utilise le roll, Drag, Shake et d'autres modifiers.
- Le Drag (au niveau d'un plan **et** au niveau global) **repart à zéro à chaque coupe**. Vérifier tous les modifiers à état (Shake, etc.) pour le même défaut.

**Autres points relevés :** roll interpolé avec une valeur périmée (`prevRollMode`) ; l'aperçu écrit l'option FOV vanilla à chaque image (spam de logs au-delà de 30-110) ; le filtre de caméra lisse dépend du framerate.
- *Relevé pendant le chantier 1 :* une prise Aperture d'un profil de 30 ticks a produit 96 images au lieu de 90 (0,1 s de trop). Le démarrage et l'arrêt de l'enregistrement par Aperture ne sont pas calés à l'image près : l'arrêt n'est vérifié qu'une fois par image d'interface (`RecordingLifecycle.minema`). Ça rejoint l'exigence de coupes nettes à l'image exacte.

**Corrections (branche `fix/camera-fov`) :**
- **C5, prises calées :** le log debug d'une prise de 30 ticks montrait 2 images avant le démarrage du runner, 3 images en double (le tick 0 durait deux ticks : dans le portage, les opérations de l'éditeur passaient **après** le tick du runner, d'où aussi un décalage d'un tick sur la scène) et 1 image en vue joueur après l'arrêt. → Ordre de tick 1.12.2 rétabli, et une prise Aperture ne garde que les images où le profil joue, dans `[début, fin)`. Prise de contrôle : exactement 90 images.
- **C4 :** l'aperçu n'écrit plus l'option FOV vanilla. **C3 :** le roll n'est plus interpolé avec une valeur périmée.
- **C2 :** seul le Drag garde un état (Shake, Math, etc. sont des fonctions du temps). Il repart à zéro à chaque coupe, global comme de plan, et quand le temps recule (scrub, boucle). Option `aperture.general.drag_reset_on_cut`, active par défaut.
- **C6 :** le roll et le FOV lissés avancent une fois par tick, et non plus une fois par image. Option `aperture.smooth.reference_fps` (60) : même vitesse qu'avant à 60 images/s, quel que soit le framerate.

**Reportés (Wixo ne s'en sert pas, 2026-10-02) :**
- Le Drag lisse une fois par **image** : son effet dépend du framerate (aperçu à 144 images/s ≠ prise à 60 images/s).
- Caméra lisse : quand l'accélération du FOV retombe à zéro, le FOV revient à l'option vanilla au lieu de rester où il a été amené (constaté dans le code, non vérifié en jeu).

---

## 6. Chantier 3 : replay (blocs, explosions, monde)  📝 (décisions prises, à détailler)

**Workflow de Wixo :** saut dans la timeline, ré-enregistrement au milieu d'une prise, enregistrement d'un acteur pendant que les autres rejouent. Solo. Scènes de 4 à 10 acteurs, parfois 20 ou plus : **aucune limite** d'acteurs.

**Défauts constatés par Wixo :** bloc qui réapparaît ou n'est pas cassé, bloc fantôme posé, bloc cassé ou posé au mauvais endroit, *(autres à compléter en test)*.

**Décisions :**
- **Priorité absolue :** quand on revient en arrière dans la timeline, ou qu'on coupe le replay, le monde est **entièrement réparé** : blocs, contenu des conteneurs, entités détruites.
- Explosions **identiques à chaque lecture** : on enregistre le résultat (blocs détruits), on ne re-simule pas.
- **Aucun drop** pendant un replay.
- Seuls les **vrais coups** portés à une entité sont rejoués. Fin des attaques fantômes à chaque mouvement de bras.

**Causes principales relevées :**
- explosions non enregistrées ;
- clic droit rejoué deux fois (objet puis bloc), d'où des blocs fantômes ;
- pose de bloc appliquée deux fois ;
- contenu des coffres perdu à la restauration ;
- rembobinage qui rejoue au lieu d'annuler ;
- avance rapide acteur par acteur au lieu de suivre l'ordre chronologique ;
- action du tick de départ appliquée deux fois ;
- dérive entre les ticks serveur (actions) et les ticks client (mouvements) en cas de lag ;
- changement d'objet en main enregistré après son utilisation ;
- seau mal placé ;
- fissures de minage partagées entre acteurs ;
- ré-enregistrement au milieu qui garde les anciennes actions ;
- damage control limité à 64 blocs autour d'un seul acteur.

---

## 7. Chantier 4 : morphs  📝 (décisions prises, à détailler)

**Modèles utilisés :** Blockbench (Chameleon), modèles Blockbuster (`model.json`), OBJ/VOX, mobs vanilla, structures.
**Défauts constatés :** position des **objets en main** et des **body parts / accessoires**.
**Causes relevées :**
- pose du membre mal restaurée entre les deux mains (`LayerHeldItem`) ;
- décalage de 0,2 bloc compté deux fois en position accroupie (`LayerBodyPartFeature`) ;
- crâne porté décalé d'un demi-bloc ;
- bras invisible en vue première personne avec un morph de mob ;
- à cheval, le corps ne suit pas la monture ;
- hitbox et pseudo des acteurs qui ne suivent pas la taille du morph ;
- mobs morphés sans animation de marche ni d'attaque ;
- mauvaise texture sur certains membres avec OBJ/VOX.

**Contrainte :** les modèles 1.12.2 se chargent et s'affichent à l'identique.

---

## 8. Chantier 5 : optimisation et robustesse  📝 (décisions prises, à détailler)

**Lags constatés par Wixo :** longues prises, lancement d'une scène.
**Causes principales :**
- chargement et sauvegarde des enregistrements en bloquant le jeu ;
- rejeu de toutes les actions depuis le tick 0 au lancement ;
- OBJ et structures recalculés à chaque image ;
- parcours du disque 20 fois par seconde (`StructureMorph.checkStructures`) ;
- skins URL téléchargés sur le thread de rendu ;
- fuites de fichiers sous Windows ;
- cache client jamais vidé ;
- allocations dans les boucles de rendu.

**Compatibilité :** Iris obligatoire. Sodium à tester et à recommander s'il améliore les performances.
**Sécurité réseau** (désérialisation, permissions) : priorité basse, car usage en solo. À corriger quand même, pour ne pas laisser de faille si le mod sert un jour sur un serveur.

---

## 9. Mission 2 : améliorations (à définir dans le CDC V2)
Éditeur caméra · Export vidéo · Éditeur d'enregistrements / timeline · Morphs / modèles.

---

## Annexe A : mise en place sur le PC de Wixo
1. Installer **Temurin JDK 21** (en plus du JDK 25 déjà présent).
2. Dans IntelliJ : *Settings → Build Tools → Gradle → Gradle JVM* = JDK 21. Le projet compile toujours en Java 17.
3. Forker `mchorse/blockbuster-1204` sur GitHub, cloner, ouvrir le dossier dans IntelliJ.
4. `gradlew.bat build` produit le jar dans `build/libs/`. `gradlew.bat runClient` lance un Minecraft de test.
5. Pour les tests avec shaders : copier le jar dans l'instance CurseForge 1.20.4 qui contient Iris et Complementary Reimagined.
6. Remarque : la variable `_JAVA_OPTIONS=-Xmx4G` est définie globalement sur le PC. Elle s'applique à **tous** les programmes Java, Gradle et Minecraft compris.
