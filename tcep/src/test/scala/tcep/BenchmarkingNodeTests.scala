package tcep

import tcep.dsl.Dsl._
import tcep.placement.PlacementStrategy
import tcep.placement.benchmarking.BenchmarkingNode
import tcep.placement.manets.StarksAlgorithm
import tcep.placement.sbon.PietzuchAlgorithm
import org.scalatest.FunSuite

class BenchmarkingNodeTests extends FunSuite{

  test("BenchmarkingNode should return Starks Algorithm on MessageOverhead requirement") {
    val messageOverheadRequirement = overhead < 10 otherwise Option.empty
    val res = BenchmarkingNode.selectBestPlacementAlgorithm(List.empty, List(messageOverheadRequirement))
    assert(res.placement === StarksAlgorithm)
  }

  test("BenchmarkingNode should return Pietzuch Algorithm on Latency requirement") {
    val latencyRequirement = latency < timespan(500.milliseconds) otherwise Option.empty
    val res = BenchmarkingNode.selectBestPlacementAlgorithm(List.empty, List(latencyRequirement))
    assert(res.placement === PietzuchAlgorithm)
  }

  test("BenchmarkingNode should not fail on unrealistic demands") {
    val latencyRequirement = latency < timespan(500.milliseconds) otherwise Option.empty
    val messageOverheadRequirement = overhead < 10 otherwise Option.empty
    val res = BenchmarkingNode.selectBestPlacementAlgorithm(List.empty, List(latencyRequirement, messageOverheadRequirement))
    assert(res.placement.isInstanceOf[PlacementStrategy])
  }

}