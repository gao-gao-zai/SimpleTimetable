@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package io.github.gaogaoza.simpletimetable.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.gaogaoza.simpletimetable.TimetableApplication
import io.github.gaogaoza.simpletimetable.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EditForm {
    var dirty by mutableStateOf(false)
    var error by mutableStateOf("")
    var confirmText by mutableStateOf("")
    var pending: (() -> Unit)? = null
    var submit: (() -> Unit)? = null
    var afterSave: (() -> Unit)? = null
    var close: () -> Unit = {}
    var open: (Editor) -> Unit = {}
    fun changed() { dirty = true; error = "" }
    fun ask(text: String, action: () -> Unit) { confirmText = text; pending = action }
    fun run(action: () -> Unit) { try { error = ""; action() } catch (e: Exception) { error = e.message ?: "操作失败" } }
}
fun editorTitle(editor: Editor): String = when (editor) {
    is Editor.SubjectEdit -> if (editor.id.isBlank()) "新建科目" else "编辑科目"
    is Editor.LayoutEdit -> "时间表"
    is Editor.SlotEdit -> "编辑时段"
    is Editor.PlanEdit -> "课表与轮换规则"
    is Editor.PlanItems -> "安排课程"
    is Editor.GroupEdit -> "课表群"
    is Editor.ProfileEdit -> "档案"
    is Editor.TemporaryEdit -> "临时安排"
    is Editor.Detail -> "课程详情"
    is Editor.ChangeLesson -> if (editor.permanent) "编辑常规课程" else "仅修改这一次"
    is Editor.Swap -> "交换两节课"
    is Editor.DatePick -> "选择日期"
    Editor.Appearance -> "主题与阅读"
    Editor.Clock -> "课表时间"
    Editor.Activation -> "启用方式"
    Editor.SurfaceSettings -> "通知与小组件设置"
    Editor.Source -> "当前运行来源"
}

@Composable fun EditorHost(app: TimetableApplication, data: AppData, profile: Profile, session: UiSession, editor: Editor, reduced: Boolean) {
    key(editor) {
        val form = remember { EditForm() }; val scope = rememberCoroutineScope()
        var visible by remember { mutableStateOf(false) }; var closing by remember { mutableStateOf(false) }
        val progress by animateFloatAsState(if (visible) 1f else 0f, if (reduced) snap() else tween(190), label = "抽屉过渡")
        fun closeForce() {
            if (closing) return
            closing = true; visible = false
            scope.launch { delay(if (reduced) 0 else 200); session.appearanceDraft = null; session.editor = null }
        }
        fun closeRequest() { if (form.dirty) form.ask("放弃未保存的修改？") { form.dirty = false; closeForce() } else closeForce() }
        form.close = { form.dirty = false; closeForce() }
        form.open = { next -> if (form.dirty) form.ask("放弃未保存的修改并继续？") { session.editor = next } else session.editor = next }
        LaunchedEffect(Unit) { visible = true }
        val height = LocalConfiguration.current.screenHeightDp.dp
        val px = with(LocalDensity.current) { height.toPx() }
        Dialog(onDismissRequest = ::closeRequest, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f * progress)).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { closeRequest() }.systemBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().heightIn(max = height * .94f).graphicsLayer { translationY = (1 - progress) * px }.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
                    Column {
                        Box(Modifier.padding(top = 10.dp, bottom = 2.dp).width(32.dp).height(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f), RoundedCornerShape(2.dp)).align(Alignment.CenterHorizontally))
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(editorTitle(editor), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium); IconButton(onClick = ::closeRequest) { Icon(Icons.Default.Close, "关闭") } }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (editor) {
                                is Editor.SubjectEdit -> SubjectEditor(app, profile, editor, form)
                                is Editor.LayoutEdit -> LayoutEditor(app, profile, editor, form)
                                is Editor.SlotEdit -> SlotEditor(app, profile, editor, form)
                                is Editor.PlanEdit -> PlanEditor(app, profile, editor, form)
                                is Editor.PlanItems -> PlanItemsEditor(app, profile, editor, form)
                                is Editor.GroupEdit -> GroupEditor(app, profile, editor, form)
                                is Editor.ProfileEdit -> ProfileEditor(app, data, session, editor, form)
                                is Editor.TemporaryEdit -> TemporaryEditor(app, profile, editor, form)
                                is Editor.Detail -> DetailEditor(app, data, editor, form)
                                is Editor.ChangeLesson -> LessonEditor(app, data, editor, form)
                                is Editor.Swap -> SwapEditor(app, data, editor, form)
                                is Editor.DatePick -> DateEditor(session, editor, form)
                                Editor.Appearance -> AppearanceEditor(app, data, session, form)
                                Editor.Clock -> ClockEditor(app, data, session, form)
                                Editor.Activation -> ActivationEditor(app, profile, data, form)
                                Editor.SurfaceSettings -> SurfaceSettingsEditor(app, data, session, form)
                                Editor.Source -> SourceEditor(app, data, form)
                            }
                            if (form.error.isNotBlank()) { val bring = remember { BringIntoViewRequester() }; LaunchedEffect(form.error) { bring.bringIntoView() }; Text(form.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.bringIntoViewRequester(bring)) }
                            Spacer(Modifier.height(6.dp))
                        }
                        if (form.submit != null) Button(onClick = { form.run { form.submit?.invoke(); form.dirty = false; val after = form.afterSave; if (after != null) after() else closeForce() } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 48.dp)) { Text("保存") }
                    }
                }
            }
            if (form.confirmText.isNotEmpty()) AlertDialog(onDismissRequest = { form.confirmText = ""; form.pending = null }, title = { Text(form.confirmText) }, confirmButton = { TextButton(onClick = { val action = form.pending; form.pending = null; form.confirmText = ""; form.run { action?.invoke() } }) { Text("确认") } }, dismissButton = { TextButton(onClick = { form.confirmText = ""; form.pending = null }) { Text("返回") } })
        }
    }
}

@Composable fun Input(label: String, value: String, form: EditForm, number: Boolean = false, onChange: (String) -> Unit) {
    TextField(value = value, onValueChange = { form.changed(); onChange(it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text))
}
@Composable fun Choice(label: String, selected: String, options: List<Pair<String, String>>, form: EditForm, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (options.isNotEmpty()) expanded = !expanded }) {
        TextField(value = options.firstOrNull { it.first == selected }?.second ?: "请选择", onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; form.changed(); onChange(id) }) } }
    }
}
@Composable fun Toggle(text: String, checked: Boolean, form: EditForm, onChange: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { form.changed(); onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) { Text(text, Modifier.weight(1f)); Spacer(Modifier.width(12.dp)); Switch(checked, onCheckedChange = { form.changed(); onChange(it) }) } }
@Composable fun Action(text: String, destructive: Boolean = false, onClick: () -> Unit) { OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) { Text(text) } }
@Composable fun Explanation(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
fun requireName(text: String): String = text.trim().also { require(it.isNotBlank()) { "名称不能为空" }; require(it.length <= 160) { "名称最多 160 字" } }
fun intRange(text: String, range: IntRange): Int = (text.toIntOrNull() ?: error("请输入整数")).also { require(it in range) { "请输入 ${range.first} 至 ${range.last} 之间的整数" } }
