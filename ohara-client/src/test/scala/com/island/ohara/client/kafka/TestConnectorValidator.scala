/*
 * Copyright 2019 is-land
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.island.ohara.client.kafka

import com.island.ohara.common.rule.SmallTest
import org.junit.Test
import org.scalatest.Matchers
import scala.concurrent.ExecutionContext.Implicits.global
class TestConnectorValidator extends SmallTest with Matchers {

  /**
    * we won't make connection in this test
    */
  private[this] val notWorkingClient = WorkerClient("localhost:2222")

  @Test
  def ignoreClassName(): Unit =
    an[NullPointerException] should be thrownBy notWorkingClient.connectorValidator().run()

  @Test
  def nullSettingKey(): Unit =
    an[NullPointerException] should be thrownBy notWorkingClient.connectorValidator().setting(null, "asdsad")

  @Test
  def emptySettingKey(): Unit =
    an[IllegalArgumentException] should be thrownBy notWorkingClient.connectorValidator().setting("", "asdsad")

  @Test
  def nullSettingValue(): Unit =
    an[NullPointerException] should be thrownBy notWorkingClient.connectorValidator().setting("asdsad", null)

  @Test
  def emptySettingValue(): Unit =
    an[IllegalArgumentException] should be thrownBy notWorkingClient.connectorValidator().setting("asdsad", "")

  @Test
  def nullSettings(): Unit =
    an[NullPointerException] should be thrownBy notWorkingClient.connectorValidator().settings(null)

  @Test
  def emptySettings(): Unit =
    an[IllegalArgumentException] should be thrownBy notWorkingClient.connectorValidator().settings(Map.empty)

  @Test
  def nullSchema(): Unit =
    an[NullPointerException] should be thrownBy notWorkingClient.connectorValidator().columns(null)

  @Test
  def illegalNumberOfTasks(): Unit =
    an[IllegalArgumentException] should be thrownBy notWorkingClient.connectorValidator().numberOfTasks(-1)

  @Test
  def nullClass(): Unit = an[NullPointerException] should be thrownBy notWorkingClient
    .connectorValidator()
    .connectorClass(null.asInstanceOf[Class[_]])

  @Test
  def nullClassName(): Unit = an[NullPointerException] should be thrownBy notWorkingClient
    .connectorValidator()
    .connectorClassName(null.asInstanceOf[String])

  @Test
  def emptyClassName(): Unit =
    an[IllegalArgumentException] should be thrownBy notWorkingClient.connectorValidator().connectorClassName("")

  @Test
  def nullTopicName(): Unit =
    an[NullPointerException] should be thrownBy notWorkingClient.connectorValidator().topicName(null)

  @Test
  def emptyTopicName(): Unit =
    an[IllegalArgumentException] should be thrownBy notWorkingClient.connectorValidator().topicName("")

  @Test
  def nullTopicNames(): Unit =
    an[NullPointerException] should be thrownBy notWorkingClient.connectorValidator().topicNames(null)

  @Test
  def emptyTopicNames(): Unit =
    an[IllegalArgumentException] should be thrownBy notWorkingClient.connectorValidator().topicNames(Seq.empty)

  @Test
  def emptyTopicNames2(): Unit =
    an[IllegalArgumentException] should be thrownBy notWorkingClient.connectorValidator().topicNames(Seq(""))
}
