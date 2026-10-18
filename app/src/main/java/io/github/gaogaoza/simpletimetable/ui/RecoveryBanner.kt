package io.github.gaogaoza.simpletimetable.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gaogaoza.simpletimetable.TimetableApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun RecoveryBanner(app: TimetableApplication, error: String) {
    var result by remember { mutableStateOf("") }; val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            result = try {
                withContext(Dispatchers.IO) { val bytes = app.repository.originalBytes(); requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(bytes) } }
                "原始文件已导出，请妥善保存以供恢复排查。"
            } catch (e: Exception) { "导出失败：${e.message}" }
        }
    }
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(error, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { export.launch("SimpleTimetable-original.json") }) { Text("导出保留的原始文件") }
            if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.bodySmall)
        }
    }
}
