package com.eenglish.listening

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Never delete user practice.db. Instrumented UI tests use a separate database. */
class TestListeningApplication : ListeningApplication() {
    override val databaseName = "instrumentation-practice.db"
    override fun onCreate() {
        deleteDatabase(databaseName)
        super.onCreate()
    }
}

class ListeningTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, TestListeningApplication::class.java.name, context)
}
