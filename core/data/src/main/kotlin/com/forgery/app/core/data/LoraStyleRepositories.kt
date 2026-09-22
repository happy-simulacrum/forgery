package com.forgery.app.core.data

import com.forgery.app.core.common.Result
import com.forgery.app.core.database.StyleDao
import com.forgery.app.core.database.StyleEntity
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.StylePreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface LoraRepository {
    fun observeFavorites(): Flow<Set<String>>
    suspend fun setFavorite(name: String, favorite: Boolean)
}

@Singleton
class DefaultLoraRepository @Inject constructor(
    private val prefs: ForgeryPreferencesDataSource,
) : LoraRepository {
    override fun observeFavorites(): Flow<Set<String>> = prefs.observeLoraFavorites()
    override suspend fun setFavorite(name: String, favorite: Boolean) =
        prefs.setLoraFavorite(name, favorite)
}

interface StyleRepository {
    fun observeAll(): Flow<List<StylePreset>>
    suspend fun upsert(preset: StylePreset)
    suspend fun delete(name: String)
    suspend fun importFromServer(): Result<Int>
}

@Singleton
class DefaultStyleRepository @Inject constructor(
    private val dao: StyleDao,
    private val generationRepository: GenerationRepository,
) : StyleRepository {
    override fun observeAll(): Flow<List<StylePreset>> =
        dao.observeAll().map { list ->
            list.map { StylePreset(it.name, it.prompt, it.negativePrompt) }
        }

    override suspend fun upsert(preset: StylePreset) {
        dao.upsert(StyleEntity(preset.name, preset.prompt, preset.negativePrompt))
    }

    override suspend fun delete(name: String) {
        dao.delete(name)
    }

    override suspend fun importFromServer(): Result<Int> =
        when (val r = generationRepository.fetchPromptStyles()) {
            is Result.Success -> {
                r.data.forEach { upsert(it) }
                Result.Success(r.data.size)
            }
            is Result.Error -> Result.Error(r.message, r.cause)
            is Result.Loading -> Result.Loading
        }
}
