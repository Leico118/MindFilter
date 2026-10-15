# MindFilter

Application Android native (Kotlin, Jetpack Compose) qui masque automatiquement, dans les applications choisies (Facebook, Instagram, Chrome, X, Reddit…), les publications liées aux sujets anxiogènes sélectionnés par l'utilisateur.

## Télécharger l'APK

Chaque envoi sur `main` déclenche le workflow **Compiler MindFilter** (onglet *Actions*). Si la compilation réussit, l'APK est publié :

- dans **Releases → MindFilter 0.1 → `MindFilter-v0.1.apk`** (téléchargement direct) ;
- comme artefact `MindFilter-v0.1` de l'exécution du workflow (fichier ZIP).

En cas d'échec, une *issue* « Échec de compilation » est ouverte automatiquement avec l'extrait du journal.

## Fonctionnement

| Élément | Fichier |
|---|---|
| Service d'accessibilité (lecture de l'arbre, anti-rebond, thread d'analyse) | `service/MindFilterAccessibilityService.kt` |
| Choix des zones à masquer (élément de liste / carte le plus pertinent) | `service/ScreenScanner.kt` |
| Masques « verre dépoli » + capsule « Masqué » | `overlay/MaskView.kt`, `overlay/MaskOverlayManager.kt` |
| Moteur de détection (mots-clés, préfixes, co-occurrences pondérées) | `engine/Classifier.kt` |
| Catégories → sous-catégories → thèmes et lexique | `data/Taxonomy.kt` |
| Préférences locales | `data/AppSettings.kt` |
| Interface (Accueil, Catégories, Applis, Filtres, Réglages) | `ui/` |

- **Sensibilité** : léger (seuil 4), normal (3), strict (2). Mot-clé fort = 4 points, moyen = 2, faible = 1.
- **Extension IA** : l'interface `ContentClassifier` permet d'ajouter plus tard un modèle local de classification sémantique.
- **Défilement** : les masques suivent le défilement (décalage immédiat via `scrollDeltaY`, puis recalcul). En mode *Protégé*, un appui sur un masque est absorbé et un glissement est rejoué sur l'application (`dispatchGesture`).
- **Confidentialité** : aucune autorisation Internet, aucun stockage de texte, champs de saisie et mots de passe ignorés.

## Limites connues (v0.1)

- Android n'autorise pas une application à flouter le contenu d'une autre : le « verre dépoli » est simulé par une surface opaque.
- Les images et vidéos sans texte ne sont pas analysées ; seul le texte exposé à l'accessibilité l'est.
- La qualité du découpage dépend de la structure d'accessibilité de chaque application (non testée sur appareil réel à ce stade).
- Signature : clé de débogage fixe (`keystore/`), pour que les versions suivantes s'installent par-dessus.
