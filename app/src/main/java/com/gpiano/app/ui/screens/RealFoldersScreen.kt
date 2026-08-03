package com.gpiano.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gpiano.app.data.Folder

@Composable
fun RealFoldersScreen(
    contentPadding: PaddingValues,
    folders: List<Folder>,
    folderError: String?,
    onNameChange: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
        Text("文件夹", modifier = Modifier.padding(top = 24.dp, bottom = 16.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; onNameChange() },
                modifier = Modifier.weight(1f),
                label = { Text("新建文件夹") },
                singleLine = true,
                isError = folderError != null,
            )
            Button(
                onClick = { onCreate(name); name = "" },
                enabled = name.isNotBlank(),
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("创建") }
        }
        folderError?.let { Text(it, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
        if (folders.isEmpty()) Text("还没有文件夹。", modifier = Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        folders.forEach { folder ->
            Text(folder.name, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}
