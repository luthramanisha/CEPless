package tcep.graph.nodes

import akka.actor.ActorRef
import tcep.data.Events._
import tcep.data.Queries._
import tcep.factories.NodeFactory
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.placement.HostInfo

case class CustomFilterNode(mode: Mode.Mode,
                      hostInfo: HostInfo,
                      backupMode: Boolean,
                      mainNode: Option[ActorRef],
                      query: FilterQuery,
                      @volatile var parentNode: ActorRef,
                      createdCallback: Option[CreatedCallback],
                      eventCallback: Option[EventCallback]
                     )
  extends UnaryNode {

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case event: Event =>
      val s = sender()
      if (parentsList.contains(s)) {
        if (query.cond(event))
          emitEvent(event)
      } else log.info(s"received event $event from $s, \n parentlist: \n $parentsList")

    case unhandledMessage => log.info(s"unhandled message $unhandledMessage")
  }

  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    val startTime = System.currentTimeMillis()
    val res = NodeFactory.createFilterNode(mode, hostInfo, backupMode, mainNode, query, parentNode, createdCallback, eventCallback, context)
    log.info(s"Spent ${System.currentTimeMillis() - startTime} milliseconds to initialize CustomFilterNode")
    res
  }

  def maxWindowTime(): Int = 0
}

