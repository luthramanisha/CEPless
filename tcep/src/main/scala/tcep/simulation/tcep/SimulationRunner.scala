package tcep.simulation.tcep

import java.io.File

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import org.discovery.vivaldi.DistVivaldiActor
import org.slf4j.LoggerFactory
import tcep.machinenodes.helper.actors.TaskManagerActor
import tcep.placement.vivaldi.VivaldiCoordinates

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.sys.process._

/**
  * Runs the Trasitive CEP simulation.
  * The code requires an optional commandline parameter for "directory path" where simulation results will be saved as
  * CSV files.
  * see local_tcep_simulation.sh file for more details.
  */
object SimulationRunner extends App {
  lazy val logger = LoggerFactory.getLogger(getClass)
  logger.info("booting up simulation runner")

  if(args.length <3 ){
    logger.info("Not enough arguments")
  } else {
    executeSimulation()
  }

  def executeSimulation(): Unit ={
    //TODO use configuraiton parser
    val dir = args(0)
    val mode = args(3).toInt
    val host = args(5)
    val port = args(7).toInt

    logger.info(s"Using name $host as host with port $port")

    val defaultConfig = ConfigFactory.load()
    val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=$port")
      .withFallback(ConfigFactory.parseString(
        s"akka.remote.netty.tcp.hostname=$host"))
      .withFallback(ConfigFactory.parseString("akka.cluster.roles=[Subscriber]"))
      .withFallback(defaultConfig)

    Future {
      "ntpd -s" !
    }

    val actorSystem: ActorSystem = ActorSystem(config.getString("clustering.cluster.name"), config)
    DistVivaldiActor.createVivIfNotExists(actorSystem)

    val directory = if(new File(dir).isDirectory) Some(new File(dir)) else {
      logger.info("Invalid directory path")
      None
    }
    val taskManagerActorProps = Props(new TaskManagerActor with VivaldiCoordinates)
    val simulatorActorProps = Props(new SimulationSetup(directory, mode, None)).withMailbox("prio-mailbox")
    //logger.info(s"taskManager mailbox: ${taskManagerActorProps.mailbox} \n simulator mailbox: ${simulatorActorProps.mailbox}")
    actorSystem.actorOf(taskManagerActorProps, "TaskManager")
    actorSystem.actorOf(simulatorActorProps,"SimulationSetup")
  }

}

object Mode {
  val TEST_PIETZUCH = 1
  val TEST_STARKS = 2
  val TEST_SMS = 3
  val TEST_MFGS = 4
  val SPLC_DATACOLLECTION = 5
  val DO_NOTHING = 6
  val TEST_GUI = 7
  val TEST_RIZOU = 8
  val TEST_CUSTOM = 9
}
