package com.example.comicdav.feature.webdav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun WebDavAccountScreen(
    uiState: WebDavUiState,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onOpenLocal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "ComicDav", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = uiState.baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("WebDAV URL") },
            singleLine = true,
        )
        OutlinedTextField(
            value = uiState.username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Username") },
            singleLine = true,
        )
        OutlinedTextField(
            value = uiState.password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(onClick = onTestConnection, enabled = !uiState.isLoading && uiState.baseUrl.isNotBlank()) {
            Text("Test")
        }
        Button(onClick = onOpenLocal, enabled = !uiState.isLoading) {
            Text("Open Local CBZ")
        }
        Text(text = uiState.status)
    }
}
