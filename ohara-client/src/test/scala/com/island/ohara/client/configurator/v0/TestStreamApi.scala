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

package com.island.ohara.client.configurator.v0

import com.island.ohara.client.configurator.v0.MetricsApi.Metrics
import com.island.ohara.client.configurator.v0.StreamApi.StreamClusterInfo
import com.island.ohara.common.rule.SmallTest
import com.island.ohara.common.setting.{ObjectKey, SettingDef, TopicKey}
import com.island.ohara.common.util.{CommonUtils, VersionUtils}
import com.island.ohara.streams.config.StreamDefUtils
import org.junit.Test
import org.scalatest.Matchers
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class TestStreamApi extends SmallTest with Matchers {

  private[this] final val accessRequest =
    StreamApi.access.hostname(CommonUtils.randomString(5)).port(CommonUtils.availablePort()).request
  private[this] final val fakeJar = ObjectKey.of(CommonUtils.randomString(1), CommonUtils.randomString(1))

  private[this] final def result[T](f: Future[T]): T = Await.result(f, 10 seconds)

  private[this] def topicKey(): TopicKey = topicKey(CommonUtils.randomString())
  private[this] def topicKey(name: String): TopicKey = TopicKey.of(TopicApi.GROUP_DEFAULT, name)

  @Test
  def checkVersion(): Unit = {
    StreamApi.IMAGE_NAME_DEFAULT shouldBe s"oharastream/streamapp:${VersionUtils.VERSION}"
  }

  @Test
  def testCloneNodeNames(): Unit = {
    val newNodeNames = Set(CommonUtils.randomString())
    val info = StreamClusterInfo(
      settings = Map(
        StreamDefUtils.NAME_DEFINITION.key() -> JsString("name"),
        StreamDefUtils.IMAGE_NAME_DEFINITION.key() -> JsString("imageName"),
        StreamDefUtils.INSTANCES_DEFINITION.key() -> JsNumber(1),
        StreamDefUtils.JAR_KEY_DEFINITION.key() -> ObjectKey.of("group", "name").toJson,
        StreamDefUtils.FROM_TOPIC_KEYS_DEFINITION.key() -> JsString("aa"),
        StreamDefUtils.TO_TOPIC_KEYS_DEFINITION.key() -> JsString("bb"),
        StreamDefUtils.JMX_PORT_DEFINITION.key() -> JsNumber(0),
        StreamDefUtils.TAGS_DEFINITION.key() -> JsObject(Map("bar" -> JsString("foo"), "he" -> JsNumber(1)))
      ),
      definition = Some(Definition("className", Seq(SettingDef.builder().key("key").group("group").build()))),
      nodeNames = Set("node1"),
      deadNodes = Set.empty,
      state = None,
      error = None,
      metrics = Metrics(Seq.empty),
      lastModified = CommonUtils.current()
    )
    info.clone(newNodeNames).nodeNames shouldBe newNodeNames
  }

  @Test
  def testStreamDefinitionEquals(): Unit = {
    val definition = Definition("className", Seq(SettingDef.builder().key("key").group("group").build()))
    definition shouldBe Definition.DEFINITION_JSON_FORMAT.read(Definition.DEFINITION_JSON_FORMAT.write(definition))
  }

  @Test
  def testStreamClusterInfoEquals(): Unit = {
    val fromTopicKey = topicKey()
    val toTopicKey = topicKey()
    val info = StreamClusterInfo(
      settings = Map(
        StreamDefUtils.NAME_DEFINITION.key() -> JsString("name"),
        StreamDefUtils.IMAGE_NAME_DEFINITION.key() -> JsString("imageName"),
        StreamDefUtils.INSTANCES_DEFINITION.key() -> JsNumber(1),
        StreamDefUtils.JAR_KEY_DEFINITION.key() -> ObjectKey.of("group", "name").toJson,
        StreamDefUtils.FROM_TOPIC_KEYS_DEFINITION.key() -> JsArray(TopicKey.toJsonString(fromTopicKey).parseJson),
        StreamDefUtils.TO_TOPIC_KEYS_DEFINITION.key() -> JsArray(TopicKey.toJsonString(toTopicKey).parseJson),
        StreamDefUtils.JMX_PORT_DEFINITION.key() -> JsNumber(0),
        StreamDefUtils.TAGS_DEFINITION.key() -> JsObject(Map("bar" -> JsString("foo"), "he" -> JsNumber(1)))
      ),
      definition = Some(Definition("className", Seq(SettingDef.builder().key("key").group("group").build()))),
      nodeNames = Set("node1"),
      deadNodes = Set.empty,
      state = None,
      error = None,
      metrics = Metrics(Seq.empty),
      lastModified = CommonUtils.current()
    )

    info shouldBe StreamApi.STREAM_CLUSTER_INFO_JSON_FORMAT.read(StreamApi.STREAM_CLUSTER_INFO_JSON_FORMAT.write(info))

    info.name shouldBe "name"
    info.imageName shouldBe "imageName"
    info.instances shouldBe 1
    info.jarKey shouldBe ObjectKey.of("group", "name")
    info.from shouldBe Set(fromTopicKey)
    info.to shouldBe Set(toTopicKey)
    info.definition.isDefined && info.definition.get.definitions.size == 1 shouldBe true
    info.tags.keys.size shouldBe 2
    // we initial exactlyOnce to be false
    info.exactlyOnce shouldBe false
  }

  @Test
  def nameFieldCheck(): Unit = {
    an[NullPointerException] should be thrownBy accessRequest.name(null)
    an[IllegalArgumentException] should be thrownBy accessRequest.name("")
  }

  @Test
  def imageNameFieldCheck(): Unit = {
    an[NullPointerException] should be thrownBy accessRequest.imageName(null)
    an[IllegalArgumentException] should be thrownBy accessRequest.imageName("")

    // default value
    accessRequest
      .name(CommonUtils.randomString(5))
      .jarKey(fakeJar)
      .creation
      .imageName shouldBe StreamApi.IMAGE_NAME_DEFAULT
  }

  @Test
  def jarFieldCheck(): Unit = {
    an[NullPointerException] should be thrownBy accessRequest.imageName(null)
    an[IllegalArgumentException] should be thrownBy accessRequest.imageName("")
  }

  @Test
  def topicFromFieldCheck(): Unit = {
    an[NullPointerException] should be thrownBy accessRequest.fromTopicKeys(null)

    // default from field will be empty
    accessRequest.name(CommonUtils.randomString(5)).creation.from shouldBe Set.empty
  }

  @Test
  def topicToFieldCheck(): Unit = {
    an[NullPointerException] should be thrownBy accessRequest.toTopicKeys(null)

    // default to field will be empty
    accessRequest.name(CommonUtils.randomString(5)).creation.to shouldBe Set.empty
  }

  @Test
  def jmxPortFieldCheck(): Unit = {
    an[IllegalArgumentException] should be thrownBy accessRequest.jmxPort(0)
    an[IllegalArgumentException] should be thrownBy accessRequest.jmxPort(-1)

    // default value
    CommonUtils.requireConnectionPort(accessRequest.name(CommonUtils.randomString(5)).creation.jmxPort)
  }

  @Test
  def instancesFieldCheck(): Unit = {
    an[IllegalArgumentException] should be thrownBy accessRequest.instances(0)
    an[IllegalArgumentException] should be thrownBy accessRequest.instances(-1)

    // default value
    accessRequest.name(CommonUtils.randomString(5)).creation.instances shouldBe 1
  }

  @Test
  def nodeNamesFieldCheck(): Unit = {
    an[NullPointerException] should be thrownBy accessRequest.nodeNames(null)
    an[IllegalArgumentException] should be thrownBy accessRequest.nodeNames(Set.empty)

    // default value
    accessRequest.name(CommonUtils.randomString(5)).creation.nodeNames shouldBe Set.empty
  }

  @Test
  def requireFieldOnPropertyCreation(): Unit = {
    // absent name will be auto generate
    accessRequest.creation

    // no jarKey is ok
    accessRequest.name(CommonUtils.randomString(5)).creation
  }

  @Test
  def testMinimumCreation(): Unit = {
    val creationApi = accessRequest.creation

    creationApi.name.nonEmpty shouldBe true
    creationApi.imageName shouldBe StreamApi.IMAGE_NAME_DEFAULT
    creationApi.jarKey shouldBe None
    creationApi.from shouldBe Set.empty
    creationApi.to shouldBe Set.empty
    creationApi.jmxPort should not be 0
    creationApi.instances shouldBe 1
    creationApi.nodeNames shouldBe Set.empty
    creationApi.tags shouldBe Map.empty

    val creationJson = StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
                                                  |  {
                                                  |  }
     """.stripMargin.parseJson)
    creationJson.name.nonEmpty shouldBe true
    creationJson.imageName shouldBe StreamApi.IMAGE_NAME_DEFAULT
    creationJson.jmxPort should not be 0
    creationJson.instances shouldBe 1
    creationJson.nodeNames shouldBe Set.empty
    creationJson.tags shouldBe Map.empty

    creationApi.settings.keys.size shouldBe creationJson.settings.keys.size
  }

  @Test
  def testCreation(): Unit = {
    val name = CommonUtils.randomString(10)
    val imageName = CommonUtils.randomString()
    val from = topicKey()
    val to = topicKey()
    val jmxPort = CommonUtils.availablePort()
    val instances = CommonUtils.randomString().length
    val nodeNames = Set(CommonUtils.randomString())
    val creation = accessRequest
      .name(name)
      .imageName(imageName)
      .jarKey(fakeJar)
      .fromTopicKey(from)
      .toTopicKey(to)
      .jmxPort(jmxPort)
      .instances(instances)
      .nodeNames(nodeNames)
      .creation

    creation.name shouldBe name
    creation.imageName shouldBe imageName
    creation.jarKey shouldBe Some(fakeJar)
    creation.from shouldBe Set(from)
    creation.to shouldBe Set(to)
    creation.jmxPort shouldBe jmxPort
    creation.instances shouldBe instances
    creation.nodeNames shouldBe nodeNames
  }

  @Test
  def testExtraSettingInCreation(): Unit = {
    val name = CommonUtils.randomString(10)
    val name2 = JsString(CommonUtils.randomString(10))
    val creation = accessRequest.name(name).settings(Map("name" -> name2)).creation

    // settings() has higher priority than name()
    creation.name shouldBe name2.value
  }

  @Test
  def parseCreation(): Unit = {
    val from = topicKey()
    val to = topicKey()
    val nodeName = "n0"
    val creation = StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "from": [
      |      {
      |        "group": "${from.group()}",
      |        "name": "${from.name()}"
      |      }
      |    ],
      |    "to": [
      |      {
      |        "group": "${to.group()}",
      |        "name": "${to.name()}"
      |      }
      |    ],
      |    "nodeNames": ["$nodeName"],
      |    "jarKey": ${fakeJar.toJson}
      |  }
      |  """.stripMargin.parseJson)
    creation.name.length shouldBe StreamApi.LIMIT_OF_NAME_LENGTH
    creation.group shouldBe StreamApi.GROUP_DEFAULT
    creation.imageName shouldBe StreamApi.IMAGE_NAME_DEFAULT
    creation.jarKey shouldBe Some(fakeJar)
    creation.from shouldBe Set(from)
    creation.to shouldBe Set(to)
    creation.jmxPort should not be 0
    creation.instances shouldBe 1
    creation.nodeNames shouldBe Set(nodeName)

    val name = CommonUtils.randomString(10)
    val creation2 = StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
       |  {
       |    "name": "$name",
       |    "from": [
       |      {
       |        "group": "${from.group()}",
       |        "name": "${from.name()}"
       |      }
       |    ],
       |    "to": [
       |      {
       |        "group": "${to.group()}",
       |        "name": "${to.name()}"
       |      }
       |    ],
       |    "nodeNames": ["$nodeName"],
       |    "jarKey": ${fakeJar.toJson}
       |  }
       |  """.stripMargin.parseJson)
    creation2.name shouldBe name
    creation2.imageName shouldBe StreamApi.IMAGE_NAME_DEFAULT
    creation2.jarKey shouldBe Some(fakeJar)
    creation.from shouldBe Set(from)
    creation.to shouldBe Set(to)
    creation2.jmxPort should not be 0
    creation2.instances shouldBe 1
    creation.nodeNames shouldBe Set(nodeName)
  }

  @Test
  def testDefaultName(): Unit = StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
       |  {
       |  }
     """.stripMargin.parseJson).name.nonEmpty shouldBe true

  @Test
  def parseNameField(): Unit = {
    val thrown2 = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "name": ""
      |  }
      |  """.stripMargin.parseJson)
    thrown2.getMessage should include("the value of \"name\" can't be empty string")
  }

  @Test
  def parseImageNameField(): Unit = {
    val thrown = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "imageName": ""
      |  }
      |  """.stripMargin.parseJson)
    thrown.getMessage should include("the value of \"imageName\" can't be empty string")
  }

  @Test
  def parseJarKeyField(): Unit = {
    val name = CommonUtils.randomString(10)

    // no jarKey is OK
    StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "name": "$name"
      |  }
      |  """.stripMargin.parseJson)

    val thrown2 = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "jarKey": ""
      |  }
      |  """.stripMargin.parseJson)
    thrown2.getMessage should include("the value of \"jarKey\" can't be empty string")
  }

  @Test
  def parseJmxPortField(): Unit = {
    // zero port
    val thrown1 = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "name": "${CommonUtils.randomString(10)}",
      |    "jmxPort": 0
      |  }
      |  """.stripMargin.parseJson)
    thrown1.getMessage should include("the connection port must be [1024, 65535)")

    // negative port
    val thrown2 = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "name": "${CommonUtils.randomString(10)}",
      |    "jmxPort": -99
      |  }
      |  """.stripMargin.parseJson)
    thrown2.getMessage should include("the connection port must be [1024, 65535), but actual port is \"-99\"")

    // not connection port
    val thrown3 = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "name": "${CommonUtils.randomString(10)}",
      |    "jmxPort": 999999
      |  }
      |  """.stripMargin.parseJson)
    thrown3.getMessage should include("the connection port must be [1024, 65535)")
  }

  @Test
  def parseInstancesField(): Unit = {
    an[DeserializationException] should be thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "instances": 0
      |  }
      |  """.stripMargin.parseJson)
    // negative instances
    val thrown = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "instances": -99
      |  }
      |  """.stripMargin.parseJson)
    thrown.getMessage should include("the \"-99\" of \"instances\" can't be either negative or zero!!!")
  }

  @Test
  def requireFieldOnPropertyUpdate(): Unit = {
    // name is required
    an[NoSuchElementException] should be thrownBy result(accessRequest.jarKey(ObjectKey.of("group", "name")).update())

    // no jar is ok
    accessRequest.name(CommonUtils.randomString(5)).update
  }

  @Test
  def testDefaultUpdate(): Unit = {
    val name = CommonUtils.randomString(10)
    val data = accessRequest.name(name).update
    data.settings.contains(StreamDefUtils.IMAGE_NAME_DEFINITION.key()) shouldBe false
    data.settings.contains(StreamDefUtils.FROM_TOPIC_KEYS_DEFINITION.key()) shouldBe false
    data.settings.contains(StreamDefUtils.TO_TOPIC_KEYS_DEFINITION.key()) shouldBe false
    data.settings.contains(StreamDefUtils.JMX_PORT_DEFINITION.key()) shouldBe false
    data.settings.contains(StreamDefUtils.INSTANCES_DEFINITION.key()) shouldBe false
    data.settings.contains(StreamDefUtils.NODE_NAMES_DEFINITION.key()) shouldBe false
  }

  @Test
  def parseImageNameFieldOnUpdate(): Unit = {
    val thrown = the[DeserializationException] thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "imageName": ""
      |  }
      |  """.stripMargin.parseJson)
    thrown.getMessage should include("the value of \"imageName\" can't be empty string")
  }

  @Test
  def parseFromFieldOnCreation(): Unit = {
    val thrown1 = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "from": [""]
      |  }
      |  """.stripMargin.parseJson)
    thrown1.getMessage should include("the value of \"from\" can't be empty string")
  }

  @Test
  def parseFromFieldOnUpdate(): Unit = {
    val thrown1 = the[DeserializationException] thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "from": [""]
      |  }
      |  """.stripMargin.parseJson)
    thrown1.getMessage should include("the value of \"from\" can't be empty string")
  }

  @Test
  def parseToFieldOnCreation(): Unit = {
    val thrown1 = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "to": [""]
      |  }
      |  """.stripMargin.parseJson)
    thrown1.getMessage should include("the value of \"to\" can't be empty string")
  }

  @Test
  def parseToFieldOnUpdate(): Unit = {
    val thrown1 = the[DeserializationException] thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "to": [""]
      |  }
      |  """.stripMargin.parseJson)
    thrown1.getMessage should include("the value of \"to\" can't be empty string")
  }

  @Test
  def parseJmxPortFieldOnUpdate(): Unit = {
    val thrown1 = the[DeserializationException] thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "jmxPort": 0
      |  }
      |  """.stripMargin.parseJson)
    thrown1.getMessage should include("the connection port must be [1024, 65535)")

    val thrown2 = the[DeserializationException] thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "jmxPort": -9
      |  }
      |  """.stripMargin.parseJson)
    thrown2.getMessage should include("the connection port must be [1024, 65535), but actual port is \"-9\"")

    val thrown3 = the[DeserializationException] thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "jmxPort": 99999
      |  }
      |  """.stripMargin.parseJson)
    thrown3.getMessage should include("the connection port must be [1024, 65535)")
  }

  @Test
  def parseInstancesFieldOnUpdate(): Unit = {
    an[DeserializationException] should be thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "instances": 0
      |  }
      |  """.stripMargin.parseJson)

    val thrown = the[DeserializationException] thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "instances": -9
      |  }
      |  """.stripMargin.parseJson)
    thrown.getMessage should include("the \"-9\" of \"instances\" can't be either negative or zero!!!")
  }

  @Test
  def parseNodeNamesFieldOnCreation(): Unit = {
    val thrown1 = the[DeserializationException] thrownBy StreamApi.STREAM_CREATION_JSON_FORMAT.read(s"""
      |  {
      |    "nodeNames": ""
      |  }
      |  """.stripMargin.parseJson)
    thrown1.getMessage should include("the value of \"nodeNames\" can't be empty string")
  }

  @Test
  def parseNodeNamesFieldOnUpdate(): Unit = {
    val thrown1 = the[DeserializationException] thrownBy StreamApi.STREAM_UPDATE_JSON_FORMAT.read(s"""
      |  {
      |    "nodeNames": ""
      |  }
      |  """.stripMargin.parseJson)
    thrown1.getMessage should include("the value of \"nodeNames\" can't be empty string")
  }

  @Test
  def ignoreNameOnCreation(): Unit =
    accessRequest.jarKey(ObjectKey.of("1", "b")).creation.name.length should not be 0

  @Test
  def groupShouldAppearInResponse(): Unit = {
    val name = CommonUtils.randomString()
    val res = StreamApi.STREAM_CLUSTER_INFO_JSON_FORMAT.write(
      StreamClusterInfo(
        settings = Map(
          StreamDefUtils.NAME_DEFINITION.key() -> JsString(name),
          StreamDefUtils.IMAGE_NAME_DEFINITION.key() -> JsString("imageName"),
          StreamDefUtils.INSTANCES_DEFINITION.key() -> JsNumber(1),
          StreamDefUtils.JAR_KEY_DEFINITION.key() -> ObjectKey.of("group", "name").toJson,
          StreamDefUtils.FROM_TOPIC_KEYS_DEFINITION.key() -> JsString("aa"),
          StreamDefUtils.TO_TOPIC_KEYS_DEFINITION.key() -> JsString("bb"),
          StreamDefUtils.JMX_PORT_DEFINITION.key() -> JsNumber(0),
          StreamDefUtils.TAGS_DEFINITION.key() -> JsObject(Map("bar" -> JsString("foo"), "he" -> JsNumber(1)))
        ),
        definition = Some(Definition("className", Seq(SettingDef.builder().key("key").group("group").build()))),
        nodeNames = Set("node1"),
        deadNodes = Set.empty,
        state = None,
        error = None,
        metrics = Metrics(Seq.empty),
        lastModified = CommonUtils.current()
      ))
    // serialize to json should see the object key (group, name)
    res.asJsObject.fields(NAME_KEY).convertTo[String] shouldBe name
    res.asJsObject.fields(GROUP_KEY).convertTo[String] shouldBe StreamApi.GROUP_DEFAULT

    // // deserialize to info should see the object key (group, name)
    val data = StreamApi.STREAM_CLUSTER_INFO_JSON_FORMAT.read(res)
    data.name shouldBe name
    data.group shouldBe StreamApi.GROUP_DEFAULT
  }

  @Test
  def testTagsOnUpdate(): Unit = accessRequest.update.tags shouldBe None

  @Test
  def testOverwriteSettings(): Unit = {
    val fromTopicKey = topicKey(CommonUtils.randomString())
    val toTopicKey = topicKey(CommonUtils.randomString())
    val r1 =
      accessRequest.fromTopicKey(fromTopicKey).toTopicKey(toTopicKey).jarKey(ObjectKey.of("group", "name")).creation

    val r2 = accessRequest
      .fromTopicKey(fromTopicKey)
      .toTopicKey(toTopicKey)
      .jarKey(ObjectKey.of("group", "name"))
      .settings(Map("name" -> JsString("fake")))
      .creation

    r1.to shouldBe r2.to
    r1.from shouldBe r2.from
    // settings will overwrite default value
    r1.name should not be r2.name
  }

  @Test
  def testBrokerClusterName(): Unit = {
    val bkName = CommonUtils.randomString()
    val r1 = accessRequest.brokerClusterName(bkName).creation
    r1.brokerClusterName.get shouldBe bkName
  }
}
