package com.gemmory.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.gemmory.chat.data.entities.ConversationEntity
import com.gemmory.chat.data.entities.MessageEntity
import com.gemmory.core.logging.AppLog

@Database(
    entities = [ConversationEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val DATABASE_NAME = "gemmory-chat.db"

        /**
         * A corrupted or unreadable database must not brick the app: the file is
         * deleted and recreated, and the user simply starts with no history.
         */
        fun create(context: Context): ChatDatabase {
            val appContext = context.applicationContext
            return try {
                build(appContext)
            } catch (t: Throwable) {
                AppLog.e("ChatDatabase", "conversation database unusable; recreating", t)
                appContext.deleteDatabase(DATABASE_NAME)
                build(appContext)
            }
        }

        private fun build(context: Context): ChatDatabase =
            Room.databaseBuilder(context, ChatDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
