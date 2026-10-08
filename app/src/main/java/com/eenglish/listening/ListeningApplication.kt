package com.eenglish.listening

import android.app.Application
import androidx.room.Room
import com.eenglish.listening.data.local.PracticeDatabase
import com.eenglish.listening.data.repository.PartRepository
import com.eenglish.listening.data.repository.PracticeRepository
import com.eenglish.listening.data.repository.AnnotationRepository

open class ListeningApplication : Application() {
    protected open val databaseName: String = "practice.db"
    val database: PracticeDatabase by lazy {
        Room.databaseBuilder(this, PracticeDatabase::class.java, databaseName)
            .addMigrations(PracticeDatabase.MIGRATION_1_2).build()
    }
    val parts by lazy { PartRepository(assets) }
    val practices by lazy { PracticeRepository(database) }
    val annotations by lazy { AnnotationRepository(database) }
}
