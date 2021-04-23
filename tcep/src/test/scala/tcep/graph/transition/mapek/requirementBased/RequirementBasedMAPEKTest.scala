
package tcep.graph.transition.mapek.requirementBased

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestKit
import org.scalatest.WordSpecLike
import org.scalatest.mockito.MockitoSugar
import tcep.data.Queries.{Requirement, Stream1}
import tcep.data.Structures.MachineLoad
import tcep.dsl.Dsl._
import tcep.graph.QueryGraph
import tcep.graph.nodes.traits.Mode
import tcep.graph.nodes.traits.Mode.Mode
import tcep.graph.transition.{AddNewDemand, MAPEK, RemoveDemand, TransitionRequest}
import tcep.placement.GlobalOptimalBDPAlgorithm
import tcep.placement.benchmarking.{BenchmarkingNode, LowChurnRate, PlacementAlgorithm}
import tcep.placement.manets.StarksAlgorithm
import tcep.placement.sbon.PietzuchAlgorithm

import scala.collection.mutable.ListBuffer

class MAPEKTest extends TestKit(ActorSystem())  with WordSpecLike with MockitoSugar {

  val latencyRequirement = latency < timespan(500.milliseconds) otherwise None
  val messageHopsRequirement = overhead < 3 otherwise None
  val loadRequirement = load < MachineLoad(1.0) otherwise None

  class WrapperActor(mode: Mode, initialRequirements: Set[Requirement]) extends Actor {

    var mapek: MAPEK = _
    var subscribers = ListBuffer[ActorRef]()
    override def preStart(): Unit = {
      super.preStart()
      mapek = new MAPEK(context, Stream1[Int]("A", initialRequirements), mock[QueryGraph])
      mapek.knowledge.client = testActor
      mapek.knowledge.setStrategy(BenchmarkingNode.selectBestPlacementAlgorithm(List(LowChurnRate), initialRequirements.toList))
      println(s"\n starting placement algorithm: ${mapek.knowledge.getStrategy()} \n")
    }

    override def receive: Receive = {

      case message => mapek.monitor.forward(message)
    }
  }

  "RequirementBasedMAPEK" must {
    "propagate msgHops requirement addition, return Starks algorithm" in {
      val w = system.actorOf(Props( new WrapperActor(Mode.MFGS, Set(latencyRequirement))))

      Thread.sleep(3000)
      w ! RemoveDemand(latencyRequirement)
      w ! AddNewDemand(Seq(messageHopsRequirement))
      expectMsg(TransitionRequest(PlacementAlgorithm(StarksAlgorithm, List(LowChurnRate), List("messageHops", "machineLoad"), 100)))

    }
  }

  "RequirementBasedMAPEK" must {
    "select GlobalOptimalBDP after latency requirement is removed and latency + msgHops requirement are added (Relaxation->GlobalOptimalBDP)" in {
      val w = system.actorOf(Props( new WrapperActor(Mode.MFGS, Set(latencyRequirement))))
      Thread.sleep(3000)
      w ! RemoveDemand(latencyRequirement)
      w ! AddNewDemand(Seq(latencyRequirement, messageHopsRequirement))
      expectMsg(TransitionRequest(PlacementAlgorithm(GlobalOptimalBDPAlgorithm, List(LowChurnRate), List("latency", "messageHops"), 100)))
    }
  }


  "RequirementBasedMAPEK" must {
    "select Relaxation after latency and msgHops requirement is removed and latency requirement is added (GlobalOptimalBDP->Relaxation)" in {
      val w = system.actorOf(Props( new WrapperActor(Mode.MFGS, Set(latencyRequirement, messageHopsRequirement))))
      Thread.sleep(3000)
      w ! RemoveDemand(latencyRequirement)
      w ! RemoveDemand(messageHopsRequirement)
      w ! AddNewDemand(Seq(latencyRequirement, loadRequirement))
      expectMsg(TransitionRequest(PlacementAlgorithm(PietzuchAlgorithm, List(LowChurnRate), List("latency", "machineLoad"), 100)))
    }
  }
}
