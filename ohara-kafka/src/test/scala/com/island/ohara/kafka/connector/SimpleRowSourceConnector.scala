package com.island.ohara.kafka.connector
import com.island.ohara.util.VersionUtil

/**
  * Used for testing.
  */
class SimpleRowSourceConnector extends RowSourceConnector {
  private[this] var config: TaskConfig = _
  override val _version: String = VersionUtil.VERSION

  override def _start(config: TaskConfig): Unit = {
    this.config = config
  }

  override def _taskClass(): Class[_ <: RowSourceTask] = classOf[SimpleRowSourceTask]

  override def _taskConfigs(maxTasks: Int): Seq[TaskConfig] = Seq.fill(maxTasks)(config)

  override def _stop(): Unit = {}
}
