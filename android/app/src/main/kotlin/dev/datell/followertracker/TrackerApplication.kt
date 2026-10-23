package dev.datell.followertracker

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.datell.followertracker.data.*
import dev.datell.followertracker.diagnostics.DebugDiagnostics
import dev.datell.followertracker.sync.*

class TrackerApplication : Application() {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        DebugDiagnostics.initialize(this)
        RapidTracking.restore(this)
        SyncScheduler.schedule(this)
    }
}

class AppGraph(context: Context) {
    val repository = TrackerRepository(Room.databaseBuilder(context, TrackerDatabase::class.java, "tracker.db")
        .addMigrations(TrackerDatabase.MIGRATION_2_3).build(), DataCipher())
    val sessions = SessionStore(context, DataCipher())
    val officialApis = OfficialApiStore(context, DataCipher())
    private val apiIdentity by lazy { androidApiIdentity(context) }
    val collector = SessionCollector(sessions, context.applicationContext, officialApis,
        OfficialApiClient(androidIdentity = { apiIdentity }))
    val coordinator = SyncCoordinator(context, repository, collector, sessionGuard = { provider ->
        val sessionCurrent = sessions.guard(provider)
        val apiCurrent = officialApis.guard(provider)
        val current: () -> Boolean = { sessionCurrent() && apiCurrent() }
        current
    })
}

val Context.appGraph: AppGraph get() = (applicationContext as TrackerApplication).graph
