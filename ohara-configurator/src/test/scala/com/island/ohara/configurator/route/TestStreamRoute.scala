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

package com.island.ohara.configurator.route

import com.island.ohara.agent.docker.ContainerState
import com.island.ohara.client.configurator.v0._
import com.island.ohara.common.rule.SmallTest
import com.island.ohara.common.setting.{ObjectKey, TopicKey}
import com.island.ohara.common.util.{CommonUtils, Releasable}
import com.island.ohara.configurator.Configurator
import com.island.ohara.streams.config.StreamDefUtils
import org.junit.{After, Test}
import org.scalatest.Matchers
import spray.json.{JsNumber, JsString}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class TestStreamRoute extends SmallTest with Matchers {

  // create all fake cluster
  private[this] val configurator = Configurator.builder.fake().build()
  private[this] val topicApi = TopicApi.access.hostname(configurator.hostname).port(configurator.port)
  private[this] val zkApi = ZookeeperApi.access.hostname(configurator.hostname).port(configurator.port)
  private[this] val bkApi = BrokerApi.access.hostname(configurator.hostname).port(configurator.port)
  private[this] val wkApi = WorkerApi.access.hostname(configurator.hostname).port(configurator.port)

  private[this] val accessJar = FileInfoApi.access.hostname(configurator.hostname).port(configurator.port)
  private[this] val accessStream = StreamApi.access.hostname(configurator.hostname).port(configurator.port)

  private[this] def result[T](f: Future[T]): T = Await.result(f, 20 seconds)
  private[this] def topicKey(): TopicKey = topicKey(CommonUtils.randomString())
  private[this] def topicKey(name: String): TopicKey = TopicKey.of(TopicApi.GROUP_DEFAULT, name)

  @Test
  def testStreamAppPropertyPage(): Unit = {
    val file = CommonUtils.createTempJar("empty_")

    val jar = result(accessJar.request.file(file).upload())

    // create default property
    val name = CommonUtils.randomString(10)
    val defaultProps = result(
      accessStream.request.name(name).jarKey(ObjectKey.of(jar.group, jar.name)).create()
    )
    defaultProps.jarKey shouldBe jar.key
    // same name property cannot create again
    an[IllegalArgumentException] should be thrownBy result(
      accessStream.request.name(name).jarKey(ObjectKey.of(jar.group, "newJar")).create())

    // get new streamApp property
    val res1 = result(accessStream.get(defaultProps.name))
    // check initial values
    res1.name shouldBe defaultProps.name
    res1.name shouldBe name
    res1.from shouldBe Set.empty
    res1.to shouldBe Set.empty
    res1.instances shouldBe 1

    // update partial properties
    val to = topicKey()
    val res2 = result(accessStream.request.name(defaultProps.name).toTopicKey(to).instances(10).update())
    res2.name shouldBe name
    res2.jarKey shouldBe jar.key
    res2.from shouldBe Set.empty
    res2.to shouldBe Set(to)
    res2.instances shouldBe 10

    // create property with some user defined properties
    val userAppId = CommonUtils.randomString(5)
    val to2 = topicKey()
    val userProps = result(
      accessStream.request
        .name(userAppId)
        .jarKey(ObjectKey.of(jar.group, jar.name))
        .toTopicKey(to2)
        .instances(99)
        .create())
    userProps.name shouldBe userAppId
    userProps.to shouldBe Set(to2)
    userProps.instances shouldBe 99

    // we create two properties, the list size should be 2
    result(accessStream.list()).size shouldBe 2

    // update properties
    val from3 = topicKey()
    val to3 = topicKey()
    val res3 = result(accessStream.request.name(userAppId).fromTopicKey(from3).toTopicKey(to3).instances(1).update())
    res3.name shouldBe userAppId
    res3.from shouldBe Set(from3)
    res3.to shouldBe Set(to3)
    res3.instances shouldBe 1

    // delete properties
    result(accessStream.delete(defaultProps.name))

    // after delete, the streamApp should not exist
    an[IllegalArgumentException] should be thrownBy result(accessStream.get(defaultProps.name))

    // delete property should not delete actual jar
    result(accessJar.list()).size shouldBe 1

    file.deleteOnExit()
  }

  @Test
  def testStreamAppAction(): Unit = {
    val file = CommonUtils.createTempJar("empty_")
    val instances = 3
    val streamAppName = CommonUtils.randomString(5)
    // we should have only one worker cluster
    val wkName = result(wkApi.list).head.name
    val from = topicKey()
    val to = topicKey()

    // upload jar
    val streamJar = result(accessJar.request.group(wkName).file(file).upload())

    // create property
    val props = result(
      accessStream.request.name(streamAppName).jarKey(ObjectKey.of(streamJar.group, streamJar.name)).create())

    // update properties
    result(accessStream.request.name(streamAppName).fromTopicKey(from).toTopicKey(to).instances(instances).update())

    // run topics
    result(topicApi.request.key(from).create().flatMap(info => topicApi.start(info.key)))
    result(topicApi.request.key(to).create().flatMap(info => topicApi.start(info.key)))

    result(accessStream.start(props.name))
    val res1 = result(accessStream.get(props.name))
    res1.name shouldBe props.name
    res1.name shouldBe streamAppName
    res1.from shouldBe Set(from)
    res1.to shouldBe Set(to)
    res1.jarKey.name shouldBe streamJar.name
    res1.instances shouldBe instances
    res1.state.get shouldBe ContainerState.RUNNING.name

    // get again
    val running = result(accessStream.get(props.name))
    running.state.get shouldBe ContainerState.RUNNING.name
    running.error.isEmpty shouldBe true

    // get the stream clusters information by clusterCache
    val cluster = result(configurator.clusterCollie.streamCollie.clusters())
    cluster.size shouldBe 1
    // jmx port should be positive
    cluster.head._1.jmxPort should not be 0

    // start the same streamApp cluster will get the previous stream cluster
    result(accessStream.start(props.name))
    val prevRes = result(accessStream.get(props.name))
    prevRes.name shouldBe props.name
    prevRes.name shouldBe streamAppName
    prevRes.state.get shouldBe ContainerState.RUNNING.name
    prevRes.error.isDefined shouldBe false

    // running streamApp cannot update state
    an[RuntimeException] should be thrownBy result(accessStream.request.name(streamAppName).instances(10).update())

    // running streamApp cannot delete
    an[RuntimeException] should be thrownBy result(accessStream.delete(props.name))

    result(accessStream.get(props.name)).state should not be None
    result(accessStream.stop(props.name))
    result(accessStream.get(props.name)).state shouldBe None

    // get the stream clusters information again, should be zero
    result(configurator.clusterCollie.streamCollie.clusters()).size shouldBe 0

    // stop the same streamApp cluster will only return the previous object
    result(accessStream.stop(props.name))
    result(accessStream.get(props.name)).state shouldBe None

    // get property will get the latest state (streamApp not exist)
    val latest = result(accessStream.get(props.name))
    latest.state shouldBe None
    latest.error.isDefined shouldBe false

    // after stop, streamApp can be deleted
    result(accessStream.delete(props.name))

    // after delete, streamApp should not exist
    an[IllegalArgumentException] should be thrownBy result(accessStream.get(props.name))

    file.deleteOnExit()
  }

  @Test
  def testStreamAppPropertyPageFailCases(): Unit = {
    val streamAppName = CommonUtils.requireNumberAndCharset(CommonUtils.randomString(5))
    //operations on non-exists property should be fail
    an[NullPointerException] should be thrownBy result(accessStream.request.name("appId").jarKey(null).update())
    an[IllegalArgumentException] should be thrownBy result(accessStream.get("non_exist_id"))

    an[IllegalArgumentException] should be thrownBy result(
      accessStream.request.name(streamAppName).fromTopicKeys(Set.empty).update())

    an[IllegalArgumentException] should be thrownBy result(
      accessStream.request.name(streamAppName).toTopicKeys(Set.empty).update())

    an[IllegalArgumentException] should be thrownBy result(
      accessStream.request.name(streamAppName).instances(0).update())

    an[IllegalArgumentException] should be thrownBy result(
      accessStream.request.name(streamAppName).instances(-99).update())

    // delete non-exists object should do nothing
    result(accessStream.delete(CommonUtils.randomString(5)))
  }

  @Test
  def testStreamAppActionPageFailCases(): Unit = {
    val file = CommonUtils.createTempJar("empty_")
    val streamAppName = CommonUtils.randomString(5)
    val wkName = result(wkApi.list()).head.name
    val from = topicKey()
    val to = topicKey()

    // upload jar
    val streamJar = result(accessJar.request.group(wkName).file(file).upload())

    // start action will check all the required parameters
    result(accessStream.request.name(streamAppName).jarKey(ObjectKey.of(streamJar.group, streamJar.name)).create())
    an[IllegalArgumentException] should be thrownBy result(accessStream.start(streamAppName))

    result(accessStream.request.name(streamAppName).fromTopicKey(from).update())
    an[IllegalArgumentException] should be thrownBy result(accessStream.start(streamAppName))

    result(accessStream.request.name(streamAppName).toTopicKey(to).update())

    // non-exist topics in broker will cause running fail
    an[IllegalArgumentException] should be thrownBy result(accessStream.start(streamAppName))

    // run topics
    result(topicApi.request.key(from).create().flatMap(info => topicApi.start(info.key)))
    result(topicApi.request.key(to).create().flatMap(info => topicApi.start(info.key)))

    // after all required parameters are set, it is ok to run
    result(accessStream.start(streamAppName))

    file.deleteOnExit()
  }

  @Test
  def duplicateStopStream(): Unit =
    (0 to 10).foreach(_ => result(accessStream.stop(CommonUtils.randomString())))

  @Test
  def duplicateDeleteStreamProperty(): Unit =
    (0 to 10).foreach(_ => result(accessStream.delete(CommonUtils.randomString(5))))

  @Test
  def updateTags(): Unit = {
    val file = CommonUtils.createTempJar("empty_")

    val jar = result(accessJar.request.file(file).upload())

    val tags = Map(
      CommonUtils.randomString(10) -> JsString(CommonUtils.randomString(10)),
      CommonUtils.randomString(10) -> JsNumber(CommonUtils.randomInteger())
    )
    val streamDesc = result(
      accessStream.request
        .name(CommonUtils.randomString(10))
        .jarKey(ObjectKey.of(jar.group, jar.name))
        .tags(tags)
        .create())
    streamDesc.tags shouldBe tags
    streamDesc.jarKey shouldBe ObjectKey.of(jar.group, jar.name)

    val tags2 = Map(
      CommonUtils.randomString(10) -> JsString(CommonUtils.randomString(10)),
      CommonUtils.randomString(10) -> JsNumber(CommonUtils.randomInteger())
    )
    val streamDesc2 = result(accessStream.request.name(streamDesc.name).tags(tags2).update())
    streamDesc2.tags shouldBe tags2
    streamDesc2.jarKey shouldBe ObjectKey.of(jar.group, jar.name)

    val streamDesc3 = result(accessStream.request.name(streamDesc.name).update())
    streamDesc3.tags shouldBe tags2

    val streamDesc4 = result(accessStream.request.name(streamDesc.name).tags(Map.empty).update())
    streamDesc4.tags shouldBe Map.empty
  }

  @Test
  def testUpdateTopics(): Unit = {
    val file = CommonUtils.createTempJar("empty_")
    val jar = result(accessJar.request.file(file).upload())
    val streamDesc = result(accessStream.request.jarKey(ObjectKey.of(jar.group, jar.name)).create())
    streamDesc.from shouldBe Set.empty
    streamDesc.to shouldBe Set.empty
    val from = topicKey()
    // update from topic
    result(accessStream.request.name(streamDesc.name).fromTopicKey(from).update()).from shouldBe Set(from)
    // update from topic to empty
    result(accessStream.request.name(streamDesc.name).fromTopicKeys(Set.empty).update()).from shouldBe Set.empty
    // to topic should still be empty
    result(accessStream.get(streamDesc.name)).to shouldBe Set.empty
  }

  @Test
  def testSettingDefault(): Unit = {
    val file = CommonUtils.createTempJar("empty_")
    val jar = result(accessJar.request.file(file).upload())
    val key = CommonUtils.randomString()
    val value = JsString(CommonUtils.randomString())
    val streamDesc = result(accessStream.request.jarKey(ObjectKey.of(jar.group, jar.name)).setting(key, value).create())
    // the url is not illegal
    streamDesc.definition should not be None
    streamDesc.settings(key) shouldBe value
  }

  @Test
  def createStream(): Unit = {
    import spray.json._
    val name = CommonUtils.randomString(5)
    val file = CommonUtils.createTempJar("empty_")
    val jar = result(accessJar.request.file(file).upload())
    val key = CommonUtils.randomString()
    val value = JsString(CommonUtils.randomString())
    val streamDesc = result(
      configurator.clusterCollie.streamCollie.creator
        .jarInfo(jar)
        .nodeName(CommonUtils.randomString(5))
        .imageName(CommonUtils.randomString())
        .clusterName(name)
        .brokerClusterName(CommonUtils.randomString())
        .setting(key, value)
        .setting(StreamDefUtils.JMX_PORT_DEFINITION.key(), JsNumber(123))
        .setting(StreamDefUtils.FROM_TOPIC_KEYS_DEFINITION.key(), JsArray(TopicKey.toJsonString(topicKey()).parseJson))
        .setting(StreamDefUtils.TO_TOPIC_KEYS_DEFINITION.key(), JsArray(TopicKey.toJsonString(topicKey()).parseJson))
        .create())
    streamDesc.name shouldBe name
    streamDesc.settings(key) shouldBe value
  }

  @Test
  def testOnlyAcceptOneTopic(): Unit = {
    // we should have only one worker cluster
    val wkName = result(wkApi.list).head.name

    val file = CommonUtils.createTempJar("empty_")
    val jar = result(accessJar.request.group(wkName).file(file).upload())
    val streamDesc = result(accessStream.request.jarKey(ObjectKey.of(jar.group, jar.name)).create())
    streamDesc.from shouldBe Set.empty
    streamDesc.to shouldBe Set.empty

    // Empty topic is not allow
    an[IllegalArgumentException] should be thrownBy result(accessStream.start(streamDesc.name))

    // multiple topics are not allow by now
    val from = topicKey()
    val to = topicKey()
    // run topics
    result(topicApi.request.key(from).create().flatMap(info => topicApi.start(info.key)))
    result(topicApi.request.key(to).create().flatMap(info => topicApi.start(info.key)))
    val thrown1 = the[IllegalArgumentException] thrownBy result(
      accessStream.request
        .name(streamDesc.name)
        .fromTopicKey(from)
        .toTopicKeys(Set(from, to))
        .update()
        .flatMap(info => accessStream.start(info.name)))
    // "to" field is used multiple topics which is not allow for current version
    thrown1.getMessage should include("We don't allow multiple topics of to field")
  }

  /**
    * stream route seeks two place to find the based cluste
    * 1) the key StreamDefinitions.BROKER_CLUSTER_NAME_DEFINITION.key()
    * 2) the group of jar (mapped to worker cluster) (this is deprecated) (see https://github.com/oharastream/ohara/issues/2151)
    */
  @Test
  def testBrokerClusterName(): Unit = {
    val nodeNames = result(bkApi.list()).head.nodeNames
    val zk = result(zkApi.request.name(CommonUtils.randomString(5)).nodeNames(nodeNames).create())
    val bk = result(
      bkApi.request.name(CommonUtils.randomString(5)).nodeNames(nodeNames).zookeeperClusterName(zk.name).create())
    result(bkApi.start(bk.name))
    val from0 = result(topicApi.request.brokerClusterName(bk.name).create())
    result(topicApi.start(from0.key))
    val to0 = result(topicApi.request.brokerClusterName(bk.name).create())
    result(topicApi.start(to0.key))
    result(bkApi.start(bk.name))
    val wkName = result(wkApi.list).head.name

    val file = CommonUtils.createTempJar("empty_")
    val jar = result(accessJar.request.group(wkName).file(file).upload())
    val streamDesc = result(
      accessStream.request
        .brokerClusterName(bk.name)
        .jarKey(ObjectKey.of(jar.group, jar.name))
        .fromTopicKey(from0.key)
        .toTopicKey(to0.key)
        .create())
    streamDesc.brokerClusterName shouldBe bk.name
    result(accessStream.start(streamDesc.name))

    // fail to update a running streamapp
    an[IllegalArgumentException] should be thrownBy result(accessStream.request.name(streamDesc.name).update())
    result(accessStream.stop(streamDesc.name))
  }

  // TODO: this is for deprecated APIs ... fix it by https://github.com/oharastream/ohara/issues/2151
  @Test
  def testWorkerClusterNameInJarGroup(): Unit = {
    val wkName = result(wkApi.list()).head.name
    val file = CommonUtils.createTempJar("empty_")
    val jar = result(accessJar.request.file(file).group(wkName).upload())
    val name = CommonUtils.randomString(10)
    val defaultProps = result(accessStream.request.name(name).jarKey(ObjectKey.of(jar.group, jar.name)).create())
    defaultProps.jarKey shouldBe jar.key
    defaultProps.brokerClusterName shouldBe result(wkApi.get(wkName)).brokerClusterName
  }

  @After
  def tearDown(): Unit = Releasable.close(configurator)
}
