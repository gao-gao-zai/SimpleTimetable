package io.github.gaogaoza.simpletimetable

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import io.github.gaogaoza.simpletimetable.data.AppClock
import io.github.gaogaoza.simpletimetable.data.Repository

class TimetableApplication : Application() {
    lateinit var repository: Repository; private set
    lateinit var clock: AppClock; private set
    override fun onCreate() {
        super.onCreate(); repository = Repository(this); clock = AppClock(this, repository)
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() { override fun onAvailable(network: Network) { clock.autoSync() } }) }
    }
}
