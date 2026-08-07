package com.jotbill.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.jotbill.app.data.entity.ChatMessageEntity
import com.jotbill.app.data.entity.ChatSessionEntity

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ChatSessionEntity)

    @Update
    suspend fun updateSession(session: ChatSessionEntity)

    @Delete
    suspend fun deleteSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getSession(id: String): ChatSessionEntity?

    @Query(
        """
        SELECT * FROM chat_sessions
        ORDER BY is_pinned DESC, updated_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun getSessions(limit: Int, offset: Int): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY seq_num ASC")
    suspend fun getMessages(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE session_id = :sessionId")
    suspend fun countMessages(sessionId: String): Int

    @Query("SELECT COALESCE(MAX(seq_num), 0) + 1 FROM chat_messages WHERE session_id = :sessionId")
    suspend fun nextSequence(sessionId: String): Int

    @Query("UPDATE chat_sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun renameSession(id: String, title: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE chat_sessions
        SET is_pinned = CASE WHEN is_pinned = 1 THEN 0 ELSE 1 END,
            updated_at = :updatedAt
        WHERE id = :id
        """
    )
    suspend fun togglePinned(id: String, updatedAt: Long): Int

    @Transaction
    suspend fun appendMessage(message: ChatMessageEntity, updatedAt: Long) {
        insertMessage(message)
        getSession(message.sessionId)?.let {
            updateSession(it.copy(updatedAt = updatedAt))
        }
    }
}
