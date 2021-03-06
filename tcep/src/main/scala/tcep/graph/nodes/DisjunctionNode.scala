package tcep.graph.nodes

import akka.actor.{ActorLogging, ActorRef}
import tcep.data.Events._
import tcep.data.Queries._
import tcep.factories.NodeFactory
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback, QueryGraph}
import tcep.placement.HostInfo

import scala.collection.mutable.ListBuffer

/**
  * Handling of [[tcep.data.Queries.DisjunctionQuery]] is done by DisjunctionNode.
  *
  * @see [[QueryGraph]]
  **/


case class DisjunctionNode(mode: Mode.Mode,
                           hostInfo: HostInfo,
                           backupMode: Boolean,
                           mainNode: Option[ActorRef],
                           query: DisjunctionQuery,
                           @volatile var parentNode1: ActorRef,
                           @volatile var parentNode2: ActorRef,
                           createdCallback: Option[CreatedCallback],
                           eventCallback: Option[EventCallback])
  extends BinaryNode with ActorLogging {

  def fillArray(desiredLength: Int, array: Array[Either[Any, Any]]): Array[Either[Any, Any]] = {
    require(array.length <= desiredLength)
    require(array.length > 0)
    val unit: Either[Unit, Unit] = array(0) match {
      case Left(_) => Left(())
      case Right(_) => Right(())
    }
    (0 until desiredLength).map(i => {
      if (i < array.length) {
        array(i)
      } else {
        unit
      }
    }).toArray
  }

  def handleEvent(array: Array[Either[Any, Any]], monitoringData: ListBuffer[MonitoringData]): Unit = query match {
    case _: Query1[_] =>
      val filledArray: Array[Either[Any, Any]] = fillArray(1, array)
      val event = Event1(filledArray(0))
      event.monitoringData = monitoringData.clone()
      emitEvent(event)
    case _: Query2[_, _] =>
      val filledArray: Array[Either[Any, Any]] = fillArray(2, array)
      val event = Event2(filledArray(0), filledArray(1))
      event.monitoringData = monitoringData.clone()
      emitEvent(event)
    case _: Query3[_, _, _] =>
      val filledArray: Array[Either[Any, Any]] = fillArray(3, array)
      val event = Event3(filledArray(0), filledArray(1), filledArray(2))
      event.monitoringData = monitoringData.clone()
      emitEvent(event)
    case _: Query4[_, _, _, _] =>
      val filledArray: Array[Either[Any, Any]] = fillArray(4, array)
      val event = Event4(filledArray(0), filledArray(1), filledArray(2), filledArray(3))
      event.monitoringData = monitoringData.clone()
      emitEvent(event)
    case _: Query5[_, _, _, _, _] =>
      val filledArray: Array[Either[Any, Any]] = fillArray(5, array)
      val event = Event5(filledArray(0), filledArray(1), filledArray(2), filledArray(3), filledArray(4))
      event.monitoringData = monitoringData.clone()
      emitEvent(event)
    case _: Query6[_, _, _, _, _, _] =>
      val filledArray: Array[Either[Any, Any]] = fillArray(6, array)
      val event = Event6(filledArray(0), filledArray(1), filledArray(2), filledArray(3), filledArray(4), filledArray(5))
      event.monitoringData = monitoringData.clone()
      emitEvent(event)
  }

  override def childNodeReceive: Receive = super.childNodeReceive orElse {
    case event: Event if p1List.contains(sender()) => event match {
      case Event1(e1) => handleEvent(Array(Left(e1)), event.monitoringData)
      case Event2(e1, e2) => handleEvent(Array(Left(e1), Left(e2)), event.monitoringData)
      case Event3(e1, e2, e3) => handleEvent(Array(Left(e1), Left(e2), Left(e3)), event.monitoringData)
      case Event4(e1, e2, e3, e4) => handleEvent(Array(Left(e1), Left(e2), Left(e3), Left(e4)), event.monitoringData)
      case Event5(e1, e2, e3, e4, e5) => handleEvent(Array(Left(e1), Left(e2), Left(e3), Left(e4), Left(e5)), event.monitoringData)
      case Event6(e1, e2, e3, e4, e5, e6) => handleEvent(Array(Left(e1), Left(e2), Left(e3), Left(e4), Left(e5), Left(e6)), event.monitoringData)
    }
    case event: Event if p2List.contains(sender()) => event match {
      case Event1(e1) => handleEvent(Array(Right(e1)), event.monitoringData)
      case Event2(e1, e2) => handleEvent(Array(Right(e1), Right(e2)), event.monitoringData)
      case Event3(e1, e2, e3) => handleEvent(Array(Right(e1), Right(e2), Right(e3)), event.monitoringData)
      case Event4(e1, e2, e3, e4) => handleEvent(Array(Right(e1), Right(e2), Right(e3), Right(e4)), event.monitoringData)
      case Event5(e1, e2, e3, e4, e5) => handleEvent(Array(Right(e1), Right(e2), Right(e3), Right(e4), Right(e5)), event.monitoringData)
      case Event6(e1, e2, e3, e4, e5, e6) => handleEvent(Array(Right(e1), Right(e2), Right(e3), Right(e4), Right(e5), Right(e6)), event.monitoringData)
    }
    case unhandledMessage => log.info(s"unhandled message $unhandledMessage")
  }

  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    NodeFactory.createDisjunctionNode(mode, hostInfo, backupMode, mainNode, query, p1List.last, p2List.last, createdCallback, eventCallback, context)
  }

  def maxWindowTime(): Int = 0
}
