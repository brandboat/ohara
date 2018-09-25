package com.island.ohara.connector.jdbc.source
import java.sql.Timestamp
import com.island.ohara.client.ConfiguratorJson.Column
import com.island.ohara.connector.jdbc.Version
import com.island.ohara.connector.jdbc.util.ColumnInfo
import com.island.ohara.data.{Cell, Row}
import com.island.ohara.io.CloseOnce
import com.island.ohara.kafka.connector.{RowSourceRecord, RowSourceTask, TaskConfig}
import com.island.ohara.serialization.DataType
import com.typesafe.scalalogging.Logger

class JDBCSourceTask extends RowSourceTask {

  private[this] lazy val logger = Logger(getClass.getName)

  private[this] var jdbcSourceConnectorConfig: JDBCSourceConnectorConfig = _
  private[this] var dbTableDataProvider: DBTableDataProvider = _
  private[this] var schema: Seq[Column] = _
  private[this] var topics: Seq[String] = _

  /**
    * Start the Task. This should handle any configuration parsing and one-time setup of the task.
    *
    * @param config initial configuration
    */
  override protected[source] def _start(config: TaskConfig): Unit = {
    logger.info("starting JDBC Source Connector")
    val props = config.options
    jdbcSourceConnectorConfig = JDBCSourceConnectorConfig(props)

    val dbURL = jdbcSourceConnectorConfig.dbURL
    val dbUserName = jdbcSourceConnectorConfig.dbUserName
    val dbPassword = jdbcSourceConnectorConfig.dbPassword
    dbTableDataProvider = new DBTableDataProvider(dbURL, dbUserName, dbPassword)

    schema = config.schema
    topics = config.topics
  }

  /**
    * Poll this SourceTask for new records. This method should block if no data is currently available.
    *
    * @return a array of RowSourceRecord
    */
  override protected[source] def _poll(): Seq[RowSourceRecord] = {
    val tableName: String = jdbcSourceConnectorConfig.dbTableName
    val timestampColumnName: String = jdbcSourceConnectorConfig.timestampColumnName

    val resultSet: QueryResultIterator =
      dbTableDataProvider.executeQuery(tableName, timestampColumnName, new Timestamp(0)) //TODO offset OHARA-413

    try resultSet
    //Create Ohara Schema
      .map(columns =>
        (if (schema.isEmpty) columns.map(c => Column(c.columnName, DataType.OBJECT, 0)) else schema, columns))
      .flatMap {
        case (newSchema, columns) =>
          topics.map(
            RowSourceRecord
              .builder()
              .sourcePartition(JDBCSourceTask.partition(tableName))
              //TODO offset OHARA-413
              .sourceOffset(JDBCSourceTask.offset(0))
              //Create Ohara Row
              .row(row(newSchema, columns))
              .build(_))
      }
      .toList
    finally resultSet.close()
  }

  /**
    * Signal this SourceTask to stop. In SourceTasks, this method only needs to signal to the task that it should stop
    * trying to poll for new data and interrupt any outstanding poll() requests. It is not required that the task has
    * fully stopped. Note that this method necessarily may be invoked from a different thread than _poll() and _commit()
    */
  override protected def _stop(): Unit = {
    CloseOnce.close(dbTableDataProvider)
  }

  /**
    * Get the version of this task. Usually this should be the same as the corresponding Connector class's version.
    *
    * @return the version, formatted as a String
    */
  override protected def _version: String = Version.getVersion()

  private[source] def row(schema: Seq[Column], columns: Seq[ColumnInfo]): Row = {
    Row
      .builder()
      .cells(
        schema
          .sortBy(_.order)
          .map(s => (s, values(s.name, columns)))
          .map {
            case (schema, value) =>
              schema.order
              Cell(
                schema.newName,
                schema.typeName match {
                  case DataType.BOOLEAN                 => value.asInstanceOf[Boolean]
                  case DataType.SHORT                   => value.asInstanceOf[Short]
                  case DataType.INT                     => value.asInstanceOf[Int]
                  case DataType.LONG                    => value.asInstanceOf[Long]
                  case DataType.FLOAT                   => value.asInstanceOf[Float]
                  case DataType.DOUBLE                  => value.asInstanceOf[Double]
                  case DataType.BYTE                    => value.asInstanceOf[Byte]
                  case DataType.STRING                  => value.asInstanceOf[String]
                  case DataType.BYTES | DataType.OBJECT => value
                  case _                                => throw new IllegalArgumentException("Unsupported type...")
                }
              )
          }
      )
      .build()
  }

  private[this] def values(schemaColumnName: String, dbColumnInfos: Seq[ColumnInfo]): Any = {
    dbColumnInfos.foreach(dbColumn => {
      if (dbColumn.columnName == schemaColumnName) {
        return dbColumn.value
      }
    })
    throw new RuntimeException(s"Database Table not have the $schemaColumnName column")
  }
}

object JDBCSourceTask {
  private[this] val DB_TABLE_NAME_KEY = "db.table.name"
  private[this] val DB_TABLE_OFFSET_KEY = "db.table.offset"

  def partition(tableName: String): Map[String, _] = Map(DB_TABLE_NAME_KEY -> tableName)
  def offset(timestamp: Long): Map[String, _] = Map(DB_TABLE_OFFSET_KEY -> timestamp)
}
