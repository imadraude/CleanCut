# CleanCut: Застосунок для автоматичного видалення фону на Android

CleanCut — сучасний нативний застосунок для Android, розроблений мовою **Kotlin** із використанням **Jetpack Compose** та офіційного API комп'ютерного зору **Google ML Kit Subject Segmentation**.

Застосунок працює повністю локально на пристрої: жодних LLM, хмарних серверів чи витоку персональних даних.

---

## Ключові технологічні рішення

1. **Google ML Kit Subject Segmentation API**:
   - Офіційне сучасне рішення Google для виділення об'єктів (люди, тварини, предмети) на фото.
   - Використовує апаратне прискорення пристрою (GPU / NPU Neural Networks API).
   - Мінімальний розмір APK: модель оптимізована й динамічно завантажується службами Google Play Services.
   - Швидкість роботи: типовий час обробки становить 30-100 мс залежно від роздільної здатності процесора.

2. **Clean Architecture (Глибокі модулі за принципом Codebase Design)**:
   - **Domain**: Інтерфейс `SubjectSegmenter` приховує деталі реалізації від UI. Будь-який рушій (ML Kit, ONNX, TFLite) може бути підключений без змін у презентаційному шарі.
   - **Data**: Адаптер `MlKitSubjectSegmenter` обробляє бінарні маски, альфа-канали ARGB_8888 та оптимізацію пам'яті.
   - **Presentation**: Сучасний інтерфейс на **Jetpack Compose** та **Material 3**.

3. **Оптимізація роботи із зображеннями**:
   - `BitmapUtils` запобігає `OutOfMemoryError` завдяки інтелектуальному субсемплінгу великих фотографій (наприклад, 48MP/108MP з камер).
   - Автоматичне виправлення орієнтації згідно з EXIF метаданими.
   - Збереження результату в галерею через Scoped Storage (`MediaStore.Images`).
   - Експорт через стандартний системний Android Sharesheet (`FileProvider`).

4. **Можливості редактора**:
   - Експорт чистого прозорого PNG з візуалізацією шахової сітки (checkerboard).
   - Заміна фону на студійні однотонні пресети (білий, чорний, сірий, пастельні відтінки).
   - Встановлення довільного власного зображення як нового фону.
   - Масштабування жестами (Pinch-to-zoom / Pan) та миттєве порівняння з оригіналом.

---

## Структура проєкту

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
│       │   │   │   └── MlKitSubjectSegmenter.kt    // Адаптер ML Kit
│       │   │   └── util/
│       │   │       └── BitmapUtils.kt              // Маніпуляції з Bitmap, EXIF, MediaStore
│       │   ├── domain/
│       │   │   ├── model/
│       │   │   │   └── SegmentationResult.kt       // Модель результату
│       │   │   ├── repository/
│       │   │   │   └── SubjectSegmenter.kt         // Інтерфейс (seam)
│       │   │   └── usecase/
│       │   │       └── SegmentImageUseCase.kt      // Сценарій використання
│       │   └── ui/
│       │       ├── MainActivity.kt                 // Вхідна точка Activity
│       │       ├── components/
│       │       │   ├── BackgroundSelector.kt       // Вибір фонів та пресетів
│       │       │   ├── CheckerboardBackground.kt   // Шахова сітка прозорості
│       │       │   └── ImagePreviewArea.kt         // Перегляд, зум, порівняння
│       │       ├── screen/
│       │       │   └── MainScreen.kt               // Головний екран (Idle, Processing, Success, Error)
│       │       ├── theme/
│       │       │   ├── Color.kt
│       │       │   ├── Theme.kt
│       │       │   └── Type.kt
│       │       └── viewmodel/
│       │           └── MainViewModel.kt            // StateFlow та бізнес-логіка UI
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

## Як відкрити та зібрати проєкт

### Варіант 1: Через Android Studio (Рекомендовано)
1. Відкрийте Android Studio.
2. Оберіть **File -> Open** та вкажіть папку `BgRemoverAndroid`.
3. Дочекайтеся завершення синхронізації Gradle.
4. Підключіть Android-пристрій або запустіть емулятор.
5. Натисніть **Run 'app'** (або комбінацію Shift + F10).

### Варіант 2: Збірка через консоль (Gradle)
Для компіляції APK виконайте команду в кореневій директорії проєкту:
```bash
./gradlew assembleDebug
```
Зібраний APK файл буде знаходитися за шляхом:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Системні вимоги
- **Android OS**: версія 7.0 (API 24) або новіша.
- **Google Play Services**: підтримуються на переважній більшості сертифікованих Android-смартфонів.
- **Java**: JDK 17.
- **Gradle**: 8.4+.
