package io.github.gaogaoza.simpletimetable.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable fun ImportPage(app: TimetableApplication, p: Profile, session: UiSession) {
    val scope = rememberCoroutineScope(); val form = remember { EditForm() }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf("") }; var tables by remember { mutableStateOf<List<ImportTable>>(emptyList()) }; var sheet by remember { mutableIntStateOf(0) }; var result by remember { mutableStateOf<ImportResult?>(null) }; var imported by remember { mutableStateOf<Profile?>(null) }
    var fileName by remember { mutableStateOf("") }; var name by remember { mutableStateOf("导入课表") }; var mode by remember { mutableStateOf("records") }; var firstRow by remember { mutableStateOf("2") }; var encoding by remember { mutableStateOf("UTF-8") }; var csvBytes by remember { mutableStateOf<ByteArray?>(null) }
    var mapping by remember { mutableStateOf(listOf(0, 1, 2, 3, -1)) }; var area by remember { mutableStateOf("B2:F7") }; var days by remember { mutableStateOf("1,2,3,4,5") }; var layout by remember { mutableStateOf(p.layouts.firstOrNull()?.id.orEmpty()) }; var transpose by remember { mutableStateOf(false) }; var activate by remember { mutableStateOf(false) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch {
        busy = true; error = ""; result = null; imported = null; tables = emptyList(); csvBytes = null; sheet = 0
        try {
            val loaded = withContext(Dispatchers.IO) {
                val title = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: uri.lastPathSegment.orEmpty()
                val bytes = app.contentResolver.openInputStream(uri)?.use { input -> val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 8 * 1024 * 1024) { "文件超过 8 MB，请缩小后重试" }; output.write(buffer, 0, count) }; output.toByteArray() } ?: kotlin.error("无法读取文件")
                title to bytes
            }
            fileName = loaded.first
            when (fileName.substringAfterLast('.').lowercase()) {
                "json" -> result = withContext(Dispatchers.IO) { Importers.classIsland(loaded.second) }
                "xlsx" -> tables = withContext(Dispatchers.IO) { Importers.xlsx(loaded.second) }
                "xls" -> kotlin.error("旧版 .xls 请先另存为 .xlsx 或 CSV")
                else -> { csvBytes = loaded.second; tables = listOf(Importers.csv(loaded.second, encoding)) }
            }
        } catch (e: Exception) { error = e.message ?: "文件读取失败" } finally { busy = false }
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        imported?.let { profile ->
            Text("导入完成", style = MaterialTheme.typography.headlineSmall); Text("已创建「${profile.name}」。当前运行档案保持为「${app.repository.current.active().name}」。")
            Button(onClick = { session.profileId = profile.id; session.page = "manage" }, modifier = Modifier.fillMaxWidth()) { Text("查看新档案") }
            OutlinedButton(onClick = { activate = true }, modifier = Modifier.fillMaxWidth()) { Text("设为当前运行档案") }
        } ?: run {
            Text("导入为新档案", style = MaterialTheme.typography.titleLarge)
            Explanation("选择 ClassIsland JSON、Excel XLSX 或 CSV。文件只在设备上读取，确认前不会写入现有档案。")
            Button(onClick = { choose.launch(arrayOf("application/json", "text/*", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel", "application/octet-stream")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (fileName.isBlank()) "选择文件" else "重新选择文件") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (fileName.isNotBlank()) Text(fileName, style = MaterialTheme.typography.bodySmall)
            if (csvBytes != null && result == null) Choice("CSV 编码", encoding, listOf("UTF-8" to "UTF-8", "GB18030" to "GB18030 / GBK"), form) { encoding = it; try { tables = listOf(Importers.csv(csvBytes!!, it)); sheet = 0; error = "" } catch (e: Exception) { tables = emptyList(); error = e.message.orEmpty() } }
            val preview = result
            if (preview != null) {
                Input("新档案名称", name, form) { name = it }
                Text("${preview.profile.subjects.size} 个科目 · ${preview.profile.layouts.size} 份时间表 · ${preview.profile.plans.size} 份课表")
                preview.warnings.forEach { Explanation(it) }
                preview.profile.plans.forEach { plan ->
                    val times = preview.profile.layouts.firstOrNull { it.id == plan.layoutId }?.slots?.filter { it.type == SlotType.LESSON }.orEmpty()
                    Text(plan.name, style = MaterialTheme.typography.titleSmall)
                    times.take(20).forEach { slot -> val subjectId = plan.subjects[slot.id].orEmpty().ifBlank { slot.defaultSubject }; Text("${clockText(slot.start)} — ${clockText(slot.end)}  ${preview.profile.subjects.firstOrNull { it.id == subjectId }?.name ?: "默认科目"}", style = MaterialTheme.typography.bodySmall) }
                }
                Button(onClick = { try { val profile = preview.profile.copy(id = newId(), name = requireName(name)); app.repository.update { it.copy(profiles = it.profiles + profile) }; imported = profile; session.profileId = profile.id } catch (e: Exception) { error = e.message.orEmpty() } }, modifier = Modifier.fillMaxWidth()) { Text("确认导入") }
                OutlinedButton(onClick = { result = null }, modifier = Modifier.fillMaxWidth()) { Text("返回调整") }
            } else if (tables.isNotEmpty()) {
                Choice("工作表", sheet.toString(), tables.mapIndexed { i, t -> i.toString() to t.name }, form) { sheet = it.toInt() }
                val table = tables[sheet]
                Row(Modifier.horizontalScroll(rememberScrollState())) { Text(table.rows.take(6).joinToString("\n") { row -> row.take(8).joinToString("  |  ") }, style = MaterialTheme.typography.bodySmall) }
                Input("新档案名称", name, form) { name = it }
                Choice("表格布局", mode, listOf("records" to "每行一节课（列映射）", "grid" to "周课表网格（区域与作息）"), form) { mode = it }
                if (mode == "records") {
                    Input("第一条课程数据所在行（从 1 起）", firstRow, form, true) { firstRow = it }
                    val headings = table.rows.firstOrNull().orEmpty().mapIndexed { i, h -> i.toString() to "第 ${i + 1} 列 · ${h.take(30)}" }
                    listOf("星期列", "开始时间列", "结束时间列", "科目列", "教师列").forEachIndexed { i, title -> Choice(title, mapping[i].toString(), if (i == 4) listOf("-1" to "不导入教师") + headings else headings, form) { v -> mapping = mapping.toMutableList().also { it[i] = v.toInt() } } }
                    Explanation("星期支持 1—7 或周一至周日；时间支持 HH:mm、HH:mm:ss 和 Excel 时间数值。")
                } else {
                    Input("科目区域（例如 B2:F7）", area, form) { area = it }
                    Input("对应星期，逗号分隔（1—7）", days, form) { days = it }
                    Toggle("星期按行排列", transpose, form) { transpose = it }
                    Choice("用于对应课程时间的时间表", layout, p.layouts.map { it.id to it.name }, form) { layout = it }
                    Action("新建时间表") { session.editor = Editor.LayoutEdit() }
                }
                Button(onClick = {
                    error = ""
                    try { result = if (mode == "records") Importers.records(table, requireName(name), mapping, intRange(firstRow, 1..2000) - 1) else Importers.grid(table, requireName(name), area, days.replace('，', ',').split(',').map(Importers::parseDay), p.layouts.firstOrNull { it.id == layout } ?: kotlin.error("请选择时间表"), transpose) }
                    catch (e: Exception) { error = e.message.orEmpty() }
                }, modifier = Modifier.fillMaxWidth()) { Text("检查并预览") }
            }
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
    if (activate) AlertDialog(onDismissRequest = { activate = false }, title = { Text("切换当前运行档案？") }, text = { Text("现在页、通知和小组件会立即使用新档案。") }, confirmButton = { TextButton(onClick = { imported?.let { p -> app.repository.update { it.copy(activeProfile = p.id) }; session.profileId = p.id; session.page = "home" }; activate = false }) { Text("切换") } }, dismissButton = { TextButton(onClick = { activate = false }) { Text("返回") } })
}
