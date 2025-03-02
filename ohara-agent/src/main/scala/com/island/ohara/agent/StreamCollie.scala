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

package com.island.ohara.agent

import java.net.URL
import java.util.Objects

import com.island.ohara.agent.docker.ContainerState
import com.island.ohara.client.configurator.v0.ContainerApi.{ContainerInfo, PortMapping, PortPair}
import com.island.ohara.client.configurator.v0.FileInfoApi.{FILE_INFO_JSON_FORMAT, FileInfo}
import com.island.ohara.client.configurator.v0.MetricsApi.Metrics
import com.island.ohara.client.configurator.v0.NodeApi.Node
import com.island.ohara.client.configurator.v0.StreamApi.StreamClusterInfo
import com.island.ohara.client.configurator.v0.{ClusterInfo, Definition, StreamApi}
import com.island.ohara.common.annotations.Optional
import com.island.ohara.common.setting.{ObjectKey, TopicKey}
import com.island.ohara.common.util.CommonUtils
import com.island.ohara.metrics.BeanChannel
import com.island.ohara.metrics.basic.CounterMBean
import com.island.ohara.streams.config.StreamDefUtils
import com.typesafe.scalalogging.Logger
import spray.json._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/**
  * An interface of controlling stream cluster.
  * It isolates the implementation of container manager from Configurator.
  */
trait StreamCollie extends Collie[StreamClusterInfo] {
  private[this] val LOG = Logger(classOf[StreamCollie])

  override def creator: StreamCollie.ClusterCreator =
    (clusterName, nodeNames, imageName, brokerClusterName, jarInfo, jmxPort, _, _, settings, executionContext) => {
      implicit val exec: ExecutionContext = executionContext
      clusters().flatMap(clusters => {
        if (clusters.keys.filter(_.isInstanceOf[StreamClusterInfo]).exists(_.name == clusterName))
          Future.failed(new IllegalArgumentException(s"stream cluster:$clusterName exists!"))
        else
          nodeCollie
            .nodes(nodeNames)
            .map(_.map(node => node -> Collie.format(prefixKey, clusterName, serviceName)).toMap)
            .flatMap(nodes => brokerContainers(brokerClusterName).map(cs => (nodes, cs)))
            .flatMap {
              case (nodes, brokerContainers) =>
                val route = resolveHostNames(
                  (nodes.map(_._1.hostname)
                    ++ brokerContainers.map(_.nodeName)
                  // make sure the streamApp can connect to configurator
                    ++ Seq(jarInfo.url.getHost)).toSet)
                // ssh connection is slow so we submit request by multi-thread
                Future
                  .sequence(nodes.map {
                    case (node, containerName) =>
                      val containerInfo = ContainerInfo(
                        nodeName = node.name,
                        id = Collie.UNKNOWN,
                        imageName = imageName,
                        created = Collie.UNKNOWN,
                        state = Collie.UNKNOWN,
                        kind = Collie.UNKNOWN,
                        name = containerName,
                        size = Collie.UNKNOWN,
                        portMappings = Seq(
                          PortMapping(
                            hostIp = Collie.UNKNOWN,
                            portPairs = Seq(
                              PortPair(
                                hostPort = jmxPort,
                                containerPort = jmxPort
                              )
                            )
                          )
                        ),
                        environments = settings.map {
                          case (k, v) =>
                            k -> (v match {
                              // the string in json representation has quote in the beginning and end.
                              // we don't like the quotes since it obstruct us to cast value to pure string.
                              case JsString(s) => s
                              // save the json string for all settings
                              // StreamDefUtils offers the helper method to turn them back.
                              case _ => CommonUtils.toEnvString(v.toString)
                            })
                        }
                        // we convert all settings to specific string in order to fetch all settings from
                        // container env quickly. Also, the specific string enable us to pick up the "true" settings
                        // from envs since there are many system-defined settings in container envs.
                          + toEnvString(settings),
                        // we should set the hostname to container name in order to avoid duplicate name with other containers
                        hostname = containerName
                      )
                      doCreator(executionContext, containerName, containerInfo, node, route, jmxPort, jarInfo).map(_ =>
                        Some(containerInfo))
                  })
                  .map(_.flatten.toSeq)
                  .flatMap(cs => loadDefinition(jarInfo.url).map(definition => cs -> definition))
                  .map {
                    case (successfulContainers, definition) =>
                      if (successfulContainers.isEmpty)
                        throw new IllegalArgumentException(s"failed to create $clusterName on $serviceName")
                      val clusterInfo = StreamClusterInfo(
                        // the other arguments (clusterName, imageName and so on) are extracted from settings so
                        // we don't need to add them back to settings.
                        settings = settings,
                        // TODO: cluster info
                        definition = definition,
                        nodeNames = successfulContainers.map(_.nodeName).toSet,
                        deadNodes = Set.empty,
                        metrics = Metrics(Seq.empty),
                        // creating cluster success but still need to update state by another request
                        state = None,
                        error = None,
                        lastModified = CommonUtils.current()
                      )
                      postCreateCluster(clusterInfo, successfulContainers)
                      clusterInfo
                  }
            }
      })
    }

  /**
    * Get all counter beans from cluster
    * @param cluster cluster
    * @return counter beans
    */
  def counters(cluster: StreamClusterInfo): Seq[CounterMBean] = cluster.nodeNames.flatMap { node =>
    BeanChannel.builder().hostname(node).port(cluster.jmxPort).build().counterMBeans().asScala
  }.toSeq

  /**
    *
    * @return async future containing configs
    */
  /**
    * Get all '''SettingDef''' of current streamApp.
    * Note: This method intends to call a method that invokes the reflection method of streamApp.
    *
    * @param jarUrl the custom streamApp jar url
    * @return stream definition
    */
  //TODO : this workaround should be removed and use a new API instead in #2191...by Sam
  def loadDefinition(jarUrl: URL)(implicit executionContext: ExecutionContext): Future[Option[Definition]] =
    Future {
      import sys.process._
      val classpath = System.getProperty("java.class.path")
      val command =
        s"""java -cp "$classpath" ${StreamCollie.MAIN_ENTRY} ${StreamDefUtils.JAR_URL_DEFINITION
          .key()}=${jarUrl.toString} ${StreamCollie.CONFIG_KEY}"""
      val result = command.!!
      val className = result.split("=")(0)
      Some(Definition(className, StreamDefUtils.ofJson(result.split("=")(1)).values.asScala))
    }.recover {
      case e: RuntimeException =>
        // We cannot parse the provided jar, return nothing and log it
        LOG.warn(s"the provided jar url: [$jarUrl] could not be parsed, return default settings only.", e)
        None
    }

  private[agent] def toStreamCluster(clusterName: String, containers: Seq[ContainerInfo])(
    implicit executionContext: ExecutionContext): Future[StreamClusterInfo] = {
    // get the first running container, or first non-running container if not found
    val first = containers.find(_.state == ContainerState.RUNNING.name).getOrElse(containers.head)
    val settings = seekSettings(first.environments)
    val jarInfo = FILE_INFO_JSON_FORMAT.read(settings(StreamDefUtils.JAR_INFO_DEFINITION.key()))
    loadDefinition(jarInfo.url).map { definition =>
      StreamClusterInfo(
        settings = settings,
        // we don't care the runtime definitions; it's saved to store already
        definition = definition,
        nodeNames = containers.map(_.nodeName).toSet,
        // Currently, docker and k8s has same naming rule for "Running",
        // it is ok that we use the containerState.RUNNING here.
        deadNodes = containers.filterNot(_.state == ContainerState.RUNNING.name).map(_.nodeName).toSet,
        metrics = Metrics(Seq.empty),
        state = toClusterState(containers).map(_.name),
        error = None,
        lastModified = CommonUtils.current()
      )
    }
  }

  /**
    * Define nodeCollie by different environment
    * @return
    */
  protected def nodeCollie: NodeCollie

  /**
    * Define prefixKey by different environment
    * @return prefix key
    */
  protected def prefixKey: String

  override val serviceName: String = StreamApi.STREAM_SERVICE_NAME

  protected def doCreator(executionContext: ExecutionContext,
                          containerName: String,
                          containerInfo: ContainerInfo,
                          node: Node,
                          route: Map[String, String],
                          jmxPort: Int,
                          jarInfo: FileInfo): Future[Unit]

  protected def postCreateCluster(clusterInfo: ClusterInfo, successfulContainers: Seq[ContainerInfo]): Unit = {
    //Default do nothing
  }

  /**
    * get the containers for specific broker cluster. This method is used to update the route.
    * @param clusterName name of broker cluster
    * @param executionContext thread pool
    * @return containers
    */
  protected def brokerContainers(clusterName: String)(
    implicit executionContext: ExecutionContext): Future[Seq[ContainerInfo]]
}

object StreamCollie {
  trait ClusterCreator extends Collie.ClusterCreator[StreamClusterInfo] {
    private[this] val settings: mutable.Map[String, JsValue] = mutable.Map[String, JsValue]()
    override protected def doCopy(clusterInfo: StreamClusterInfo): Unit = this.settings ++= clusterInfo.settings

    /**
      * Stream collie overrides this method in order to put the same config to settings.
      * @param clusterName cluster name
      * @return this creator
      */
    override def clusterName(clusterName: String): ClusterCreator.this.type = {
      super.clusterName(clusterName)
      settings += StreamDefUtils.NAME_DEFINITION.key() -> JsString(clusterName)
      this
    }

    /**
      * Stream collie overrides this method in order to put the same config to settings.
      * @param nodeNames nodes' name
      * @return cluster description
      */
    override def nodeNames(nodeNames: Set[String]): ClusterCreator.this.type = {
      super.nodeNames(nodeNames)
      settings += StreamDefUtils.NODE_NAMES_DEFINITION.key() -> JsArray(nodeNames.map(JsString(_)).toVector)
      this
    }

    /**
      * Stream collie overrides this method in order to put the same config to settings.
      * @param imageName image name
      * @return this creator
      */
    override def imageName(imageName: String): ClusterCreator.this.type = {
      super.imageName(imageName)
      settings += StreamDefUtils.IMAGE_NAME_DEFINITION.key() -> JsString(imageName)
      this
    }

    /**
      * set the jar url for the streamApp running
      *
      * @param jarInfo jar info
      * @return this creator
      */
    def jarInfo(jarInfo: FileInfo): ClusterCreator = {
      settings += StreamDefUtils.JAR_KEY_DEFINITION.key() -> ObjectKey.toJsonString(jarInfo.key).parseJson
      settings += StreamDefUtils.JAR_INFO_DEFINITION.key() -> FILE_INFO_JSON_FORMAT.write(jarInfo)
      this
    }

    /**
      * add a key-value data map for container
      *
      * @param key data key
      * @param value data value
      * @return this creator
      */
    @Optional("default is empty map")
    def setting(key: String, value: JsValue): ClusterCreator = settings(Map(key -> value))

    /**
      * add the key-value data map for container
      *
      * @param settings data settings
      * @return this creator
      */
    @Optional("default is empty map")
    def settings(settings: Map[String, JsValue]): ClusterCreator = {
      this.settings ++= Objects.requireNonNull(settings)
      this
    }

    def brokerClusterName(brokerClusterName: String): ClusterCreator = {
      settings += StreamDefUtils.BROKER_CLUSTER_NAME_DEFINITION.key() -> JsString(
        CommonUtils.requireNonEmpty(brokerClusterName))
      this
    }

    /**
      * get the value, which is mapped to input key, from settings. If the key is not associated, NoSuchElementException will
      * be thrown.
      * @param key key
      * @param f marshalling function
      * @tparam T type
      * @return value
      */
    private[this] def get[T](key: String, f: JsValue => T): T =
      settings.get(key).map(f).getOrElse(throw new NoSuchElementException(s"$key is required"))

    import com.island.ohara.client.configurator.v0.TOPIC_KEY_FORMAT
    import com.island.ohara.client.configurator.v0.FileInfoApi.FILE_INFO_JSON_FORMAT
    import spray.json.DefaultJsonProtocol._
    override def create(): Future[StreamClusterInfo] = doCreate(
      clusterName = get(StreamDefUtils.NAME_DEFINITION.key(), _.convertTo[String]),
      nodeNames = get(StreamDefUtils.NODE_NAMES_DEFINITION.key(), _.convertTo[Vector[String]].toSet),
      imageName = get(StreamDefUtils.IMAGE_NAME_DEFINITION.key(), _.convertTo[String]),
      brokerClusterName = get(StreamDefUtils.BROKER_CLUSTER_NAME_DEFINITION.key(), _.convertTo[String]),
      jarInfo = get(StreamDefUtils.JAR_INFO_DEFINITION.key(), _.convertTo[FileInfo]),
      jmxPort = get(StreamDefUtils.JMX_PORT_DEFINITION.key(), _.convertTo[Int]),
      fromTopics = CommonUtils
        .requireNonEmpty(
          get(StreamDefUtils.FROM_TOPIC_KEYS_DEFINITION.key(), _.convertTo[Set[TopicKey]]).asJava,
          () => s"${StreamDefUtils.FROM_TOPIC_KEYS_DEFINITION.key()} can't be associated to empty array"
        )
        .asScala
        .toSet,
      toTopics = CommonUtils
        .requireNonEmpty(
          get(StreamDefUtils.TO_TOPIC_KEYS_DEFINITION.key(), _.convertTo[Set[TopicKey]]).asJava,
          () => s"${StreamDefUtils.TO_TOPIC_KEYS_DEFINITION.key()} can't be associated to empty array"
        )
        .asScala
        .toSet,
      settings = Objects.requireNonNull(settings.toMap),
      executionContext = Objects.requireNonNull(executionContext)
    )

    override protected def checkClusterName(clusterName: String): String = {
      StreamApi.STREAM_CREATION_JSON_FORMAT.check("name", JsString(clusterName))
      clusterName
    }

    protected def doCreate(clusterName: String,
                           nodeNames: Set[String],
                           imageName: String,
                           brokerClusterName: String,
                           jarInfo: FileInfo,
                           jmxPort: Int,
                           fromTopics: Set[TopicKey],
                           toTopics: Set[TopicKey],
                           settings: Map[String, JsValue],
                           executionContext: ExecutionContext): Future[StreamClusterInfo]
  }

  /**
    * the only entry for ohara streamApp
    */
  val MAIN_ENTRY = "com.island.ohara.streams.StreamApp"

  /**
    * the flag to get/set streamApp configs for container
    */
  private[agent] val CONFIG_KEY = "CONFIG_KEY"

  /**
    * generate the jmx required properties
    *
    * @param hostname the hostname used by jmx remote
    * @param port the port used by jmx remote
    * @return jmx properties
    */
  private[agent] def formatJMXProperties(hostname: String, port: Int): Seq[String] = {
    Seq(
      "-Dcom.sun.management.jmxremote",
      "-Dcom.sun.management.jmxremote.authenticate=false",
      "-Dcom.sun.management.jmxremote.ssl=false",
      s"-Dcom.sun.management.jmxremote.port=$port",
      s"-Dcom.sun.management.jmxremote.rmi.port=$port",
      s"-Djava.rmi.server.hostname=$hostname"
    )
  }
}
