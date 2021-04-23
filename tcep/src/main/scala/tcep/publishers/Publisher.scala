package tcep.publishers

import akka.actor.{ActorLogging, ActorRef}
import akka.cluster.ClusterEvent._
import tcep.graph.nodes.traits.Node._
import tcep.machinenodes.helper.actors.PlacementMessage
import tcep.placement.vivaldi.VivaldiCoordinates
import tcep.publishers.Publisher._

import scala.collection.mutable

/**
  * Publisher Actor
  **/
trait Publisher extends VivaldiCoordinates with ActorLogging {

  var subscribers: mutable.Set[ActorRef] = mutable.Set.empty[ActorRef]

  override def receive: Receive = super.receive orElse {
    case Subscribe() =>{
      log.info(s"${sender().path.name} subscribed")
      subscribers += sender()
      sender() ! AcknowledgeSubscription
    }

    case UnSubscribe() =>{
      log.info(s"${sender().path.name} unSubscribed")
      subscribers -= sender()
    }

    case _: MemberEvent => // ignore
  }

}

/**
  * List of Akka Messages which is being used by Publisher actor.
  **/
object Publisher {
  case object AcknowledgeSubscription extends PlacementMessage
}
