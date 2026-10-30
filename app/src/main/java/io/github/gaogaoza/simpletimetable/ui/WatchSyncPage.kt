package io.github.gaogaoza.simpletimetable.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.AppData
import io.github.gaogaoza.simpletimetable.sync.WatchClient

@Composable fun WatchSyncPage(app: TimetableApplication, db: AppData) {
    val context = LocalContext.current
    val initialized = remember { runCatching { WatchClient(context.applicationContext, app.repository) } }
    val client = initialized.getOrNull()
    if (client == null) { Text("同步存储读取失败，原文件已保留：${initialized.exceptionOrNull()?.message}", Modifier.padding(16.dp)); return }
    val state by client.view.collectAsState()
    var code by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var refreshCache by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> permissionMessage = if (result.values.all { it }) "已授权，可以扫描" else "授权未完成，可在系统设置中授权后重试" }
    DisposableEffect(client) { onDispose { client.close() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("BlueOS 手表同步", style = MaterialTheme.typography.titleLarge) }
        item { Text("先在手表开启同步，选择附近的手表并输入六位码。连接码用于防误连，此测试版不声明加密传输。设备校准和显示设置各自保留。") }
        item { Text(state.status); if (state.progress > 0) LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth()) }
        item { if (permissionMessage.isNotEmpty()) Text(permissionMessage); Button(onClick = { permissions.launch(client.permissions()) }) { Text("授权附近设备") }; Button(onClick = { client.scan() }, enabled = !state.scanning && !state.paired && !state.connecting) { Text(if (state.scanning) "扫描中" else "扫描手表") } }
        item { OutlinedTextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("手表六位连接码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth()) }
        state.devices.forEach { device -> item(key = device.address) { OutlinedButton(onClick = { client.connect(device.address, code, refreshCache); code = "" }, enabled = code.length == 6 && !state.paired && !state.connecting && device.connectable, modifier = Modifier.fillMaxWidth()) { Text("连接 ${device.name} · ${device.address.takeLast(5)} · ${device.source}${if (device.service) " · 课表服务" else ""}") } } }
        state.request?.let { request -> item { Text("手表请求发送：$request"); Button(onClick = { client.respond(true) }) { Text("同意接收") }; OutlinedButton(onClick = { client.respond(false) }) { Text("拒绝接收") } } }
        if (state.paired) {
            item { Text("发送手机版完整档案", style = MaterialTheme.typography.titleMedium) }
            db.profiles.forEach { p -> item(key = "send-${p.id}") { Button(onClick = { confirm = "发送 ${p.name}？手表仍需预览确认。" to { client.sendProfile(p.id) } }, modifier = Modifier.fillMaxWidth()) { Text("发送 ${p.name}") } } }
            item { Text("接收手表版完整档案", style = MaterialTheme.typography.titleMedium); OutlinedButton(onClick = { client.requestCatalog() }) { Text("请求手表档案列表") }; OutlinedButton(onClick = { client.cancelTransfer() }) { Text("取消本次传输") } }
            state.profiles.forEach { p -> item(key = "pull-${p.id}") { OutlinedButton(onClick = { client.pull(p.id) }, modifier = Modifier.fillMaxWidth()) { Text("接收 ${p.name}") } } }
        }
        state.pending?.let { summary ->
            val previewNonce = state.pendingNonce
            item { HorizontalDivider(); Text("接收预览", style = MaterialTheme.typography.titleMedium); Text(summary); Text(when(state.suggestion) { "same" -> "版本相同"; "receive" -> "仅手表版变化，建议接收"; "send" -> "仅手机版变化，建议保留手机版"; "conflict" -> "双端均已修改，请明确选择；不会逐节自动合并"; else -> "首次同步，请明确选择版本" }) }
            listOf("replace" to "保存手表版", "keep" to "保留手机版", "copy" to "手表版另存副本").forEach { (choice, label) -> item { Button(onClick = { confirm = "$label？保存完成后才发送回执。" to { client.accept(choice, previewNonce) } }, modifier = Modifier.fillMaxWidth()) { Text(label) } } }
        }
        item { TextButton(onClick = { expanded = !expanded }) { Text("日志与备用入口") }; if (expanded) { Row { Checkbox(checked = refreshCache, onCheckedChange = { refreshCache = it }); Text("连接前请求刷新服务缓存（可选诊断）") }; Text(state.diagnostics); OutlinedButton(onClick = { client.pairedDevices() }) { Text("查看已配对设备（不是扫描结果）") } } }
        item { OutlinedButton(onClick = { client.disconnect() }, modifier = Modifier.fillMaxWidth()) { Text("结束同步") }; Text("断连会丢弃未完成分包；重连后请重新请求接收。没有收到对端保存回执时，请勿视为同步完成。GT2 无线互通仍待真机验证。", style = MaterialTheme.typography.bodySmall) }
    }
    confirm?.let { (message, action) -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认同步操作") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
}
