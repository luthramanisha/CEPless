package tcep.factories

import java.util.UUID

import akka.actor.{ActorContext, ActorRef, Deploy, Props}
import akka.remote.RemoteScope
import tcep.data.Queries._
import tcep.graph.nodes._
import tcep.graph.nodes.traits.Mode
import tcep.graph.nodes.traits.Mode._
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.placement.HostInfo

object NodeFactory {

  def createConjunctionNode(mode: Mode.Mode,
                            hostInfo: HostInfo,
                            backupMode: Boolean,
                            mainNode: Option[ActorRef],
                            query: ConjunctionQuery,
                            parentNode1: ActorRef,
                            parentNode2: ActorRef,
                            createdCallback: Option[CreatedCallback],
                            eventCallback: Option[EventCallback],
                            context: ActorContext): ActorRef = {
    context.actorOf(Props(
        classOf[ConjunctionNode],
        mode,
        hostInfo,
        backupMode,
        mainNode,
        query,
        parentNode1,
        parentNode2,
        createdCallback,
        eventCallback
      ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"ConjunctionNode${UUID.randomUUID.toString}"
    )
  }

  def createDisjunctionNode(mode: Mode.Mode,hostInfo: HostInfo,
                           backupMode: Boolean,
                           mainNode: Option[ActorRef],
                           query: DisjunctionQuery,
                           parentNode1: ActorRef,
                           parentNode2: ActorRef,
                           createdCallback: Option[CreatedCallback],
                           eventCallback: Option[EventCallback],
                           context: ActorContext): ActorRef = {

    context.system.actorOf(Props(
        classOf[DisjunctionNode],
        mode,
        hostInfo,
        backupMode,
        mainNode,
        query,
        parentNode1,
        parentNode2,
        createdCallback,
        eventCallback
      ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"DisjunctionNode${UUID.randomUUID.toString}"
    )
  }

  def createDropElementNode(mode: Mode.Mode,
                            hostInfo: HostInfo,
                            backupMode: Boolean,
                            mainNode: Option[ActorRef],
                            query: DropElemQuery,
                            parentNode: ActorRef,
                            createdCallback: Option[CreatedCallback],
                            eventCallback: Option[EventCallback],
                            context: ActorContext): ActorRef = {
    context.system.actorOf(Props(
        classOf[DropElemNode],
        mode,
        hostInfo,
        backupMode,
        mainNode,
        query,
        parentNode,
        createdCallback,
        eventCallback
      ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"DropElemNode${UUID.randomUUID.toString}"
    )
  }

  def createFilterNode(mode: Mode.Mode,
                       hostInfo: HostInfo,
                       backupMode: Boolean,
                       mainNode: Option[ActorRef],
                       query: FilterQuery,
                       parentNode: ActorRef,
                       createdCallback: Option[CreatedCallback],
                       eventCallback: Option[EventCallback],
                       context: ActorContext): ActorRef = {
    context.system.actorOf(Props(
        classOf[FilterNode],
        mode,
        hostInfo,
        backupMode,
        mainNode,
        query,
        parentNode,
        createdCallback,
        eventCallback
      ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"FilterNode${UUID.randomUUID.toString}"
    )
  }

  def createJoinNode(mode: Mode,
                     hostInfo: HostInfo,
                     backupMode: Boolean,
                     mainNode: Option[ActorRef],
                     query: JoinQuery,
                     parentNode1: ActorRef,
                     parentNode2: ActorRef,
                     createdCallback: Option[CreatedCallback],
                     eventCallback: Option[EventCallback],
                     context: ActorContext): ActorRef = {

    context.system.actorOf(Props(
        classOf[JoinNode],
        mode,
        hostInfo,
        backupMode,
        mainNode,
        query,
        parentNode1,
        parentNode2,
        createdCallback,
        eventCallback
      ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"JoinNode${UUID.randomUUID.toString}"
    )
  }

  def createSelfJoinNode(mode: Mode,
                         hostInfo: HostInfo,
                         backupMode: Boolean,
                         mainNode: Option[ActorRef],
                         query: SelfJoinQuery,
                         parentNode: ActorRef,
                         createdCallback: Option[CreatedCallback],
                         eventCallback: Option[EventCallback],
                         context: ActorContext): ActorRef = {

    context.system.actorOf(Props(
        classOf[SelfJoinNode],
        mode,
        hostInfo,
        backupMode,
        mainNode,
        query,
        parentNode,
        createdCallback,
        eventCallback
      ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"SelfJoinNode${UUID.randomUUID.toString}"
    )
  }

  def createSequenceNode(mode: Mode,
                         hostInfo: HostInfo,
                         backupMode: Boolean,
                         mainNode: Option[ActorRef],
                       query: SequenceQuery,
                       publishers: Seq[ActorRef],
                       createdCallback: Option[CreatedCallback],
                       eventCallback: Option[EventCallback],
                       context: ActorContext): ActorRef = {

    context.system.actorOf(Props(
        classOf[SequenceNode],
        mode,
        hostInfo,
        backupMode,
        mainNode,
        query,
        publishers,
        createdCallback,
        eventCallback
      ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"),s"SequenceNode${UUID.randomUUID.toString}"
    )
  }

  def createStreamNode( mode: Mode,
                        hostInfo: HostInfo,
                        backupMode: Boolean,
                        mainNode: Option[ActorRef],
                        query: StreamQuery,
                        publisher: ActorRef,
                        createdCallback: Option[CreatedCallback],
                        eventCallback: Option[EventCallback],
                        context: ActorContext): ActorRef = {

    context.system.actorOf(Props(
        classOf[StreamNode],
        mode,
        hostInfo,
        backupMode,
        mainNode,
        query,
        publisher,
        createdCallback,
        eventCallback
      ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"StreamNode${UUID.randomUUID.toString}"
    )
  }

  def createCustomNode(operatorName: String,
                       mode: Mode.Mode,
                       hostInfo: HostInfo,
                       backupMode: Boolean,
                       mainNode: Option[ActorRef],
                       query: CustomQuery,
                       parentNode: ActorRef,
                       createdCallback: Option[CreatedCallback],
                       eventCallback: Option[EventCallback],
                       context: ActorContext): ActorRef = {
    context.system.actorOf(Props(
      classOf[CustomNode],
      operatorName,
      mode,
      hostInfo,
      backupMode,
      mainNode,
      query,
      parentNode,
      createdCallback,
      eventCallback
    ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"CustomNode${UUID.randomUUID.toString}"
    )
  }

  def createBenchmarkNode(
                       mode: Mode.Mode,
                       hostInfo: HostInfo,
                       backupMode: Boolean,
                       mainNode: Option[ActorRef],
                       query: BenchmarkQuery,
                       parentNode: ActorRef,
                       createdCallback: Option[CreatedCallback],
                       eventCallback: Option[EventCallback],
                       context: ActorContext): ActorRef = {
    context.system.actorOf(Props(
      classOf[BenchmarkNode],
      mode,
      hostInfo,
      backupMode,
      mainNode,
      query,
      parentNode,
      createdCallback,
      eventCallback
    ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"BenchmarkNode${UUID.randomUUID.toString}"
    )
  }

  def createKMeansNode(
                           mode: Mode.Mode,
                           hostInfo: HostInfo,
                           backupMode: Boolean,
                           mainNode: Option[ActorRef],
                           query: KMeansQuery,
                           parentNode: ActorRef,
                           createdCallback: Option[CreatedCallback],
                           eventCallback: Option[EventCallback],
                           context: ActorContext): ActorRef = {
    context.system.actorOf(Props(
      classOf[KMeansNode],
      mode,
      hostInfo,
      backupMode,
      mainNode,
      query,
      parentNode,
      createdCallback,
      eventCallback
    ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"KMeansNode${UUID.randomUUID.toString}"
    )
  }

  def createForwardNode(
                           mode: Mode.Mode,
                           hostInfo: HostInfo,
                           backupMode: Boolean,
                           mainNode: Option[ActorRef],
                           query: ForwardQuery,
                           parentNode: ActorRef,
                           createdCallback: Option[CreatedCallback],
                           eventCallback: Option[EventCallback],
                           context: ActorContext): ActorRef = {
    context.system.actorOf(Props(
      classOf[ForwardNode],
      mode,
      hostInfo,
      backupMode,
      mainNode,
      query,
      parentNode,
      createdCallback,
      eventCallback
    ).withDeploy(Deploy(scope = RemoteScope(hostInfo.member.address))).withMailbox("prio-mailbox"), s"ForwardNode${UUID.randomUUID.toString}"
    )
  }
}
