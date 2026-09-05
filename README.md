# CleanCut: Застосунок для автоматичного видалення фону на Android

CleanCut — сучасний нативний застосунок для Android, розроблений мовою **Kotlin** із використанням **Jetpack Compose**, подвійного рушія комп'ютерного зору (**Google ML Kit + Guided Filter** та **Bria AI RMBG-1.4 через ONNX Runtime Mobile**) і вбудованої системи автооновлень через GitHub Releases.

Застосунок працює повністю локально на пристрої: жодних LLM, хмарних серверів чи витоку персональних даних.

---

## Подвійна система сегментації (Гібридний рушій)

1. **Швидкий режим (Fast + Edge Refined)**:
   - Працює на базі Google ML Kit Subject Segmentation API.
   - Застосовує алгоритмічний **Guided Filter (керований фільтр)**: використовує повнорозмірне RGB-зображення як орієнтир для точного припасування меж м'якої маски до реальних колірних градієнтів об'єкта.
   - Усуває колірний ореол фону (Defringing / Chromatic Bleed Removal) уздовж контурів волосся та одягу.
   - Миттєва швидкість (30-60 мс) та 0 МБ додаткового розміру.

2. **Студійний режим (Studio RMBG-1.4 через ONNX Runtime)**:
   - Працює на базі нейромережі **Bria AI RMBG-1.4** (DIS-5K) через **ONNX Runtime Mobile**.
   - Виконує обчислення на тензорі високої роздільної здатності 1024x1024.
   - Забезпечує найвищу студійну точність вирізання складних деталей: окремих пасм волосся, хутра тварин, напівпрозорих тканин та тонких предметів.
   - Завантажується в один клік на вимогу користувача (~42 МБ), кешується в пам'яті смартфона та надалі працює 100% офлайн.

---

## Архітектурний дизайн (Clean Architecture + Deep Modules)

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
│       │   │   │   ├── HybridSubjectSegmenter.kt   // Маршрутизатор між FAST та STUDIO
│       │   │   │   ├── MlKitSubjectSegmenter.kt    // Адаптер ML Kit + GuidedFilter
│       │   │   │   └── OnnxRmbgSegmenter.kt        // Адаптер ONNX Runtime Mobile (RMBG-1.4)
│       │   │   ├── update/
│       │   │   │   └── GitHubUpdateManager.kt      // Автооновлення через GitHub Releases API
│       │   │   └── util/
│       │   │       └── BitmapUtils.kt              // Маніпуляції з Bitmap, EXIF, MediaStore
│       │   ├── domain/
│       │   │   ├── model/
│       │   │   │   ├── AppUpdate.kt                // Модель даних оновлення
│       │   │   │   ├── SegmentationMode.kt         // Перелік режимів (FAST, STUDIO)
│       │   │   │   └── SegmentationResult.kt       // Модель результату
│       │   │   ├── repository/
│       │   │   │   ├── SubjectSegmenter.kt         // Інтерфейс сегментації (seam)
│       │   │   │   └── UpdateManager.kt            // Інтерфейс оновлення застосунку
│       │   │   └── usecase/
│       │   │       └── SegmentImageUseCase.kt      // Сценарій сегментації
│       │   └── ui/
│       │       ├── MainActivity.kt                 // Вхідна точка Activity
│       │       ├── components/
│       │       │   ├── BackgroundSelector.kt       // Вибір фонів та кольорових пресетів
│       │       │   ├── CheckerboardBackground.kt   // Шахова сітка прозорості
│       │       │   ├── ImagePreviewArea.kt         // Полотно, зум, панорамування, порівняння
│       │       │   ├── QualityModeSelector.kt      // Перемикач Швидкий / Студійний
│       │       │   └── UpdateDialog.kt             // Діалог перевірки та завантаження оновлень
│       │       ├── screen/
│       │       │   └── MainScreen.kt               // Головний екран
│       │       ├── theme/
│       │       │   ├── Color.kt
│       │       │   ├── Theme.kt
│       │       │   └── Type.kt
│       │       └── viewmodel/
│       │           └── MainViewModel.kt            // StateFlow та реактивна бізнес-логіка
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

## Вбудована система автооновлень
- Застосунок самостійно перевіряє вихід нових версій через публічний GitHub Releases API.
- При виявленні нової версії з'являється діалогове вікно зі списком змін та кнопкою встановлення.
- Завантаження виконується напряму з GitHub Actions, після чого відкривається стандартний інсталятор Android пакетів.
