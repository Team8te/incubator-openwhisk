/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.core.mesos

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.pattern.ask
import com.adobe.api.platform.runtime.mesos.MesosClient
import com.adobe.api.platform.runtime.mesos.Subscribe
import com.adobe.api.platform.runtime.mesos.SubscribeComplete
import com.adobe.api.platform.runtime.mesos.Teardown
import java.time.Instant
import pureconfig.loadConfigOrThrow
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.util.Try
import whisk.common.Counter
import whisk.common.Logging
import whisk.common.TransactionId
import whisk.core.ConfigKeys
import whisk.core.WhiskConfig
import whisk.core.containerpool.Container
import whisk.core.containerpool.ContainerArgsConfig
import whisk.core.containerpool.ContainerFactory
import whisk.core.containerpool.ContainerFactoryProvider
import whisk.core.entity.ByteSize
import whisk.core.entity.ExecManifest
import whisk.core.entity.InstanceId
import whisk.core.entity.UUID

/**
 * Configuration for MesosClient
 * @param masterUrl The mesos url e.g. http://leader.mesos:5050.
 * @param masterPublicUrl A public facing mesos url (which may be different that the internal facing url) e.g. http://mymesos:5050.
 * @param role The role used by this framework (see http://mesos.apache.org/documentation/latest/roles/#associating-frameworks-with-roles).
 * @param failoverTimeout Timeout allowed for framework to reconnect after disconnection.
 * @param mesosLinkLogMessage If true, display a link to mesos in the static log message, otherwise do not include a link to mesos.
 */
case class MesosConfig(masterUrl: String,
                       masterPublicUrl: Option[String],
                       role: String,
                       failoverTimeout: FiniteDuration,
                       mesosLinkLogMessage: Boolean)

class MesosContainerFactory(config: WhiskConfig,
                            actorSystem: ActorSystem,
                            logging: Logging,
                            parameters: Map[String, Set[String]],
                            containerArgs: ContainerArgsConfig =
                              loadConfigOrThrow[ContainerArgsConfig](ConfigKeys.containerArgs),
                            mesosConfig: MesosConfig = loadConfigOrThrow[MesosConfig](ConfigKeys.mesos),
                            clientFactory: (ActorSystem, MesosConfig) => ActorRef = MesosContainerFactory.createClient,
                            taskIdGenerator: () => String = MesosContainerFactory.taskIdGenerator)
    extends ContainerFactory {

  val subscribeTimeout = 10.seconds
  val teardownTimeout = 30.seconds

  implicit val as: ActorSystem = actorSystem
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  /** Inits Mesos framework. */
  val mesosClientActor = clientFactory(as, mesosConfig)

  subscribe()

  /** Subscribes Mesos actor to mesos event stream; retry on timeout (which should be unusual). */
  private def subscribe(): Future[Unit] = {
    logging.info(this, s"subscribing to Mesos master at ${mesosConfig.masterUrl}")
    mesosClientActor
      .ask(Subscribe)(subscribeTimeout)
      .mapTo[SubscribeComplete]
      .map(complete => logging.info(this, s"subscribe completed successfully... $complete"))
      .recoverWith {
        case e =>
          logging.error(this, s"subscribe failed... $e}")
          subscribe()
      }
  }

  override def createContainer(tid: TransactionId,
                               name: String,
                               actionImage: ExecManifest.ImageName,
                               userProvidedImage: Boolean,
                               memory: ByteSize,
                               cpuShares: Int)(implicit config: WhiskConfig, logging: Logging): Future[Container] = {
    implicit val transid = tid
    val image = if (userProvidedImage) {
      actionImage.publicImageName
    } else {
      actionImage.localImageName(config.dockerRegistry, config.dockerImagePrefix, Some(config.dockerImageTag))
    }

    logging.info(this, s"using Mesos to create a container with image $image...")
    MesosTask.create(
      mesosClientActor,
      mesosConfig,
      taskIdGenerator,
      tid,
      image = image,
      userProvidedImage = userProvidedImage,
      memory = memory,
      cpuShares = cpuShares,
      environment = Map("__OW_API_HOST" -> config.wskApiHost),
      network = containerArgs.network,
      dnsServers = containerArgs.dnsServers,
      name = Some(name),
      //strip any "--" prefixes on parameters (should make this consistent everywhere else)
      parameters
        .map({ case (k, v) => if (k.startsWith("--")) (k.replaceFirst("--", ""), v) else (k, v) })
        ++ containerArgs.extraArgs)
  }

  override def init(): Unit = Unit

  /** Cleanups any remaining Containers; should block until complete; should ONLY be run at shutdown. */
  override def cleanup(): Unit = {
    val complete: Future[Any] = mesosClientActor.ask(Teardown)(teardownTimeout)
    Try(Await.result(complete, teardownTimeout))
      .map(_ => logging.info(this, "Mesos framework teardown completed."))
      .recover {
        case _: TimeoutException => logging.error(this, "Mesos framework teardown took too long.")
        case t: Throwable =>
          logging.error(this, s"Mesos framework teardown failed : $t}")
      }
  }
}
object MesosContainerFactory {
  private def createClient(actorSystem: ActorSystem, mesosConfig: MesosConfig): ActorRef =
    actorSystem.actorOf(
      MesosClient
        .props(
          "whisk-containerfactory-" + UUID(),
          "whisk-containerfactory-framework",
          mesosConfig.masterUrl,
          mesosConfig.role,
          mesosConfig.failoverTimeout))

  val counter = new Counter()
  val startTime = Instant.now.getEpochSecond
  private def taskIdGenerator(): String = {
    s"whisk-${counter.next()}-${startTime}"
  }
}

object MesosContainerFactoryProvider extends ContainerFactoryProvider {
  override def getContainerFactory(actorSystem: ActorSystem,
                                   logging: Logging,
                                   config: WhiskConfig,
                                   instance: InstanceId,
                                   parameters: Map[String, Set[String]]): ContainerFactory =
    new MesosContainerFactory(config, actorSystem, logging, parameters)
}
