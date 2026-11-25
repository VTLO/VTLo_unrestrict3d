package com.vtlo.unrestrict3d

import android.app.Application
import com.vtlo.unrestrict3d.core.Diagnostics
import com.vtlo.unrestrict3d.core.EventLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** System memory-trim levels (ComponentCallbacks2.TRIM_MEMORY_*) fan out to the view-model. */
object MemoryEvents {
    private val _flow = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val flow: SharedFlow<Int> = _flow.asSharedFlow()
    fun emit(level: Int) { _flow.tryEmit(level) }
}

class VtloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
        Diagnostics.installCrashHandler(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        EventLog.log("onTrimMemory level=$level")
        MemoryEvents.emit(level)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        EventLog.log("onLowMemory")
        MemoryEvents.emit(80)
    }
}
