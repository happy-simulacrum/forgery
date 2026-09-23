# Forge Neo API — срез для Android-клиента Forgery

> Сервер: ветка `neo` репозитория `Haoming02/sd-webui-forge-classic`.
> Дата просмотра: `2026-09-23`.
> Клиентский срез: `core/network/src/main/kotlin/com/forgery/app/core/network/ForgeApi.kt`
> (`ForgeService`, `ForgeJson`, `ForgeHeadersInterceptor`, `ForgeApiFactory`).

## Правило обновления дока

1. При подключении любого `❌`-эндпоинта переключить его статус на `✅` и вписать caller
   (класс/метод репозитория, Worker'а или билдера payload'а) и маппинг полей.
2. Фактуру сверять с сервером:
   - роуты — `modules/api/api.py`;
   - DTO — `modules/api/models.py`;
   - шедулеры — `modules/sd_schedulers.py`.
3. Имена полей, эндпоинтов и ключей payload'а писать английским как в API, окружающий текст — русским.

## Правила клиента (обязательные)

- База: `<baseUrl>/` + `sdapi/v1/...`. Legacy-исключение: `GET /file=` (без префикса `sdapi/v1/`).
- Заголовки: `ForgeHeadersInterceptor` добавляет `ngrok-skip-browser-warning: true`,
  опционально `CF-Access-Client-Id` / `CF-Access-Client-Secret` (Cloudflare Access / ACR).
  Зеркалит `network.js` Cloudflare-инжект веб-UI.
- Ответы нетипизированные: `ForgeService` возвращает `JsonObject` / `List<JsonObject>`,
  парсер `ForgeJson = Json { ignoreUnknownKeys = true; explicitNulls = false }`.
  Жёсткие DTO не используем — поля `options`/`info` смешивают строки/числа/объекты.
- Хелперы `ForgeApi.kt`:
  - `JsonObject.stringField(vararg names)` — первый непустой `contentOrNull`
    (используем для фолбэков вида `stringField("model_name", "title")`);
  - `JsonObject.images()` — `images: [base64, ...]`;
  - `JsonObject.progressValue()` — `progress: Double ?: 0.0`.
- `POST sdapi/v1/options` (`setOptions`) возвращает `None`/`null`, поэтому в клиенте
  это сырой `ResponseBody` (закрывается caller'ом), а не `JsonObject`.
- `ForgeApiFactory.sanitized()` прогоняет копию payload'а через `sanitizeOverrideSettings`
  для ключа `override_settings` перед `txt2img`/`img2img`.

## Роуты `/sdapi/v1/` — полный список ветки `neo`

Префикс всех роутов ниже — `/sdapi/v1/`, метод указан явно.
`✅` — используется приложением (caller + маппинг), `❌` — не используется (одной строкой).

### Генерация изображений

| Метод + путь | Статус | Комментарий |
|---|---|---|
| `POST txt2img` | ✅ | Caller: `PayloadBuilder` (+ `GenerationRepository`/`GenerationWorker` поверх). Ключи payload: стандартные поля `processing` + `sampler_index`, `script_name`, `script_args`, `send_images`, `save_images`, `alwayson_scripts`, `force_task_id`, `infotext` и Neo-ключ `scheduler`. Ответ: `JsonObject` с `images`, `parameters`, `info`. |
| `POST img2img` | ✅ | Caller: `PayloadBuilder` (та же схема, что `txt2img` + init image/маска/denoise). Neo-ключ `scheduler` тоже поддерживается. Ответ: `JsonObject` (`images`, `parameters`, `info`). |
| `POST extra-single-image` | ❌ | Возвращает один апскейл; мог бы пригодиться для кнопки «Upscale» без очереди генерации. |
| `POST extra-batch-images` | ❌ | Пакетный апскейл; пригодился бы для Gallery bulk-операций. |
| `POST png-info` | ❌ | Возвращает `info`/`parameters` из PNG; пригодился бы для «Copy generation params» в Gallery. |

### Прогресс и управление задачей

| Метод + путь | Статус | Комментарий |
|---|---|---|
| `GET progress` | ✅ | Caller: `GenerationWorker` (опрос во время генерации). Маппинг: `progressValue()` (`progress`), `images()` (превью), плюс `state`/`eta`/`textinfo` по мере нужды. |
| `POST interrupt` | ❌ | Прерывает текущую задачу; нужен для кнопки «Stop». |
| `POST skip` | ❌ | Пропускает текущий шаг/итерацию; нужен для кнопки «Skip» при batch. |

### Конфиг и флаги

| Метод + путь | Статус | Комментарий |
|---|---|---|
| `GET options` | ✅ | Caller: flow `ensureModel` читает текущий checkpoint (ключ `sd_model_checkpoint`) чтобы решить, нужен ли `setOptions` перед генерацией. Весь объект — `JsonObject` без DTO. |
| `POST options` | ✅ | Caller: `ForgeService.setOptions()` (смена checkpoint/настроек, `override`-флоу `ensureModel`). Тело — `JsonObject`, ответ сырой `ResponseBody` (`null`). |
| `GET cmd-flags` | ❌ | Возвращает CLI-флаги сервера; полезно для диагностики/фича-флагов (`--api-server-stop` и т.п.). |

### Справочники: сэмплеры, шедулеры, апскейлеры, модели

| Метод + путь | Статус | Комментарий |
|---|---|---|
| `GET samplers` | ✅ | Caller: `GenerationRepository.fetchSamplers`. DTO `SamplerItem`, берём `name` (`aliases`/`options` игнорируем). |
| `GET schedulers` | ✅ новый | Neo-специфика (в A1111 этого роута нет). Caller: `GenerationRepository.fetchSchedulers` (добавляется параллельно другим сабагентом). DTO `SchedulerItem`, берём `label`, фолбэк `name`. Детали — секция «Schedulers» ниже. |
| `GET upscalers` | ✅ | Caller: `GenerationRepository.fetchUpscalers`. DTO `UpscalerItem`, берём `name` (`model_name`/`model_path`/`model_url`/`scale` пока не маппим). |
| `GET latent-upscale-modes` | ❌ | Возвращает режимы latent-апскейла; пригодился бы при hires-fix UI. |
| `GET sd-models` | ✅ | Caller: `GenerationRepository.fetchSdModels`. DTO `SDModelItem`, маппинг через `stringField("model_name", "title")` + `filename`/`hash`/`sha256` для идентификации и дедупа. |
| `GET sd-modules` | ✅ | Neo-специфика: объединённый каталог VAE / Text Encoder (`models/VAE` + `models/text_encoder`). Caller: `GenerationRepository.fetchModules`. DTO `SDModuleItem`, маппинг `stringField("model_name")` (basename, напр. `ae.safetensors`) + `filename` (полный серверный путь). |
| `GET face-restorers` | ❌ | Возвращает список face-restore моделей; пригодился бы для тумблера Restore Faces. |

### Стили, эмбеддинги, LoRA

| Метод + путь | Статус | Комментарий |
|---|---|---|
| `GET prompt-styles` | ✅ | Caller: `GenerationRepository.fetchPromptStyles`. DTO `PromptStyleItem`, маппинг `name` + `prompt` + `negative_prompt`. |
| `GET embeddings` | ❌ | Возвращает `{ loaded, skipped }` текстовых инверсий; полезно для автодополнения триггеров. |
| `GET loras` | ✅ | Caller: каталог LoRA (поверх `ForgeService.loras()`). Поля как у `SDModelItem`-подобных записей (`name`/`alias`/`path`); sidecar тянем через `/file=`. |
| `POST refresh-embeddings` | ❌ | Пересканирует embeddings; нужен после закачки новых файлов без рестарта. |
| `GET /file=` (legacy, без `sdapi/v1/`) | ✅ | Не из `api.py`. Caller: `fetchLoraSidecar` — sidecar JSON LoRA (`GET /file=<base>.json`) + превью. Параметр `url` передаётся через `@Url`. |

### Память, checkpoint-утилиты

| Метод + путь | Статус | Комментарий |
|---|---|---|
| `GET memory` | ❌ | Возвращает RAM/VRAM (`ram`, `cuda`); полезно для бейджа памяти и диагностики OOM. |
| `POST refresh-checkpoints` | ❌ | Пересканирует checkpoints; нужен после закачки модели без рестарта. |
| `POST refresh-vae` | ❌ | Пересканирует VAE; пара к `sd-modules` при смене VAE-файлов. |
| `POST unload-checkpoint` | ✅ | Caller: `confirmUnloadModel`. Освобождает VRAM активной модели. |

### Скрипты и расширения

| Метод + путь | Статус | Комментарий |
|---|---|---|
| `GET scripts` | ❌ | Возвращает каталог scripts (`txt2img`/`img2img`); нужен чтобы слать `script_name`/`script_args` осознанно. |
| `GET script-info` | ❌ | Возвращает описание одного скрипта; пара к `scripts` для UI параметров скриптов. |
| `GET extensions` | ❌ | Возвращает список расширений; полезно для диагностики/гейтинга фич. |

### Служебные server-\* (только с флагом `--api-server-stop`)

| Метод + путь | Статус | Комментарий |
|---|---|---|
| `POST server-kill` | ❌ | Убивает серверный процесс; в приложении не нужен (опасная операция). |
| `POST server-restart` | ❌ | Перезапускает сервер; мог бы пригодиться как «Restart server» в Config при смене моделей. |
| `POST server-stop` | ❌ | Останавливает сервер; аналогично — только power-user сценарий. |

## DTO (`modules/api/models.py`)

Поля — английским как на сервере. Порядок и типы — по `models.py` ветки `neo`.

- `SamplerItem { name, aliases, options: dict }` — клиент берёт только `name`.
- `SchedulerItem { name, label, aliases, default_rho, need_inner_model }` — клиент берёт
  `label`, фолбэк `name`; `default_rho`/`need_inner_model` пока не маппим.
  Neo-специфика: такого DTO в A1111 нет.
- `UpscalerItem { name, model_name, model_path, model_url, scale }` — клиент берёт `name`.
- `SDModelItem { title, model_name, hash, sha256, filename, config }` — клиент различает
  `title` (display) и `model_name` (короткое имя), читает через `stringField("model_name", "title")`;
  `filename` — полный путь, `hash`/`sha256` — идентификация.
- `SDModuleItem { model_name, filename }` — Neo-специфика (VAE / Text Encoder);
  `model_name` — basename, `filename` — полный путь.
- `PromptStyleItem { name, prompt, negative_prompt }` — маппинг 1-в-1 в UI стилей.

Запросы `txt2img`/`img2img` — динамические: строятся через `PydanticModelGenerator`
поверх processing-классов, поэтому фиксированного списка полей нет. Правило:
payload = все поля processing + служебные `sampler_index`, `script_name`, `script_args`,
`send_images`, `save_images`, `alwayson_scripts`, `force_task_id`, `infotext`
и Neo-поле `scheduler`. Все поля перечислять не нужно — клиент шлёт `JsonObject`
и терпит неизвестные ключи благодаря `ignoreUnknownKeys` на приёме.

## Schedulers (Neo-специфика)

Отдельный роут `GET sdapi/v1/schedulers`, DTO `SchedulerItem`, источник — `modules/sd_schedulers.py`.

### Таблица `name` ↔ `label`

| `name` | `label` |
|---|---|
| `automatic` | `Automatic` |
| `karras` | `Karras` |
| `exponential` | `Exponential` |
| `polyexponential` | `Polyexponential` |
| `normal` | `Normal` |
| `simple` | `Simple` |
| `uniform` | `Uniform` |
| `sgm_uniform` | `SGM Uniform` |
| `linear_quadratic` | `Linear Quadratic` |
| `kl_optimal` | `KL Optimal` |
| `ddim` | `DDIM` |
| `align_your_steps` | `Align Your Steps` |
| `beta` | `Beta` |
| `turbo` | `Turbo` |
| `bong_tangent` | `Bong Tangent` |
| `flow_match` | `FlowMatchEulerDiscrete` |
| `flux2` | `Flux2` |

### Правила

- Фильтр: записи, скрытые через `hide_schedulers`, в выдачу роута не попадают.
- Резолв: `schedulers_map` принимает и `name`, и `label` (регистр/формат — как на сервере).
- Семантика `Automatic`: значение `automatic` резолвится из сэмплера через
  `get_sampler_and_scheduler` (шедулер по умолчанию для выбранного сэмплера).
- Клиент: `fetchSchedulers` показывает `label`, фолбэк `name`; в payload `txt2img`/`img2img`
  уходит ключ `scheduler` (принимаются `name` и `label`).
