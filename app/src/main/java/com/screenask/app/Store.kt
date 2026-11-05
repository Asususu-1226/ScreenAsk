package com.screenask.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: Long,
    val time: Long,
    val kind: String,
    val question: String,
    val answer: String,
    val model: String,
    val hasImage: Boolean
)

/** Small JSON-file backed history. No database, no cloud, excluded from backup. */
object HistoryStore {
    private const val FILE_NAME = "history.json"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun all(context: Context): List<HistoryEntry> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val text = runCatching { f.readText() }.getOrDefault("[]")
        val array = runCatching { JSONArray(text) }.getOrDefault(JSONArray())
        val list = ArrayList<HistoryEntry>(array.length())
        for (i in array.length() - 1 downTo 0) {
            val o = array.optJSONObject(i) ?: continue
            list += HistoryEntry(
                id = o.optLong("id"),
                time = o.optLong("time"),
                kind = o.optString("kind", "parse"),
                question = o.optString("question", ""),
                answer = o.optString("answer", ""),
                model = o.optString("model", ""),
                hasImage = o.optBoolean("hasImage", false)
            )
        }
        list
    }

    fun add(
        context: Context,
        kind: String,
        question: String,
        answer: String,
        model: String,
        hasImage: Boolean,
        limit: Int
    ) {
        synchronized(lock) {
            val list = ArrayList<HistoryEntry>(all(context))
            list.add(
                0,
                HistoryEntry(
                    id = System.currentTimeMillis(),
                    time = System.currentTimeMillis(),
                    kind = kind,
                    question = question,
                    answer = answer,
                    model = model,
                    hasImage = hasImage
                )
            )
            val trimmed = if (limit > 0 && list.size > limit) list.subList(0, limit) else list
            val array = JSONArray()
            for (entry in trimmed.asReversed()) {
                array.put(
                    JSONObject()
                        .put("id", entry.id)
                        .put("time", entry.time)
                        .put("kind", entry.kind)
                        .put("question", entry.question)
                        .put("answer", entry.answer)
                        .put("model", entry.model)
                        .put("hasImage", entry.hasImage)
                )
            }
            runCatching { file(context).writeText(array.toString()) }
        }
    }

    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { file(context).delete() }
        }
    }

    fun latest(context: Context): HistoryEntry? = all(context).firstOrNull()
}

/** Last captured frame, shared by one-shot, session and follow-up flows. */
object FrameStore {
    private const val FILE_NAME = "screenask_last_frame.jpg"
    private val lock = Any()

    fun file(context: Context) = File(context.cacheDir, FILE_NAME)

    fun save(context: Context, jpeg: ByteArray) {
        synchronized(lock) {
            runCatching {
                val f = file(context)
                val tmp = File(context.cacheDir, FILE_NAME + ".tmp")
                tmp.writeBytes(jpeg)
                if (f.exists()) f.delete()
                if (!tmp.renameTo(f)) {
                    f.writeBytes(jpeg)
                    tmp.delete()
                }
            }
        }
    }

    fun read(context: Context): ByteArray? {
        synchronized(lock) {
            val f = file(context)
            if (!f.exists() || f.length() == 0L) return null
            return runCatching { f.readBytes() }.getOrNull()
        }
    }

    fun exists(context: Context): Boolean {
        val f = file(context)
        return f.exists() && f.length() > 0
    }

    fun clear(context: Context) {
        synchronized(lock) { runCatching { file(context).delete() } }
    }
}
