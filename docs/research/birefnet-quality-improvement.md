# Дослідження якості сегментації BiRefNet (Bilateral Reference Network) у CleanCut

## 1. Вступ та контекст проблеми

У застосунку CleanCut режим **«Ultra»** реалізовано за допомогою архітектури **BiRefNet-Lite** ([OnnxBiRefNetSegmenter.kt](file:///data/data/com.termux/files/home/BgRemoverAndroid/app/src/main/java/com/cleancut/bgremover/data/ml/OnnxBiRefNetSegmenter.kt)). Проте користувачі стикаються з помітними дефектами якості:
1. «Або зайве виріже» (відрізає руки, ноги, предмети в руках, частини людей у групі);
2. «Або надто сильно вигризе межі» (рвані контури, втрата тонкого волосся, пікселізація переходу);
3. «Або щось залишить» (шматки фону, стільці, сторонні предмети).

Це дослідження розкриває першопричини цих дефектів на основі аналізу першоджерел (офіційний репозиторій автора, стаття CAAI AIR, релізи Hugging Face, екосистеми `rembg` та ComfyUI) та формулює вичерпний інженерний план оптимізації.

---

## 2. Препроцесинг та вхідні дані

### 2.1. Офіційний пайплайн трансформацій BiRefNet
У першоджерелі ([ZhengPeng7/BiRefNet](https://github.com/ZhengPeng7/BiRefNet)) та тренувальному коді `dataset.py` стандартний пайплайн перетворення вхідних зображень визначається так:

```python
from torchvision import transforms

transform_image = transforms.Compose([
    transforms.Resize((1024, 1024)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
])
```

- **Resize без збереження пропорцій vs Letterbox**:
  - Офіційний BiRefNet тренується на **прямому `Resize((1024, 1024))` без letterboxing / padding**.
  - Мережа є повністю згортковою трансформерною архітектурою (Fully Convolutional Hierarchical Architecture зі Swin Transformer). Вона оптимізована під повне заповнення тензора $1024 \times 1024$.
  - Після інференсу вихідна маска розміром $1024 \times 1024$ інтерполюється (білінійно або бікубічно) безпосередньо назад до оригінальних габаритів зображення $(W, H)$.
- **Проблема Letterboxing у CleanCut**:
  - У поточному коді CleanCut ([OnnxBiRefNetSegmenter.kt](file:///data/data/com.termux/files/home/BgRemoverAndroid/app/src/main/java/com/cleancut/bgremover/data/ml/OnnxBiRefNetSegmenter.kt#L168-L202)) реалізовано letterbox із заповненням відступів значенням `0.0f` у нормалізованому просторі ImageNet.
  - У нормалізованому просторі $0.0f$ відповідає кольору `RGB = (123.675, 116.28, 103.535)` (сірий колір ImageNet mean).
  - На стику справжнього зображення та штучного сірого поля виникає різкий штучний перепад яскравості, на який реагують внутрішні референсні блоки BiRefNet, сприймаючи цей стик як межу об'єкта або, навпаки, штучний фон.
- **Стандартний розмір для Lite**:
  - Для `BiRefNet_lite` еталонний розмір вхідного тензора — **$1024 \times 1024$**.
  - Бекбон Swin-v1-Tiny розрахований на сітку патчів під $1024 \times 1024$.
- **Колірний простір та нормалізація**:
  - Колірний простір: **RGB** (не BGR).
  - Нормалізація: суворо **ImageNet**:
    - $\text{mean} = [0.485, 0.456, 0.406]$
    - $\text{std} = [0.229, 0.224, 0.225]$
  - Таблиці пошуку (LUT) у CleanCut для нормалізації реалізовано математично правильно, проте заповнення відступів нулями створює хибні контури.

---

## 3. Чекпойнти моделі: академічний DIS5K проти General Background Removal

### 3.1. Що насправді завантажує CleanCut?
Наразі CleanCut завантажує:
`https://huggingface.co/onnx-community/BiRefNet_lite-ONNX/resolve/main/onnx/model.onnx`

Згідно з офіційною карткою моделі Hugging Face ([onnx-community/BiRefNet_lite-ONNX](https://huggingface.co/onnx-community/BiRefNet_lite-ONNX) та [ZhengPeng7/BiRefNet_lite](https://huggingface.co/ZhengPeng7/BiRefNet_lite)):
- **Ця модель натренована ВИКЛЮЧНО на датасеті DIS-TR (DIS5K)!**

### 3.2. Чому модель DIS5K не підходить для загального видалення фону?
1. **Специфіка задачі Dichotomous Image Segmentation (DIS)**:
   - Датасет DIS5K створено для академічного бенчмарку сегментації **одного головного дихотомічного об'єкта** зі складною мікроструктурою (павутина, дерево, скульптура, паркан, годинниковий механізм).
   - У DIS5K розмічальники виділяли лише один цільовий суб'єкт. Якщо в кадрі людина тримає чашку чи смартфон, у DIS5K чашка або смартфон часто вважалися фоном, або навпаки!
2. **Відсутність портретних і побутових датасетів**:
   - DIS-TR не містить датасетів портретної сегментації людини (`P3M-10k`, `Human-2k`, `TR-humans`, `PPM-100`).
   - Коли користувач CleanCut обробляє фото людини, модель DIS5K:
     - Відрізає кінцівки, сумки, окуляри, головні убори;
     - У групових знімках вибирає одного суб'єкта і «вигризає» інших;
     - Залишає частини стільців, меблів чи підлоги, оскільки плутає їх із цільовим об'єктом.

### 3.3. Моделі General Background Removal (`BiRefNet-general`, `BiRefNet-general-lite`)
Для якісного комерційного видалення фону автор BiRefNet (Zheng Peng) та бібліотека `rembg` використовують ваги серії **General** або **Massive**:
- **Набір датасетів General**:
  $\text{DIS5K} + \text{HRS10K} + \text{UHRSD} + \text{P3M-10k (портрети)} + \text{Human-2k} + \text{TR-humans} + \text{AM-2k (тварини/хутро)} + \text{AIM-500} + \text{Distinctions-646} + \text{HIM2K} + \text{PPM-100}$.
- **Офіційний та перевірений ONNX-чекпойнт**:
  У репозиторії `danielgatis/rembg` (релізний тег `v0.0.0`) опубліковано готові ONNX моделі:
  - **`birefnet-general-lite.onnx`** (~224 МБ):
    URL: `https://github.com/danielgatis/rembg/releases/download/v0.0.0/birefnet-general-lite.onnx`
    Це саме архітектура BiRefNet-Lite (Swin-v1-Tiny), але навчена на мультидоменному наборі General. Вона відмінно сегментує людей, тварин, предмети, одяг та складні сцени.

---

## 4. Постпроцесинг: анатомія «вигризання меж»

### 4.1. Формат виходу: Raw Logits замість Sigmoid Probabilities
- Усі моделі BiRefNet (включно з ONNX-експортами) на виході повертають **неактивовані сирі логіти** (Raw Logits), а не ймовірності $[0, 1]$.
- Для отримання альфа-маски обов'язково викликається $\sigma(x) = \frac{1}{1 + e^{-x}}$.

### 4.2. Критичний баг перевірки Sigmoid у CleanCut
У файлі [OnnxBiRefNetSegmenter.kt](file:///data/data/com.termux/files/home/BgRemoverAndroid/app/src/main/java/com/cleancut/bgremover/data/ml/OnnxBiRefNetSegmenter.kt#L228-L240) реалізовано таку логіку:
```kotlin
// Check if output is raw logits needing sigmoid activation
var needsSigmoid = false
val checkLimit = min(100, planeSize)
for (i in 0 until checkLimit) {
    if (mask1024[i] < -0.01f || mask1024[i] > 1.01f) {
        needsSigmoid = true
        break
    }
}
if (needsSigmoid) {
    for (i in 0 until planeSize) {
        mask1024[i] = 1f / (1f + exp(-mask1024[i]))
    }
}
```
**Чому це призводить до катастрофи якості**:
1. Перевіряються лише **перші 100 пікселів** масиву (рядок $y=0$, стовпчики $x \in [0, 99]$).
2. Через letterbox у верхній частині тензора знаходиться штучний відступ (padding).
3. Якщо на порожньому відступі нейромережа видала значення логітів у діапазоні від $-0.01$ до $+1.01$ (типово для однорідних ділянок без об'єктів), прапорець `needsSigmoid` залишається `false`!
4. В результаті вся маска використовується **як сирі логіти без sigmoid**, що призводить до повного спотворення альфа-каналу, диких артефактів та «вигризання» цілих областей.
5. **Висновок**: BiRefNet **завжди** потребує Sigmoid. Ця евристика шкідлива та має бути замінена безумовним розрахунком.

### 4.3. Руйнівний вплив Guided Filter та подвійного Smoothstep
Архітектурна основа BiRefNet — це **Bilateral Reference Blocks (BRB)**. Мережа спеціально навчена поєднувати глобальний контекст із попіксельними високочастотними референсними картами, самостійно вирішуючи задачу маттингу на субпіксельному рівні (пасма волосся, прозоре скло, мереживо).

У поточному конвеєрі CleanCut після отримання маски BiRefNet виконуються такі кроки:
1. **Fast Guided Filter** ([GuidedFilter.kt](file:///data/data/com.termux/files/home/BgRemoverAndroid/app/src/main/java/com/cleancut/bgremover/data/ml/GuidedFilter.kt#L204-L208)):
   ```kotlin
   var q = aVal * luminance + bVal
   q = when {
       q < 0.15f -> 0f
       q > 0.85f -> 1f
       else -> smoothstep(0.15f, 0.85f, q)
   }
   output[rowDst + x] = q
   ```
2. **Фінальний композитинг** ([OnnxBiRefNetSegmenter.kt](file:///data/data/com.termux/files/home/BgRemoverAndroid/app/src/main/java/com/cleancut/bgremover/data/ml/OnnxBiRefNetSegmenter.kt#L298-L302)):
   ```kotlin
   alpha = when {
       alpha < 0.05f -> 0f
       alpha > 0.95f -> 1f
       else -> smoothstep(0.05f, 0.95f, alpha)
   }
   ```

**Наслідки**:
- **Знищення матовості та напівпрозорості**: Усі напівпрозорі значення альфи (волосся, пух тварин мають альфу $0.2 \dots 0.6$) потрапляють під подвійний жорсткий S-подібний контраст `smoothstep` та відсікання. Плавний край штучно бінаризується, перетворюючись на рвані «покусані» сходинки.
- **Паразитна інтерференція Guided Filter**: Guided Filter базується на локальній лінійній регресії відносно яскравості (Luminance) RGB-зображення ($q = a \cdot I + b$). Якщо за волоссям знаходиться неоднорідне тло (листя, цегла, контрастні шпалери), Guided Filter бере текстуру фону і модулює нею маску! Маска або «вгризається» всередину зачіски до першого різкого колірного перепаду, або «захоплює» шматки фону.
- **Висновок**: Для BiRefNet Guided Filter **протипоказаний**. Він корисний лише для моделей низької роздільності (наприклад ML Kit з $256 \times 256$). Для BiRefNet із нативною роздільністю $1024 \times 1024$ вихідний альфа-канал має залишатися незайманим.

---

## 5. Дорожня карта та інженерні рекомендації для CleanCut

### 1. Заміна чекпойнту моделі (Ultra)
- Замінити URL завантаження на генеральний мультидоменний чекпойнт:
  `https://github.com/danielgatis/rembg/releases/download/v0.0.0/birefnet-general-lite.onnx`
  - Розмір: ~224 МБ (практично ідентичний до поточних 213 МБ).
  - Спеціалізація: універсальне комерційне видалення фону (люди, групи людей, тварини, товари, складні об'єкти).
  - Усуває проблему відрізання рук, ніг та залишення меблів.

### 2. Спрощення та очищення препроцесингу
- Перейти на еталонний пайплайн BiRefNet: прямий `Resize((1024, 1024))` без штучних сірих полів відступу з наступним прямим білінійним ресемплінгом маски $1024 \times 1024$ до оригінальних розмірів $(W, H)$ зображення.

### 3. Безумовне та швидке застосування Sigmoid
- Видалити хибну 100-піксельну евристику `val checkLimit = min(100, planeSize)`.
- Логіти завжди переводити через Sigmoid:
  $$\alpha_i = \frac{1}{1 + e^{-z_i}}$$
  (Для максимальної швидкості на мобільному CPU можна використовувати LUT або Fast Sigmoid).

### 4. Повне вилучення Guided Filter з режиму Ultra
- У сегментері [OnnxBiRefNetSegmenter.kt](file:///data/data/com.termux/files/home/BgRemoverAndroid/app/src/main/java/com/cleancut/bgremover/data/ml/OnnxBiRefNetSegmenter.kt) **повністю виключити** виклик `GuidedFilter.filter(...)`.
- Високоточна маска BiRefNet одразу переноситься на повнорозмірне зображення.

### 5. Природний альфа-композитинг
- Прибрати агресивний подвійний `smoothstep(0.15, 0.85)` та `smoothstep(0.05, 0.95)`.
- Залишити лише безпечне відсікання фонового шуму ($\alpha < 0.01 \to 0$, $\alpha > 0.99 \to 1$).
- Зберігати реальні напівпрозорі значення альфи $\alpha \in [0, 255]$ для ідеального злиття пасом волосся та дрібних деталей із будь-яким новим фоном.

---

## 6. Першоджерела та бібліографія

1. **Офіційна стаття BiRefNet**:
   - Zheng Peng et al., *BiRefNet: Bilateral Reference for High-Resolution Dichotomous Image Segmentation*, CAAI AIR / arXiv:2401.03407, 2024.
   - [arXiv:2401.03407](https://arxiv.org/abs/2401.03407)
2. **Офіційний вихідний код BiRefNet**:
   - [GitHub: ZhengPeng7/BiRefNet](https://github.com/ZhengPeng7/BiRefNet)
   - Модулі `models/birefnet.py`, `dataset.py`, `inference.py`.
3. **Офіційний модельний зоопарк Hugging Face**:
   - [ZhengPeng7/BiRefNet_lite](https://huggingface.co/ZhengPeng7/BiRefNet_lite) (академічний DIS5K-чекпойнт)
   - [ZhengPeng7/BiRefNet-general](https://huggingface.co/ZhengPeng7/BiRefNet-general) (універсальний мультидатасетний чекпойнт)
   - [onnx-community/BiRefNet_lite-ONNX](https://huggingface.co/onnx-community/BiRefNet_lite-ONNX) (ONNX-конверсія для Transformers.js)
4. **Еталонна інтеграція ONNX для бекграунд-ремуверів**:
   - [GitHub: danielgatis/rembg](https://github.com/danielgatis/rembg)
   - Модель `birefnet-general-lite.onnx` у релізах `v0.0.0`.
