# Changelog — Blockbuster (fork Wixo)

## 2.7.3-wixo.2 — chantier 2 : caméra, FOV et coupes (validé en jeu le 2026-10-02)

### Prises Aperture calées à l'image près
- Un profil de N ticks donne exactement N × (images/s ÷ 20) images : 90 images pour 30 ticks à 60 images/s, au lieu de 96.
- Plus d'images avant le démarrage du profil ni d'image en vue joueur après la fin. Seules les images de la plage choisie sont enregistrées.
- Le premier tick du profil ne dure plus deux ticks : la caméra n'a plus un tick de retard sur la scène (ordre de tick de la 1.12.2 rétabli).

### Roll et FOV
- L'aperçu de l'éditeur n'écrit plus l'option FOV de Minecraft : ton FOV n'est plus modifié, et plus de « Illegal option value » dans le log.
- Le roll n'est plus mélangé à une valeur périmée après la lecture d'une scène.
- Coupes entre plans de roll et de FOV différents : nettes sur l'image exacte (vérifié ; le retard d'une image de la 1.12.2 n'existe pas en 1.20.4).

### Modifiers
- **Drag** : il repart à zéro à chaque coupe (global comme de plan) et quand on revient en arrière (scrub, boucle). Option Aperture › Général › « Drag remis à zéro à chaque coupe », activée par défaut.

### Caméra lisse
- Le roll et le FOV lissés ne dépendent plus du framerate. Option Aperture › Caméra lisse › « Images/s de référence » (60 par défaut : même vitesse qu'avant à 60 images/s).

### Diagnostic
- Mode debug vidéo : le log indique pour chaque image l'état de la caméra (en lecture, tick, fraction de tick), et les images écartées hors prise.

## 2.7.3-wixo.1 — chantier 1 : vidéo (validé en jeu le 2026-10-02 : V1 à V9)

### Résolution (CDC R1, R2, R4)
- La vidéo a toujours la taille choisie, toujours paire, quelle que soit la fenêtre (plus petite, plus grande, autre proportion, minimisée).
- **Fonctionne avec les shaders Iris** : le monde est rendu directement à la résolution choisie (option `Résolution choisie avec shaders`, activée par défaut).
- Fenêtre de taille impaire : rendu à la taille paire. Si c'est impossible, la ligne en trop est rognée.
- Si le rendu à la taille choisie est refusé, la fenêtre est mise à l'échelle avec des bandes noires, et un message jaune l'explique.
- Fenêtre redimensionnée pendant une prise : la vidéo garde sa taille, plus d'image noire ou figée.
- **Vidéo verticale native** : préréglages Vertical 1080×1920 et 2160×3840. Même cadrage que le letterbox 9:16 d'Aperture, sans rendre les côtés perdus.

### Fenêtre minimisée, alt-tab (CDC R3)
- Pendant une prise, la perte de focus n'ouvre plus le menu pause, qui figeait la scène.

### Messages (CDC R4)
- Dans le chat : début d'enregistrement (taille, encodage, shaders), dégradations en jaune, erreurs en rouge avec les dernières lignes du log ffmpeg, et « Vidéo terminée : <chemin> ».
- ffmpeg introuvable : message rouge à l'entrée dans un monde (revérifié si le chemin change) et au lancement d'une prise.

### Encodage (CDC R5)
- Préréglages : **H.264 qualité 1.12.2** (défaut, réglages Minema), **H.264 qp 10**, **NVENC** et **personnalisé** (anciens gabarits `video.arguments*`).
- Couleurs converties et étiquetées en **BT.709** : plus de décalage dans Premiere.
- Les configs existantes migrent toutes seules : si les gabarits avaient été modifiés, le préréglage devient « personnalisé ».
- Repli PNG sans conversion pixel par pixel.

### Performance (CDC R6)
- Lecture GPU asynchrone (anneau de 3 PBO, BGRA), sans copie intermédiaire.
- **Envoi à ffmpeg par blocs de 4 Mo** au lieu de 8 Ko. Le tuyau plafonnait à 300 Mo/s : en 4K avec Complementary, on passe de 8 à environ 40 images/s (2 min de vidéo : 15 min → 3 min).
- La fermeture de ffmpeg se fait en arrière-plan : le jeu ne gèle plus à la fin d'une prise. Le code de sortie est vérifié.

### Interface (CDC R7)
- **Réglages vidéo Blockbuster** : bloc Résolution (1080p, 1440p, 4K, Taille de la fenêtre, Vertical 1080×1920, Vertical 2160×3840, largeur et hauteur libres, résolution effective en direct) et sélecteur d'encodage.
- Maj+F4 : les mêmes boutons et le même choix d'encodage. Maj est lu au moment de l'appui (un Maj+F4 rapide lançait une prise).
- La résolution effective s'affiche aussi dans le panneau d'enregistrement de l'éditeur caméra.
- Commentaires de config pour toutes les options vidéo, traduction française.
- Option `Mode debug` : dans `latest.log`, temps par image et par étape (rendu GPU, lecture, copie, envoi à ffmpeg).

### Tracking (CDC R8)
- Le JSON de tracking annonce la taille réelle de la vidéo.

### Build
- Loom fixé en 1.6.12 (au lieu de `1.6-SNAPSHOT`).
- Tests unitaires réactivés (`src/test`, JUnit 5), lancés par `gradlew build`. Le préréglage par défaut est testé avec le vrai ffmpeg quand il est installé.
- Tâche `installToInstance` (instance Prism dans `local.properties`) à la place de `installToMultiMC`.
