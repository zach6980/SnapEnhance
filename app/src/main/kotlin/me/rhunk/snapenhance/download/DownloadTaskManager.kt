package me.rhunk.snapenhance.download

import android.annotation.SuppressLint
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import me.rhunk.snapenhance.core.download.data.DownloadMetadata
import me.rhunk.snapenhance.core.download.data.DownloadStage
import me.rhunk.snapenhance.core.download.data.MediaDownloadSource
import me.rhunk.snapenhance.core.util.SQLiteDatabaseHelper
import me.rhunk.snapenhance.core.util.ktx.getIntOrNull
import me.rhunk.snapenhance.core.util.ktx.getStringOrNull
import java.util.concurrent.Executors

class DownloadTaskManager {
    private lateinit var taskDatabase: SQLiteDatabase
    private val pendingTasks = mutableMapOf<Int, DownloadObject>()
    private val cachedTasks = mutableMapOf<Int, DownloadObject>()
    private val executor = Executors.newSingleThreadExecutor()

    @SuppressLint("Range")
    fun init(context: Context) {
        if (this::taskDatabase.isInitialized) return
        taskDatabase = context.openOrCreateDatabase("download_tasks", Context.MODE_PRIVATE, null).apply {
            SQLiteDatabaseHelper.createTablesFromSchema(this, mapOf(
                "tasks" to listOf(
                    "id INTEGER PRIMARY KEY AUTOINCREMENT",
                    "hash VARCHAR UNIQUE",
                    "outputPath TEXT",
                    "outputFile TEXT",
                    "mediaAuthor TEXT",
                    "downloadSource TEXT",
                    "iconUrl TEXT",
                    "downloadStage TEXT"
                )
            ))
        }
    }

    fun addTask(task: DownloadObject) {
        executor.execute {
            taskDatabase.execSQL("INSERT INTO tasks (hash, outputPath, outputFile, downloadSource, mediaAuthor, iconUrl, downloadStage) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    task.metadata.mediaIdentifier,
                    task.metadata.outputPath,
                    task.outputFile,
                    task.metadata.downloadSource,
                    task.metadata.mediaAuthor,
                    task.metadata.iconUrl,
                    task.downloadStage.name
                )
            )
            task.downloadId = taskDatabase.rawQuery("SELECT last_insert_rowid()", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
            pendingTasks[task.downloadId] = task
        }
    }

    fun updateTask(task: DownloadObject) {
        executor.execute {
            taskDatabase.execSQL(
                "UPDATE tasks SET hash = ?, outputPath = ?, outputFile = ?, downloadSource = ?, mediaAuthor = ?, iconUrl = ?, downloadStage = ? WHERE id = ?",
                arrayOf(
                    task.metadata.mediaIdentifier,
                    task.metadata.outputPath,
                    task.outputFile,
                    task.metadata.downloadSource,
                    task.metadata.mediaAuthor,
                    task.metadata.iconUrl,
                    task.downloadStage.name,
                    task.downloadId
                )
            )
        }
        //if the task is no longer active, move it to the cached tasks
        if (task.isJobActive()) {
            pendingTasks[task.downloadId] = task
        } else {
            pendingTasks.remove(task.downloadId)
            cachedTasks[task.downloadId] = task
        }
    }

    @SuppressLint("Range")
    fun canDownloadMedia(mediaIdentifier: String?): DownloadStage? {
        if (mediaIdentifier == null) return null

        val cursor = taskDatabase.rawQuery("SELECT * FROM tasks WHERE hash = ?", arrayOf(mediaIdentifier))
        if (cursor.count > 0) {
            cursor.moveToFirst()
            val downloadStage = DownloadStage.valueOf(cursor.getString(cursor.getColumnIndex("downloadStage")))
            cursor.close()

            //if the stage has reached a final stage and is not in a saved state, remove the task
            if (downloadStage.isFinalStage && downloadStage != DownloadStage.SAVED) {
                taskDatabase.execSQL("DELETE FROM tasks WHERE hash = ?", arrayOf(mediaIdentifier))
                return null
            }

            return downloadStage
        }
        cursor.close()
        return null
    }

    fun isEmpty(): Boolean {
        return cachedTasks.isEmpty() && pendingTasks.isEmpty()
    }

    private fun removeTask(id: Int) {
        executor.execute {
            taskDatabase.execSQL("DELETE FROM tasks WHERE id = ?", arrayOf(id))
            cachedTasks.remove(id)
            pendingTasks.remove(id)
        }
    }

    fun removeTask(task: DownloadObject) {
        removeTask(task.downloadId)
    }

    fun queryFirstTasks(filter: MediaDownloadSource): Map<Int, DownloadObject> {
        val isPendingFilter = filter == MediaDownloadSource.PENDING
        val tasks = mutableMapOf<Int, DownloadObject>()

        tasks.putAll(pendingTasks.filter { isPendingFilter || filter.matches(it.value.metadata.downloadSource) })
        if (isPendingFilter) {
            return tasks.toSortedMap(reverseOrder())
        }

        tasks.putAll(queryTasks(
            from = tasks.values.lastOrNull()?.downloadId ?: Int.MAX_VALUE,
            amount = 30,
            filter = filter
        ))

        return tasks.toSortedMap(reverseOrder())
    }

    @SuppressLint("Range")
    fun queryTasks(from: Int, amount: Int = 30, filter: MediaDownloadSource = MediaDownloadSource.NONE): Map<Int, DownloadObject> {
        if (filter == MediaDownloadSource.PENDING) {
            return emptyMap()
        }

        val cursor = taskDatabase.rawQuery(
            "SELECT * FROM tasks WHERE id < ? AND downloadSource LIKE ? ORDER BY id DESC LIMIT ?",
            arrayOf(
                from.toString(),
                if (filter.ignoreFilter) "%" else "%${filter.key}",
                amount.toString()
            )
        )

        val result = sortedMapOf<Int, DownloadObject>()

        while (cursor.moveToNext()) {
            val task = DownloadObject(
                downloadId = cursor.getIntOrNull("id")!!,
                outputFile = cursor.getStringOrNull("outputFile"),
                metadata = DownloadMetadata(
                    outputPath = cursor.getStringOrNull("outputPath")!!,
                    mediaIdentifier = cursor.getStringOrNull("hash"),
                    downloadSource = cursor.getStringOrNull("downloadSource") ?: MediaDownloadSource.NONE.key,
                    mediaAuthor = cursor.getStringOrNull("mediaAuthor"),
                    iconUrl = cursor.getStringOrNull("iconUrl")
                )
            ).apply {
                updateTaskCallback = { updateTask(it) }
                downloadStage = DownloadStage.valueOf(cursor.getStringOrNull("downloadStage")!!)
                //if downloadStage is not saved, it means the app was killed before the download was finished
                if (downloadStage != DownloadStage.SAVED) {
                    downloadStage = DownloadStage.FAILED
                }
            }
            result[task.downloadId] = task
        }
        cursor.close()

        return result.toSortedMap(reverseOrder())
    }

    fun removeAllTasks() {
        executor.execute {
            taskDatabase.execSQL("DELETE FROM tasks")
            cachedTasks.clear()
            pendingTasks.clear()
        }
    }
}