package dev.datell.followertracker

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.datell.followertracker.data.*
import dev.datell.followertracker.sync.*

class TrackerApplication : Application() {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        RapidTracking.restore(this)
        SyncScheduler.schedule(this)
    }
}

class AppGraph(context: Context) {
    val repository = TrackerRepository(Room.databaseBuilder(context, TrackerDatabase::class.java, "tracker.db").build(), DataCipher())
    val sessions = SessionStore(context, DataCipher())
    val collector = SessionCollector(sessions, context.applicationContext)
    val coordinator = SyncCoordinator(context, repository, collector)
}

val Context.appGraph: AppGraph get() = (applicationContext as TrackerApplication).graph
