# Hőmérők – Wear OS app a Smart Life / Tuya eszközökhöz

Az órán megjeleníti a Smart Life appban lévő eszközöket (hőmérséklet, páratartalom, elem).
Az **új eszközök maguktól megjelennek**, mert az app mindig a teljes listát kéri le.

Tartalma:
- **App**: az összes hőmérő listája (színezve: kék < 18°, narancs > 24°), alatta az egyéb eszközök (pl. gateway).
- **Csempe** („Hőmérők”): 6 hőmérő egy pillantásra. Az alsó időpontra koppintva frissít, máshová koppintva megnyitja az appot.
- **Komplikáció** („Hőmérő”): egy kiválasztott hőmérő (pl. Külső) a számlapon.

---

## 1. Tuya kulcsok beszerzése (egyszer kell)

1. Regisztrálj: https://platform.tuya.com
2. **Cloud → Development → Create Cloud Project**
   - Development Method: **Smart Home**
   - Data Center: **Central Europe Data Center**
3. A felkínált API-k közül fogadd el: **IoT Core**, **Authorization Token Management**
   (ha van: Smart Home Basic Service, Device Status Notification).
4. A projektben: **Devices → Link App Account → Add App Account** → QR-kód.
   Telefonon a Smart Life appban: **Me (Én) → jobb felső sarok, szkennelés ikon** → olvasd be.
   Ezután a projekt Devices listájában látnod kell a hőmérőket.
5. A projekt **Overview** oldalán megtalálod:
   - **Access ID / Client ID**
   - **Access Secret / Client Secret**

> Az ingyenes „Trial” csomagot pár havonta meg kell hosszabbítani a Tuya oldalán
> (Cloud → Cloud Services → IoT Core → Extend Trial). Ha az app „lejárt próbaidőszak”
> hibát ír, ez a teendő.

## 2. Az APK letöltése

1. GitHubon nyisd meg a repót → **Actions** fül → **„Wear OS app (Hőmérők) – APK build”**.
2. Kattints a legfelső zöld pipás futásra.
3. Lent az **Artifacts** résznél töltsd le a **homero-wear-apk** fájlt (ZIP), és csomagold ki → `homero-wear.apk`.

## 3. Telepítés az órára

**Az órán:** Beállítások → Rendszer → Névjegy (Szoftverinformáció) → a **Build-szám**ra koppints 7×.
Utána: Beállítások → **Fejlesztői beállítások** → **ADB-hibakeresés** BE, **Wi-Fi-n keresztüli hibakeresés** BE.
(Az óra és a telefon/számítógép legyen ugyanazon a Wi-Fi-n.)

### A) Telefonról, számítógép nélkül
Telepíts a telefonra egy Wear OS APK-telepítő appot a Play Áruházból (pl. **„Wear Installer 2”** vagy **„Bugjaeger”**),
add meg az órán látható IP-címet és párosító kódot, majd válaszd ki a `homero-wear.apk` fájlt.

### B) Számítógépről
1. Töltsd le az Android **platform-tools**-t: https://developer.android.com/tools/releases/platform-tools és csomagold ki.
2. Az órán: Wi-Fi-n keresztüli hibakeresés → **Eszköz párosítása** – megjelenik egy IP:port és egy kód.
3. A platform-tools mappában nyiss parancssort:
   ```
   adb pair 192.168.1.50:41234        (a párosító IP:port, majd beírod a kódot)
   adb connect 192.168.1.50:5555      (a Wi-Fi-hibakeresés főképernyőjén látható IP:port)
   adb install homero-wear.apk
   ```

## 4. Kulcsok megadása

**Legkényelmesebb (ha a B módszerrel telepítettél)** – egyetlen parancs, nem kell az órán gépelni:
```
adb shell am start -n hu.homero.wear/.MainActivity --es access_id IDE_AZ_ACCESS_ID --es access_secret IDE_A_SECRET --es region EU
```

**Vagy az órán:** nyisd meg a **Hőmérők** appot → **Beállítások** → *Access ID*, majd *Access Secret* →
írd be (sok órán a telefonon is begépelheted). A *Régió* alapból Közép-Európa (koppintással váltható).
Végül: **Kész – frissítés**.

## 5. Csempe és számlap

- **Csempe:** az órán húzd balra a számlapot a csempék végéig → **+** → **Hőmérők**.
  Ha bizonyos hőmérőket szeretnél a csempén: az appban koppints a hőmérőre → **Csempén** BE.
  (Ha egyiket sem jelölöd, az első 6 jelenik meg.)
- **Számlap:** az appban koppints a hőmérőre (pl. Külső) → **Számlapon** BE.
  Utána a számlapot nyomd hosszan → Testreszabás → egy komplikáció-hely → **Hőmérők → Hőmérő**.

Az adatok kb. 15 percenként frissülnek (a Tuya ingyenes keretét kímélve), az appban bármikor kézzel is frissíthetsz.

## Hibaelhárítás

| Üzenet | Teendő |
|---|---|
| Hibás aláírás | Rossz Access Secret – add meg újra. |
| Érvénytelen Access ID / rossz régió | Ellenőrizd az ID-t, vagy próbáld a Nyugat-Európa régiót. |
| Nincs eszköz | A Smart Life fiók nincs összekapcsolva a projekttel (1/4. lépés), vagy rossz a régió. |
| Lejárt a Tuya próbaidőszak | Hosszabbítsd meg az IoT Core Trialt a platform.tuya.com-on. |
| Nincs internetkapcsolat | Az óra legyen Wi-Fi-n vagy kapcsolódjon a telefonhoz. |

## Fejlesztőknek

Android Studióval megnyitható (`wear-homero` mappa). Kotlin + Compose for Wear OS, Tiles (ProtoLayout),
Complications. A Tuya OpenAPI HMAC-SHA256 aláírással, közvetlenül az óráról hívódik; a kulcsok csak az órán tárolódnak.
