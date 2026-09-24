# Bugs — verified 2026-09-24, updated after Critical fix 2026-09-24

Legend: `[CONFIRMED]` воспроизводится, `[PARTIAL]` частично, `[REJECTED]` нет / уже был исправлен,
`[FIXED]` исправлено в заходе 2026-09-24, `[DONE]` закрыто. Строки — фактические на момент проверки;
раздел "После Critical-фикса" фиксирует новый код (Room v4, таблицы очереди).
Итог проверки: 68/69 подтверждены (включая 4 partial), 1 rejected (H-1).
Итог после фикса: 4/4 Critical FIXED; из High H-1 REJECTED, H-7 DONE, H-8 PARTIAL, остальные открыты;
L-2 PARTIAL (off-by-one fixed), L-6 DONE, L-24 PARTIAL (навигация fixed).

## Critical — ALL FIXED 2026-09-24 (debug собран, стоит на Pixel 6a, смок чистый)

- C-1 [FIXED] jobsJson в WorkManager inputData: очередь нормализована в таблицы
  (`queue_jobs`/`queue_results` + slim `queue_state(executingJobId)`, DB v3→v4, MIGRATION_3_4 чисто SQL destructive —
  ToDo при апгрейде сносится, принято). `launchWorker(host, origin)` (QueueRepository.kt:488-499) и
  Watchdog несут только host/origin (~50 байт); KEY_JOBS удален полностью (Imp #2 DONE).
  Inpaint file-back: `QueueInputs` (`queue_inputs/<jobId>/`), payload в DB ~1KB, воркер разворачивает в base64
  перед POST (GenerationWorker.kt:176-183), чистка файлов + orphan-sweep. `QueueSnapshot.currentIndex` → `executingJobId`
  (Models.kt), UI/тесты обновлены. Проверено: `./run_tests` зеленые + C-1 тест (20 задач, inputData без payloads).
- C-2 [FIXED] LoraViewModel.kt `insert()`: `appendPrompt(mode, "$tag $trigger".trim(), "")` — trigger в positive;
  вес через `Locale.US` (M-21 VM-часть закрыта заодно). Тест поправлен.
- C-3 [FIXED] GenerationRepository.kt: `catch (CancellationException) { throw e }` перед generic Exception
  во всех 5 местах (call, callGeneration, callWithHost, ensureModel, ensureAdditionalModules).
- C-4 [FIXED] Handoff: `consume() = getAndUpdate { null }` (атомарно; заодно закрыт L-6),
  снапшот `parsed` внутри launch, навигация только после `copyDone`, NavHost `popUpTo+launchSingleTop+restoreState`
  (стек не плодится; заодно закрыта nav-половина L-24). HR-merge вместо overwrite, cfg не трогается (заодно закрыт H-7).

## High

- H-1 [REJECTED — уже исправлен] GenerationWorker.kt record() lost-update race.
  Переписан 2026-09-24 на новые таблицы (id-based merge: duplicate-check via resultsDao + executingId advance в record);
  гонки нет. Регресс-тест record vs concurrent clear/remove — по-прежнему опционально.
- H-2 [CONFIRMED] QueueRepository.kt `cancel()` (~413: только `running=false, executingJobId=null` + `cancelUniqueWork`).
  Методов interrupt/skip нет (ForgeApi.kt:31-82; docs/forge-neo-api.md:57-58 ❌). Текущий txt2img на сервере продолжает считаться.
  Fix: `POST sdapi/v1/interrupt (+skip)` в ForgeService + `generationRepository.interrupt()` в cancel() (Imp #4).
- H-3 [CONFIRMED] Displayed model ≠ executed: GenerateScreen.kt:543-544 `selected.ifBlank{options.first()}` (вызов 214-224),
  GenerateViewModel.kt:462 `ModelChanged→rest`, исполнение `""` = серверный checkpoint (PayloadBuilder.kt:61-63, GenerationRepository.kt:195);
  InpaintScreen.kt:205-212 тот же fallback, InpaintViewModel.kt:107,159-169,264,280 уходит `modelTitle=""`.
  Fix: коммитить `models.first()` при загрузке каталога (Imp #7).
- H-4 [CONFIRMED, обе части] SettingsViewModel.kt:175-185 CHECK: `forgeApiFactory.create(url)` без `cfClientId/Secret`
  (config 146-147 игнорируется) + затирает глобальный синглтон ForgeHeadersInterceptor (ForgeApi.kt:94-96,155-157).
  Плюс использован `create` (infinite read) вместо `createControl` — см. M-13.
  Fix: `createControl(url, cfId, cfSecret)` + per-client интерцепторы (Imp #10).
- H-5 [CONFIRMED] Gallery deletes не удаляют файлы: GalleryViewModel.kt:119-139 → Repositories.kt:37-38
  `dao.deleteByIds/clear` (ForgeryDatabase.kt:67-71), `File.delete` нигде нет. Писатель GenerationWorker.kt:312-322 `native_queue`.
  Примечание 2026-09-24: чистка `queue_inputs/<jobId>` при remove/clear очереди сделана (C-1), но удаление истории
  по-прежнему оставляет файлы `native_queue` + thumbs навсегда.
  Fix: SELECT path → File.delete → DAO-delete в транзакции + orphan-sweep (Imp #5).
- H-6 [CONFIRMED] GalleryViewModel.kt:26,31-34,46-61 page не клампится: дисплей `coerceIn`, источник `page.value` не правится.
  Сценарий 51→50 айтемов: `observePage(50,50)=[]`, Next/Prev заблокированы — вечная пустая страница. Сброс есть только в All:123.
  Fix: клампить источник после изменения count + в Selected/Single.
- H-7 [DONE в заходе C-4, 2026-09-24] Был clobber `saveHr(..., cfg=1.0)` + целостная перезапись.
  Стало: merge `cur = observeHr(mode).first(); saveHr(cur.copy(...))`, cfg вообще не трогается.
- H-8 [PARTIAL после C-1, 2026-09-24] JSON-блоб `jobsJson/resultsJson` удален полностью (таблицы); построчный decode
  (`decodeStrings` в мапперах репозитория, modules/files в воркере) обернут в runCatching → пустой список вместо краша.
  Осталось: тест на мусор в строке (битая modulesJson/filesJson) + политика лечения битой строки.
- H-9 [CONFIRMED, структурно] GenerationWorker.kt:187 `CoroutineScope(coroutineContext).launch` — поллер child doWork
  (переписан, но конструкция та же). progress() обычно не бросает (call ловит Exception),
  но конструкция хрупкая. Fix: `+SupervisorJob()` + try/catch внутри цикла.
- H-10 [CONFIRMED] GenerationWorker.kt:128-133 catch-all → `success()` (переписан, конструкция та же): `JobFailedException` от abort + OOM/IO/saveImage/history
  прячутся от WorkManager (нет failure/retry), нотификация `Batch Complete` vs `Batch Paused` рассинхрон.
  Fix: неожиданное → failure/retry, success только при чистом выходе.
- H-11 [CONFIRMED] SettingsScreen.kt:91-100,193-208 мертвые UiPrefs: продюсер есть (`UiPrefsChanged`, saveUiPrefs),
  консьюмера ноль — MainActivity.kt:13-14 `ForgeryTheme{}` без darkTheme (Theme.kt:28 default true),
  NavHost.kt:62-69 dests захардкожен, `observeUiPrefs` только в Settings+тестах.
  Fix: дочитать в MainActivity/NavHost либо удалить секцию (Imp #9).
- H-12 [CONFIRMED] PromptDraftRepository.kt:65-85,87-95,75-77 uncaught DataStore: голые `first()/edit()` (ForgeryPreferences.kt:192-214
  без catch). Падает в commitInputs, copyToMode, LoRA/Styles/MagicPrompt; flush в scope без хендлера → краш процесса.
  Fix: `.catch{emit(PromptDraft())}` + try/catch IOException с ретраем.

## Medium (24)

- M-1 [CONFIRMED] GenerationRepository.kt:213,252-254 setOptions POST через 30s control client (ForgeApi.kt:148-153; call 98-103;
  комментарий 72-75: reload десятки секунд блокирует /progress). Ложный Error по таймауту + MODULE_SYNC 10x3s упирается в лимит.
  Fix: POST через generation client (infinite) или отдельный controlPost с большим timeout; timeout после POST = non-fatal + poll options.
- M-2 [CONFIRMED] No double-submit: GenerateViewModel.kt:616-637 stageQueue, 643-664 generateNow — голый launch, `UUID.randomUUID()` per call
  (603-612); InpaintViewModel.kt `enqueue()` (теперь file-backed job + `saveBase64`, но флага нет) — статус не блокирует повторный Generate.
  Дабл-тап = дубликаты. Fix: submitting flag + enabled + try/finally.
- M-3 [CONFIRMED, жив после нормализации] GenerationWorker.kt:127 финал безусловный `running=false, executingJobId=null`
  vs QueueRepository.kt:203 `stage()`: worker решил break (нет pending) → stage видит running=true + append → финал ставит false → stranded.
  enqueueImmediate KEEP маскирует, stage — нет. Fix: CAS-финал или повторный проход цикла.
- M-4 [CONFIRMED] InpaintScreen.kt:126 сырой aspect, 312-315 clamp 0.25–4, 317-322 Fit+matchParentSize letterbox,
  331-335 дроби от Box vs InpaintViewModel.kt:208-212 дроби от исходника → смещение на панорамах/портретах. Fix: letterbox-aware маппинг (Imp #11).
- M-5 [CONFIRMED] ImageAttachmentReader.kt:26-28 unbounded readBytes + double decode, AnalyzeImageSource.kt:17-23 то же;
  OutOfMemoryError не ловится. Fix: decodeStream bounds без readBytes + cap ~32MB + catch OOM.
- M-6 [CONFIRMED] InpaintViewModel.kt: missing mask silently accepted (проверки `strokes.isEmpty` в `enqueue()` нет;
  `mask=null` → payload без mask + `maskPath=null` уходят в очередь как обычный img2img, без предупреждения).
  Fix: `if strokes.isEmpty → "Draw a mask first."`.
- M-7 [PARTIAL] GenerateViewModel.kt unbounded numerics: bounded OK (345-351 parseSizeInput 64..2048, batch 1..4, count 1..8,
  steps 1..50, cfg 0..15; GenerationParamsInputs.kt:38-43), unbounded: 352 distilled + 366-371 HR scale/steps/denoise/cfg + 385-390.
  Fix: parseBounded для distilled и всех HR.
- M-8 [CONFIRMED] PngMetadata.kt:65-68,92-95 zTXt/iTXt: single `inflate(out=size*8+64)` без цикла/finished → молчаливая обрезка.
  Fix: while(!finished) в ByteArrayOutputStream (Imp #13).
- M-9 [CONFIRMED] ForgeApi.kt:93-105 @Volatile mutable CF creds + 155-157 перезапись перед create + 136-153 shared lazy clients →
  конкурентные запросы перекрывают creds. Fix: per-baseUrl+creds кэш с собственным интерцептором (Imp #10).
- M-10 [CONFIRMED] SettingsViewModel.kt:63-66 ConfigChanged пересоздает texts из коммиченного config, остальные 67-111 via buffered().
  Триггеры SettingsScreen.kt:109,117,172. Набрал IP, ткнул toggle — ввод потерян. Fix: `draft.value?.texts ?: from(...)` или commitDraft перед применением.
- M-11 [CONFIRMED] SettingsViewModel.kt:36-50 draft shadows external (`draftValue?.config ?: stored`) + 152-165 save полным снапшотом
  incl. llmKey/llmModel (Models.kt:23-24), писатель MagicpromptViewModel.kt:147-151. Сценарий затирания ключа. Fix: rebase на save (merge только редактируемых).
- M-12 [CONFIRMED] ForgeryPreferences.kt:137 `IS_CONFIGURED=true` вместо `config.isConfigured`; MagicpromptViewModel.kt:150
  побочно маркирует configured; GenerateViewModel.kt:218-219 boot завязан на флаг. Fix: `e[IS_CONFIGURED]=config.isConfigured`.
- M-13 [CONFIRMED] SettingsViewModel.kt:179 CHECK через generation client infinite read (ForgeApi.kt:167-168,135-141);
  job не хранится, Dismiss не канселит, CF не передаются. Fix: createControl + withTimeout(30s) + хранимый Job (Imp #10).
- M-14 [CONFIRMED] MainActivity.kt:10-17 POST_NOTIFICATIONS не запрашивается (манифест:11 объявлен, grep RequestPermission — только манифест+bugs).
  GenerationWorker.kt foreground (~348-376); targetSdk 35, API 33+ режет без рантайма. Fix: RequestPermission в onCreate (Imp #4).
- M-15 [CONFIRMED] PowerViewModel.kt:54-68 busy без try/finally → исключение/cancel = busy навсегда, кнопки PowerScreen.kt:74,79,92,95 мертвы.
  Fix: try/finally.
- M-16 [CONFIRMED] PowerScreen.kt:77-81 KILL! напрямую → PowerAction.Kill → powerOff (VM:42), без AlertDialog. Fix: confirmKill диалог (Imp #17).
- M-17 [CONFIRMED] ModulesViewModel.kt:102-108 toggle read-modify-write без мьютекса (Clear 76-78 то же). Дабл-тап = потеря.
  Fix: Mutex или атомарный edit в репозитории.
- M-18 [CONFIRMED] AnalyzeViewModel.kt:90-110 concurrent analyze(): новый launch без Job/cancel; побеждает последний завершившийся,
  не последний выбранный. Fix: хранимый Job + `if (uri.value==u)` guard.
- M-19 [CONFIRMED] QueueWatchdogWorker.kt + `scheduleWatchdog()` в QueueRepository без `setConstraints(CONNECTED)` (grep NetworkType — 0;
  watchdog после C-1 несет только host/origin, но офлайн-рестарт остался).
  Watchdog перезапускает офлайн → мгновенный fail. Fix: Constraints на worker+watchdog.
- M-20 [CONFIRMED] LoraScreen.kt:102 key by name (LoraItem Models.kt:142 — коллизии из разных папок), 103,119 fav по name,
  VM 102-104, ForgeryPreferences.kt:344-349 LORA_FAVS сет имен. Fix: key/fav по `path.ifBlank{name}` (Imp #6).
- M-21 [PARTIAL после C-2, 2026-09-24] LoraViewModel insert — FIXED (`Locale.US`); остался display LoraScreen.kt:146
  `"%.2f".format(detail.weight)` с default locale (только отображение, тег уже корректен).
  Fix: `Locale.US` + тест с GERMANY (Imp #6).
- M-22 [CONFIRMED] GalleryFiles.kt:27-33 insert row затем copy без `delete(uri)` на ошибке + без IS_PENDING → ghost 0-байт записи.
  Fix: try/catch delete + IS_PENDING 1→0 (Imp #18).
- M-23 [CONFIRMED] MagicpromptScreen.kt:75 внешний Column без scroll; 124-146 Card, 128-129 Text scroll+weight(fill=false),
  кнопки 131-143 ниже текста → длинный вывод уводит кнопки за экран. Fix: внешний verticalScroll или зафиксировать Card.
- M-24 [CONFIRMED] GenerationRepository.kt:79-80 @Volatile кэш + 236-238 early-return без GET после рестарта сервера (global сброшен,
  кейс 38-43 темные картинки/VAE leak); проверка current==want 245-249 не выполняется. Fix: всегда GET options + сравнение.

## Low

- L-1 [PARTIAL: Generate CONFIRMED, Inpaint переоценен] GenerateScreen.kt:280 `"%.1f".format(cfg)` без Locale → `7,5` в de/ru,
  parseCfgInput 620-623 `toDoubleOrNull` только точка → OK молча не делает ничего; InpaintScreen.kt:166-169 `%d` (без десятичной точки,
  не диалог 381-397) — эффект ~0. Fix: Locale.US + tolerant parse.
- L-2 [PARTIAL после C-1, 2026-09-24] Off-by-one FIXED: GenerateScreen показывает `job ${batchDone+1}/${batchTotal}`;
  QueueScreen `Running ${batchDone}/${batchTotal}` — семантика batchDone (0/3 при выполнении 1-й) осталась сознательно.
  Badge QueueBadgeViewModel.kt:18-23 по-прежнему включает исполняемую — консистентно с TO DO, вопрос семантики открыт.
- L-3 [CONFIRMED] Dead args GenerateRoute.id (api GenerateNavigation.kt:7; impl 17-26 игнорирует; NavHost 63,121,145 всегда без arg;
  navigateToGenerate(id) не вызывается) + GalleryRoute.id аналогично (InpaintRoute.id — живой, VM 95-97). Dead onBackClick в 6 таб-экранах
  (Generate/Inpaint/Queue/Gallery/Settings/Styles — принимается, не пробрасывается; NavHost передает `{}` vs popBackStack для саб-экранов).
  QueueService.kt:1-10 stub (реально GenerationWorker). Fix: удалить id/хелперы/onBackClick/QueueService.
- L-4 [CONFIRMED] GenerationWorker.kt `acquireWakeLock()` (`acquire(30min)`, ~389) — авто-релиз посреди длинного батча,
  релиз в finally 109-111 корректен; WifiLock 339-347 без таймаута (асимметрия). Fix: `acquire()` без timeout.
- L-5 [CONFIRMED] GenerationRepository.kt:175-176 `api.file("$baseUrl/file=$basePath.json")` сырым (пути `models\Lora\Foo Bar.safetensors`),
  приемник ForgeApi.kt:80-81 `@Url` — пробелы/бэкслэши → IllegalArgumentException. Fix: encode сегмент или `@Query`.
- L-6 [DONE в заходе C-4, 2026-09-24] Было read-then-write consume; стало `state.getAndUpdate { null }` (атомарный CAS;
  `getAndSet` отсутствует в coroutines 1.9.0 — использован эквивалент).
- L-7 [CONFIRMED x3] StylesScreen.kt:115 DEL без confirm → VM 68-70 сразу delete; SaveStyle 94-103 безусловный upsert (dao.upsert) +
  редактор не инвалидируется → DEL+ SAVE = resurrect; переименование невозможно (159 readOnly); import LoraStyleRepositories.kt:52-57
  молча перезаписывает. Fix: confirm + закрывать редактор + считать overwritten (Imp #17).
- L-8 [CONFIRMED] GenerationRepository.kt:111-128 слепой retry: 5 ретраев IOException на неидемпотентные txt2img/img2img (278-281);
  комментарий 106-110 «never reached» неверен для обрыва после отправки + ForgeApi.kt:133 retryOnConnectionFailure. Редко (readTimeout=0),
  но дублирует GPU-минуты. Fix: ретраить только connect-фазу.
- L-9 [PARTIAL: !! почти недостижим, leak CONFIRMED] InpaintViewModel.kt:123-127 двойное чтение + `!!` (все onAction на main, саспенда нет —
  NPE требует межпоточного вмешательства); leak: activeStroke:55 не чистится в ClearSource:104 / attachSource:185-188,
  InpaintScreen.kt:326-353 `pointerInput(Unit)` не перезапускается при смене uri. Fix: локальный val + зануление + ключ pointerInput(uri).
- L-10 [CONFIRMED] GenerateScreen.kt:292-307 flip читает коммиченный p (302-303) и перезаписывает инпуты (VM 485-491),
  отбрасывая набранное (коммит только на фокус/Commit/Generate); SizeSwap 492-495 правильно меняет сырые инпуты. Fix: flip сырых инпутов.
- L-11 [CONFIRMED] QueueScreen drag против stale-снапшота: completion mid-drag сжимает pendingJobs,
  to выходит за диапазон → moveJob молчаливый no-op/неверный слот. `executingJobId` теперь id-based из Snapshot
  (UiState + валидация по jobs/results), но перетаскиваемая карточка mid-gesture не пересчитывается.
  Fix: ребаз/отмена drag на изменении idSet либо MoveJob как afterJobId.
- L-12 [CONFIRMED] QueueViewModel.kt:64-69 Request/ConfirmClearCompleted идентичны, диалога для DONE нет (vs pending 44-60);
  QueueScreen.kt:190 vs 384 (AlertDialog 404-426 только pending); RequestClearCompleted мертв. Fix: DONE через Request + диалог либо удалить мертвый.
- L-13 [CONFIRMED x2] InpaintViewModel.kt:172-191 attach без поколения/мьютекса (A→B побеждает позже завершивший);
  рестор 95-97 content:// без persistable (GetContent 65-67, takePersistable 0, reader просто openInputStream) → SecurityException→null→"Could not read image".
  Fix: generation-id + OpenDocument+persist или копия в internal storage.
- L-14 [CONFIRMED] GenerateViewModel.kt:546-550 init guard неатомарен (два вызова из Uninitialized оба проходят до 551 fetchSdModels),
  silent=true вообще не ставит Initializing (boot 217-220 + Refresh 530 штормят). Fix: Mutex/initJob single-flight.
- L-15 [CONFIRMED x2] LoraViewModel.kt:123-135 openDetail без Job/request-id (A→B чей позже тот показан; CloseDetail 98 не отменяет);
  LoraScreen.kt:127-131 Dialog(onDismissRequest={}) + LinearProgressIndicator, Cancel нет → блок при висящей сети.
  Fix: detailJob?.cancel + requestedId guard + dismiss с cancel.
- L-16 [CONFIRMED] LoraViewModel.kt:126 `substringBeforeLast('.', item.name)`: path без расширения теряет директорию,
  `a.b/c` режется до `a`. Бьет в GenerationRepository.kt:176 file=. Fix: резать расширение только в basename.
- L-17 [CONFIRMED x2] MagicpromptViewModel.kt:147-152 persist берет кэш `busy.config` (83-94), до первой эмиссии silent return —
  набранные key/model теряются; 86-91,137 неявно пишет глобальный ConnectionRepository (Repositories.kt:56→Preferences 122-139 + force configured).
  Fix: `observe().first()` в persist + явный Save; LLM вынести из ConnectionConfig.
- L-18 [CONFIRMED] MagicpromptScreen.kt:99-105 ключ plaintext: нет visualTransformation/Password keyboard/toggle. Fix: PasswordVisualTransformation + eye-toggle.
- L-19 [CONFIRMED x2] ForgeryPreferences.kt:161-176 resetConnection не чистит LLM_KEY/MODEL (56-57), хотя save 135-136 их пишет;
  SettingsScreen.kt:234-237 RESET без confirm (VM 167-173 необратимо). Fix: чистить LLM + AlertDialog.
- L-20 [CONFIRMED x2] SettingsViewModel.kt:134-150 commitDraft verbatim без trim/валидации (Models.kt:27-29 режет только trailing /;
  пробелы → `http:// 192...`); parsePort 222-223 filter+take(5)+coerce молча оставляет старый/клампит без фидбека.
  Fix: trim+inline error; порты — surface error/блок SAVE.
- L-21 [CONFIRMED] SettingsViewModel.kt:152-165 save vs 167-173 reset без Mutex/single-flight; UI 234-243 RESET не блокируется isSaving;
  два SAVE = две корутины со stale-снимком; reset→опоздавший save = resurrect. Fix: общий Mutex + перечитывать draft внутри + дизейбл RESET.
- L-22 [CONFIRMED, номера уточнены: onAction 40-52, run 54-68] Power double-tap (каждый тап отдельная корутина, busy только после рекомпозиции;
  чужой false гасит чужой in-flight + нет finally M-15); PowerScreen.kt:69 hardcoded `:5000`; onBackClick 45-57 принимается, в PowerContent 60-64 нет.
  Fix: guard/Mutex+finally; порт из конфига; убрать или прокинуть onBackClick.
- L-23 [CONFIRMED] ComfyViewModel.kt:13-27,35-37 TODO + fake `listOf("Item 1","Item 2")`; NavHost без import/comfyScreen (граф 121-152),
  хотя settings.gradle 56-57 + app deps 80 тянут вес; `ComfyRoute(id)` тоже мертв. Fix: допилить+зарегистрировать либо удалить модули.
- L-24 [PARTIAL после C-4, 2026-09-24] Навигация FIXED: analyze→generate идет с popUpTo+singleTop+restoreState после
  завершения copyToMode — стек не плодится. Осталось: мертвые поля AnalyzeUiState.kt metadata/noMetadata (писатель всегда null, читателей 0).
  Fix: удалить поля.
- L-25 [CONFIRMED] ModulesScreen.kt:150-153 REFRESH (+117 RETRY) без `enabled=!listLoading` (флаг UiState:16, индикатор 101-103);
  VM 82-100 без guard — параллельные fetch + конкурирующий prune 90-93. Fix: enabled + single-flight.
- L-26 [CONFIRMED] LoraViewModel.kt:32-34 `runCatching{valueOf}?:SDXL` молча (тот же паттерн ModulesVM 34-36, MagicpromptVM 33, StylesVM 30,
  Preferences 208). Fix: parseModeOrNull + лог/Error-state.
- L-27 [CONFIRMED, Low] PromptDraftRepository.kt:48 plain var flushJob + 79-85 cancel+launch неатомарны (set/append suspend из разных корутин;
  flush 87-95/94 смягчает — двойные записи, не потеря). Fix: Mutex/actor.
- L-28 [CONFIRMED x2] ForgeApi.kt:116 plain debugLogging читается раз в lazy 123-128 (App:28-30 выставляет до create, но видимость не гарантирована;
  зеркало LlmApi.kt:56,58-70); JSON helpers бросают: ForgeApi 87-88 images/jsonArray/jsonPrimitive, 91 progress jsonPrimitive;
  LlmApi 36-48 jsonObject/jsonArray/jsonPrimitive. Комментарий 26-30 описывает ровно то, что делают. Fix: @Volatile+чтение per-request; safe-касты.
- L-29 [CONFIRMED x2, дубликат L-3 для id] GalleryScreen.kt:91 ALL → VM 90-96 выбирает `current.items` = одна страница (22-24,53-55, PAGE_SIZE=50);
  GalleryRoute.id (api:7,14-16) мертв как в L-3 (impl 13-18 не читает, NavHost 66,136-139 всегда без id).
  Fix: переименовать в PAGE либо настоящий select-all; удалить id.

## Improvements (18)

1. [APPLICABLE] safeApiCall единый — нет (grep только bugs.md); разрозненные catch глотают Cancel (C-3; MagicPowerRepositories 47,74; SettingsVM 182).
2. [DONE 2026-09-24] KEY_JOBS удален полностью: `launchWorker(host, origin)` + watchdog только host/origin;
  список живет в Room-таблицах, воркер читает next-pending построчно. C-1 тест (20 задач) в QueueRepositoryTest.
3. [DONE] Сериализация мутаций очереди — QueueTx (db.withTransaction) + tx.run везде (stage 216, start 268, enqueue 314, remove 393, move 429,
   cancel 464, clearCompleted 471, clearPending 506; worker record 212-234, persist 250-252; id-based withResult 54-71).
4. [APPLICABLE] Настоящий cancel + actions + permission — interrupt 0, нотификация 298-314 без addAction/ongoing, permission в манифесте:11 без запроса (MainActivity 10-16).
5. [APPLICABLE] Удалять файлы + sweep — delete=DAO-only (Repositories 37-38), grep File.delete/orphan 0, writer native_queue (worker 256-264).
6. [PARTIAL 2026-09-24] LoRA пакет: positive (DONE) + Locale.US в insert (DONE, M-21 VM-часть); осталось: key/fav by path
  (сейчас key/fav по name) + Locale.US в display LoraScreen:146.
7. [PARTIAL] Коммитить модель — Generate коммитит сразу (VM:462, Screen:218, buildJob 603-612 из rest 380-404) OK; Inpaint расходится (Screen:208 vs VM:164,264,280).
8. [APPLICABLE] Submit guards + snackbar — нет isSubmitting (GEN 616-664, INP 239-295), кнопки всегда enabled (Screen 502-520), SnackbarHost только GalleryDetail 108.
9. [APPLICABLE] UiPrefs/LLM — секция wired но мертва (см. H-11), LLM в ConnectionConfig (Models 23-24, prefs 103-139), reset LLM не трогает (161-176).
10. [PARTIAL] CHECK/interceptors/кэш — разделение generation/control сделано (ForgeApi 136-153), не сделано: singleton-мутация (94-105,155-157, M-9),
    новый Retrofit per call без кэша (155-165), CHECK не по ТЗ (SettingsVM 175-186), debugLogging разовый (116-128).
11. [APPLICABLE] Маска — letterbox-баг (M-4), жесты ручные 326-353 (не awaitEachGesture), activeStroke leak + in-memory only (L-9).
12. [APPLICABLE] Room — exportSchema=false (DB:119), без индекса createdAt (Entity 15-23), paging limit/offset 55-56 (не Paging3),
    importFromServer цикл без транзакции (LoraStyleRepositories 52-60).
13. [APPLICABLE] PNG inflate + тесты — single inflate (56-72,88-95; M-8), тесты только tEXt/parseA1111 (PngMetadataTest 46-144), zTXt/iTXt нет.
14. [APPLICABLE] Тесты — 24 файла, есть Queue/GenerationRepo/PngMetadata, нет GenerationWorker/Watchdog; parseSeed/parseSize (Inputs 38-54) непокрыты
    (TextFieldFilterTest только filterDigits); seed round-trip нет.
15. [PARTIAL 2026-09-24] Навигация: analyze→generate теперь popUpTo+singleTop+restoreState (DONE в C-4);
  осталось: единые tab options для остальных cross-feature + убрать мёртвые route-аргументы/onBackClick
  (GenerateRoute(id), GalleryRoute(id) + хелперы; NavHost onBackClick={} для 5 табов).
16. [APPLICABLE] Мертвый код — ничего не удалено: QueueService (+манифест:29), ForgeryDispatchers (Common 8-12), PrefsKeys MODEL/PROMPT/NEG_INP
    (45,49,53; используются только 3 мода 180-190), Result.Loading (~20 ветвлений), observeDetail default (Repositories:17), comfy/*.
17. [PARTIAL] Подтверждения — нет: KILL (Power 77-82), RESET (Settings 234-237), DEL (Styles:115), DONE без диалога (Queue 380-385 vs диалог 404-426 только pending);
    есть: Gallery 156-172 + VM 104-142, Generate unload 484-499.
18. [APPLICABLE] Галерея — без IS_PENDING (Files 21-30; M-22), хардкод Forgery_*.png/image/png (22-24, share 45), AsyncImage без placeholder/error
    (Gallery 108-111, Inpaint 317-322), empty state нет (пустой грид + Page 1/1 vs Queue:387, Modules:123).
