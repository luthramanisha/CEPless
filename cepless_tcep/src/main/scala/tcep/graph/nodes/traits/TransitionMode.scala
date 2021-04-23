package tcep.graph.nodes.traits

import akka.actor.{Actor, ActorLogging, ActorRef, Terminated}
import org.discovery.vivaldi.{Coordinates, DistVivaldiActor}
import tcep.graph.nodes.traits.Node.{Subscribe, UnSubscribe}
import tcep.graph.transition._
import tcep.placement.HostInfo
import tcep.placement.benchmarking.PlacementAlgorithm

import scala.collection.mutable
/**
  * Common methods for different transition modes
  */
trait TransitionMode extends Actor with ActorLogging {

  val modeName: String = "transition-mode name not specified"
  var coordinates: Coordinates = DistVivaldiActor.localPos.coordinates
  val subscribers: mutable.Set[ActorRef]

  @volatile var started: Boolean
  val backupMode: Boolean
  val mainNode: Option[ActorRef]
  val hostInfo: HostInfo

  def createDuplicateNode(hostInfo: HostInfo): ActorRef
  def executionStarted()
  def getParentNodes: Seq[ActorRef]
  def maxWindowTime(): Int

  // Notice I'm using `PartialFunction[Any,Any]` which is different from akka's default
  // receive partial function which is `PartialFunction[Any,Unit]`
  // because I needed to `cache` some messages before forwarding them to the child nodes
  // @see the receive method in Node trait for more details.
  def transitionReceive: PartialFunction[Any, Any] = {
    case Subscribe() => {
      val s = sender()
      log.info(s"${s.path.name} subscribed for ${this.self.path.name}")
      subscribers += s
      Unit
    }

    case UnSubscribe() => {
      val s = sender()
      log.info(s"${s.path.name} unSubscribed from ${this.self.path.name}")
      subscribers -= s
      Unit
    }

    case TransitionRequest(algorithm) => {
      log.info(s"${this.modeName.toUpperCase()}: received transition request")
      handleTransitionRequest(sender(), algorithm)
      Unit
    }

    case Terminated(killed) => {
      if (killed.equals(mainNode.get)) {
        started = true
      }
      Unit
    }

    case unhandled => {
      unhandled //forwarding to childreceive
    }
  }

  def handleTransitionRequest(requester: ActorRef, algorithm: PlacementAlgorithm)

  def executeTransition(requester: ActorRef, algorithm: PlacementAlgorithm): Unit
}