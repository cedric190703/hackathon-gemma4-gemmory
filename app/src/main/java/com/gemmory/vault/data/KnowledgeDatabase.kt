package com.gemmory.vault.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.gemmory.core.logging.AppLog
import com.gemmory.inbox.data.InboxEntryEntity
import com.gemmory.vault.data.entities.KnowledgeChatEntity
import com.gemmory.vault.data.entities.KnowledgeMessageEntity
import com.gemmory.vault.data.entities.VaultChangeSetEntity
import com.gemmory.vault.data.entities.VaultLinkEntity
import com.gemmory.vault.data.entities.VaultNoteEntity
import com.gemmory.vault.data.entities.VaultNoteFtsEntity
import com.gemmory.vault.data.entities.VaultRevisionEntity

@Database(
    entities = [
        InboxEntryEntity::class,
        VaultNoteEntity::class,
        VaultNoteFtsEntity::class,
        VaultLinkEntity::class,
        VaultRevisionEntity::class,
        VaultChangeSetEntity::class,
        KnowledgeChatEntity::class,
        KnowledgeMessageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KnowledgeDatabase : RoomDatabase() {

    abstract fun knowledgeDao(): KnowledgeDao

    companion object {
        const val DATABASE_NAME = "gemmory-knowledge.db"

        fun create(context: Context): KnowledgeDatabase {
            val appContext = context.applicationContext
            return try {
                build(appContext)
            } catch (t: Throwable) {
                AppLog.e("KnowledgeDatabase", "knowledge database unusable; recreating", t)
                appContext.deleteDatabase(DATABASE_NAME)
                build(appContext)
            }
        }

        private fun build(context: Context): KnowledgeDatabase =
            Room.databaseBuilder(context, KnowledgeDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
