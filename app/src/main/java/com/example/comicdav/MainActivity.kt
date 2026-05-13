package com.example.comicdav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.comicdav.feature.webdav.WebDavAccountScreen
import com.example.comicdav.feature.webdav.WebDavBrowserScreen
import com.example.comicdav.feature.webdav.WebDavViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ComicDavApp() }
    }
}

@Composable
fun ComicDavApp() {
    val viewModel: WebDavViewModel = viewModel()
    val uiState = viewModel.uiState
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (uiState.status == "Connected") {
                WebDavBrowserScreen(
                    uiState = uiState,
                    onItemClick = { item ->
                        if (item.isDirectory) {
                            viewModel.openDirectory(item)
                        } else {
                            viewModel.selectItem(item)
                        }
                    },
                    onProbeTail = viewModel::probeTail64KiB,
                )
            } else {
                WebDavAccountScreen(
                    uiState = uiState,
                    onBaseUrlChange = viewModel::updateBaseUrl,
                    onUsernameChange = viewModel::updateUsername,
                    onPasswordChange = viewModel::updatePassword,
                    onTestConnection = viewModel::testConnection,
                )
            }
        }
    }
}
