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

package com.island.ohara.configurator

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server
import akka.http.scaladsl.server.Directives._
import com.island.ohara.agent.{ClusterCollie, Collie, NodeCollie}
import com.island.ohara.client.configurator.Data
import com.island.ohara.client.configurator.v0.BrokerApi.BrokerClusterInfo
import com.island.ohara.client.configurator.v0.MetricsApi.Metrics
import com.island.ohara.client.configurator.v0.StreamApi.StreamClusterInfo
import com.island.ohara.client.configurator.v0.WorkerApi.WorkerClusterInfo
import com.island.ohara.client.configurator.v0.ZookeeperApi.ZookeeperClusterInfo
import com.island.ohara.client.configurator.v0.{
  ClusterCreationRequest,
  ClusterInfo,
  ClusterUpdateRequest,
  CreationRequest,
  ErrorApi,
  OharaJsonFormat
}
import com.island.ohara.common.annotations.VisibleForTesting
import com.island.ohara.common.setting.ObjectKey
import com.island.ohara.common.util.VersionUtils
import com.island.ohara.configurator.route.hook._
import com.island.ohara.configurator.store.{DataStore, MeterCache}
import spray.json.DefaultJsonProtocol._
import spray.json.{JsArray, JsString, RootJsonFormat}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.{ClassTag, classTag}

package object route {

  /**
    * generate the error message used to indicate that some fields are miss in the update request.
    * @param key key
    * @param fieldName name of field
    * @return error message
    */
  def errorMessage(key: ObjectKey, fieldName: String): String =
    s"$key does not exist so there is an new object will be created. Hence, you cannot ignore $fieldName"

  /** default we restrict the jar size to 50MB */
  private[route] val DEFAULT_FILE_SIZE_BYTES = 50 * 1024 * 1024L
  private[route] val NAME_KEY: String = com.island.ohara.client.configurator.v0.NAME_KEY
  private[route] val GROUP_KEY: String = com.island.ohara.client.configurator.v0.GROUP_KEY
  private[route] val CLUSTER_KEY: String = com.island.ohara.client.configurator.v0.CLUSTER_KEY
  private[route] val TAGS_KEY: String = com.island.ohara.client.configurator.v0.TAGS_KEY
  private[this] val FORCE_KEY: String = com.island.ohara.client.configurator.v0.FORCE_KEY
  @VisibleForTesting
  private[configurator] val START_COMMAND: String = com.island.ohara.client.configurator.v0.START_COMMAND
  @VisibleForTesting
  private[configurator] val STOP_COMMAND: String = com.island.ohara.client.configurator.v0.STOP_COMMAND
  private[this] val PAUSE_COMMAND: String = com.island.ohara.client.configurator.v0.PAUSE_COMMAND
  private[this] val RESUME_COMMAND: String = com.island.ohara.client.configurator.v0.RESUME_COMMAND

  /**
    * a route to custom CREATION and UPDATE resource. It offers default implementation to GET, LIST and DELETE.
    * The CREATION is routed to "POST  /$root"
    * The UPDATE is routed to "PUT /$root/$name"
    * The GET is routed to "GET /$root/$name"
    * The LIST is routed to "GET /$root"
    * The DELETE is routed to "DELETE /$root/$name"
    * @param root the prefix of URL
    * @param hookOfGroup used to generate the true group used by route
    * @param hookOfCreation custom action for CREATION. the name is either user-defined request or random string
    * @param hookOfUpdate custom action for UPDATE. the name from URL is must equal to name in payload
    * @param store data store
    * @param rm used to marshal request
    * @param rm2 used to marshal response
    * @param executionContext thread pool
    * @tparam Creation request type
    * @tparam Res response type
    * @return route
    */
  private[route] def route[Creation <: CreationRequest, Update, Res <: Data: ClassTag](
    root: String,
    hookOfGroup: HookOfGroup,
    hookOfCreation: HookOfCreation[Creation, Res],
    hookOfUpdate: HookOfUpdate[Creation, Update, Res])(implicit store: DataStore,
                                                       // normally, update request does not carry the name field,
                                                       // Hence, the check of name have to be executed by format of creation
                                                       // since it must have name field.
                                                       rm: OharaJsonFormat[Creation],
                                                       rm1: RootJsonFormat[Update],
                                                       rm2: RootJsonFormat[Res],
                                                       executionContext: ExecutionContext): server.Route =
    route(
      root = root,
      hookOfGroup = hookOfGroup,
      hookOfCreation = hookOfCreation,
      hookOfUpdate = hookOfUpdate,
      hookOfGet = (res: Res) => Future.successful(res),
      hookOfList = (res: Seq[Res]) => Future.successful(res),
      hookBeforeDelete = (_: ObjectKey) => Future.successful(Unit)
    )

  /**
    *  this is the basic route of all APIs to access ohara's data.
    *  It implements 1) get, 2) list, 3) delete, 4) add and 5) update function.
    * @param root path to root
    * @param hookOfGroup used to generate the true group used by route
    * @param hookOfCreation used to convert request to response for Add function
    * @param hookOfUpdate used to convert request to response for Update function
    * @param hookOfList used to convert response for List function
    * @param hookOfGet used to convert response for Get function
    * @param hookBeforeDelete used to do something before doing delete operation. For example, validate the name.
    * @param store data store
    * @param rm marshalling of creation
    * @param rm1 marshalling of update
    * @param rm2 marshalling of response
    * @param executionContext thread pool
    * @tparam Creation creation request
    * @tparam Update creation request
    * @tparam Res response
    * @return route
    */
  private[route] def route[Creation <: CreationRequest, Update, Res <: Data: ClassTag](
    root: String,
    hookOfGroup: HookOfGroup,
    hookOfCreation: HookOfCreation[Creation, Res],
    hookOfUpdate: HookOfUpdate[Creation, Update, Res],
    hookOfList: HookOfList[Res],
    hookOfGet: HookOfGet[Res],
    hookBeforeDelete: HookBeforeDelete)(implicit store: DataStore,
                                        // normally, update request does not carry the name field,
                                        // Hence, the check of name have to be executed by format of creation
                                        // since it must have name field.
                                        rm: OharaJsonFormat[Creation],
                                        rm1: RootJsonFormat[Update],
                                        rm2: RootJsonFormat[Res],
                                        executionContext: ExecutionContext): server.Route =
    pathPrefix(root) {
      pathEnd {
        post(entity(as[Creation]) { creation =>
          complete(hookOfCreation(creation).flatMap(res => store.addIfAbsent(res)))
        }) ~
          get(complete(store.values[Res]().flatMap(hookOfList(_))))
      } ~ path(Segment) { name =>
        parameter(GROUP_KEY ?) { groupOption =>
          val key =
            ObjectKey.of(rm.check(GROUP_KEY, JsString(hookOfGroup(groupOption))).value,
                         rm.check(NAME_KEY, JsString(name)).value)
          get(complete(store.value[Res](key).flatMap(hookOfGet(_)))) ~
            delete(complete(
              hookBeforeDelete(key).map(_ => key).flatMap(store.remove[Res](_).map(_ => StatusCodes.NoContent)))) ~
            put(
              entity(as[Update])(
                update =>
                  complete(
                    store
                      .get[Res](key)
                      .flatMap(previous => hookOfUpdate(key = key, update = update, previous = previous))
                      .flatMap(store.add))))
        }
      }
    }

  /**
    * this is the basic route of all APIs to access ohara's data.
    * It implements 1) get, 2) list, 3) delete, 4) add, 5) update, 6) start and 7) stop function.
    * The CREATION is routed to "POST  /$root"
    * The UPDATE is routed to "PUT /$root/$name"
    * The GET is routed to "GET /$root/$name"
    * The LIST is routed to "GET /$root"
    * The DELETE is routed to "DELETE /$root/$name"
    * The START is routed to "PUT /$root/$name/start"
    * The STOP is routed to "PUT /$root/$name/stop"
    * @param root path to root
    * @param hookOfGroup used to generate the true group used by route
    * @param hookOfCreation used to convert request to response for Add function
    * @param hookOfUpdate used to convert request to response for Update function
    * @param hookOfList used to convert response for List function
    * @param hookOfGet used to convert response for Get function
    * @param hookBeforeDelete used to do something before doing delete operation. For example, validate the name.
    * @param hookOfStart used to handle start command
    * @param hookOfStop used to handle stop command
    * @param store data store
    * @param rm marshalling of creation
    * @param rm1 marshalling of update
    * @param rm2 marshalling of response
    * @param executionContext thread pool
    * @tparam Creation creation request
    * @tparam Update creation request
    * @tparam Res response
    * @return route
    */
  private[route] def route[Creation <: CreationRequest, Update, Res <: Data: ClassTag](
    root: String,
    hookOfGroup: HookOfGroup,
    hookOfCreation: HookOfCreation[Creation, Res],
    hookOfUpdate: HookOfUpdate[Creation, Update, Res],
    hookOfList: HookOfList[Res],
    hookOfGet: HookOfGet[Res],
    hookBeforeDelete: HookBeforeDelete,
    hookOfStart: HookOfAction,
    hookOfStop: HookOfAction)(implicit store: DataStore,
                              // normally, update request does not carry the name field,
                              // Hence, the check of name have to be executed by format of creation
                              // since it must have name field.
                              rm: OharaJsonFormat[Creation],
                              rm1: RootJsonFormat[Update],
                              rm2: RootJsonFormat[Res],
                              executionContext: ExecutionContext): server.Route = route(
    root = root,
    hookOfGroup = hookOfGroup,
    hookOfCreation = hookOfCreation,
    hookOfUpdate = hookOfUpdate,
    hookOfList = hookOfList,
    hookOfGet = hookOfGet,
    hookBeforeDelete = hookBeforeDelete,
    hookOfActions = Map(
      START_COMMAND -> hookOfStart,
      STOP_COMMAND -> hookOfStop,
    )
  )

  /**
    * this is the basic route of all APIs to access ohara's data.
    * It implements 1) get, 2) list, 3) delete, 4) add, 5) update, 6) start and 7) stop function.
    * The CREATION is routed to "POST  /$root"
    * The UPDATE is routed to "PUT /$root/$name"
    * The GET is routed to "GET /$root/$name"
    * The LIST is routed to "GET /$root"
    * The DELETE is routed to "DELETE /$root/$name"
    * The START is routed to "PUT /$root/$name/start"
    * The STOP is routed to "PUT /$root/$name/stop"
    * The PAUSE is routed to "PUT /$root/$name/pause"
    * The RESUME is routed to "PUT /$root/$name/resume"
    * @param root path to root
    * @param hookOfGroup used to generate the true group used by route
    * @param hookOfCreation used to convert request to response for Add function
    * @param hookOfUpdate used to convert request to response for Update function
    * @param hookOfList used to convert response for List function
    * @param hookOfGet used to convert response for Get function
    * @param hookBeforeDelete used to do something before doing delete operation. For example, validate the name.
    * @param hookOfStart used to handle start command
    * @param hookOfStop used to handle stop command
    * @param hookOfPause used to handle pause command
    * @param hookOfResume used to handle resume command
    * @param store data store
    * @param rm marshalling of creation
    * @param rm1 marshalling of update
    * @param rm2 marshalling of response
    * @param executionContext thread pool
    * @tparam Creation creation request
    * @tparam Update creation request
    * @tparam Res response
    * @return route
    */
  private[route] def route[Creation <: CreationRequest, Update, Res <: Data: ClassTag](
    root: String,
    hookOfGroup: HookOfGroup,
    hookOfCreation: HookOfCreation[Creation, Res],
    hookOfUpdate: HookOfUpdate[Creation, Update, Res],
    hookOfList: HookOfList[Res],
    hookOfGet: HookOfGet[Res],
    hookBeforeDelete: HookBeforeDelete,
    hookOfStart: HookOfAction,
    hookOfStop: HookOfAction,
    hookOfPause: HookOfAction,
    hookOfResume: HookOfAction)(implicit store: DataStore,
                                // normally, update request does not carry the name field,
                                // Hence, the check of name have to be executed by format of creation
                                // since it must have name field.
                                rm: OharaJsonFormat[Creation],
                                rm1: RootJsonFormat[Update],
                                rm2: RootJsonFormat[Res],
                                executionContext: ExecutionContext): server.Route = route(
    root = root,
    hookOfGroup = hookOfGroup,
    hookOfCreation = hookOfCreation,
    hookOfUpdate = hookOfUpdate,
    hookOfList = hookOfList,
    hookOfGet = hookOfGet,
    hookBeforeDelete = hookBeforeDelete,
    hookOfActions = Map(
      START_COMMAND -> hookOfStart,
      STOP_COMMAND -> hookOfStop,
      PAUSE_COMMAND -> hookOfPause,
      RESUME_COMMAND -> hookOfResume
    )
  )

  /**
    * this is the basic route of all APIs to access ohara's data.
    * It implements 1) get, 2) list, 3) delete, 4) add, 5) update, 6) start and 7) stop function.
    * The CREATION is routed to "POST  /$root"
    * The UPDATE is routed to "PUT /$root/$name"
    * The GET is routed to "GET /$root/$name"
    * The LIST is routed to "GET /$root"
    * The DELETE is routed to "DELETE /$root/$name"
    * The ACTION is routed to "PUT /$root/$name/$action"
    * @param root path to root
    * @param hookOfGroup used to generate the true group used by route
    * @param hookOfCreation used to convert request to response for Add function
    * @param hookOfUpdate used to convert request to response for Update function
    * @param hookOfList used to convert response for List function
    * @param hookOfGet used to convert response for Get function
    * @param hookBeforeDelete used to do something before doing delete operation. For example, validate the name.
    * @param hookOfActions used to handle particular commands
    * @param store data store
    * @param rm marshalling of creation
    * @param rm1 marshalling of update
    * @param rm2 marshalling of response
    * @param executionContext thread pool
    * @tparam Creation creation request
    * @tparam Update creation request
    * @tparam Res response
    * @return route
    */
  private[route] def route[Creation <: CreationRequest, Update, Res <: Data: ClassTag](
    root: String,
    hookOfGroup: HookOfGroup,
    hookOfCreation: HookOfCreation[Creation, Res],
    hookOfUpdate: HookOfUpdate[Creation, Update, Res],
    hookOfList: HookOfList[Res],
    hookOfGet: HookOfGet[Res],
    hookBeforeDelete: HookBeforeDelete,
    hookOfActions: Map[String, HookOfAction])(implicit store: DataStore,
                                              // normally, update request does not carry the name field,
                                              // Hence, the check of name have to be executed by format of creation
                                              // since it must have name field.
                                              rm: OharaJsonFormat[Creation],
                                              rm1: RootJsonFormat[Update],
                                              rm2: RootJsonFormat[Res],
                                              executionContext: ExecutionContext): server.Route = route(
    root = root,
    hookOfGroup = hookOfGroup,
    hookOfCreation = hookOfCreation,
    hookOfUpdate = hookOfUpdate,
    hookOfList = hookOfList,
    hookOfGet = hookOfGet,
    hookBeforeDelete = hookBeforeDelete,
    HookOfSubNameOfPut = HookOfSubName(hookOfActions),
    HookOfSubNameOfDeletion = HookOfSubName.empty
  )

  /**
    * this is the basic route of all APIs to access ohara's data.
    * It implements 1) get, 2) list, 3) delete, 4) add, 5) update,and 6) various "actions" function.
    * The CREATION is routed to "POST  /$root"
    * The UPDATE is routed to "PUT /$root/$name"
    * The GET is routed to "GET /$root/$name"
    * The LIST is routed to "GET /$root"
    * The DELETE is routed to "DELETE /$root/$name"
    * The "ACTION"" is routed to "PUT /$root/$name/$action". Noted: the action request will get NotFound if the input
    * action handles are disable to process the request properly.
    * @param root path to root
    * @param hookOfGroup used to generate the true group used by route
    * @param hookOfCreation used to convert request to response for Add function
    * @param hookOfUpdate used to convert request to response for Update function
    * @param hookOfList used to convert response for List function
    * @param hookOfGet used to convert response for Get function
    * @param hookBeforeDelete used to do something before doing delete operation. For example, validate the name.
    * @param HookOfSubNameOfPut used to handle custom commands for PUT method
    * @param HookOfSubNameOfDeletion used to handle custom commands for DELETE method
    * @param store data store
    * @param rm marshalling of creation
    * @param rm1 marshalling of update
    * @param rm2 marshalling of response
    * @param executionContext thread pool
    * @tparam Creation creation request
    * @tparam Update creation request
    * @tparam Res response
    * @return route
    */
  private[this] def route[Creation <: CreationRequest, Update, Res <: Data: ClassTag](
    root: String,
    hookOfGroup: HookOfGroup,
    hookOfCreation: HookOfCreation[Creation, Res],
    hookOfUpdate: HookOfUpdate[Creation, Update, Res],
    hookOfList: HookOfList[Res],
    hookOfGet: HookOfGet[Res],
    hookBeforeDelete: HookBeforeDelete,
    HookOfSubNameOfPut: HookOfSubName,
    HookOfSubNameOfDeletion: HookOfSubName)(implicit store: DataStore,
                                            // normally, update request does not carry the name field,
                                            // Hence, the check of name have to be executed by format of creation
                                            // since it must have name field.
                                            rm: OharaJsonFormat[Creation],
                                            rm1: RootJsonFormat[Update],
                                            rm2: RootJsonFormat[Res],
                                            executionContext: ExecutionContext): server.Route = route(
    root = root,
    hookOfGroup = hookOfGroup,
    hookOfCreation = hookOfCreation,
    hookOfUpdate = hookOfUpdate,
    hookOfList = hookOfList,
    hookOfGet = hookOfGet,
    hookBeforeDelete = hookBeforeDelete
  ) ~ pathPrefix(root / Segment / Segment) {
    case (name, subName) =>
      parameterMap { params =>
        val key = ObjectKey.of(hookOfGroup(params.get(GROUP_KEY)), name)
        put {
          HookOfSubNameOfPut(key, subName, params)
            .map(_.map(_ => StatusCodes.Accepted))
            .map(complete(_))
            .getOrElse(routeToOfficialUrl(s"/$root/$subName"))
        } ~ delete {
          HookOfSubNameOfDeletion(key, subName, params)
            .map(_.map(_ => StatusCodes.Accepted))
            .map(complete(_))
            .getOrElse(routeToOfficialUrl(s"/$root/$subName"))
        }
      }
  }

  /**
    * this is a variety to basic route of all APIs to access ohara's "cluster" data.
    * It implements 1) get, 2) list, 3) delete, 4) add, 5) update, 6) start and 7) stop function.
    * The CREATION is routed to "POST  /$root"
    * The UPDATE is routed to "PUT /$root/$name"
    * The GET is routed to "GET /$root/$name"
    * The LIST is routed to "GET /$root"
    * The DELETE is routed to "DELETE /$root/$name"
    * The START is routed to "PUT /$root/$name/start"
    * The STOP is routed to "PUT /$root/$name/stop"
    *
    * The following routes are added autmatically.
    * The GET/LIST route auto-update the state of cluster
    * The DELETE route reject the request to a running cluster
    * @param root path to root
    * @param hookOfGroup used to generate the true group used by route
    * @param hookOfCreation used to convert request to response for Add function
    * @param hookOfUpdate used to convert request to response for Update function
    * @param hookOfStart used to handle start command
    * @param hookBeforeStop used to perform checks before stopping cluster
    * @param store data store
    * @param rm marshalling of creation
    * @param rm1 marshalling of update
    * @param rm2 marshalling of response
    * @param executionContext thread pool
    * @tparam Creation creation request for cluster resources
    * @tparam Update creation request
    * @tparam Cluster cluster info
    * @return route
    */
  private[route] def clusterRoute[Cluster <: ClusterInfo: ClassTag,
                                  Creation <: ClusterCreationRequest,
                                  Update <: ClusterUpdateRequest](root: String,
                                                                  metricsKey: Option[String],
                                                                  hookOfGroup: HookOfGroup,
                                                                  hookOfCreation: HookOfCreation[Creation, Cluster],
                                                                  hookOfUpdate: HookOfUpdate[Creation, Update, Cluster],
                                                                  hookOfStart: HookOfAction,
                                                                  hookBeforeStop: HookOfAction)(
    implicit store: DataStore,
    meterCache: MeterCache,
    collie: Collie[Cluster],
    clusterCollie: ClusterCollie,
    nodeCollie: NodeCollie,
    rm: OharaJsonFormat[Creation],
    rm1: RootJsonFormat[Update],
    rm2: RootJsonFormat[Cluster],
    executionContext: ExecutionContext): server.Route =
    clusterRoute[Cluster, Creation, Update](
      root = root,
      hookOfGroup = hookOfGroup,
      hookOfCreation = hookOfCreation,
      hookOfUpdate = hookOfUpdate,
      hookOfGet = updateState[Cluster](_, metricsKey),
      hookOfList = (clusters: Seq[Cluster]) => Future.traverse(clusters)(updateState(_, metricsKey)),
      hookBeforeDelete = hookBeforeDelete[Cluster](metricsKey),
      hookOfStart = hookOfStart,
      hookBeforeStop = hookBeforeStop
    )

  /**
    * this is a variety to basic route of all APIs to access ohara's "cluster" data.
    * It implements 1) get, 2) list, 3) delete, 4) add, 5) update, 6) start and 7) stop function.
    * The CREATION is routed to "POST  /$root"
    * The UPDATE is routed to "PUT /$root/$name"
    * The GET is routed to "GET /$root/$name"
    * The LIST is routed to "GET /$root"
    * The DELETE is routed to "DELETE /$root/$name"
    * The START is routed to "PUT /$root/$name/start"
    * The STOP is routed to "PUT /$root/$name/stop"
    * @param root path to root
    * @param hookOfGroup used to generate the true group used by route
    * @param hookOfCreation used to convert request to response for Add function
    * @param hookOfUpdate used to convert request to response for Update function
    * @param hookOfList used to convert response for List function
    * @param hookOfGet used to convert response for Get function
    * @param hookBeforeDelete used to do something before doing delete operation. For example, validate the name.
    * @param hookOfStart used to handle start command
    * @param hookBeforeStop used to perform checks before stopping cluster
    * @param store data store
    * @param rm marshalling of creation
    * @param rm1 marshalling of update
    * @param rm2 marshalling of response
    * @param executionContext thread pool
    * @tparam Creation creation request for cluster resources
    * @tparam Update creation request
    * @tparam Cluster cluster info
    * @return route
    */
  private[this] def clusterRoute[Cluster <: ClusterInfo: ClassTag,
                                 Creation <: ClusterCreationRequest,
                                 Update <: ClusterUpdateRequest](root: String,
                                                                 hookOfGroup: HookOfGroup,
                                                                 hookOfCreation: HookOfCreation[Creation, Cluster],
                                                                 hookOfUpdate: HookOfUpdate[Creation, Update, Cluster],
                                                                 hookOfGet: HookOfGet[Cluster],
                                                                 hookOfList: HookOfList[Cluster],
                                                                 hookBeforeDelete: HookBeforeDelete,
                                                                 hookOfStart: HookOfAction,
                                                                 hookBeforeStop: HookOfAction)(
    implicit store: DataStore,
    collie: Collie[Cluster],
    clusterCollie: ClusterCollie,
    nodeCollie: NodeCollie,
    rm: OharaJsonFormat[Creation],
    rm1: RootJsonFormat[Update],
    rm2: RootJsonFormat[Cluster],
    executionContext: ExecutionContext): server.Route =
    route(
      root = root,
      hookOfGroup = hookOfGroup,
      hookOfCreation = hookOfCreation,
      hookOfUpdate = hookOfUpdate,
      hookOfList = hookOfList,
      hookOfGet = hookOfGet,
      hookBeforeDelete = hookBeforeDelete,
      HookOfSubNameOfPut = HookOfSubName.builder
      // start cluster
        .hook(
          START_COMMAND,
          (key: ObjectKey, subName: String, params: Map[String, String]) =>
            store.value[Cluster](key).flatMap { req =>
              collie.exist(req.name).flatMap {
                if (_) {
                  // this cluster already exists, return OK
                  Future.unit
                } else {
                  checkResourcesConflict(nodeCollie, clusterCollie, req).flatMap(_ => hookOfStart(key, subName, params))
                }
              }
          }
        )
        // stop cluster
        .hook(
          STOP_COMMAND,
          (key: ObjectKey, subName: String, params: Map[String, String]) =>
            hookBeforeStop(key, subName, params).flatMap(
              _ =>
                collie
                  .clusters()
                  .flatMap { clusters =>
                    if (!clusters.map(_._1.name).exists(_ == key.name())) Future.unit
                    else if (params.get(FORCE_KEY).exists(_.toLowerCase == "true")) collie.forceRemove(key.name())
                    else collie.remove(key.name())
                  }
                  .flatMap(_ => Future.unit)
          )
        )
        // handle the node increment
        .hook((key: ObjectKey, nodeName: String, _: Map[String, String]) =>
          collie.cluster(key.name()).map(_._1).flatMap { cluster =>
            // A BIT hard-code here to reuse the checker :(
            rm.check[JsArray]("nodeNames", JsArray(JsString(nodeName)))
            if (cluster.nodeNames.contains(nodeName)) Future.unit
            else collie.addNode(key.name(), nodeName).map(_ => Unit)
        })
        .build(),
      // handle the node decrement
      HookOfSubNameOfDeletion = HookOfSubName((key: ObjectKey, nodeName: String, _: Map[String, String]) =>
        collie.clusters().map(_.keys.toSeq).flatMap { clusters =>
          if (clusters.exists(cluster => cluster.name == key.name() && cluster.nodeNames.contains(nodeName)))
            collie.removeNode(key.name(), nodeName).map(_ => Unit)
          else Future.unit
      })
    )

  /**
    * the url to official APIs documentation.
    * @return url string
    */
  def apiUrl: String = {
    val docVersion = if (VersionUtils.BRANCH == "master") "latest" else VersionUtils.BRANCH
    s"https://ohara.readthedocs.io/en/$docVersion/rest_interface.html"
  }

  private[this] def errorWithOfficialApis(inputPath: String): ErrorApi.Error = ErrorApi.Error(
    code = s"Unsupported API: $inputPath",
    message = s"please see link to find the available APIs. input url:$inputPath",
    stack = "N/A",
    apiUrl = Some(apiUrl)
  )

  def routeToOfficialUrl(inputPath: String): server.Route = complete(
    StatusCodes.NotFound -> errorWithOfficialApis(inputPath))

  /**
    * Test whether this cluster satisfied the following rules:
    * <p>
    * 1) cluster image in all nodes
    * 2) name should not conflict
    * 3) port should not conflict
    *
    * @param nodeCollie nodeCollie instance
    * @param clusterCollie clusterCollie instance
    * @param req cluster creation request
    * @param executionContext execution context
    * @tparam Cluster type of request
    * @return clusters that fitted the requires
    */
  private[route] def checkResourcesConflict[Cluster <: ClusterInfo: ClassTag](
    nodeCollie: NodeCollie,
    clusterCollie: ClusterCollie,
    req: Cluster)(implicit executionContext: ExecutionContext): Future[Unit] =
    // nodeCollie.nodes(req.nodeNames) is used to check the existence of node names of request
    nodeCollie
      .nodes(req.nodeNames)
      .flatMap(clusterCollie.images)
      // check the docker images
      .map { nodesImages =>
        nodesImages
          .filterNot(_._2.contains(req.imageName))
          .keys
          .map(_.name)
          .foreach(n => throw new IllegalArgumentException(s"$n doesn't have docker image:${req.imageName}"))
      }
      .flatMap(_ => clusterCollie.clusters().map(_.keys.toSeq))
      .flatMap { clusters =>
        def serviceName(cluster: ClusterInfo): String = cluster match {
          case _: ZookeeperClusterInfo => s"zookeeper cluster:${cluster.name}"
          case _: BrokerClusterInfo    => s"broker cluster:${cluster.name}"
          case _: WorkerClusterInfo    => s"worker cluster:${cluster.name}"
          case _: StreamClusterInfo    => s"stream cluster:${cluster.name}"
          case _                       => s"cluster:${cluster.name}"
        }
        // check name conflict
        clusters
          .filter(c => classTag[Cluster].runtimeClass.isInstance(c))
          .map(_.asInstanceOf[Cluster])
          .find(_.name == req.name)
          .foreach(conflictCluster => throw new IllegalArgumentException(s"${serviceName(conflictCluster)} is running"))

        // check port conflict
        Some(clusters
          .flatMap { cluster =>
            val conflictPorts = cluster.ports.intersect(req.ports)
            if (conflictPorts.isEmpty) None
            else Some(cluster -> conflictPorts)
          }
          .map {
            case (cluster, conflictPorts) =>
              s"ports:${conflictPorts.mkString(",")} are used by ${serviceName(cluster)} (the port is generated randomly if it is ignored from request)"
          }
          .mkString(";")).filter(_.nonEmpty).foreach(s => throw new IllegalArgumentException(s))
        Future.unit
      }

  private[this] def updateState[Cluster <: ClusterInfo: ClassTag](cluster: Cluster, metricsKey: Option[String])(
    implicit meterCache: MeterCache,
    store: DataStore,
    collie: Collie[Cluster],
    executionContext: ExecutionContext): Future[Cluster] =
    collie
      .clusters()
      .map(
        _.keys
          .find(_.name == cluster.name)
          .map(runningCluster =>
            runningCluster.clone(metrics =
              Metrics(metricsKey.flatMap(key => meterCache.meters(runningCluster).get(key)).getOrElse(Seq.empty))))
          .getOrElse(cluster
            .clone(state = None, error = None)
            // the cluster is stooped (all containers are gone) so we don't need to fetch metrics.
            .clone(metrics = Metrics(Seq.empty)))
          // the actual type is erased since the clone method returns the ClusterInfo type.
          // However, it is safe to case the type to the input type since all sub classes of ClusterInfo should work well.
          .asInstanceOf[Cluster]
      )
      .flatMap { cluster =>
        // add the up-to-date object to store
        // TODO: in fact, this may be a useless action since all Getters do update state before generating response.
        store.add[Cluster](cluster)
      }

  private[this] def hookBeforeDelete[Cluster <: ClusterInfo: ClassTag](metricsKey: Option[String])(
    implicit store: DataStore,
    meterCache: MeterCache,
    collie: Collie[Cluster],
    executionContext: ExecutionContext): HookBeforeDelete = (key: ObjectKey) =>
    store.get[Cluster](key).flatMap {
      _.fold(Future.unit) { info =>
        updateState(info, metricsKey).flatMap { data =>
          if (data.state.isEmpty) Future.unit
          else
            Future.failed(new RuntimeException(
              s"You cannot delete a non-stopped ${classTag[Cluster].runtimeClass.getSimpleName} :$key"))
        }
      }
  }
}
