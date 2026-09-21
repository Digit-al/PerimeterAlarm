# Périmètre Alarme 📍🔔

Application Android qui **déclenche une alarme lorsque vous entrez dans un périmètre** défini autour d'une localisation choisie sur une carte OpenStreetMap.

- **Langage / UI** : Kotlin + Jetpack Compose (Material 3)
- **Carte** : [osmdroid](https://osmdroid.org/) (tuiles OpenStreetMap, aucune clé API requise)
- **Persistance** : SharedPreferences + Gson (pas de base de données)
- **minSdk** 26 (Android 8.0) · **targetSdk** 34 (Android 14)

📄 Also available in English: [README.md](README.md)

---

## Fonctionnalités

### Écran d'accueil
- Liste des alarmes programmées.
- Pour chaque alarme : **bouton d'édition** ✏️ et **toggle d'activation** (interrupteur).
- Un point vert indique une alarme **active et dans sa période de validité** en ce moment.
- **Distance à l'entrée** du périmètre affichée en direct pour les alarmes actives et dans leur période de validité (« à X m à l'entrée » ou « dans la zone »).
- **Bouton « + »** en bas à droite pour ajouter une alarme, et un bouton **🐞 Debug** dans la barre de titre.

### Édition d'une alarme
Une alarme se compose de :

1. **Localisation** — choisie sur la carte OpenStreetMap :
   - touchez la carte pour déplacer le repère,
   - bouton **« Ma position »** pour centrer sur votre position actuelle,
   - bouton **« Recadrer »** pour ajuster le zoom sur le cercle.

2. **Périmètre** (cercle) — trois éléments **synchronisés en temps réel** :
   - le **cercle visuel** autour de la localisation sur la carte,
   - un **slider** pour agrandir/réduire le rayon (10 m → 5 km),
   - un **champ texte** pour saisir une valeur exacte en mètres.
   - Modifier l'un des trois met à jour instantanément les deux autres.

3. **Période de validité** :
   - **toggle « Ponctuelle »** — l'alarme est activée manuellement et se désactive automatiquement après le premier déclenchement (pas de période récurrente),
   - des **cases à cocher** pour chaque **jour de la semaine** (Lun → Dim),
   - **heure de début** et **heure de fin** (sélecteurs d'heure, gère les périodes qui traversent minuit),
   - ou un simple **toggle « Toujours active »** (24 h/24, 7 j/7).
   - Les jours et heures sont masqués quand « Ponctuelle » est sélectionnée.

4. **Sonnerie & vibration** (spécifique à l'alarme) :
   - toggle « utiliser les réglages par défaut » (sinon réglages personnalisés),
   - **vibreur** (on/off), **volume** (slider 0–100 %), **sonnerie** (sélecteur système — affiche toutes les sonneries disponibles, ou défaut système).
   - Un **bouton ×** réinitialise la sonnerie au défaut système.
   - Le son de l'alarme joue sur le flux **volume alarme** système (`USAGE_ALARM`) — indépendant du volume média, et audible même en mode silencieux ; le slider règle un pourcentage de ce flux.

### Logique de vérification dynamique
Lorsqu'une alarme est **activée** et **dans sa période de validité**, la position est vérifiée à des intervalles dynamiques :

- **toutes les 30 s** au début,
- puis l'intervalle est calculé à partir de la **distance à l'entrée du périmètre** (et non au centre) et de votre **vitesse de rapprochement** :

  ```
  distance à l'entrée      =  max(0, distance au centre - rayon)
  durée d'arrivée estimée  =  distance à l'entrée / vitesse de rapprochement
  prochain intervalle      =  durée d'arrivée estimée / 2
  ```

  borné entre un **minimum** et un **maximum** (par défaut 30 s et 5 min) :
  - **à moins de 100 m de l'entrée** (ou à l'intérieur du périmètre) → vérifications à l'intervalle minimum (déclenchement immédiat attendu près de la frontière) ;
  - si vous vous rapprochez vite → vérifications plus fréquentes (jusqu'au minimum, 30 s) ;
  - si vous êtes **à l'arrêt** (bouchon, discussion, …) ou en déplacement lent → l'intervalle est plafonné par le temps de couvrir la distance restante à une vitesse de « reprise » réaliste (≈ une voiture en ville) : ≈ 30 s sous 600 m, ≈ 1 min à 1 km, ≈ 2 min à 2 km — au lieu du maximum complet ;
  - si vous êtes loin et ne vous rapprochez pas → vérifications espacées (jusqu'au maximum, 5 min).

- **Déclenchement** : quand la distance devient ≤ au rayon, une alarme (notification haute priorité + son + vibration) est émise. Une **hystérésis** de 15 % évite les re-déclenchements tant que vous restez dans la zone.

### Optimisation batterie (sommeil ciblé)
Quand aucune alarme n'est active et dans sa période, le service **dort jusqu'au prochain début de période** (calculé à partir des jours + heures, jusqu'à 8 jours devant) au lieu de poller toutes les 30 s. Sauvegarder une alarme réveille le service immédiatement. Pour les sommeils de plus de 10 minutes, le service planifie en plus un **réveil résistant au Doze** (`AlarmManager.setAlarmClock`) : il se déclenche à l'heure même si l'appareil est en mode Doze, et une icône horloge est affichée dans la barre d'état tant que le réveil est en attente. Sur Android 12+ (ciblant le SDK 31+), cela nécessite la permission `SCHEDULE_EXACT_ALARM` : elle est **refusée par défaut pour les apps ciblant le SDK 33+** (on cible le 34), l'utilisateur l'accorde donc en un geste : l'app affiche **automatiquement une carte d'avertissement sur l'écran d'accueil** (et une carte d'état dans Réglages) dont le bouton ouvre l'écran système dédié à l'app. Si elle manque, l'app vérifie `canScheduleExactAlarms()` et dégrade gracieusement en sommeil inexact simple.

### Paramètres
La page **Paramètres** permet de configurer :
- l'**intervalle minimum** (secondes, défaut 30),
- l'**intervalle maximum** (secondes, défaut 300 = 5 min),
- la **langue** : *Auto* (suit la langue du téléphone), *Anglais* ou *Français*,
- l'**alarme par défaut** : vibreur, volume et sonnerie (appliquée aux alarmes qui n'ont pas de réglage personnalisé),
- **Sauvegarde & restauration** : exporter toute la configuration (paramètres + alarmes) vers un fichier JSON, ou l'importer depuis un fichier précédemment exporté (Storage Access Framework — aucune permission supplémentaire requise).

### Page de debug
Accessible via le bouton 🐞 de l'accueil. Elle **journalise l'état de toutes les alarmes toutes les 30 secondes**, mais uniquement tant que la page est visible ; chaque ligne commence par la **date/heure** et indique : actif, dans la période de validité, distance à l'entrée du périmètre, vitesse de rapprochement, et l'instant (ou le délai) du **prochain rafraîchissement** du service. Un compte à rebours affiche le délai avant la prochaine mise à jour.

---

## Architecture

```
app/src/main/java/fr/rsgnl/perimetre/
├── MainActivity.kt                  # Activité Compose unique + permissions + routage
├── PerimetreApp.kt                  # Application : applique la langue choisie au démarrage
├── data/
│   ├── Alarm.kt                     # Modèle Alarm + SoundSettings + AppSettings + AppLanguage
│   └── AlarmRepository.kt           # Persistance SharedPreferences + Gson
├── ui/
│   ├── AppViewModel.kt              # État (alarmes, paramètres, navigation, langue)
│   ├── HomeScreen.kt                # Liste + toggle + édition + FAB
│   ├── EditorScreen.kt              # Carte + périmètre + période + son
│   ├── SettingsScreen.kt            # Intervalles + langue + alarme par défaut
│   ├── DebugScreen.kt               # Journalisation d'état (30 s, visible seulement)
│   ├── Strings.kt                   # Helper des noms de jours localisés
│   ├── theme/Theme.kt               # Thème Material 3
│   └── components/
│       ├── OsmMap.kt                # Carte osmdroid (marqueur, cercle, tap)
│       └── Widgets.kt               # Observeur position, sélecteur jours, éditeur son
├── service/
│   ├── LocationMonitorService.kt    # Foreground service : surveillance + alarme
│   ├── BootReceiver.kt              # Redémarrage après reboot
│   └── WakeReceiver.kt              # Réveil résistant au Doze (longs sommeils)
└── util/
    ├── Geo.kt                       # Haversine (distance)
    ├── TimeUtils.kt                 # Période de validité + prochain début + formatage
    ├── LocationUtils.kt             # Dernière position connue
    ├── Format.kt                    # Formatage des distances
    └── RingtoneUtils.kt             # Lister les sons d'alarme de l'appareil
```

### Service de surveillance
`LocationMonitorService` est un **foreground service** (type `location`) qui :
1. demande un **fix GPS unique** à chaque vérification (GPS allumé ~5-15 s par cycle → batterie minimale),
2. pour chaque alarme active et dans sa période, maintient un **état** (dernière distance, vitesse, prochain intervalle, état déclenché),
3. planifie la prochaine vérification via la formule dynamique ci-dessus,
4. déclenche l'alarme à l'entrée dans le périmètre,
5. pour les **longs sommeils** (aucune alarme active, à des heures/jours), planifie un réveil résistant au Doze via `AlarmManager.setAlarmClock` (géré par `WakeReceiver`).

Le service démarre automatiquement s'il existe au moins une alarme activée (y compris au redémarrage de l'appareil via `BootReceiver`), et s'arrête quand aucune alarme n'est active.

---

## Build & installation

### A) Avec Android Studio (recommandé)
1. Ouvrez le dossier `PerimetreAlarm` dans Android Studio.
2. Laissez la synchronisation Gradle se terminer.
3. Appuyez sur **Run ▶** (ou `./gradlew installDebug`).

### B) En ligne de commande
Prérequis : JDK 17+ et Android SDK (platform 34, build-tools 34.0.0).

```bash
# définir le SDK si nécessaire
echo "sdk.dir=/chemin/vers/le/android-sdk" > local.properties

# générer l'APK de debug
./gradlew :app:assembleDebug

# APK produit :
# app/build/outputs/apk/debug/app-debug.apk

# ou installer directement sur un appareil connecté (adb)
./gradlew :app:installDebug
```

> L'APK de debug est signé avec la clé de debug : c'est suffisant pour un test,
> mais pour une publication il faudra configurer un keystore de release.

### Permissions demandées
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — position
- `POST_NOTIFICATIONS` (Android 13+) — notifications d'alarme
- `READ_MEDIA_AUDIO` (Android 13+) / `READ_EXTERNAL_STORAGE` (< 13) — audio (pour la liste des sonneries ; le sélecteur système lui-même n'en a pas besoin)
- `FOREGROUND_SERVICE(_LOCATION)` — service de surveillance
- `VIBRATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `INTERNET`

---

## Notes
- **Aucune clé API** : les tuiles proviennent de `tile.openstreetmap.org` (attribution osmdroid). Pour un usage intensif, pensez à respecter la charte OSM ou à brancher votre propre serveur de tuiles.
- La **batterie** est épargnée grâce aux intervalles dynamiques (pas de polling en continu), au sommeil ciblé jusqu'au prochain début de période, et au foreground service.
- La **carte** affiche en permanence la **position actuelle** (point bleu, rafraîchie) par rapport à la localisation et au périmètre de l'alarme ; le zoom initial cadre le cercle (sur la position actuelle pour une nouvelle alarme, ou sur le point configuré en édition).
- **Langues** : anglais (défaut) et français. « Auto » suit la langue du téléphone ; une langue précise peut être imposée depuis les Paramètres.
- Pour passer en **release** : créez un keystore, puis `./gradlew :app:bundleRelease` ou `assembleRelease`.
