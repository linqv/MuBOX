package com.example.comicdav.feature.webdav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.example.comicdav.ui.MuBoxHeaderBar
import com.example.comicdav.ui.MuBoxMetrics
import com.example.comicdav.ui.muBoxAppBackground
import com.example.comicdav.ui.muBoxGradientBorder
import com.example.comicdav.ui.rememberMuBoxColors

// §8.6 视觉统一：紧凑标题栏 + 返回按钮，表单置于 colors.panel 圆角面板（1dp 边框），字段与校验逻辑不变。
@Composable
fun WebDavAccountScreen(
    uiState: WebDavUiState,
    onDisplayNameChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onRootPathChange: (String) -> Unit,
    onUseHttpsChange: (Boolean) -> Unit,
    onAnonymousAccessChange: (Boolean) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onBackToLibrary: () -> Unit,
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = rememberMuBoxColors()
    Column(
        modifier = modifier
            .fillMaxSize()
            .muBoxAppBackground(colors),
    ) {
        MuBoxHeaderBar(
            title = "添加网络连接",
            navigationIcon = {
                IconButton(onClick = onBackToLibrary) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val panelShape = RoundedCornerShape(MuBoxMetrics.RadiusXlDp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 420.dp)
                    .muBoxGradientBorder(colors = colors, shape = panelShape),
                shape = panelShape,
                color = colors.panel,
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.displayName,
                            onValueChange = onDisplayNameChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("名称") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = "WebDAV",
                            onValueChange = {},
                            modifier = Modifier.weight(1f),
                            label = { Text("协议") },
                            readOnly = true,
                            singleLine = true,
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDropDown,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
                    OutlinedTextField(
                        value = uiState.host,
                        onValueChange = onHostChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("主机/IP地址") },
                        singleLine = true,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.port,
                            onValueChange = onPortChange,
                            modifier = Modifier.weight(0.42f),
                            label = { Text("端口") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = uiState.rootPath,
                            onValueChange = onRootPathChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("路径") },
                            singleLine = true,
                        )
                    }
                    CheckRow(
                        checked = uiState.anonymousAccess,
                        onCheckedChange = onAnonymousAccessChange,
                        label = "匿名/访客访问",
                    )
                    CheckRow(
                        checked = uiState.useHttps,
                        onCheckedChange = onUseHttpsChange,
                        label = "使用 HTTPS（安全连接）",
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = uiState.username,
                            onValueChange = onUsernameChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("用户名") },
                            enabled = !uiState.anonymousAccess,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = uiState.password,
                            onValueChange = onPasswordChange,
                            modifier = Modifier.weight(1f),
                            label = { Text("密码") },
                            enabled = !uiState.anonymousAccess,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                    if (!message.isNullOrBlank()) {
                        Text(
                            text = message,
                            color = colors.errorText,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (uiState.status != WEB_DAV_STATUS_NOT_CONNECTED) {
                        Text(text = uiState.status, color = colors.text)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onBackToLibrary,
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text("取消")
                        }
                        Button(
                            onClick = onTestConnection,
                            enabled = !uiState.isLoading && uiState.host.isNotBlank(),
                            modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    val colors = rememberMuBoxColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = colors.muted,
        )
    }
}
