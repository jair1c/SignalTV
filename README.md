# SignalTV

App IPTV con panel admin oculto, Firebase Firestore y compilación automática de APK via GitHub Actions.

## Estructura del repositorio

```
├── index.html              ← Web app (desplegada en Vercel)
├── android-app/            ← Proyecto Android
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/signaltv/app/
│   │   │   ├── MainActivity.kt     (Phone / Tablet)
│   │   │   └── TvActivity.kt       (Android TV)
│   │   └── res/
│   └── build.gradle
└── .github/workflows/
    └── build-apk.yml       ← CI/CD automático
```

## Setup inicial

### 1. Cambiar URL de Vercel en la app

Edita `android-app/app/src/main/java/com/signaltv/app/MainActivity.kt`:
```kotlin
const val APP_URL = "https://TU-PROYECTO.vercel.app"  // ← tu URL real
```

### 2. Generar el Keystore (firma de la APK)

Solo necesitas hacerlo una vez:
```bash
keytool -genkey -v -keystore signaltv.jks \
  -alias signaltv \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Convierte el `.jks` a base64:
```bash
# Linux / Mac
base64 -i signaltv.jks | tr -d '\n'

# Windows (PowerShell)
[Convert]::ToBase64String([IO.File]::ReadAllBytes("signaltv.jks"))
```

### 3. Agregar Secrets en GitHub

Ve a tu repo → **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Valor |
|--------|-------|
| `KEYSTORE_BASE64` | Output del comando base64 del paso anterior |
| `KEYSTORE_PASSWORD` | Contraseña que pusiste al crear el keystore |
| `KEY_ALIAS` | `signaltv` (o el alias que usaste) |
| `KEY_PASSWORD` | Contraseña de la key (puede ser la misma) |

### 4. Compilar la APK

**Opción A — Con tag (genera Release en GitHub automáticamente):**
```bash
git tag v1.0.0
git push origin v1.0.0
```

**Opción B — Manual:**
Ve a tu repo → **Actions → Build & Release APK → Run workflow**

La APK se descarga desde **Actions → tu workflow → Artifacts** o desde **Releases**.

## Acceso al panel admin

Toca el logo **SIGNALTV** exactamente **5 veces** en menos de 3 segundos.

Credenciales por defecto:
- Usuario: `admin`
- Contraseña: `signal2024`

> Cambia la contraseña desde el panel admin después del primer login.

## Firebase

El proyecto usa Firebase Firestore para persistir los canales en la nube.
Reglas de Firestore recomendadas para producción:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /canales/{doc} {
      allow read: if true;
      allow write: if false; // Solo desde panel admin (client-side auth)
    }
    match /config/{doc} {
      allow read, write: if false;
    }
  }
}
```
