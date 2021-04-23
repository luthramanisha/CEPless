package tcep.graph.nodes

import akka.actor.{ActorLogging, ActorRef}
import tcep.data.Events._
import tcep.data.Queries._
import tcep.factories.NodeFactory
import tcep.graph.nodes.traits.Mode._
import tcep.graph.nodes.traits.Node.{Subscribe, UnSubscribe}
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback, QueryGraph}
import tcep.placement.HostInfo
import tcep.publishers.Publisher._

/**
  * Handling of [[tcep.data.Queries.StreamQuery]] is done by StreamNode.
  *
  * @see [[QueryGraph]]
  **/

case class StreamNode(mode: Mode,
                      hostInfo: HostInfo,
                      backupMode: Boolean,
                      mainNode: Option[ActorRef],
                      query: StreamQuery,
                      publisher: ActorRef,
                      createdCallback: Option[CreatedCallback],
                      eventCallback: Option[EventCallback]
                     )
  extends LeafNode with ActorLogging {

  override def executionStarted(): Unit = {
    log.info(s"subscribing for events from ${publisher.path.name}")
    publisher ! Subscribe()
  }

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case DependenciesRequest => sender ! DependenciesResponse(Seq.empty)
    case AcknowledgeSubscription if sender().equals(publisher) => emitCreated()
    case event: Event => {
      //if (sender ().equals(publisher)) {
        emitEvent(event)
      //} else {
      //  println (s"Event received from unknown publisher $publisher")
     //}
    }
    case unhandledMessage => log.info(s"${self.path.name} unhandled message $unhandledMessage by ${sender().path.name}")
  }

  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    val startTime = System.currentTimeMillis()
    val res = NodeFactory.createStreamNode(mode, hostInfo, backupMode, mainNode, query, publisher, createdCallback, eventCallback, context)
    log.info(s"Spent ${System.currentTimeMillis() - startTime} milliseconds to initalize StreamNode")
    res
  }

  override def postStop(): X = {
    super.postStop()
    publisher ! UnSubscribe()
  }

  def getParentNodes(): Seq[ActorRef] = Seq(publisher)
  def maxWindowTime(): Int = 0
}

