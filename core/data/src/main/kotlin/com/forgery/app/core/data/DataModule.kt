package com.forgery.app.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun bindHistory(impl: OfflineFirstHistoryRepository): HistoryRepository
    @Binds abstract fun bindConnection(impl: DefaultConnectionRepository): ConnectionRepository
    @Binds abstract fun bindGeneration(impl: DefaultGenerationRepository): GenerationRepository
    @Binds abstract fun bindQueue(impl: DefaultQueueRepository): QueueRepository
    @Binds abstract fun bindDrafts(impl: DefaultPromptDraftRepository): PromptDraftRepository
    @Binds abstract fun bindLora(impl: DefaultLoraRepository): LoraRepository
    @Binds abstract fun bindStyles(impl: DefaultStyleRepository): StyleRepository
    @Binds abstract fun bindHr(impl: DefaultHrSettingsRepository): HrSettingsRepository
    @Binds abstract fun bindDefaults(impl: DefaultDefaultsRepository): DefaultsRepository
    @Binds abstract fun bindModules(impl: DefaultModulesSelectionRepository): ModulesSelectionRepository
    @Binds abstract fun bindQueueInputs(impl: FileQueueInputs): QueueInputs
}
