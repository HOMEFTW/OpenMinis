package com.openminis.app.config

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import com.openminis.app.config.fields.PrefsBoolField
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.util.MemorySharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserConfigTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun changingTheConfigUpdatesAnOpenBrowserAndClearingItRemovesTheListener() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val prefs = MemorySharedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        val store = ViewModelStore()
        try {
            val browser = FileBrowserViewModel(rootPath = temporary.root, appContext = context)
            store.put("files", browser)
            val field = PrefsBoolField("files.showHidden", "Hidden files", "", prefs,
                FileBrowserViewModel.PREF_KEY_SHOW_HIDDEN, false)
            field.write(ConfigValue.Bool(true))
            runCurrent()
            assertTrue(browser.uiState.value.showHidden)
            browser.setShowHidden(false)
            assertFalse((field.read() as ConfigValue.Bool).value)
            store.clear()
            field.write(ConfigValue.Bool(true))
            runCurrent()
            assertFalse(browser.uiState.value.showHidden)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
