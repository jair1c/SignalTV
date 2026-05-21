# SignalTV

App IPTV con panel admin oculto, Firebase Firestore y compilación automática de APK via GitHub Actions.

## Estructura del repositorio

```
├── index.html                  ← Web app (desplegada en Vercel)
├── android-app/                ← Proyecto Android
│   ├── app/src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/signaltv/app/
│   │   │   ├── MainActivity.kt     (Phone / Tablet)
│   │   │   └── TvActivity.kt       (Android TV)
│   │   └── res/
│   └── build.gradle
└── .github/workflows/
    └── build-apk.yml           ← CI/CD automático
```

## Paso 1 — Cambiar URL de Vercel

Edita `android-app/app/src/main/java/com/signaltv/app/MainActivity.kt`:
```kotlin
const val APP_URL = "https://TU-PROYECTO.vercel.app"  // ← tu URL real
```

## Paso 2 — Generar el Keystore (solo una vez)

```bash
keytool -genkey -v \
  -keystore signaltv.jks \
  -alias signaltv \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

Convertir a base64:
```bash
# Linux / Mac
base64 -i signaltv.jks | tr -d '\n'

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("signaltv.jks"))
```

## Paso 3 — Secrets en GitHub

Repo → **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Valor |
|--------|-------|
| `KEYSTORE_BASE64` | Output del comando base64 |
| `KEYSTORE_PASSWORD` | Contraseña del keystore |
| `KEY_ALIAS` | `signaltv` |
| `KEY_PASSWORD` | Contraseña de la key |

## Paso 4 — Compilar la APK

**Con tag (crea Release automático):**
```bash
git add .
git commit -m "feat: SignalTV app"
git tag v1.0.0
git push origin main --tags
```

**Manual:** Repo → **Actions → Build & Release APK → Run workflow**

La APK aparece en **Releases** o en **Actions → tu workflow → Artifacts**.

## Acceso al panel admin

Toca el logo **SIGNALTV** exactamente **5 veces** en menos de 3 segundos.

- Usuario: `admin`
- Contraseña: `signal2024`

## Firebase Firestore — Reglas de producción

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /canales/{doc} {
      allow read: if true;
      allow write: if false;
    }
    match /config/{doc} {
      allow read, write: if false;
    }
  }
}
```
