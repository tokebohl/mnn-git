package com.tokebohl.mnnnext.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tokebohl.mnnnext.engine.MnnLlmRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class MemoryFact(
    val id: Long,
    val category: String,
    val content: String,
    val importance: Float,
    val timestamp: Long,
    val score: Float = 0f
)

class JarvisMemoryStore(
    context: Context,
    private val llmRunnerProvider: () -> MnnLlmRunner? = { null }
) : SQLiteOpenHelper(context, "jarvis_memory.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                content TEXT NOT NULL UNIQUE ON CONFLICT REPLACE,
                importance REAL DEFAULT 1.0,
                embedding BLOB,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                content,
                category,
                content='memories',
                content_rowid='id'
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
                INSERT INTO memories_fts(rowid, content, category)
                VALUES (new.id, new.content, new.category);
            END;
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
                INSERT INTO memories_fts(memories_fts, rowid, content, category)
                VALUES('delete', old.id, old.content, old.category);
            END;
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
                INSERT INTO memories_fts(memories_fts, rowid, content, category)
                VALUES('delete', old.id, old.content, old.category);
                INSERT INTO memories_fts(rowid, content, category)
                VALUES (new.id, new.content, new.category);
            END;
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
                    INSERT INTO memories_fts(memories_fts, rowid, content, category)
                    VALUES('delete', old.id, old.content, old.category);
                    INSERT INTO memories_fts(rowid, content, category)
                    VALUES (new.id, new.content, new.category);
                END;
                """.trimIndent()
            )
        }
    }

    fun remember(
        content: String,
        category: String = "general",
        importance: Float = 1.0f
    ): Long {
        val cleaned = content.trim()
        if (cleaned.isEmpty()) return -1L
        val vector = computeSemanticVector(cleaned)
        val values = ContentValues().apply {
            put("category", category.trim().ifEmpty { "general" })
            put("content", cleaned)
            put("importance", importance.coerceIn(0.1f, 5.0f))
            put("timestamp", System.currentTimeMillis())
            put("embedding", floatArrayToBytes(vector))
        }
        return writableDatabase.insertWithOnConflict(
            "memories",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun updateMemory(
        id: Long,
        newContent: String,
        newCategory: String = "manual",
        newImportance: Float = 1.5f
    ): Boolean {
        val cleaned = newContent.trim()
        if (cleaned.isEmpty()) return false
        val vector = computeSemanticVector(cleaned)
        val values = ContentValues().apply {
            put("content", cleaned)
            put("category", newCategory.trim().ifEmpty { "manual" })
            put("importance", newImportance.coerceIn(0.1f, 5.0f))
            put("timestamp", System.currentTimeMillis())
            put("embedding", floatArrayToBytes(vector))
        }
        val rows = writableDatabase.update("memories", values, "id = ?", arrayOf(id.toString()))
        return rows > 0
    }

    fun deleteMemory(id: Long) {
        writableDatabase.delete("memories", "id = ?", arrayOf(id.toString()))
    }

    fun listAllMemories(limit: Int = 200): List<MemoryFact> {
        val list = mutableListOf<MemoryFact>()
        readableDatabase.rawQuery(
            "SELECT id, category, content, importance, timestamp FROM memories ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    MemoryFact(
                        id = cursor.getLong(0),
                        category = cursor.getString(1),
                        content = cursor.getString(2),
                        importance = cursor.getFloat(3),
                        timestamp = cursor.getLong(4)
                    )
                )
            }
        }
        return list
    }

    fun recallRelevant(query: String, limit: Int = 5): List<MemoryFact> {
        val results = mutableMapOf<Long, MemoryFact>()
        val queryVec = computeSemanticVector(query)

        val sanitizedTokens = query.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }
            .joinToString(" OR ") { "$it*" }

        if (sanitizedTokens.isNotEmpty()) {
            val sql = """
                SELECT m.id, m.category, m.content, m.importance, m.timestamp
                FROM memories_fts f
                JOIN memories m ON f.rowid = m.id
                WHERE memories_fts MATCH ?
                ORDER BY bm25(memories_fts) ASC
                LIMIT ?
            """.trimIndent()

            runCatching {
                readableDatabase.rawQuery(sql, arrayOf(sanitizedTokens, (limit * 2).toString())).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val importance = cursor.getFloat(3)
                        results[id] = MemoryFact(
                            id = id,
                            category = cursor.getString(1),
                            content = cursor.getString(2),
                            importance = importance,
                            timestamp = cursor.getLong(4),
                            score = 0.65f * importance
                        )
                    }
                }
            }
        }

        readableDatabase.rawQuery(
            "SELECT id, category, content, importance, timestamp, embedding FROM memories WHERE embedding IS NOT NULL",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val blob = cursor.getBlob(5) ?: continue
                val storedVec = bytesToFloatArray(blob)
                val sim = cosineSimilarity(queryVec, storedVec)

                if (sim >= 0.22f) {
                    val importance = cursor.getFloat(3)
                    val existingScore = results[id]?.score ?: 0f
                    results[id] = MemoryFact(
                        id = id,
                        category = cursor.getString(1),
                        content = cursor.getString(2),
                        importance = importance,
                        timestamp = cursor.getLong(4),
                        score = existingScore + (sim * importance)
                    )
                }
            }
        }

        return results.values
            .sortedByDescending { it.score }
            .take(limit)
    }

    fun extractUserFactsAutomatically(userInput: String): List<String> {
        val extracted = extractFactsFromText(userInput)
        for ((fact, category) in extracted) {
            remember(fact, category, importance = 1.4f)
        }
        return extracted.map { it.first }
    }

    fun computeSemanticVector(text: String, dims: Int = 384): FloatArray {
        val tokens = tokenizeToIds(text)
        val nativeVec = llmRunnerProvider()?.computeEmbedding(tokens)
        if (nativeVec != null && nativeVec.size == dims) {
            return nativeVec
        }
        return computeLocalSemanticVector(text, dims)
    }

    companion object {
        private val USER_FACT_PATTERNS = listOf(
            Regex("(?i)\\b(?:jeg hedder|mit navn er|my name is)\\s+([^.!?\\n]+)") to "identity",
            Regex("(?i)\\b(?:husk at|glem ikke at|remember that|note that)\\s+([^.!?\\n]+)") to "explicit_note",
            Regex("(?i)\\b(?:jeg foretrækker|jeg kan godt lide|i prefer|i like)\\s+([^.!?\\n]+)") to "preference",
            Regex("(?i)\\b(?:jeg bor i|jeg arbejder hos|i live in|i work at)\\s+([^.!?\\n]+)") to "profile",
            Regex("(?i)\\b(?:min\\s+[a-zæøå0-9_\\s]{2,20}\\s+er|my\\s+[a-z0-9_\\s]{2,20}\\s+is)\\s+([^.!?\\n]+)") to "fact"
        )

        fun extractFactsFromText(text: String): List<Pair<String, String>> {
            val found = mutableListOf<Pair<String, String>>()
            val sentences = text.split(Regex("(?<=[.!?\\n])\\s*"))
            for (raw in sentences) {
                val sentence = raw.trim()
                if (sentence.length < 6) continue
                for ((pattern, category) in USER_FACT_PATTERNS) {
                    if (pattern.containsMatchIn(sentence)) {
                        found.add(sentence to category)
                        break
                    }
                }
            }
            return found
        }

        fun tokenizeToIds(text: String): IntArray {
            val normalized = text.lowercase().trim()
            if (normalized.isEmpty()) return intArrayOf(0)
            val ids = ArrayList<Int>(normalized.length)
            val words = normalized.split(Regex("\\s+"))
            for (word in words) {
                ids.add(word.hashCode())
                if (word.length >= 3) {
                    for (i in 0..word.length - 3) {
                        ids.add(word.substring(i, i + 3).hashCode())
                    }
                }
            }
            return ids.toIntArray()
        }

        fun computeLocalSemanticVector(text: String, dims: Int = 384): FloatArray {
            val vec = FloatArray(dims)
            val normalized = text.lowercase().trim()
            if (normalized.isEmpty()) return vec

            val words = normalized.split(Regex("\\s+"))
            for (word in words) {
                val h = (word.hashCode() and Int.MAX_VALUE) % dims
                vec[h] += 1.5f
                if (word.length >= 3) {
                    for (i in 0..word.length - 3) {
                        val trigram = word.substring(i, i + 3)
                        val idx = (trigram.hashCode() and Int.MAX_VALUE) % dims
                        vec[idx] += 0.7f
                    }
                }
            }

            var norm = 0f
            for (v in vec) norm += v * v
            norm = sqrt(norm)
            if (norm > 0f) {
                for (i in vec.indices) vec[i] /= norm
            }
            return vec
        }

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            return if (denom > 1e-6f) dot / denom else 0f
        }

        fun floatArrayToBytes(floats: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in floats) buffer.putFloat(f)
            return buffer.array()
        }

        fun bytesToFloatArray(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.float }
        }
    }
}
