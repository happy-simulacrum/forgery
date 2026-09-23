package com.forgery.app.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.DefaultField
import com.forgery.app.core.model.GenDefaults
import com.forgery.app.core.model.GenerationMode
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultsRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): DefaultDefaultsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { tmp.newFile("prefs.preferences_pb") },
        )
        return DefaultDefaultsRepository(ForgeryPreferencesDataSource(dataStore))
    }

    @Test
    fun `empty by default`() = runTest {
        val repo = repo()
        GenerationMode.entries.forEach { mode ->
            assertEquals(GenDefaults(), repo.observeDefaults(mode).first())
        }
    }

    @Test
    fun `round-trip saves and observes all fields`() = runTest {
        val repo = repo()
        val mode = GenerationMode.SDXL
        repo.saveDefault(mode, DefaultField.PROMPT, "a cat")
        repo.saveDefault(mode, DefaultField.NEGATIVE, "blurry")
        repo.saveDefault(mode, DefaultField.MODEL, "model.safetensors")
        repo.saveDefault(mode, DefaultField.SAMPLER, "Euler")
        repo.saveDefault(mode, DefaultField.SCHEDULER, "Normal")
        repo.saveDefault(mode, DefaultField.UPSCALER, "Latent")
        assertEquals(
            GenDefaults(
                prompt = "a cat",
                negativePrompt = "blurry",
                modelTitle = "model.safetensors",
                sampler = "Euler",
                scheduler = "Normal",
                upscaler = "Latent",
            ),
            repo.observeDefaults(mode).first(),
        )
        // Overwrite of a single field keeps the rest.
        repo.saveDefault(mode, DefaultField.PROMPT, "a dog")
        assertEquals("a dog", repo.observeDefaults(mode).first().prompt)
        assertEquals("Euler", repo.observeDefaults(mode).first().sampler)
    }

    @Test
    fun `modes are isolated`() = runTest {
        val repo = repo()
        repo.saveDefault(GenerationMode.SDXL, DefaultField.PROMPT, "xl prompt")
        repo.saveDefault(GenerationMode.FLUX, DefaultField.PROMPT, "flux prompt")
        repo.saveDefault(GenerationMode.FLUX, DefaultField.SAMPLER, "FluxSampler")
        assertEquals("xl prompt", repo.observeDefaults(GenerationMode.SDXL).first().prompt)
        assertEquals("", repo.observeDefaults(GenerationMode.SDXL).first().sampler)
        assertEquals("flux prompt", repo.observeDefaults(GenerationMode.FLUX).first().prompt)
        assertEquals("FluxSampler", repo.observeDefaults(GenerationMode.FLUX).first().sampler)
        assertEquals(GenDefaults(), repo.observeDefaults(GenerationMode.QWEN).first())
    }
}
