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

package com.island.ohara.it

import java.time.Duration
import java.util.concurrent.TimeUnit

import com.island.ohara.agent.NoSuchClusterException
import com.island.ohara.agent.docker.ContainerState
import com.island.ohara.client.configurator.v0.ClusterInfo
import com.island.ohara.client.configurator.v0.ContainerApi.ContainerInfo
import com.island.ohara.common.rule.OharaTest
import com.island.ohara.common.util.CommonUtils
import org.junit.Rule
import org.junit.rules.Timeout

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class IntegrationTest extends OharaTest {
  @Rule def globalTimeout: Timeout = new Timeout(12, TimeUnit.MINUTES)

  protected def result[T](f: Future[T]): T = IntegrationTest.result(f)

  protected def await(f: () => Boolean): Unit = IntegrationTest.await(f)

  /**
    * the creation of cluster is async so you need to wait the cluster to build.
    * @param clusters clusters
    * @param containers containers
    * @param name cluster name
    */
  protected def assertCluster(clusters: () => Seq[ClusterInfo],
                              containers: () => Seq[ContainerInfo],
                              name: String): Unit = await(() =>
    try {
      clusters().map(_.name).contains(name) &&
      // since we only get "active" containers, all containers belong to the cluster should be running.
      // Currently, both k8s and pure docker have the same context of "RUNNING".
      // It is ok to filter container via RUNNING state.
      containers().nonEmpty &&
      containers().map(_.state).forall(_.equals(ContainerState.RUNNING.name))
    } catch {
      // the collie impl throw exception if the cluster "does not" exist when calling "containers"
      case _: NoSuchClusterException => false
  })
}

object IntegrationTest {
  private[this] val TIMEOUT = 2 minutes

  def result[T](f: Future[T]): T = Await.result(f, TIMEOUT)

  def await(f: () => Boolean): Unit = CommonUtils.await(() => f(), Duration.ofSeconds(TIMEOUT.toSeconds))
}
