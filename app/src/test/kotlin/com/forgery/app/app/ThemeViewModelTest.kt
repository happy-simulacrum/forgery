package com.forgery.app.app

import com.forgery.app.core.data.ConnectionRepository
import com.forgery.app.core.model.ConnectionConfig
import com.forgery.app.core.model.UiPrefs
import com.forgery.app.core.testing.TestDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeThemeConnectionRepository : ConnectionRepository {
    private val config = MutableStateFlow(ConnectionConfig())
    private val ui = MutableStateFlow(UiPrefs())

    override fun observe(): Flow<ConnectionConfig> = config.asStateFlow()
    override suspend fun save(c: ConnectionConfig) {
        config.value = c
    }
    override fun observeUiPrefs(): Flow<UiPrefs> = ui.asStateFlow()
    override suspend fun saveUiPrefs(prefs: UiPrefs) {
        ui.value = prefs
    }
    override suspend fun reset() {
        config.value = ConnectionConfig()
    }

    fun emit(prefs: UiPrefs) {
        ui.value = prefs
    }
}

class ThemeViewModelTest {

    @get:Rule
    val dispatcherRule = TestDispatcherRule()

    @Test
    fun `defaults to dark theme`() = runTest {
        val vm = ThemeViewModel(FakeThemeConnectionRepository())
        assertTrue(vm.uiPrefs.first { it.darkTheme }.darkTheme)
    }

    @Test
    fun `emits stored light theme`() = runTest {
        val repo = FakeThemeConnectionRepository()
        repo.emit(UiPrefs(darkTheme = false))
        val vm = ThemeViewModel(repo)
        assertFalse(vm.uiPrefs.first { !it.darkTheme }.darkTheme)
    }

    @Test
    fun `follows uiPrefs updates`() = runTest {
        val repo = FakeThemeConnectionRepository()
        val vm = ThemeViewModel(repo)
        assertEquals(true, vm.uiPrefs.first().darkTheme)
        repo.emit(UiPrefs(darkTheme = false))
        assertEquals(false, vm.uiPrefs.first { !it.darkTheme }.darkTheme)
        repo.emit(UiPrefs(darkTheme = true))
        assertEquals(true, vm.uiPrefs.first { it.darkTheme }.darkTheme)
    }
}
