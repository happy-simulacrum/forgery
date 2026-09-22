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
    @Binds abstract fun bindMagic(impl: DefaultMagicPromptRepository): MagicPromptRepository
    @Binds abstract fun bindPower(impl: DefaultPowerRepository): PowerRepository
    @Binds abstract fun bindHr(impl: DefaultHrSettingsRepository): HrSettingsRepository
}
