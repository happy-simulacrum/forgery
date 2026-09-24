package com.forgery.app.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.forgery.app.core.datastore.ForgeryPreferencesDataSource
import com.forgery.app.core.model.ModelLastUsed
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelParamsRepositoryTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo(): DefaultModelParamsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { tmp.newFile("prefs.preferences_pb") },
        )
        return DefaultModelParamsRepository(ForgeryPreferencesDataSource(dataStore))
    }

    @Test
    fun `null when nothing saved`() = runTest {
        assertNull(repo().observeForModel("model.safetensors").first())
    }

    @Test
    fun `save and load round-trip`() = runTest {
        val repo = repo()
        val params = ModelLastUsed(steps = 30, sampler = "DPM++ 2M", additionalModules = listOf("ae.safetensors"))
        repo.saveForModel("Model.safetensors", params)
        assertEquals(params, repo.observeForModel("Model.safetensors").first())
    }

    @Test
    fun `key is normalized`() = runTest {
        val repo = repo()
        val params = ModelLastUsed()
        repo.saveForModel("waiIllustriousSDXL_v170 [abc123]", params)
        assertEquals(params, repo.observeForModel("waiillustrioussdxl_v170 [zzz]").first())
    }

    @Test
    fun `blank title observes null`() = runTest {
        val repo = repo()
        assertNull(repo.observeForModel("").first())
        assertNull(repo.observeForModel("   ").first())
    }

    @Test
    fun `blank title save is no-op`() = runTest {
        val repo = repo()
        repo.saveForModel("", ModelLastUsed(steps = 30))
        repo.saveForModel("   ", ModelLastUsed(steps = 30))
        assertNull(repo.observeForModel("other.safetensors").first())
    }

    @Test
    fun `saves for two models merge`() = runTest {
        val repo = repo()
        val a = ModelLastUsed(steps = 30, sampler = "Euler")
        val b = ModelLastUsed(steps = 12, sampler = "DDIM", enableHr = true)
        repo.saveForModel("a.safetensors", a)
        repo.saveForModel("b.safetensors", b)
        assertEquals(a, repo.observeForModel("a.safetensors").first())
        assertEquals(b, repo.observeForModel("b.safetensors").first())
        // Overwrite of one model keeps the other.
        val a2 = a.copy(steps = 40)
        repo.saveForModel("a.safetensors", a2)
        assertEquals(a2, repo.observeForModel("a.safetensors").first())
        assertEquals(b, repo.observeForModel("b.safetensors").first())
    }

    @Test
    fun `corrupt json pref observes null`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { tmp.newFile("corrupt.preferences_pb") },
        )
        dataStore.edit { it[stringPreferencesKey("bojro_model_params")] = "{broken" }
        val repo = DefaultModelParamsRepository(ForgeryPreferencesDataSource(dataStore))
        assertNull(repo.observeForModel("a.safetensors").first())
    }
}
