package com.labsage.app.`data`.local.dao

import androidx.room.EntityDeleteOrUpdateAdapter
import androidx.room.EntityInsertAdapter
import androidx.room.RoomDatabase
import androidx.room.coroutines.createFlow
import androidx.room.util.getColumnIndexOrThrow
import androidx.room.util.performSuspending
import androidx.sqlite.SQLiteStatement
import com.labsage.app.`data`.local.entity.MessageEntity
import javax.`annotation`.processing.Generated
import kotlin.Boolean
import kotlin.Int
import kotlin.Long
import kotlin.String
import kotlin.Suppress
import kotlin.Unit
import kotlin.collections.List
import kotlin.collections.MutableList
import kotlin.collections.mutableListOf
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.Flow

@Generated(value = ["androidx.room.RoomProcessor"])
@Suppress(names = ["UNCHECKED_CAST", "DEPRECATION", "REDUNDANT_PROJECTION", "REMOVAL"])
public class MessageDao_Impl(
  __db: RoomDatabase,
) : MessageDao {
  private val __db: RoomDatabase

  private val __insertAdapterOfMessageEntity: EntityInsertAdapter<MessageEntity>

  private val __updateAdapterOfMessageEntity: EntityDeleteOrUpdateAdapter<MessageEntity>
  init {
    this.__db = __db
    this.__insertAdapterOfMessageEntity = object : EntityInsertAdapter<MessageEntity>() {
      protected override fun createQuery(): String =
          "INSERT OR ABORT INTO `messages` (`id`,`conversation_id`,`role`,`content`,`timestamp`,`route`,`is_partial`,`original_question`,`error_message`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)"

      protected override fun bind(statement: SQLiteStatement, entity: MessageEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.conversationId)
        statement.bindText(3, entity.role)
        statement.bindText(4, entity.content)
        statement.bindLong(5, entity.timestamp)
        val _tmpRoute: String? = entity.route
        if (_tmpRoute == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpRoute)
        }
        val _tmp: Int = if (entity.isPartial) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        val _tmpOriginalQuestion: String? = entity.originalQuestion
        if (_tmpOriginalQuestion == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpOriginalQuestion)
        }
        val _tmpErrorMessage: String? = entity.errorMessage
        if (_tmpErrorMessage == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpErrorMessage)
        }
      }
    }
    this.__updateAdapterOfMessageEntity = object : EntityDeleteOrUpdateAdapter<MessageEntity>() {
      protected override fun createQuery(): String =
          "UPDATE OR ABORT `messages` SET `id` = ?,`conversation_id` = ?,`role` = ?,`content` = ?,`timestamp` = ?,`route` = ?,`is_partial` = ?,`original_question` = ?,`error_message` = ? WHERE `id` = ?"

      protected override fun bind(statement: SQLiteStatement, entity: MessageEntity) {
        statement.bindLong(1, entity.id)
        statement.bindText(2, entity.conversationId)
        statement.bindText(3, entity.role)
        statement.bindText(4, entity.content)
        statement.bindLong(5, entity.timestamp)
        val _tmpRoute: String? = entity.route
        if (_tmpRoute == null) {
          statement.bindNull(6)
        } else {
          statement.bindText(6, _tmpRoute)
        }
        val _tmp: Int = if (entity.isPartial) 1 else 0
        statement.bindLong(7, _tmp.toLong())
        val _tmpOriginalQuestion: String? = entity.originalQuestion
        if (_tmpOriginalQuestion == null) {
          statement.bindNull(8)
        } else {
          statement.bindText(8, _tmpOriginalQuestion)
        }
        val _tmpErrorMessage: String? = entity.errorMessage
        if (_tmpErrorMessage == null) {
          statement.bindNull(9)
        } else {
          statement.bindText(9, _tmpErrorMessage)
        }
        statement.bindLong(10, entity.id)
      }
    }
  }

  public override suspend fun insert(message: MessageEntity): Long = performSuspending(__db, false,
      true) { _connection ->
    val _result: Long = __insertAdapterOfMessageEntity.insertAndReturnId(_connection, message)
    _result
  }

  public override suspend fun update(message: MessageEntity): Unit = performSuspending(__db, false,
      true) { _connection ->
    __updateAdapterOfMessageEntity.handle(_connection, message)
  }

  public override fun getMessagesByConversation(conversationId: String): Flow<List<MessageEntity>> {
    val _sql: String = "SELECT * FROM messages WHERE conversation_id = ? ORDER BY timestamp ASC"
    return createFlow(__db, false, arrayOf("messages")) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, conversationId)
        val _columnIndexOfId: Int = getColumnIndexOrThrow(_stmt, "id")
        val _columnIndexOfConversationId: Int = getColumnIndexOrThrow(_stmt, "conversation_id")
        val _columnIndexOfRole: Int = getColumnIndexOrThrow(_stmt, "role")
        val _columnIndexOfContent: Int = getColumnIndexOrThrow(_stmt, "content")
        val _columnIndexOfTimestamp: Int = getColumnIndexOrThrow(_stmt, "timestamp")
        val _columnIndexOfRoute: Int = getColumnIndexOrThrow(_stmt, "route")
        val _columnIndexOfIsPartial: Int = getColumnIndexOrThrow(_stmt, "is_partial")
        val _columnIndexOfOriginalQuestion: Int = getColumnIndexOrThrow(_stmt, "original_question")
        val _columnIndexOfErrorMessage: Int = getColumnIndexOrThrow(_stmt, "error_message")
        val _result: MutableList<MessageEntity> = mutableListOf()
        while (_stmt.step()) {
          val _item: MessageEntity
          val _tmpId: Long
          _tmpId = _stmt.getLong(_columnIndexOfId)
          val _tmpConversationId: String
          _tmpConversationId = _stmt.getText(_columnIndexOfConversationId)
          val _tmpRole: String
          _tmpRole = _stmt.getText(_columnIndexOfRole)
          val _tmpContent: String
          _tmpContent = _stmt.getText(_columnIndexOfContent)
          val _tmpTimestamp: Long
          _tmpTimestamp = _stmt.getLong(_columnIndexOfTimestamp)
          val _tmpRoute: String?
          if (_stmt.isNull(_columnIndexOfRoute)) {
            _tmpRoute = null
          } else {
            _tmpRoute = _stmt.getText(_columnIndexOfRoute)
          }
          val _tmpIsPartial: Boolean
          val _tmp: Int
          _tmp = _stmt.getLong(_columnIndexOfIsPartial).toInt()
          _tmpIsPartial = _tmp != 0
          val _tmpOriginalQuestion: String?
          if (_stmt.isNull(_columnIndexOfOriginalQuestion)) {
            _tmpOriginalQuestion = null
          } else {
            _tmpOriginalQuestion = _stmt.getText(_columnIndexOfOriginalQuestion)
          }
          val _tmpErrorMessage: String?
          if (_stmt.isNull(_columnIndexOfErrorMessage)) {
            _tmpErrorMessage = null
          } else {
            _tmpErrorMessage = _stmt.getText(_columnIndexOfErrorMessage)
          }
          _item =
              MessageEntity(_tmpId,_tmpConversationId,_tmpRole,_tmpContent,_tmpTimestamp,_tmpRoute,_tmpIsPartial,_tmpOriginalQuestion,_tmpErrorMessage)
          _result.add(_item)
        }
        _result
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun updateContent(
    messageId: Long,
    content: String,
    isPartial: Boolean,
  ) {
    val _sql: String = "UPDATE messages SET content = ?, is_partial = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindText(_argIndex, content)
        _argIndex = 2
        val _tmp: Int = if (isPartial) 1 else 0
        _stmt.bindLong(_argIndex, _tmp.toLong())
        _argIndex = 3
        _stmt.bindLong(_argIndex, messageId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun finalizeMessage(
    messageId: Long,
    content: String,
    route: String?,
  ) {
    val _sql: String = "UPDATE messages SET is_partial = 0, route = ?, content = ? WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        if (route == null) {
          _stmt.bindNull(_argIndex)
        } else {
          _stmt.bindText(_argIndex, route)
        }
        _argIndex = 2
        _stmt.bindText(_argIndex, content)
        _argIndex = 3
        _stmt.bindLong(_argIndex, messageId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public override suspend fun deleteById(messageId: Long) {
    val _sql: String = "DELETE FROM messages WHERE id = ?"
    return performSuspending(__db, false, true) { _connection ->
      val _stmt: SQLiteStatement = _connection.prepare(_sql)
      try {
        var _argIndex: Int = 1
        _stmt.bindLong(_argIndex, messageId)
        _stmt.step()
      } finally {
        _stmt.close()
      }
    }
  }

  public companion object {
    public fun getRequiredConverters(): List<KClass<*>> = emptyList()
  }
}
