# CleanCut: Застосунок для автоматичного видалення фону на Android

CleanCut — сучасний нативний застосунок для Android, розроблений мовою **Kotlin** із використанням **Jetpack Compose**, багаторівневої системи комп'ютерного зору (**Google ML Kit + Guided Filter**, **Bria AI RMBG-1.4**, **BiRefNet-Lite** та **IS-Net Anime через ONNX Runtime Mobile** з інтелектуальним **MatteDefringer**) і вбудованої системи автооновлень через GitHub Releases.

Застосунок працює повністю локально на пристрої: жодних LLM, хмарних серверів чи витоку персональних даних.

---

## Рівні якості сегментації

1. **Швидкий режим (Fast + Guided Filter)**:
   - Базується на Google ML Kit Subject Segmentation API.
   - Застосовує алгоритмічний **Guided Filter (керований фільтр)** для усунення розмиття та припасування маски до RGB-градієнтів.
   - Швидкість: 30-60 мс, 0 МБ додаткового розміру.

2. **Студійний режим (Studio RMBG-1.4)**:
   - Базується на моделі Bria AI RMBG-1.4 (ONNX Runtime Mobile).
   - Обробка на тензорі 1024x1024.
   - Висока точність для волосся, контурів та силуетів.
   - Розмір: ~42 МБ (завантажується за запитом користувача в один клік).

3. **Ультра режим (Ultra BiRefNet-Lite)**:
   - Базується на еталонній світовій архітектурі **BiRefNet** (Bilateral Reference Network) зі Swin Transformer бекбоном.
   - Двосторонні референсні зв'язки передають піксельні координати безпосередньо у вихідні шари декодера.
   - Максимальна деталізація для найскладніших фото: окремі пасма волосся, спиці коліс, напівпрозоре скло, мереживо, паркани та тонка геометрія.
   - Розмір: ~224 МБ (завантажується за бажанням користувача, кешується офлайн).

4. **Аніме режим (Anime IS-Net)**:
   - Спеціалізована модель **IS-Net Anime** (SkyTNT), натренована виключно на 2D-ілюстраціях, аніме-портретах та манзі.
   - Забезпечує ідеальний зріз по зовнішньому контуру (line-art) без фотографічного розмиття.
   - Розмір: ~168 МБ.

Усі нейромережеві режими використовують **MatteDefringer** — математичну деконтамінацію кольору фону (un-premultiplying white/uniform matte) та придушення ореолів (halo choke), що повністю ліквідує білі контури навколо вирізаного об'єкта.

---

## Архітектура проєкту (Clean Architecture)

```text
BgRemoverAndroid/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cleancut/bgremover/
│       │   ├── data/
│       │   │   ├── ml/
│       │   │   │   ├── GuidedFilter.kt             // Алгоритм керованої фільтрації країв
│       │   │   │   ├── HybridSubjectSegmenter.kt   // Багаторівневий маршрутизатор (FAST, STUDIO, ULTRA, ANIME)
│       │   │   │   ├── MatteDefringer.kt           // Деконтамінація фону та придушення білих ореолів
│       │   │   │   ├── MlKitSubjectSegmenter.kt    // ML Kit + GuidedFilter
│       │   │   │   ├── OnnxBiRefNetSegmenter.kt    // BiRefNet-Lite via ONNX Runtime Mobile
│       │   │   │   ├── OnnxIsnetAnimeSegmenter.kt  // IS-Net Anime via ONNX Runtime Mobile
│       │   │   │   └── OnnxRmbgSegmenter.kt        // RMBG-1.4 via ONNX Runtime Mobile
│       │   │   ├── update/
│       │   │   │   └── GitHubUpdateManager.kt      // Автооновлення через GitHub Releases
│       │   │   └── util/
│       │   │       └── BitmapUtils.kt              // Маніпуляції з Bitmap, EXIF, MediaStore
│       │   ├── domain/
│       │   │   ├── model/
│       │   │   │   ├── AppUpdate.kt                // Модель оновлення
│       │   │   │   ├── SegmentationMode.kt         // Перелік режимів (FAST, STUDIO, ULTRA, ANIME)
│       │   │   │   └── SegmentationResult.kt       // Модель результату
│       │   │   ├── repository/
│       │   │   │   ├── SubjectSegmenter.kt         // Інтерфейс сегментації (seam)
│       │   │   │   └── UpdateManager.kt            // Інтерфейс оновлення
│       │   │   └── usecase/
│       │   │       └── SegmentImageUseCase.kt      // Бізнес-сценарій
│       │   └── ui/
│       │       ├── MainActivity.kt                 // Вхідна точка Activity
│       │       ├── components/
│       │       │   ├── BackgroundSelector.kt       // Вибір фону та палітри
│       │       │   ├── CheckerboardBackground.kt   // Шахова сітка прозорості
│       │       │   ├── ImagePreviewArea.kt         // Зум, порівняння оригіналу
│       │       │   ├── QualityModeSelector.kt      // Перемикач 3 режимів + діалог завантаження
│       │       │   └── UpdateDialog.kt             // Діалог оновлення версій
│       │       ├── screen/
│       │       │   └── MainScreen.kt               // Головний екран
│       │       ├── theme/
│       │       │   ├── Color.kt
│       │       │   ├── Theme.kt
│       │       │   └── Type.kt
│       │       └── viewmodel/
│       │           └── MainViewModel.kt            // Керування реактивним станом
│       └── res/
│           ├── values/
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── xml/
│               ├── backup_rules.xml
│               ├── data_extraction_rules.xml
│               └── file_paths.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── README.md
```

---

## Вбудована система версій та автооновлень
- Застосунок періодично опитує публічний GitHub Releases API.
- Якщо опубліковано новішу версію APK, на екрані з'являється діалог оновлення з переліком змін.
- Завантаження та виклик системного інсталятора Android виконуються в один дотик.
