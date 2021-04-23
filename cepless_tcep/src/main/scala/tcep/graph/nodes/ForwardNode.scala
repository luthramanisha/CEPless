package tcep.graph.nodes

import akka.actor.ActorRef
import tcep.data.Events._
import tcep.data.Queries._
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.placement.HostInfo
import tcep.graph.nodes.traits.Mode.Mode

import scala.language.postfixOps

case class ForwardNode(mode: Mode,
                      hostInfo: HostInfo,
                      backupMode: Boolean,
                      mainNode: Option[ActorRef],
                      query: UnaryQuery,
                      @volatile var parentNode: ActorRef,
                      createdCallback: Option[CreatedCallback],
                      eventCallback: Option[EventCallback]
                     )
  extends UnaryNode {

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case event: Event => {
      emitEvent(event)
    }
    case unhandledMessage => log.info(s"unhandled message $unhandledMessage")
  }

  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    val startTime = System.currentTimeMillis()
    val res = null // NodeFactory.createFilterNode(mode, hostInfo, backupMode, mainNode, query, parentNode, createdCallback, eventCallback, context)
    log.info(s"Spent ${System.currentTimeMillis() - startTime} milliseconds to initialize BenchmarkNode")
    res
  }

  def maxWindowTime(): Int = 0
}




