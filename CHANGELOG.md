# Changelog — Blockbuster (fork Wixo)

## 2.7.3-wixo.1 — chantier 1 : vidéo (à tester en jeu)

### Résolution (CDC R1, R2, R4)
- La vidéo a toujours la taille choisie, toujours paire, quelle que soit la fenêtre (plus petite, plus grande, autre proportion, minimisée).
- **Fonctionne avec les shaders Iris** : le monde est rendu directement à la résolution choisie (option `Résolution choisie avec shaders`, activée par défaut).
- Fenêtre de taille impaire : rendu à la taille paire. Si c'est impossible, la ligne en trop est rognée.
- Si le rendu à la taille choisie est refusé, la fenêtre est mise à l'échelle avec des bandes noires, et un message jaune l'explique.
- Fenêtre redimensionnée pendant une prise : la vidéo garde sa taille, plus d'image noire ou figée.

### Fenêtre minimisée, alt-tab (CDC R3)
- Pendant une prise, la perte de focus n'ouvre plus le menu pause, qui figeait la scène.

### Messages (CDC R4)
- Dans le chat : début d'enregistrement (taille, encodage, shaders), dégradations en jaune, erreurs en rouge avec les dernières lignes du log ffmpeg, et « Vidéo terminée : <chemin> ».
- ffmpeg introuvable : message rouge à l'entrée dans un monde et au lancement d'une prise.

### Encodage (CDC R5)
- Préréglages : **H.264 qualité 1.12.2** (défaut, réglages Minema), **H.264 qp 10**, **NVENC** et **personnalisé** (anciens gabarits `video.arguments*`).
- Couleurs converties et étiquetées en **BT.709** : plus de décalage dans Premiere.
- Les configs existantes migrent toutes seules : si les gabarits avaient été modifiés, le préréglage devient « personnalisé ».
- Repli PNG sans conversion pixel par pixel.

### Performance (CDC R6)
- Lecture GPU asynchrone (anneau de 3 PBO, BGRA), sans copie intermédiaire.
- La fermeture de ffmpeg se fait en arrière-plan : le jeu ne gèle plus à la fin d'une prise. Le code de sortie est vérifié.

### Interface (CDC R7)
- Maj+F4 : boutons 1080p / 1440p / 4K / Taille de la fenêtre, choix de l'encodage, résolution effective affichée en direct (aussi dans le panneau de l'éditeur caméra).
- Commentaires de config pour toutes les options vidéo, traduction française.
- Option `Mode debug` (temps de lecture GPU dans `latest.log`).

### Tracking (CDC R8)
- Le JSON de tracking annonce la taille réelle de la vidéo.

### Build
- Loom fixé en 1.6.12 (au lieu de `1.6-SNAPSHOT`).
- Tests unitaires réactivés (`src/test`, JUnit 5), lancés par `gradlew build`. Le préréglage par défaut est testé avec le vrai ffmpeg quand il est installé.
- Tâche `installToInstance` (instance Prism dans `local.properties`) à la place de `installToMultiMC`.
