package com.adshield.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.adshield.app.data.db.PopupEventDao
import com.adshield.app.data.db.PopupEventEntity

/**
 * 界面广告记录使用**独立数据库**，与 DNS 统计库（adshield.db）分开。
 *
 * 原因：M2 新增表若并入旧库需要版本迁移，手写迁移脚本与实际 schema 稍有差异
 * 就会在打开时抛 `IllegalStateException`。独立库可以各自演进、互不影响，
 * 也不会因为升级清空已有的拦截统计。
 */
@Database(
    entities = [PopupEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PopupDatabase : RoomDatabase() {

    abstract fun popupEventDao(): PopupEventDao

    companion object {
        private const val NAME = "adshield_popup.db"

        @Volatile
        private var instance: PopupDatabase? = null

        fun get(context: Context): PopupDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PopupDatabase::class.java,
                    NAME,
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}