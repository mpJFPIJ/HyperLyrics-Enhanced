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
    private val clearGate = AiCacheClearGate()
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

    fun getFromMemory(key: String): List<TranslationItem>? =
        memory[key].takeIf { clearGate.mayServeCache() }

    suspend fun getFromDb(key: String): List<TranslationItem>? = dbMutex.withLock {
        if (!clearGate.mayReadDatabase()) return@withLock null
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
                    json.decodeFromString<List<TranslationItem>>(jsonData)
                        .takeIf { clearGate.mayReadDatabase() }
                        ?.also { memory[key] = it }
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
        song: Song,
        ticket: AiCacheWriteTicket,
    ): Boolean {
        val jsonData = items.toJson()
        return dbMutex.withLock {
            if (key !in ticket.scope.keys || !mayWrite(ticket)) return@withLock false
            val db = dbHelper?.writableDatabase ?: return@withLock false
            val saved = runCatching {
                val values = ContentValues().apply {
                    put(DatabaseHelper.COLUMN_ID, key)
                    put(DatabaseHelper.COLUMN_DATA, jsonData)
                    put(DatabaseHelper.COLUMN_TIMESTAMP, System.currentTimeMillis())
                    put(DatabaseHelper.COLUMN_GROUP_ID, ticket.scope.groupId)
                    put(DatabaseHelper.COLUMN_SONG_TITLE, song.name)
                    put(DatabaseHelper.COLUMN_SONG_ARTIST, song.artist)
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

    suspend fun writeTicket(
        scope: AiCacheScope, song: Song, startingGeneration: Int,
    ): AiCacheWriteTicket = dbMutex.withLock {
        val ticket = AiCacheWriteTicket(scope, groupEpochs.current(scope), startingGeneration)
        // Publish legacy-to-song identity before a sibling variant can be saved.
        if (mayWrite(ticket)) {
            runCatching {
                val values = ContentValues().apply {
                    put(DatabaseHelper.COLUMN_GROUP_ID, scope.groupId)
                    put(DatabaseHelper.COLUMN_SONG_TITLE, song.name)
                    put(DatabaseHelper.COLUMN_SONG_ARTIST, song.artist)
                }
                val placeholders = scope.keys.joinToString(",") { "?" }
                dbHelper?.writableDatabase?.update(DatabaseHelper.TABLE_NAME, values,
                    "${DatabaseHelper.COLUMN_GROUP_ID} IS NULL AND ${DatabaseHelper.COLUMN_ID} IN ($placeholders)",
                    scope.keys.toTypedArray())
            }.onFailure { HookLogger.e(TAG, "缓存歌曲信息补全失败", it) }
        }
        ticket
    }

    suspend fun ensureCurrent(ticket: AiCacheWriteTicket) = dbMutex.withLock {
        requireCurrentAiResult(ticket.generation, ticket.epoch, generation.get(), groupEpochs.current(ticket.scope))
    }

    // Call while holding dbMutex so no per-song delete can interleave with the mutation.
    private fun mayWrite(ticket: AiCacheWriteTicket): Boolean =
        clearGate.mayReadDatabase() && ticket.isCurrent(generation.get(), groupEpochs.current(ticket.scope))

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

    /** Resolve the displayed row, delete its variants and invalidate pending writers atomically. */
    suspend fun removeSong(
        groupId: String,
        knownKeys: Set<String> = emptySet(),
        onRemoved: (AiCacheScope) -> Unit,
    ): Boolean = dbMutex.withLock {
        val db = dbHelper?.writableDatabase ?: return@withLock false
        val selected = AiCachedSongCatalog.deletionScope(groupId, loadRows(db))
        val removed = selected.copy(keys = selected.keys + knownKeys)
        if (removed.keys.isEmpty()) return@withLock false
        val success = runCatching {
            val selection = removed.keys.joinToString(prefix = "${DatabaseHelper.COLUMN_ID} IN (",
                postfix = ")", separator = ",") { "?" }
            db.delete(DatabaseHelper.TABLE_NAME, selection, removed.keys.toTypedArray())
            true
        }.getOrElse {
            HookLogger.e(TAG, "删除当前歌曲翻译缓存失败", it)
            false
        }
        if (success) {
            groupEpochs.advance(removed)
            removed.keys.forEach(memory::remove)
            onRemoved(removed)
        }
        success
    }

    fun clear(callback: () -> Unit) {
        clearGate.beginClear()
        generation.incrementAndGet()
        memory.clear()
        scope.launch(Dispatchers.IO) {
            var cleared = false
            try {
                dbMutex.withLock {
                    cleared = runCatching {
                        dbHelper?.writableDatabase?.delete(DatabaseHelper.TABLE_NAME, null, null)
                        true
                    }.getOrElse {
                        HookLogger.e(TAG, "清空翻译缓存数据库失败", it)
                        false
                    }
                    // Readers that completed just before the delete acquired the
                    // mutex might have re-populated RAM after the first clear.
                    memory.clear()
                    if (cleared) {
                        HookLogger.d(TAG, "翻译缓存数据库已清空")
                    }
                }
            } finally {
                clearGate.finishClear(cleared)
            }
            if (cleared) {
                runCatching { withContext(Dispatchers.Main) { callback() } }
                    .onFailure { HookLogger.e(TAG, "翻译缓存清理完成回调失败", it) }
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
