package com.orientlock.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orientlock.ui.components.GradientBackground

/** 把 ViewModel 与主题接起来，供 MainActivity 直接调用 */
@Composable
fun AppRoot() {
    val context = LocalContext.current
    val viewModel: MainViewModel = viewModel(
        factory = MainViewModel.factory(
            context.applicationContext as android.app.Application
        )
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    GradientBackground {
        MainScreen(state = state, viewModel = viewModel)
    }
}
