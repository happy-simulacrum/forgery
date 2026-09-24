Critical:
- C-1: QueueRepository.kt:376-386 + QueueWatchdogWorker.kt:37-43 — jobsJson в WorkManager inputData > 10KB
- C-2: LoraViewModel.kt:143 — trigger words → negative prompt
- C-3: GenerationRepository.kt:98-128 etc — catch(Exception) swallows CancellationException
- C-4: AnalyzeViewModel.kt:118-149 — handoff race

High:
- H-1: GenerationWorker.kt:194-211 record() lost-update race
- H-2: QueueRepository.kt:310-313 cancel without /interrupt
- H-3: GenerateScreen.kt:544 / InpaintScreen.kt:208 displayed model ≠ executed
- H-4: SettingsViewModel.kt:179 CHECK without CF creds + wipes global CF
- H-5: Gallery deletes never delete files (GalleryViewModel.kt:122-135, GenerationWorker.kt:234-265)
- H-6: GalleryViewModel.kt:26-34 page not clamped → stranded
- H-7: AnalyzeViewModel.kt:124-136 HR settings clobbered
- H-8: QueueRepository.kt:117-124,404-413 uncaught SerializationException crash loop
- H-9: GenerationWorker.kt:131-149 poller child cancels worker
- H-10: GenerationWorker.kt:98-100 catch-all returns success()
- H-11: SettingsScreen.kt:91-100,193-208 dead UiPrefs (dark theme + tabs)
- H-12: PromptDraftRepository.kt:79-95 uncaught DataStore exception crash

Medium:
- M-1: GenerationRepository.kt:213,252-254 + ForgeApi.kt:148-153 — reload POST via 30s control client
- M-2: No double-submit protection (GenerateViewModel.kt:616-664, InpaintViewModel.kt:239-295)
- M-3: GenerationWorker.kt:78-92 worker-finish vs stage() race
- M-4: InpaintScreen.kt:126,315,331-335 mask misalignment extreme aspect
- M-5: ImageAttachmentReader.kt:26-35 + AnalyzeImageSource.kt:17-23 readBytes OOM
- M-6: InpaintViewModel.kt:193-194 missing mask silently accepted
- M-7: GenerateViewModel.kt:352-372 unbounded numeric inputs
- M-8: PngMetadata.kt:65-68,92-95 zTXt truncation
- M-9: ForgeApi.kt:94-105 shared mutable CF credentials race
- M-10: SettingsViewModel.kt:63-66 ConfigChanged discards uncommitted text
- M-11: SettingsViewModel.kt:36-50,152-165 draft shadows external writes (llmKey clobber)
- M-12: ForgeryPreferences.kt:137 saveConnection force isConfigured=true
- M-13: SettingsViewModel.kt:179 CHECK infinite read timeout hang
- M-14: MainActivity.kt — POST_NOTIFICATIONS never requested (API 33+)
- M-15: PowerViewModel.kt:54-68 busy not reset in finally
- M-16: PowerScreen.kt:77-81 KILL no confirmation
- M-17: ModulesViewModel.kt:102-108 toggle race
- M-18: AnalyzeViewModel.kt:90-110 concurrent analyze() race
- M-19: QueueWatchdogWorker.kt:36-45 no network constraint
- M-20: LoraScreen.kt:102 items key by name collision; favorites by name
- M-21: LoraViewModel.kt:141 locale weight formatting
- M-22: GalleryFiles.kt:27-33 ghost MediaStore entries
- M-23: MagicpromptScreen.kt:75,124-146 long output unreachable buttons
- M-24: GenerationRepository.kt:236-238 module-sync cache survives server restart

That's 24 medium. 

Low:
- L-1: GenerateScreen.kt:280 / InpaintScreen.kt:167 locale number formatting in dialogs
- L-2: QueueScreen.kt:153 "Running 0/3" off-by-one; QueueBadgeViewModel.kt:22 badge counts executing job
- L-3: Dead route args GenerateRoute.id, GalleryRoute.id; dead onBackClick in 6 screens; QueueService stub
- L-4: GenerationWorker.kt:311 WakeLock 30min cap
- L-5: GenerationRepository.kt:176 unencoded sidecar URL
- L-6: AnalyzeHandoffRepository.kt:21-25 non-atomic consume
- L-7: StylesScreen.kt:115 delete no confirmation; StylesViewModel.kt:64-70,94-103 SAVE resurrects deleted style; import overwrites silently
- L-8: GenerationRepository.kt:111-128 blind retry duplicates generations
- L-9: InpaintViewModel.kt:124-125 !! on StateFlow re-read; activeStroke leak across image changes (:55, 185-188, InpaintScreen.kt:326-353)
- L-10: GenerateScreen.kt:305 grid flip clobbers uncommitted sizes
- L-11: QueueScreen.kt:233-266 drag-index divergence on completion mid-drag
- L-12: QueueViewModel.kt:64-66 + QueueScreen.kt:384 clear-completed inconsistency
- L-13: InpaintViewModel.kt:172-191 attach-pick race; content:// URI restore without persistable permission (:95-97)
- L-14: GenerateViewModel.kt:548 concurrent engine init guard gap
- L-15: LoraViewModel.kt:123-135 racy openDetail; LoraScreen.kt:127-131 modal uncancellable dialog
- L-16: LoraViewModel.kt:126 wrong sidecar fallback when no extension
- L-17: MagicpromptViewModel.kt:147-152 typed key/model not persisted if config null; :86-91 global settings mutated
- L-18: MagicpromptScreen.kt:99-105 API key plaintext
- L-19: ForgeryPreferences.kt:161-176 reset() asymmetric (LLM keys remain) + no confirm (SettingsScreen.kt:234-237)
- L-20: SettingsViewModel.kt:139,144-145 baseIp/URL no trim/validation; parsePort silently drops
- L-21: SettingsViewModel.kt:152-173 save/reset race
- L-22: PowerViewModel.kt:40-56 double-tap race; PowerScreen.kt:69 hardcoded port; onBackClick dead (PowerScreen.kt:45-57)
- L-23: ComfyViewModel.kt:19-27 stub feature, fake data, unreachable
- L-24: AnalyzeUiState.kt:10,14 dead state fields; AnalyzeScreen.kt:140-149 navigation stacks Generate entries
- L-25: ModulesScreen.kt:150-153 REFRESH not disabled while loading
- L-26: LoraViewModel.kt:32-34 etc invalid mode arg silently falls back to SDXL
- L-27: PromptDraftRepository.kt:48,79-85 unsynchronized flushJob race
- L-28: ForgeApi.kt:116,123-128 debugLogging read once non-volatile; LlmApi.kt:36-48/ForgeApi.kt:87-88 JSON helpers throw on wrong shapes
- L-29: GalleryScreen.kt:91 ALL selects only current page; GalleryNavigation.kt:7,14 dead id arg (already in L-3?) — GalleryRoute.id was in L-3. Keep gallery ALL separate.

Improvements (18):
1. safeApiCall единый
2. Убрать KEY_JOBS из inputData (закрывает C-1)
3. Сериализация мутаций очереди (транзакции/мьютекс)
4. Настоящий cancel + notification action + runtime permission
5. Удалять файлы с rows + orphan sweep
6. LoRA пакет (positive, Locale.US, key by path)
7. Коммитить отображаемую модель
8. Submit guards + snackbar
9. UiPrefs до конца или удалить секцию; LLM prefs в отдельную запись
10. Settings CHECK via createControl + CF + timeout; per-client interceptors, кэш Retrofit per baseUrl
11. Маска letterbox-aware + awaitEachGesture + persist штрихов
12. Room: exportSchema, индекс createdAt, Paging 3, транзакция importFromServer
13. PNG inflate loop + тесты zTXt/iTXt
14. Тесты: GenerationWorker, watchdog, parseSeedInput/parseSizeInput, seed round-trip
15. Навигация: единые tab options для cross-feature; убрать мёртвые route-аргументы/onBackClick
16. Мёртвый код: QueueService, ForgeryDispatchers, PrefsKeys.*_INP, Result.Loading, observeDetail default, Comfy
17. UX-подтверждения: KILL/RESET/DEL/CLEAR DONE
18. Галерея: IS_PENDING, placeholder/error, contentType, empty state
