package tcep.machinenodes

import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import org.discovery.vivaldi.DistVivaldiActor
import scalaj.http.{Http, HttpOptions}
import tcep.config.ConfigurationParser
import tcep.data.Events.Event1
import tcep.machinenodes.helper.actors.TaskManagerActor
import tcep.publishers.{CSVPublisher, KafkaPublisher, RegularPublisher}
import tcep.simulation.tcep.GUIConnector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.util.parsing.json.JSONObject

/**
  * Created by raheel
  * on 09/08/2017.
  */
object PublisherApp extends ConfigurationParser with App {
  logger.info("booting up PublisherApp")

  val actorSystem: ActorSystem = ActorSystem(config.getString("clustering.cluster.name"), config)
  DistVivaldiActor.createVivIfNotExists(actorSystem)
  val eventIntervalNano = ConfigFactory.load().getInt("constants.event-interval-nano")
  actorSystem.actorOf(Props(KafkaPublisher(eventIntervalNano)), s"P:${options('name).toString}")
  // actorSystem.actorOf(Props(RegularPublisher(eventIntervalMillis, id => Event1(id))), s"P:${options('name).toString}")
  actorSystem.actorOf(Props(new TaskManagerActor), "TaskManager") // necessary for measuring bandwidth

  actorSystem.scheduler.schedule(FiniteDuration(30, TimeUnit.SECONDS), FiniteDuration(30, TimeUnit.SECONDS), () => {
    GUIConnector.sendInitialPublisherOperator()
  })
/*
  var actorPublisher = options('name) match {
    case "A" => actorSystem.actorOf(Props(RegularPublisher(100, id => Event1(id))), "A")
    case "B" => actorSystem.actorOf(Props(RegularPublisher(100, id => Event1(id))), "B")
    case "C" => actorSystem.actorOf(Props(RegularPublisher(100, id => Event1(id))), "C")
    case "D" => actorSystem.actorOf(Props(RegularPublisher(100, id => Event1(id))), "D")
    case _ => actorSystem.actorOf(Props(RegularPublisher(100, id => Event1(id))), "A")
  }
*/

  override def getRole: String = "Publisher"
  override def getArgs: Array[String] = args
}