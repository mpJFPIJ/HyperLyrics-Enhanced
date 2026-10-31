package com.juren233.hyperlyricsenhanced.root.aitrans

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.juren233.hyperlyricsenhanced.lyric.model.Song
import com.juren233.hyperlyricsenhanced.common.extensions.json
import com.juren233.hyperlyricsenhanced.common.extensions.toJson
import com.juren233.hyperlyricsenhanced.root.utils.HookLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Memory + SQLite cache for completed song-level AI translations. */
internal class AITranslationCache(
    private val maxCacheSize: Int,
    private val generation: AtomicInteger,
    private val scope: CoroutineScope
) {
    private companion object {
        const val TAG = "AITranslationCache"
    }

    private val dbMutex = Mutex()
    private val groupEpochs = AiCacheGroupEpochs()
    private var dbHelper: DatabaseHelper? = null

    private val memory: MutableMap<String, List<TranslationItem>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, List<TranslationItem>>(maxCacheSize, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TranslationItem>>?): Boolean {
                    return size > maxCacheSize
                }
            }
        )

    fun init(context: Context) {
        if (dbHelper == null) {
            synchronized(this) {
                if (dbHelper == null) {
                    HookLogger.d(TAG, "初始化翻译缓存数据库")
                    dbHelper = DatabaseHelper(context.applicationContext)
                }
            }
        }
    }

    fun getFromMemory(key: String): List<TranslationItem>? = memory[key]

    fun putMemory(key: String, items: List<TranslationItem>) {
        memory[key] = items
    }

    suspend fun getFromDb(key: String): List<TranslationItem>? = dbMutex.withLock {
        val db = dbHelper?.readableDatabase ?: return null
        return runCatching {
            db.query(
                DatabaseHelper.TABLE_NAME,
                arrayOf(DatabaseHelper.COLUMN_DATA),
                "${DatabaseHelper.COLUMN_ID} = ?",
                arrayOf(key),
                null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val jsonData =
                        cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_DATA))
                    json.decodeFromString<List<TranslationItem>>(jsonData).also {
                        // Keep the RAM mirror consistent with deletion under dbMutex.
                        memory[key] = it
                    }
                } else null
            }
        }.getOrElse {
            HookLogger.e(TAG, "查询翻译缓存失败", it)
            null
        }
    }

    suspend fun saveToDb(
        key: String,
        items: List<TranslationItem>,
        song: Song? = null,
        originalLines: List<String> = emptyList(),
        expectedEpoch: Long? = null,
    ): Boolean {
        val jsonData = items.toJson()
        val groupId = song?.let { AiCachedSongCatalog.groupId(it.name, it.artist, originalLines) }
        return dbMutex.withLock {
            val db = dbHelper?.writableDatabase ?: return@withLock false
            if (expectedEpoch != null && (groupId == null ||
                    !groupEpochs.isCurrent(groupId, expectedEpoch))) return@withLock false
            val saved = runCatching {
                val values = ContentValues().apply {
                    put(DatabaseHelper.COLUMN_ID, key)
                    put(DatabaseHelper.COLUMN_DATA, jsonData)
                    put(DatabaseHelper.COLUMN_TIMESTAMP, System.currentTimeMillis())
                    if (song != null) {
                        put(DatabaseHelper.COLUMN_GROUP_ID, groupId)
                        put(DatabaseHelper.COLUMN_SONG_TITLE, song.name)
                        put(DatabaseHelper.COLUMN_SONG_ARTIST, song.artist)
                    }
                }
                db.insertWithOnConflict(
                    DatabaseHelper.TABLE_NAME,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                ) != -1L
            }.getOrElse {
                HookLogger.e(TAG, "写入翻译缓存失败", it)
                false
            }
            if (saved) memory[key] = items
            saved
        }
    }

    suspend fun currentGroupEpoch(groupId: String): Long = dbMutex.withLock {
        groupEpochs.current(groupId)
    }

    /** Backfill catalog metadata for a legacy cache hit without altering its cached lyrics. */
    suspend fun attachSongMetadata(key: String, song: Song, originalLines: List<String>) {
        dbMutex.withLock {
            val db = dbHelper?.writableDatabase ?: return@withLock
            runCatching {
                val values = ContentValues().apply {
                    put(DatabaseHelper.COLUMN_GROUP_ID,
                        AiCachedSongCatalog.groupId(song.name, song.artist, originalLines))
                    put(DatabaseHelper.COLUMN_SONG_TITLE, song.name)
                    put(DatabaseHelper.COLUMN_SONG_ARTIST, song.artist)
                }
                db.update(DatabaseHelper.TABLE_NAME, values,
                    "${DatabaseHelper.COLUMN_ID} = ? AND ${DatabaseHelper.COLUMN_GROUP_ID} IS NULL",
                    arrayOf(key))
            }.onFailure { HookLogger.e(TAG, "缓存歌曲信息补全失败", it) }
        }
    }

    private fun loadRows(db: SQLiteDatabase): List<AiCachedSongRow> {
        val rows = mutableListOf<AiCachedSongRow>()
        db.query(DatabaseHelper.TABLE_NAME, arrayOf(
            DatabaseHelper.COLUMN_ID, DatabaseHelper.COLUMN_GROUP_ID,
            DatabaseHelper.COLUMN_SONG_TITLE, DatabaseHelper.COLUMN_SONG_ARTIST,
            DatabaseHelper.COLUMN_TIMESTAMP,
        ), null, null, null, null, null).use { cursor ->
            val keyColumn = cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_ID)
            val groupColumn = cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_GROUP_ID)
            val titleColumn = cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SONG_TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_SONG_ARTIST)
            val dateColumn = cursor.getColumnIndexOrThrow(DatabaseHelper.COLUMN_TIMESTAMP)
            while (cursor.moveToNext()) {
                rows += AiCachedSongRow(
                    cacheKey = cursor.getString(keyColumn),
                    songGroupId = cursor.getString(groupColumn),
                    title = cursor.getString(titleColumn),
                    artist = cursor.getString(artistColumn),
                    updatedAt = cursor.getLong(dateColumn),
                )
            }
        }
        return rows
    }

    suspend fun listSavedSongs(): List<AiCachedSong> = dbMutex.withLock {
        val db = dbHelper?.readableDatabase ?: return@withLock emptyList()
        AiCachedSongCatalog.groupRows(loadRows(db))
    }

    suspend fun keysForGroup(groupId: String): Set<String> = dbMutex.withLock {
        val db = dbHelper?.readableDatabase ?: return@withLock emptySet()
        AiCachedSongCatalog.keysForGroup(groupId, loadRows(db))
    }

    /**
     * Delete a single song's derived cache variants from memory and SQLite.
     * Other songs' translations and pronunciation caches remain intact.
     */
    suspend fun removeKeys(keys: Set<String>, groupId: String? = null): Boolean = dbMutex.withLock {
        if (keys.isEmpty()) return@withLock false
        val db = dbHelper?.writableDatabase ?: return@withLock false
        // Include variants saved since the UI first enumerated this group.
        val allKeys = if (groupId != null) {
            keys + AiCachedSongCatalog.keysForGroup(groupId, loadRows(db))
        } else keys
        if (groupId != null) groupEpochs.advance(groupId)
        val removed = runCatching {
            val selection = allKeys.joinToString(prefix = "${DatabaseHelper.COLUMN_ID} IN (",
                postfix = ")", separator = ",") { "?" }
            db.delete(DatabaseHelper.TABLE_NAME, selection, allKeys.toTypedArray())
            true
        }.getOrElse {
            HookLogger.e(TAG, "删除当前歌曲翻译缓存失败", it)
            false
        }
        if (removed) allKeys.forEach(memory::remove)
        removed
    }

    fun clear(callback: () -> Unit) {
        generation.incrementAndGet()
        memory.clear()
        scope.launch(Dispatchers.IO) {
            dbMutex.withLock {
                runCatching {
                    dbHelper?.writableDatabase?.delete(DatabaseHelper.TABLE_NAME, null, null)
                    HookLogger.d(TAG, "翻译缓存数据库已清空")
                    withContext(Dispatchers.Main) { callback() }
                }.onFailure {
                    HookLogger.e(TAG, "清空翻译缓存数据库失败", it)
                }
            }
        }
    }

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        companion object {
            const val DATABASE_NAME = "hyperlyricsenhanced_translation.db"
            const val DATABASE_VERSION = 2
            const val TABLE_NAME = "ai_cache"
            const val COLUMN_ID = "song_id"
            const val COLUMN_DATA = "translation_json"
            const val COLUMN_TIMESTAMP = "created_at"
            const val COLUMN_GROUP_ID = "song_group_id"
            const val COLUMN_SONG_TITLE = "song_title"
            const val COLUMN_SONG_ARTIST = "song_artist"
        }

        override fun onCreate(db: SQLiteDatabase) {
            HookLogger.i(TAG, "创建翻译缓存数据表")
            db.execSQL(
                """
                CREATE TABLE $TABLE_NAME (
                    $COLUMN_ID TEXT PRIMARY KEY,
                    $COLUMN_DATA TEXT,
                    $COLUMN_TIMESTAMP INTEGER,
                    $COLUMN_GROUP_ID TEXT,
                    $COLUMN_SONG_TITLE TEXT,
                    $COLUMN_SONG_ARTIST TEXT
                )
            """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_timestamp ON $TABLE_NAME($COLUMN_TIMESTAMP)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_song_group ON $TABLE_NAME($COLUMN_GROUP_ID)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // Previously stored translations must never be deleted by a schema upgrade.
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_GROUP_ID TEXT")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_SONG_TITLE TEXT")
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_SONG_ARTIST TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_song_group ON $TABLE_NAME($COLUMN_GROUP_ID)")
            }
        }
    }
}
