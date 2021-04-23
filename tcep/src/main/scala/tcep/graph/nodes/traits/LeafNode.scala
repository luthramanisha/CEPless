package tcep.graph.nodes.traits

import akka.actor.ActorRef
import tcep.data.Queries.LeafQuery
import tcep.graph.nodes.ShutDown
import tcep.graph.transition.TransitionRequest
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.placement.benchmarking.PlacementAlgorithm

/**
  * Handling of [[tcep.data.Queries.LeafQuery]] is done by LeafNode
  **/
trait LeafNode extends Node {

  val createdCallback: Option[CreatedCallback]
  val eventCallback: Option[EventCallback]
  val query: LeafQuery

  override def emitCreated(): Unit = {
    super.emitCreated()
  }

  override def childNodeReceive: Receive = {
    case TransitionRequest(algorithm) => handleTransitionRequest(sender(), algorithm)
    case ShutDown() => {
      context.stop(self)
    }

  }

  override def handleTransitionRequest(requester: ActorRef, algorithm: PlacementAlgorithm) = {
    executeTransition(requester, algorithm)
  }
}
