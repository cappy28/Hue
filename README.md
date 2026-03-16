# 💡 LumiControl — Application Android de contrôle d'ampoules Bluetooth

> Application 100% gratuite, open-source, sans cloud, sans collecte de données.

---

## 📋 Table des matières

1. [Présentation](#présentation)
2. [Fonctionnalités](#fonctionnalités)
3. [Architecture du projet](#architecture)
4. [Installation & Compilation](#installation)
5. [Guide de test](#guide-de-test)
6. [Ajouter une nouvelle ampoule](#ajouter-une-ampoule)
7. [Protocole Bluetooth BLE](#protocole-ble)
8. [Structure des fichiers](#structure-des-fichiers)

---

## Présentation

**LumiControl** est une application Android native (Kotlin) permettant de contrôler des ampoules connectées via Bluetooth Low Energy (BLE). Elle est compatible avec la majorité des ampoules BLE génériques disponibles sur le marché (YeeLight BLE, MIPOW, ampoules génériques chinoises à protocole GATT standard).

- **Aucun paiement / achat intégré**
- **Aucune collecte de données personnelles**
- **100% local via Bluetooth**
- **Open-source (Apache 2.0)**

---

## Fonctionnalités

### Connexion
- Scan automatique des appareils BLE environnants
- Connexion simultanée à plusieurs ampoules (jusqu'à 7 en BLE)
- Historique des appareils connus pour reconnexion rapide
- Indicateur d'état de connexion en temps réel

### Contrôle des lumières
- Allumer / éteindre (individuel ou groupe)
- Sélecteur de couleur RVB avec palette visuelle
- Curseur de luminosité (0–100%)
- Température de couleur (blanc chaud/froid)
- **Modes préconfigurés** : Relax 🟠, Lecture 📖, Fête 🎉, Sommeil 😴, Réveil ☀️
- **Effets dynamiques** : Fade, Clignotement, Cycle couleurs, Réactivité musicale

### Automatisation
- Planificateur horaire (allumer/éteindre/changer couleur)
- Minuteur et compte à rebours
- Détection de présence via capteur de proximité
- Effets synchronisés multi-ampoules

### Interface
- Material Design 3 (Material You)
- Mode sombre / clair automatique
- Groupes et favoris d'ampoules
- Widget écran d'accueil

### Fonctions avancées
- Synchronisation musicale (microphone → couleur/luminosité)
- Éditeur d'animations DIY
- Export/import de scènes (JSON)

---

## Architecture

```
LumiControl
├── bluetooth/          # Gestion BLE (scan, connexion, commandes GATT)
│   ├── BleManager.kt
│   ├── BleScanner.kt
│   └── BulbGattProfile.kt
├── models/             # Modèles de données
│   ├── Bulb.kt
│   ├── Scene.kt
│   ├── Schedule.kt
│   └── BulbGroup.kt
├── ui/
│   ├── activities/     # Activités principales
│   ├── fragments/      # Fragments UI
│   ├── adapters/       # RecyclerView adapters
│   └── viewmodels/     # ViewModels (MVVM)
├── services/           # Services arrière-plan
│   ├── BleService.kt
│   ├── MusicSyncService.kt
│   └── SchedulerService.kt
├── data/
│   ├── database/       # Room database (SQLite local)
│   └── repository/     # Repositories
└── utils/              # Utilitaires (couleurs, animations, etc.)
```

**Pattern** : MVVM + Repository + Room + Coroutines

---

## Installation

### Prérequis
- Android Studio Hedgehog (2023.1) ou supérieur
- SDK Android 26+ (Android 8.0)
- Appareil physique Android avec Bluetooth (BLE requis pour les tests réels)

### Étapes

```bash
# 1. Cloner le projet
git clone https://github.com/votre-repo/lumicontrol.git
cd lumicontrol

# 2. Ouvrir dans Android Studio
# File > Open > sélectionner le dossier LumiControl/

# 3. Synchroniser Gradle
# Android Studio > Sync Project with Gradle Files

# 4. Compiler et installer
./gradlew installDebug
# OU utiliser le bouton Run ▶ dans Android Studio
```

### Sur appareil physique
1. Activer **Options développeur** sur votre téléphone
2. Activer **Débogage USB**
3. Connecter via USB et accepter la connexion
4. Cliquer **Run** dans Android Studio

### Permissions requises (AndroidManifest)
```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

---

## Guide de test

### Test sans ampoule réelle (émulateur BLE)
Utiliser **nRF Connect** (app Android) pour simuler un périphérique BLE GATT.

1. Installer nRF Connect sur un second téléphone
2. Créer un serveur GATT avec les UUIDs standards (voir section BLE)
3. LumiControl le détectera comme une ampoule compatible

### Test avec ampoules compatibles
| Marque | Modèle | Compatibilité |
|--------|--------|---------------|
| MIPOW | PLAYBULB Sphere | ✅ Complète |
| YeeLight | YLDP06YL | ✅ Complète |
| Generique | RGB BLE bulb | ✅ Basique |
| Philips Hue | (ZigBee) | ❌ Protocole différent |

---

## Ajouter une nouvelle ampoule

### 1. Identifier le profil GATT de l'ampoule
Utiliser **nRF Connect** pour lire les UUIDs du service et des caractéristiques.

### 2. Créer un profil dans `BulbGattProfile.kt`

```kotlin
// Ajouter dans l'enum BulbType
NEW_BRAND {
    override val serviceUUID = UUID.fromString("0000XXXX-0000-1000-8000-00805f9b34fb")
    override val colorCharUUID = UUID.fromString("0000YYYY-0000-1000-8000-00805f9b34fb")
    override val brightnessCharUUID = UUID.fromString("0000ZZZZ-0000-1000-8000-00805f9b34fb")
    
    override fun buildColorCommand(r: Int, g: Int, b: Int, brightness: Int): ByteArray {
        // Construire la commande selon le protocole de la marque
        return byteArrayOf(r.toByte(), g.toByte(), b.toByte(), brightness.toByte())
    }
}
```

### 3. Ajouter la détection automatique dans `BleScanner.kt`

```kotlin
fun detectBulbType(scanResult: ScanResult): BulbType {
    val name = scanResult.device.name ?: ""
    return when {
        name.startsWith("NEW_BRAND") -> BulbType.NEW_BRAND
        // ... autres cas
        else -> BulbType.GENERIC
    }
}
```

---

## Protocole BLE

### UUIDs standards utilisés

```
Service principal        : 0000FFD5-0000-1000-8000-00805F9B34FB
Caractéristique couleur  : 0000FFD9-0000-1000-8000-00805F9B34FB
Caractéristique état     : 0000FFD4-0000-1000-8000-00805F9B34FB

Format commande couleur (7 bytes) :
[0x56, R, G, B, 0x00, 0xF0, 0xAA]

Allumer  : [0xCC, 0x23, 0x33]
Éteindre : [0xCC, 0x24, 0x33]

Luminosité (0-255) :
[0x56, 0x00, 0x00, 0x00, brightness, 0x0F, 0xAA]
```

---

## Structure des fichiers

```
app/src/main/
├── java/com/lumicontrol/
│   ├── bluetooth/
│   │   ├── BleManager.kt          ← Gestionnaire central BLE
│   │   ├── BleScanner.kt          ← Scan et découverte
│   │   └── BulbGattProfile.kt     ← Profils GATT par marque
│   ├── models/
│   │   ├── Bulb.kt                ← Modèle ampoule
│   │   ├── Scene.kt               ← Scène personnalisée
│   │   ├── Schedule.kt            ← Planification horaire
│   │   └── BulbGroup.kt           ← Groupe d'ampoules
│   ├── ui/
│   │   ├── activities/
│   │   │   ├── MainActivity.kt    ← Écran principal
│   │   │   ├── ScanActivity.kt    ← Scan BLE
│   │   │   └── SettingsActivity.kt
│   │   ├── fragments/
│   │   │   ├── HomeFragment.kt    ← Dashboard ampoules
│   │   │   ├── ColorFragment.kt   ← Sélecteur couleur
│   │   │   ├── ScenesFragment.kt  ← Scènes
│   │   │   └── ScheduleFragment.kt
│   │   ├── adapters/
│   │   │   ├── BulbAdapter.kt     ← Liste ampoules
│   │   │   └── SceneAdapter.kt
│   │   └── viewmodels/
│   │       ├── BulbViewModel.kt
│   │       └── SceneViewModel.kt
│   ├── services/
│   │   ├── BleService.kt          ← Service BLE arrière-plan
│   │   ├── MusicSyncService.kt    ← Synchro musicale
│   │   └── SchedulerService.kt    ← Planificateur
│   ├── data/
│   │   ├── database/
│   │   │   ├── AppDatabase.kt     ← Room database
│   │   │   ├── BulbDao.kt
│   │   │   └── SceneDao.kt
│   │   └── repository/
│   │       ├── BulbRepository.kt
│   │       └── SceneRepository.kt
│   └── utils/
│       ├── ColorUtils.kt          ← Conversion couleurs HSV/RGB
│       ├── AnimationUtils.kt      ← Effets lumineux
│       └── PrefsManager.kt        ← SharedPreferences
└── res/
    ├── layout/                    ← Fichiers XML des layouts
    ├── values/                    ← Couleurs, strings, thèmes
    └── drawable/                  ← Icônes et assets
```
