package io.github.gaogaoza.simpletimetable.verifier

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Shell-owned evidence survives the test runner uninstalling the application. */
fun captureDevice(name:String) {
    require(name.matches(Regex("[a-z-]+")))
    val command="screencap -p /data/local/tmp/simpletimetable/$name.png"
    val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
}
