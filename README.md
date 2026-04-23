# FaceDemo – Dokumentace projektu

> Poslední aktualizace: 2026-04-23  
> Jazyk: Kotlin | Platform: Android

---

## Obsah

1. [Přehled aplikace](#1-přehled-aplikace)
2. [Kamera a náhled](#2-kamera-a-náhled)
3. [Detekce tváří](#3-detekce-tváří)
4. [Identifikace a rozpoznávání tváří](#4-identifikace-a-rozpoznávání-tváří)
5. [Překryvné vrstvy (Overlay)](#5-překryvné-vrstvy-overlay)
6. [Detekce objektů](#6-detekce-objektů)
7. [Zachytávání snímků a galerie](#7-zachytávání-snímků-a-galerie)
8. [Nastavení](#8-nastavení)
9. [Navigační menu](#9-navigační-menu)
10. [Pomocné třídy a nástroje](#10-pomocné-třídy-a-nástroje)
11. [Datové modely](#11-datové-modely)
12. [Přehled souborů](#12-přehled-souborů)

---

## 1. Přehled aplikace

FaceDemo je Android aplikace pro real-time detekci a identifikaci tváří pomocí kamery. Aplikace kombinuje Google ML Kit pro detekci obličejů s vlastním systémem rozpoznávání na základě geometrických deskriptorů bodů tváře (landmarks). Zároveň podporuje detekci obecných objektů pomocí YOLO modelu a detekci bankovek pomocí specializovaného TFLite modelu.

**Hlavní funkce:**
- Detekce a sledování tváří v reálném čase
- Identifikace osob podle jména (uložení a rozpoznání)
- Zobrazení landmarků tváře (oči, nos, ústa, uši, líce)
- Detekce úsměvu a stavu očí
- Detekce obecných objektů pomocí YOLO/COCO (TFLite)
- Detekce bankovek pomocí specializovaného modelu (`banknote_detector.tflite`)
- Zachytávání obrazovky včetně všech aktivních překryvných vrstev
- Galerie uložených snímků s náhledem a mazáním

---

## 2. Kamera a náhled

**Soubor:** `MainActivity.kt`

### Spuštění kamery

```
fun startCamera()
```
Inicializuje CameraX pipeline. Nastavuje `Preview` pro zobrazení obrazu a `ImageAnalysis` pro zpracování snímků na pozadí. Výchozí rozlišení je 1280×720. Kamera se po spuštění zváže k životnímu cyklu aktivity.

```
fun requestPermission()
```
Požádá uživatele o povolení kamery (`CAMERA`). Po udělení automaticky zavolá `startCamera()`.

### Přepínání kamery

```
fun toggleCamera()
```
Přepíná mezi přední (`LENS_FACING_FRONT`) a zadní (`LENS_FACING_BACK`) kamerou. Před přepnutím ověří, zda je požadovaná kamera dostupná. Po přepnutí restartuje celou pipeline voláním `startCamera()`.

### Transformace překryvné vrstvy

```
fun updateOverlayTransforms(mediaImage: android.media.Image, rotation: Int)
```
Přepočítává `scale`, `offsetX` a `offsetY` pro všechny tři překryvné vrstvy (`FaceOverlay`, `ObjectDetectionOverlay`, `BanknoteOverlay`) tak, aby bounding boxy z ML Kit a TFLite modelů přesně odpovídaly pozici v náhledovém okně. Bere v úvahu rotaci snímku (0/90/180/270°).

### Životní cyklus

| Metoda | Popis |
|--------|-------|
| `onCreate()` | Inicializace UI, manažerů, nastavení kamery a menu |
| `onResume()` | Obnoví data tváří, přečte nastavení ze SharedPreferences, zavolá `applyDebugUiState()` (viditelnost debug panelu + registrace listeneru), aktualizuje menu |
| `onPause()` | Odregistruje debug posluchač |
| `onStart()` | Registruje debug posluchač (pokud je debug mód zapnutý) |
| `onDestroy()` | Ukončí vlákno kamery, uvolní ObjectDetector i BanknoteDetector |

---

## 3. Detekce tváří

**Soubor:** `MainActivity.kt`

### Konfigurace detektoru

ML Kit FaceDetector je nakonfigurován s těmito možnostmi:
- `PERFORMANCE_MODE_FAST` – rychlý mód pro real-time zpracování
- `LANDMARK_MODE_ALL` – detekce všech bodů tváře (oči, nos, ústa, uši, líce)
- `CLASSIFICATION_MODE_ALL` – detekce úsměvu a stavu očí
- `enableTracking()` – sledování tváří přes snímky (přiřazuje `trackingId`)

### Zpracování výsledků detekce

Po detekci se pro každou tvář spustí dvoustupňové rozpoznávání:

**Stupeň 1 – Rychlé vyhledání přes trackingId**  
Pokud ML Kit přiřadil tváři `trackingId`, zkusí se nejdříve najít uložený záznam v `FaceIdentificationManager` pomocí `findFaceByTrackingId()`. Toto je levná operace s cache.

**Stupeň 2 – Deskriptorové rozpoznání**  
Pokud tvář nebyla nalezena přes `trackingId`, spustí se `FaceDescriptor.compute()` a výsledek se porovná se všemi uloženými tvářemi pomocí `faceManager.recognizeByDescriptor()`. Toto se spouští nejvýše jednou za `recognizeIntervalMs` (200 ms) pro každé `trackingId`.

### Výkonnostní parametry analyzátoru

| Proměnná | Hodnota | Popis |
|----------|---------|-------|
| `recognizeIntervalMs` | 200 ms | Minimální interval mezi deskriptorovým rozpoznáním pro jednu tvář |
| `descriptorUpdateIntervalMs` | 800 ms | Minimální interval mezi aktualizací uloženého deskriptoru |
| `objectDetectionIntervalMs` | 120 ms | Minimální interval mezi YOLO/COCO detekcemi |
| `banknoteDetectionIntervalMs` | 200 ms | Minimální interval mezi detekcemi bankovek |
| `debugLogIntervalMs` | 3000 ms | Minimální interval mezi debug výpisy |

### Uložení jména tváře

```
fun persistFaceName(face: Face, faceIndex: Int, name: String)
```
Uloží jméno k tváři. Pokud existuje deskriptor (landmarks jsou dostupné), uloží i geometrický deskriptor pro pozdější rozpoznání. Pokud tvář s daným `trackingId` již existuje, aktualizuje její záznam. Jinak vytvoří nový.

```
fun showNameInputDialog(face: Face, faceIndex: Int)
```
Zobrazí dialog pro zadání jména identifikované tváře. Po potvrzení zavolá `persistFaceName()`.

---

## 4. Identifikace a rozpoznávání tváří

### FaceIdentificationManager

**Soubor:** `FaceIdentificationManager.kt`

Spravuje persistentní databázi identifikovaných tváří. Data jsou uložena v `EncryptedSharedPreferences` (šifrování AES-256). Obsahuje in-memory cache pro rychlý přístup bez opakovaného parsování JSON.

#### Metody

```
fun saveFace(faceData: SavedFaceData)
```
Uloží nebo přepíše záznam tváře. Automaticky aktualizuje mapování `trackingId → faceId`.

```
fun loadAllFaces(): List<SavedFaceData>
```
Vrátí seznam všech uložených tváří (z cache, nebo ze storage).

```
fun findFaceByTrackingId(trackingId: Int): SavedFaceData?
```
Rychle najde tvář podle `trackingId` přes in-memory mapu.

```
fun updateLastSeen(faceId: String)
```
Aktualizuje časové razítko posledního výskytu tváře.

```
fun recognizeByDescriptor(descriptor: FloatArray, threshold: Float = 0.82f): Pair<SavedFaceData, Float>?
```
Porovná zadaný deskriptor se všemi uloženými tvářemi. Vrátí nejlépe odpovídající tvář a skóre podobnosti, pokud překročí `threshold`. Podobnost je počítána kosinovou vzdáleností (viz `FaceDescriptor.similarity()`).

```
fun updateDescriptor(faceId: String, newDescriptor: FloatArray, alpha: Float = 0.15f)
```
Aktualizuje uložený deskriptor tváře pomocí exponenciálního klouzavého průměru (EMA). `alpha = 0.15` znamená, že nový snímek má 15% váhu. Průběžně zlepšuje přesnost rozpoznávání.

```
fun saveFaceWithDescriptor(name: String, descriptor: FloatArray, trackingId: Int?): SavedFaceData
```
Vytvoří a uloží nový záznam tváře s prvním deskriptorem (při počátečním pojmenování).

```
fun deleteAllFaces()
```
Smaže všechna data z paměti i úložiště. Commit probíhá synchronně (`commit = true`), aby byl stav konzistentní při návratu ze Settings.

```
fun clearInMemoryCache()
```
Vymaže in-memory cache. Při příštím čtení se data načtou znovu z úložiště.

---

### FaceDescriptor

**Soubor:** `FaceDescriptor.kt`

Singleton objekt pro výpočet geometrického deskriptoru tváře ze souřadnic landmarků.

#### Princip

1. Souřadnice všech landmarků jsou normalizovány relativně ke středu očí a mezioční vzdálenosti (invariantnost vůči posunutí a měřítku).
2. Jsou vypočítány párové vzdálenosti mezi všemi dvojicemi landmarků (45 kombinací z 10 bodů).
3. Přidány jsou také úhly hlavy (yaw, roll/Z-osa) normalizované do rozsahu `[-1, 1]` (`headEulerAngleY` a `headEulerAngleZ`).

Výsledný vektor má konstantní délku bez ohledu na chybějící body (chybějící bod je zastoupen hodnotou 0).

> **Poznámka k prahům podobnosti:** Metoda `recognizeByDescriptor()` používá výchozí práh **0.82**. Interní komentář v `similarity()` zmiňuje hodnotu 0.92 jako konzervativnější práh — tyto dvě hodnoty jsou v kódu nedůsledné a 0.82 je aktuálně aktivní výchozí hodnota.

#### Metody

```
fun compute(face: Face): FloatArray?
```
Vypočítá deskriptor tváře. Vrátí `null` pokud chybí oči (nezbytné pro normalizaci).

```
fun similarity(a: FloatArray, b: FloatArray): Float
```
Kosinová podobnost dvou deskriptorů. Rozsah `[-1, 1]`, vyšší = podobnější. Aktivní práh v `recognizeByDescriptor()`: **0.82** (komentář v metodě uvádí 0.92).

```
fun euclidean(a: FloatArray, b: FloatArray): Float
```
Euklidovská vzdálenost dvou deskriptorů. Nižší = podobnější. Alternativní metrika.

```
fun average(descriptors: List<FloatArray>): FloatArray?
```
Průměruje seznam deskriptorů do jednoho.

```
fun serialize(d: FloatArray): String
```
Převede deskriptor na textový řetězec oddělený čárkami pro uložení.

```
fun deserialize(s: String): FloatArray?
```
Načte deskriptor z uloženého textového řetězce.

---

## 5. Překryvné vrstvy (Overlay)

### FaceOverlay

**Soubor:** `FaceOverlay.kt`  
Vlastní `View` kreslené přes náhled kamery. Zobrazuje bounding boxy tváří, landmarky, jména, procenta úsměvu a stavu očí.

#### Kreslení

```
override fun onDraw(canvas: Canvas)
```
Pro každou detekovanou tvář nakreslí:
- **Žlutý rámeček** – neidentifikovaná tvář
- **Zelený rámeček** – identifikovaná tvář (se jménem)
- **Jméno** – nad rámečkem (zelené)
- **Úsměv %** – vlevo od rámečku (pokud je povoleno)
- **Stav očí %** – vpravo od rámečku (pokud je povoleno); `R:` = levé oko, `L:` = pravé oko (z pohledu ML Kit)
- **Landmarky** – tečky + popisky na pozicích obou očí, nosu, líček, úst a uší (pokud je povoleno)

#### Souřadnicová transformace

```
fun mapBoxToView(bounds: Rect): RectF    // privátní
fun mapPointToView(px: Float, py: Float): PointF    // privátní
```
Převádí souřadnice z prostoru snímku ML Kit do souřadnic View. Při přední kameře je obraz horizontálně zrcadlen.

#### Správa jmen

```
fun setIdentifiedFace(trackingId: Int?, name: String, faceIndex: Int?)
```
Nastaví jméno tváře. Přes `trackingId` = persistentní, přes `faceIndex` = dočasné (jen pro aktuální snímek).

```
fun loadNames(context: Context)
```
Načte uložená jména ze SharedPreferences a nastavení detekce.

```
fun refreshAllData(context: Context)
```
Znovu načte všechna data (volá se při návratu ze Settings).

```
fun loadDetectionSettings(context: Context)
```
Načte pouze `smile_detection_enabled` a `eyes_detection_enabled` ze SharedPreferences a aktualizuje stav overlay. Volá se přímo z `MenuAdapter` při každém přepnutí Smile/Eyes, bez nutnosti plného `loadNames()`.

```
fun saveName(context: Context, trackingId: Int, name: String)
```
Uloží jméno do starého úložiště (SharedPreferences `face_names`) i do `FaceIdentificationManager`.

```
fun clearAllNames(context: Context)
```
Smaže všechna jména z obou úložišť.

```
fun getNameForFace(face: Face, faceIndex: Int): String?
```
Vrátí uložené jméno pro tvář, pokud existuje.

#### Zachytávání tváří

```
fun captureAllFaces(captureManager: CaptureManager, nameInputCallback: ...): List<String>
```
Vyřízne každou detekovanou tvář z posledního snímku (`latestFrame`), zapíše jméno do obrázku a uloží přes `CaptureManager`. Vrátí seznam cest k uloženým souborům.

#### Interakce s dotykem

```
override fun onTouchEvent(event: MotionEvent?): Boolean
```
Detekuje kliknutí na oblast tváře a spustí callback `onFaceClick`. Oblast kliknutí je zvětšena o 10 px na každou stranu.

---

### ObjectDetectionOverlay

**Soubor:** `ObjectDetectionOverlay.kt`  
Vlastní `View` zobrazující výsledky YOLO/COCO detekce jako **oranžové** obdélníky s popisky.

#### Vlastnosti

| Vlastnost | Popis |
|-----------|-------|
| `detections` | Seznam detekcí (`List<ObjectDetection>`); přiřazení automaticky spustí překreslení |
| `isFrontCamera` | Při `true` horizontálně zrcadlí bounding boxy |
| `scale`, `offsetX`, `offsetY` | Transformační parametry nastavované z `MainActivity` |

```
override fun onDraw(canvas: Canvas)
```
Pro každou detekci nakreslí zaoblený oranžový rámeček (#FF9800) a nad ním štítek s názvem třídy a procentuální jistotou na tmavém pozadí.

---

### BanknoteOverlay

**Soubor:** `BanknoteOverlay.kt`  
Vlastní `View` zobrazující výsledky detektoru bankovek jako **zelené** obdélníky s popisky. Vizuálně odlišitelné od `ObjectDetectionOverlay` (oranžová).

#### Vlastnosti

| Vlastnost | Popis |
|-----------|-------|
| `detections` | Seznam detekcí (`List<BanknoteDetection>`); přiřazení automaticky spustí překreslení |
| `isFrontCamera` | Při `true` horizontálně zrcadlí bounding boxy |
| `scale`, `offsetX`, `offsetY` | Transformační parametry nastavované z `MainActivity` |

```
override fun onDraw(canvas: Canvas)
```
Pro každou detekci nakreslí zaoblený zelený rámeček (#4CAF50) a štítek s názvem třídy a jistotou.

---

## 6. Detekce objektů

### ObjectDetector – YOLO/COCO obecné objekty

**Soubor:** `ObjectDetector.kt`

Obaluje TFLite interpreter pro detekci obecných objektů (COCO třídy). Podporuje jak YOLO (jeden výstup), tak SSD/TF OD API (čtyři výstupy). Model se načítá ze složky `assets/` – prioritně `yolo_coco_float32_float32.tflite`.

#### Inicializace

Při vytvoření objektu se:
1. Načte TFLite model ze `assets/` (prochází seznam kandidátů `MODEL_CANDIDATES`)
2. Načte seznam tříd ze souboru `coco_labels.txt`
3. Detekuje formát výstupu modelu (YOLO vs. SSD)
4. Zjistí vstupní rozměr z tensoru modelu

#### Hlavní metody

```
fun detect(bitmap: Bitmap): List<ObjectDetection>
```
Spustí inferenci na snímku. Vrátí seznam detekcí po NMS filtrování. Musí být voláno z vlákna na pozadí. Pokud probíhá jiná inference (`isBusy == true`), vrátí prázdný seznam.

```
fun close()
```
Uvolní TFLite interpreter.

#### Prahové hodnoty

| Konstanta | Hodnota | Popis |
|-----------|---------|-------|
| `CONFIDENCE_THRESHOLD` | 0.45 | Minimální celková jistota detekce |
| `OBJECTNESS_THRESHOLD` | 0.35 | Minimální jistota přítomnosti objektu (YOLO) |
| `CLASS_THRESHOLD` | 0.25 | Minimální třídní jistota (YOLO) |
| `NMS_IOU_THRESHOLD` | 0.45 | Práh překryvu pro NMS |
| `MAX_DETECTIONS` | 100 | Maximální počet výsledků po NMS |
| `MAX_PRE_NMS_CANDIDATES` | 300 | Maximální počet kandidátů předaných do NMS (YOLO) |

---

### BanknoteDetector – specializovaný detektor bankovek

**Soubor:** `BanknoteDetector.kt`

Obaluje TFLite interpreter výhradně pro model `banknote_detector.tflite`. Podporuje stejné výstupní formáty jako `ObjectDetector` (YOLO / SSD). Při inicializaci loguje **všechny** tvary tensorů do `DebugLogger`, což umožňuje identifikovat neznámé třídy modelu.

#### Jak identifikovat labely

1. Zapni Debug Mode v nastavení aplikace.
2. Namiř kameru na bankovku.
3. V debug logu hledej řádky `[BanknoteDetector] Hit: Class_X conf=0.XX`.
4. Zjisti, kterému nominálu odpovídá index X.
5. Vytvoř soubor `assets/banknote_labels.txt` (jeden label na řádek, index 0 jako první).

Dokud soubor `banknote_labels.txt` neexistuje, detektor používá zástupné labely `Class_0`, `Class_1`, …

#### Hlavní metody

```
fun detect(bitmap: Bitmap): List<BanknoteDetection>
```
Spustí inferenci na snímku. Funguje stejně jako `ObjectDetector.detect()`.

```
fun close()
```
Uvolní TFLite interpreter.

#### Prahové hodnoty

Záměrně nižší než u `ObjectDetector` pro snazší ladění při neznámých labelech:

| Konstanta | Hodnota | Popis |
|-----------|---------|-------|
| `CONFIDENCE_THRESHOLD` | 0.35 | Minimální celková jistota detekce |
| `OBJECTNESS_THRESHOLD` | 0.25 | Minimální jistota přítomnosti objektu (YOLO) |
| `CLASS_THRESHOLD` | 0.20 | Minimální třídní jistota (YOLO) |
| `NMS_IOU_THRESHOLD` | 0.45 | Práh překryvu pro NMS |
| `MAX_DETECTIONS` | 20 | Maximální počet výsledků |

---

## 7. Zachytávání snímků a galerie

### CaptureManager

**Soubor:** `CaptureManager.kt`  
Spravuje ukládání a mazání fotografií na disk.

Fotografie se ukládají do: `<external_files_dir>/face_captures/`

```
fun saveScreenCapture(bitmap: Bitmap): String?
```
Uloží bitmap celé obrazovky (kamera + překryvné vrstvy) jako JPEG (kvalita 95 %). Název souboru: `capture_<timestamp>.jpg`. Vrátí absolutní cestu k souboru, nebo `null` při chybě.

```
fun saveFaceCapture(bitmap: Bitmap, name: String = ""): String?
```
Uloží vyříznutou tvář jako JPEG (kvalita 90 %). Název souboru obsahuje timestamp a jméno osoby. Vrátí absolutní cestu k souboru, nebo `null` při chybě.

```
fun getAllCaptures(): List<File>
```
Vrátí seznam všech zachycených fotografií seřazených od nejnovější.

```
fun deleteCapture(file: File): Boolean
```
Smaže jeden soubor. Vrátí `true` při úspěchu.

```
fun deleteAllCaptures()
```
Smaže všechny zachycené fotografie.

---

### captureScreen()

**Soubor:** `MainActivity.kt`

Zachytí přesně to, co uživatel vidí – obraz z kamery se všemi aktivními překryvnými vrstvami.

Postup:
1. Zkontroluje, zda má `previewView` nenulové rozměry.
2. Načte bitmap z `previewView.bitmap` (vyžaduje `ImplementationMode.COMPATIBLE` / TextureView).
3. Vytvoří výsledný bitmap v rozměrech `viewW × viewH`.
4. Nakreslí preview obsah přes celý canvas (škálování eliminuje nesoulad rozměrů bitmap/view).
5. Postupně nakreslí `faceOverlay`, `objectDetectionOverlay` a `banknoteOverlay` přes canvas – překryvy jsou v souřadnicích View, takže nepotřebují žádné škálování.
6. Uloží přes `captureManager.saveScreenCapture()` a otevře galerii.

---

### GalleryActivity

**Soubor:** `GalleryActivity.kt`  
Aktivita zobrazující mřížku (GridView) zachycených fotografií. Při návratu do foreground obnoví seznam snímků (`onResume()`).

---

### GalleryAdapter

**Soubor:** `GalleryAdapter.kt`  
`BaseAdapter` pro `GridView` v galerii.

Obsahuje `LruCache<String, Bitmap>` pro cachování náhledů (velikost = `maxMemory / 8` KB). Cache klíč zahrnuje `lastModified()` souboru, takže upravené soubory se automaticky překreslí.

```
fun updateData(newCaptures: List<File>)
```
Aktualizuje seznam fotografií a překreslí GridView.

**Kliknutí** → otevře `PhotoDetailActivity` s cestou k souboru.  
**Dlouhý stisk** → smaže fotografii ze seznamu, disku i cache.

Náhledy jsou dekódovány pomocí `BitmapFactory.Options.inSampleSize` (metoda `decodeThumbnail()`), aby nedocházelo k načítání plného rozlišení do paměti.

---

### PhotoDetailActivity

**Soubor:** `PhotoDetailActivity.kt`  
Zobrazuje jednotlivou fotografii. Cestu k souboru přijímá přes Intent extra `"photo_path"`.

- Bitmap je dekódován metodou `decodeSampled()` s max. rozlišením 1920×1080 pomocí `inSampleSize`, aby nedocházelo k OOM chybám.
- Tlačítko **Smazat fotografii** zobrazí potvrzovací dialog (`AlertDialog`).
- Pokud `File.delete()` selže, zobrazí se Toast "Smazání selhalo" a aktivita zůstane otevřená.

---

## 8. Nastavení

**Soubor:** `SettingsActivity.kt`  
Aktivita pro konfiguraci aplikace. Nastavení jsou uložena v SharedPreferences `"detection_settings"`.

### Dostupná nastavení

| Klíč | Výchozí | Popis |
|------|---------|-------|
| `smile_detection_enabled` | `true` | Zobrazení procenta úsměvu na overlay |
| `eyes_detection_enabled` | `true` | Zobrazení stavu očí na overlay |
| `debug_mode_enabled` | `false` | Zobrazení debug panelu s logy |

### Tlačítka

- **Smile Detection** – přepíná zobrazení úsměvu
- **Eyes Detection** – přepíná zobrazení stavu očí
- **Debug Mode** – zapíná/vypíná debug log panel; volá `DebugLogger.setEnabled()`
- **Clear Names** – smaže všechna uložená jména z `SharedPreferences` i z `FaceIdentificationManager`

```
private fun updateButtonText(button: Button, isEnabled: Boolean, label: String)
```
Aktualizuje text tlačítka na formát `"Label: ZAP"` / `"Label: VYP"` podle stavu.

---

## 9. Navigační menu

**Soubor:** `MainActivity.kt` – vnitřní třída `MenuAdapter`

Horizontální `RecyclerView` ve spodní části obrazovky. Každá položka se skládá z ikony (`ImageButton`) a popisku.

### Položky menu

| Položka (`MenuItemType`) | Label | Funkce |
|--------------------------|-------|--------|
| `SETTINGS` | Settings | Otevře `SettingsActivity` |
| `CAMERA_SWITCH` | Camera | Přepne přední/zadní kameru (`toggleCamera()`) |
| `SMILE` | Smile | Přepne detekci úsměvu (zapíše do SharedPreferences + aktualizuje overlay) |
| `EYES` | Eyes | Přepne detekci stavu očí |
| `FACE` | Face | Zapne/vypne celou detekci tváří |
| `LANDMARKS` | Landmarks | Zapne/vypne zobrazení landmarků |
| `CAPTURE` | Capture | Zachytí obrazovku (kamera + všechny překryvy) a otevře galerii |
| `GALLERY` | Gallery | Otevře `GalleryActivity` |
| `OBJECT_DETECTION` | Objects | Zapne/vypne YOLO/COCO detekci obecných objektů |
| `BANKNOTE` | Banknote | Zapne/vypne detekci bankovek (`banknote_detector.tflite`) |

**Aktivní položky** (přepínače) mají modré/cyan pozadí (`btn_circle_active`), neaktivní mají průhledné skleněné pozadí (`btn_circle_glass`).

### CAPTURE flow

1. Zavolá `captureScreen()`.
2. `captureScreen()` zkontroluje připravenost kamery.
3. Složí bitmap z preview + všech aktivních překryvů (viz sekce 7).
4. Uloží přes `CaptureManager.saveScreenCapture()`.
5. Po úspěchu otevře `GalleryActivity`.

---

## 10. Pomocné třídy a nástroje

### NV21ToBitmap

**Soubor:** `NV21ToBitmap.kt`  
Singleton pro konverzi snímků z kamery na `Bitmap`.

```
fun convertNV21(mediaImage: Image): Bitmap
```
Převede Android `MediaImage` z formátu YUV_420_888 na NV21 a pak na JPEG Bitmap. Používá se v analyzátoru kamery pro každý snímek.

```
fun rotate(bitmap: Bitmap, degrees: Int): Bitmap
```
Otočí bitmap o 0/90/180/270 stupňů. Originální bitmap automaticky recykluje (pokud byl vytvořen nový). Vrátí stejný objekt, pokud je rotace 0°.

```
private fun yuv420888ToNv21(image: Image): ByteArray
```
Interní konverze YUV planes na NV21 byte array. Správně zpracovává `rowStride` a `pixelStride` pro různé hardwarové formáty.

---

### DebugLogger

**Soubor:** `DebugLogger.kt`  
Thread-safe singleton pro in-app debug logování. Maximálně udržuje `250` řádků.

```
fun setEnabled(enabled: Boolean)
```
Zapne/vypne logování. Při zapnutí přidá první zprávu `"Debug mode ON"`.

```
fun log(tag: String, message: String)
```
Přidá záznam ve formátu `"HH:mm:ss.SSS [TAG] message"`. Pokud je logování vypnuto, nedělá nic.

```
fun registerListener(listener: (String) -> Unit)
```
Zaregistruje posluchač, který dostane aktuální snapshot a pak každou novou zprávu. Používá se pro zobrazení logů v UI.

```
fun unregisterListener(listener: (String) -> Unit)
```
Odregistruje posluchač (typicky při `onPause()`).

```
fun snapshot(): String
```
Vrátí aktuální obsah logu jako jeden víceřádkový string.

```
fun clear()
```
Smaže všechny záznamy.

---

## 11. Datové modely

### SavedFaceData

**Soubor:** `FaceIdentificationManager.kt`  
Data class reprezentující jeden uložený záznam tváře.

| Pole | Typ | Popis |
|------|-----|-------|
| `faceId` | `String` | Unikátní ID ve formátu `"face_<timestamp>"` |
| `name` | `String` | Jméno osoby zadané uživatelem |
| `trackingIds` | `List<Int>` | Seznam trackingId přidělených ML Kit (mohou se měnit mezi relacemi) |
| `firstSeen` | `Long` | Čas prvního uložení (Unix ms) |
| `lastSeen` | `Long` | Čas posledního výskytu (Unix ms) |
| `notes` | `String` | Volné textové poznámky |
| `descriptorCsv` | `String` | Geometrický deskriptor serializovaný jako CSV |
| `descriptorSamples` | `Int` | Počet snímků zahrnutých do průměrného deskriptoru |

---

### ObjectDetection

**Soubor:** `ObjectDetector.kt`  
Data class pro jeden detekovaný obecný objekt (YOLO/COCO).

| Pole | Typ | Popis |
|------|-----|-------|
| `label` | `String` | Název třídy (např. `"person"`, `"car"`) |
| `confidence` | `Float` | Jistota detekce 0–1 |
| `boundingBox` | `RectF` | Normalizovaný bounding box `[0,1]` (left, top, right, bottom) |

---

### BanknoteDetection

**Soubor:** `BanknoteDetector.kt`  
Data class pro jeden detekovaný objekt z banknote modelu.

| Pole | Typ | Popis |
|------|-----|-------|
| `label` | `String` | Název třídy – dočasně `"Class_X"` dokud není vytvořen `banknote_labels.txt` |
| `confidence` | `Float` | Jistota detekce 0–1 |
| `boundingBox` | `RectF` | Normalizovaný bounding box `[0,1]` (left, top, right, bottom) |

---

### IdentifiedFace

**Soubor:** `IdentifiedFace.kt`  
Jednoduchá data class pro přenos identifikované tváře.

| Pole | Typ | Popis |
|------|-----|-------|
| `faceId` | `String` | ID záznamu |
| `name` | `String` | Jméno osoby |
| `trackingId` | `Int?` | Aktuální trackingId (může být `null`) |

---

## 12. Přehled souborů

| Soubor | Kategorie | Popis |
|--------|-----------|-------|
| `MainActivity.kt` | Hlavní aktivita | Kamera, detekce, menu, orchestrace |
| `FaceIdentificationManager.kt` | Identifikace | Databáze tváří, rozpoznávání, CRUD |
| `FaceDescriptor.kt` | Identifikace | Výpočet a porovnávání geometrických deskriptorů |
| `FaceOverlay.kt` | UI / Overlay | Kreslení rámečků, landmarků, jmen přes náhled kamery |
| `ObjectDetector.kt` | Detekce objektů | YOLO/COCO TFLite wrapper pro obecné objekty |
| `ObjectDetectionOverlay.kt` | UI / Overlay | Kreslení YOLO/COCO výsledků (oranžová) |
| `BanknoteDetector.kt` | Detekce bankovek | TFLite wrapper pro `banknote_detector.tflite` |
| `BanknoteOverlay.kt` | UI / Overlay | Kreslení výsledků detekce bankovek (zelená) |
| `CaptureManager.kt` | Galerie | Ukládání a správa zachycených fotografií |
| `GalleryActivity.kt` | Galerie | Mřížkový přehled fotografií |
| `GalleryAdapter.kt` | Galerie | Adapter pro GridView s LruCache cachováním náhledů |
| `PhotoDetailActivity.kt` | Galerie | Zobrazení fotografie v plné velikosti s potvrzením mazání |
| `SettingsActivity.kt` | Nastavení | Konfigurace přepínačů a smazání dat |
| `NV21ToBitmap.kt` | Utility | Konverze snímků kamery na Bitmap |
| `DebugLogger.kt` | Utility | In-app debug logger s UI výstupem |
| `IdentifiedFace.kt` | Model | Data class pro identifikovanou tvář |

> **Poznámka:** `SavedFaceData` a `ObjectDetection` / `BanknoteDetection` nejsou samostatné soubory — jsou definovány na začátku `FaceIdentificationManager.kt`, resp. `ObjectDetector.kt` a `BanknoteDetector.kt`.
