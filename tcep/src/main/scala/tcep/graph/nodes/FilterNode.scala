package tcep.graph.nodes

import java.io.{File, PrintStream}

import akka.actor.ActorRef
import tcep.data.Events._
import tcep.data.Queries._
import tcep.factories.NodeFactory
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback, QueryGraph}
import tcep.placement.HostInfo
import tcep.simulation.tcep.SimulationRunner.logger

/**
  * Handling of [[tcep.data.Queries.FilterQuery]] is done by FilterNode.
  *
  * @see [[QueryGraph]]
  **/

case class FilterNode(mode: Mode.Mode,
                      hostInfo: HostInfo,
                      backupMode: Boolean,
                      mainNode: Option[ActorRef],
                      query: FilterQuery,
                      @volatile var parentNode: ActorRef,
                      createdCallback: Option[CreatedCallback],
                      eventCallback: Option[EventCallback]
                     )
  extends UnaryNode {

  val directory = if(new File("./logs").isDirectory) Some(new File("./logs")) else {
    logger.info("Invalid directory path")
    None
  }

  val csvWriter = directory map { directory => new PrintStream(new File(directory, s"filter-messages.csv"))
  } getOrElse java.lang.System.out
  csvWriter.println("time\tfiltered\tvalue1\tvalue2")

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
    log.info(s"Spent ${System.currentTimeMillis() - startTime} milliseconds to initialize FilterNode")
    res
  }

  def maxWindowTime(): Int = 0
}

