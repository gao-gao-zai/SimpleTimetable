package io.github.gaogaoza.simpletimetable

import android.appwidget.*
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.widget.ListView
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.gaogaoza.simpletimetable.data.*
import io.github.gaogaoza.simpletimetable.system.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class WidgetHostTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualWidgetHostLoadsCardAndScrollableTimetable() {
        val app=compose.activity.application as TimetableApplication
        compose.runOnUiThread {
            val p=SampleData.profile()
            val boot=android.provider.Settings.Global.getInt(app.contentResolver,android.provider.Settings.Global.BOOT_COUNT,-1)
            app.repository.update { AppData(profiles=listOf(p),activeProfile=p.id,settings=Settings(seed="#B34E78",softwareClock=true,autoSync=false,syncEpoch=LocalDate.of(2026,10,12).atTime(8,10).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),syncElapsed=SystemClock.elapsedRealtime(),syncBootCount=boot)) }
        }
        val manager=AppWidgetManager.getInstance(app); val host=AppWidgetHost(app,9715)
        val folder=compose.activity.getExternalFilesDir("acceptance")!!;folder.mkdirs()
        var originalFailure:Throwable?=null
        try {
            compose.runOnUiThread { host.startListening() }
            for ((provider,label) in listOf(CourseWidget::class.java to "course-widget",TimetableWidget::class.java to "timetable-widget")) {
                var view:AppWidgetHostView?=null
                compose.runOnUiThread {
                    val id=host.allocateAppWidgetId()
                    val options=Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,360);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,360);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,300);putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,300) }
                    assertTrue("CI must grant appwidget binding permission",manager.bindAppWidgetIdIfAllowed(id,ComponentName(app,provider),options))
                    view=host.createView(compose.activity,id,manager.getAppWidgetInfo(id))
                    compose.activity.setContent {
                        val height=(LocalConfiguration.current.screenHeightDp-32).coerceAtMost(400)
                        Box(Modifier.fillMaxSize().padding(16.dp),contentAlignment=Alignment.Center) { AndroidView(factory={view!!},modifier=Modifier.fillMaxWidth().height(height.dp)) }
                    }
                    SurfaceUpdater.update(app)
                }
                compose.waitUntil(20000) {
                    var ready=false
                    compose.runOnUiThread { ready=if(provider==CourseWidget::class.java) view?.findViewById<TextView>(R.id.course_title)?.text=="语文" else (view?.findViewById<ListView>(R.id.widget_list)?.adapter?.count ?: 0)==6 }
                    ready
                }
                captureDevice(label)
            }
        } catch(t:Throwable) { originalFailure=t;throw t }
        finally { runCatching { compose.runOnUiThread { host.stopListening();host.deleteHost();SurfaceUpdater.update(app) } }.onFailure { if(originalFailure==null) throw it else android.util.Log.w("STTTest","Host cleanup failed after test failure",it) } }
    }
}
