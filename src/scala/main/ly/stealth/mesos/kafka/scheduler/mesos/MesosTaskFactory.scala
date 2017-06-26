/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  *    http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package ly.stealth.mesos.kafka.scheduler.mesos

import com.google.protobuf.{ByteString, Descriptors}
import ly.stealth.mesos.kafka.Broker.{Container, ContainerType, MountMode, Reservation}
import ly.stealth.mesos.kafka._
import ly.stealth.mesos.kafka.executor.LaunchConfig
import ly.stealth.mesos.kafka.json.JsonUtil
import ly.stealth.mesos.kafka.readJson.executorMap
import ly.stealth.mesos.kafka.scheduler.KafkaDistributionComponent
import net.elodina.mesos.util.Version
import org.apache.log4j.Logger
import org.apache.mesos.Protos.ContainerInfo.{DockerInfo, MesosInfo}
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._

import scala.collection.mutable
import scala.collection.JavaConversions._

trait MesosTaskFactoryComponent {
  val taskFactory: MesosTaskFactory

  trait MesosTaskFactory {
    def newTask(broker: Broker, offer: Offer, reservation: Broker.Reservation): TaskInfo
  }
}

trait MesosTaskFactoryComponentImpl extends MesosTaskFactoryComponent {
  this: KafkaDistributionComponent =>

  val taskFactory: MesosTaskFactory = new MesosTaskFactoryImpl

  class MesosTaskFactoryImpl extends MesosTaskFactory {
    private[this] val logger = Logger.getLogger("MesosTaskFactoryImpl")

    private[kafka] def newExecutor(broker: Broker): ExecutorInfo = {
      if (broker.executor == null || broker.executor == "default") {
        val distInfo = kafkaDistribution.distInfo
        var cmd = ""
        if (broker.executionOptions.container.isDefined) {
          cmd += "cd $MESOS_SANDBOX && "
        }
        cmd += s"${broker.executionOptions.javaCmd} -cp ${distInfo.jar.getName} -Xmx${broker.heap}m"
        if (broker.executionOptions.jvmOptions != null)
          cmd += " " + broker.executionOptions.jvmOptions.replace("$id", broker.id.toString)

        if (Config.debug) cmd += " -Ddebug"
        cmd += " ly.stealth.mesos.kafka.executor.Executor"

        val commandBuilder = CommandInfo.newBuilder
        if (Config.jre != null) {
          commandBuilder
            .addUris(CommandInfo.URI.newBuilder().setValue(Config.api + "/jre/" + Config.jre.getName))
          cmd = "jre/bin/" + cmd
        }

        val env = new mutable.HashMap[String, String]()
        env("MESOS_SYSLOG_TAG") = Config.frameworkName + "-" + broker.id
        if (broker.syslog) env("MESOS_SYSLOG") = "true"

        val envBuilder = Environment.newBuilder()
        for ((name, value) <- env)
          envBuilder.addVariables(Variable.newBuilder().setName(name).setValue(value))
        commandBuilder.setEnvironment(envBuilder)

        commandBuilder
          .addUris(CommandInfo.URI.newBuilder()
            .setValue(Config.api + "/jar/" + distInfo.jar.getName).setExtract(false))
          .addUris(CommandInfo.URI.newBuilder()
            .setValue(Config.api + "/kafka/" + distInfo.kafkaDist.getName))
          .setValue(cmd)

        val executor = ExecutorInfo.newBuilder()
          .setExecutorId(ExecutorID.newBuilder.setValue(Broker.nextExecutorId(broker)))
          .setCommand(commandBuilder)
          .setName("broker-" + broker.id)

        broker.executionOptions.container.foreach { c =>
          executor.setContainer(createContainerInfo(c, broker.id))
        }
        executor.build()
      } else {
        for(executor <- executorMap.keys){
          val map = executorMap(executor).asInstanceOf[Map[String,Any]]
          logger.info("name: " + map("name"))
          logger.info("command: "+ map("command"))
          logger.info("resources: "+  map("resources"))
          logger.info("-------------------------------------------------------")
        }
        var cmd = "./executor"
        //        val cmd = "python dce-go-paypal-ipvlan-dev.py"
        val commandBuilder = CommandInfo.newBuilder
        commandBuilder // HARDCODED VALUES IN HERE
          .addUris(CommandInfo.URI.newBuilder().setValue("http://10.180.45.38:8888/data/docker-images/docker-compose.yml").setExtract(false).setExecutable(false))
          .addUris(CommandInfo.URI.newBuilder().setValue("http://10.180.45.38:8888/data/executor-related/executor").setExtract(false).setExecutable(true))
          .addUris(CommandInfo.URI.newBuilder().setValue("http://10.180.45.38:8888/data/executor-related/config.yaml").setExtract(false).setExecutable(true))
          .addUris(CommandInfo.URI.newBuilder().setValue("http://10.180.45.38:8888/data/executor-related/general.yaml").setExtract(false).setExecutable(true))
          //.addUris(CommandInfo.URI.newBuilder().setValue("https://cronus-srepo.qa.paypal.com/packages/hostutils/GO-DCE/dce-go-paypal-ipvlan/develop/dce-go-paypal-ipvlan.py").setExecutable(true).setExtract(false))
          .setValue(cmd)

        val executor = ExecutorInfo.newBuilder()
          .setExecutorId(ExecutorID.newBuilder.setValue(Broker.nextExecutorId(broker)))
          .setCommand(commandBuilder)
          .setName("broker-dcego-" + broker.id)

        executor.build()
      }
    }

    private def createContainerInfo(c: Container, brokerId: Int): ContainerInfo = {
      val containerName = c.name.replace("$id", brokerId.toString)
      val containerInfo =
        if (c.ctype == ContainerType.Docker) {
          ContainerInfo.newBuilder()
            .setDocker(DockerInfo.newBuilder()
              .setImage(containerName)
              .setNetwork(DockerInfo.Network.HOST))
            .setType(ContainerInfo.Type.DOCKER)
        } else if (c.ctype == ContainerType.Mesos) {
          ContainerInfo.newBuilder()
            .setMesos(MesosInfo.newBuilder()
              .setImage(Image.newBuilder()
                .setDocker(Image.Docker.newBuilder().setName(containerName))
                .setType(Image.Type.DOCKER)))
            .setType(ContainerInfo.Type.MESOS)
        } else {
          throw new IllegalArgumentException("unsupported type")
        }

      containerInfo
        .addAllVolumes(
          c.mounts.map(m =>
            Volume.newBuilder()
              .setHostPath(m.hostPath.replace("$id", brokerId.toString))
              .setContainerPath(m.containerPath.replace("$id", brokerId.toString))
              .setMode(m.mode match {
                case MountMode.ReadWrite => Volume.Mode.RW
                case MountMode.ReadOnly => Volume.Mode.RO
              })
              .build()
          ))
        .build()
    }


    def newTask(broker: Broker, offer: Offer, reservation: Broker.Reservation): TaskInfo = {
      def populate(taskBuilder: TaskInfo.Builder, reservation: Broker.Reservation, broker: Broker): TaskInfo.Builder = {
        if (!(broker.executor == null || broker.executor == "default")) {
          //        taskBuilder.setLabels(Labels.newBuilder().addLabels(Label.newBuilder().setKey("fileName").setValue(broker.executorFiles)))
          taskBuilder.setLabels(Labels.newBuilder().addLabels(Label.newBuilder().setKey("fileName").setValue("docker-compose.yml")))
        }
        //    taskBuilder.addAllResources(reservation.toResources)
        taskBuilder.setExecutor(newExecutor(broker))
        //    Resource.newBuilder()
        taskBuilder
      }
      val taskData = {
        var defaults: Map[String, String] = Map(
          "broker.id" -> broker.id.toString,
          "port" -> ("" + reservation.port),
          "log.dirs" -> "kafka-logs",
          "log.retention.bytes" -> ("" + 10l * 1024 * 1024 * 1024),

          "zookeeper.connect" -> Config.zk,
          "host.name" -> offer.getHostname
        )

        if (kafkaDistribution.distInfo.kafkaVersion.compareTo(new Version("0.9")) >= 0)
          defaults += ("listeners" -> s"PLAINTEXT://:${reservation.port}")

        if (reservation.volume != null)
          defaults += ("log.dirs" -> "data/kafka-logs")

        val launchConfig = LaunchConfig(
          broker.id,
          broker.options,
          broker.syslog,
          broker.log4jOptions,
          broker.bindAddress,
          defaults)
        ByteString.copyFrom(JsonUtil.toJsonBytes(launchConfig))
      }

      val taskBuilder: TaskInfo.Builder = {
        var taskBuilder = TaskInfo.newBuilder
          .setName(Config.frameworkName + "-" + broker.id)
          .setTaskId(TaskID.newBuilder.setValue(Broker.nextTaskId(broker)).build)
          .setSlaveId(offer.getSlaveId)
          .setData(taskData)
        taskBuilder = populate(taskBuilder, reservation, broker)
        taskBuilder
      }
      taskBuilder.addAllResources(reservation.toResources)
      taskBuilder.build
    }

  }
}