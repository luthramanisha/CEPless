package tcep.graph.nodes

import java.util.Random

import akka.actor.ActorRef
import tcep.data.Events._
import tcep.data.Queries._
import tcep.graph.nodes.traits._
import tcep.graph.{CreatedCallback, EventCallback}
import tcep.placement.HostInfo
import tcep.graph.nodes.traits.Mode.Mode

import scala.language.postfixOps


case class KMeansNode(mode: Mode,
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
      val event2: Event2 = event.asInstanceOf[Event2]
      val result = predict(event2.e1.asInstanceOf[Int], event2.e2.asInstanceOf[Array[Array[Float]]])
      emitEvent(Event1(result))
    }
  }

  def createDuplicateNode(hostInfo: HostInfo): ActorRef = {
    null
  }

  def maxWindowTime(): Int = 0

  import java.util

  private val mRandomState = new Random()
  private val mMaxIterations = 30
  private val mSqConvergenceEpsilon = 0.005f

  def predict(k: Int, inputData: Array[Array[Float]]): util.ArrayList[Mean] = {
    var dimension: Int = inputData(0).length
    val means: util.ArrayList[Mean] = new util.ArrayList[Mean]()
    for (i <- 0 to k) {
      var m: Mean = new Mean(dimension)
      for (j <- 0 to dimension) {
        m.mCentroid(j) = mRandomState.nextFloat()
      }
      means.add(m)
    }
    var converged: Boolean = false
    for (i <- 0 to mMaxIterations) {
      converged = step(means, inputData)
      if (converged) {
        return means
      }
    }
    means
  }

  /**
    * K-Means iteration.
    *
    * @param means     Current means
    * @param inputData Input data
    * @return True if data set converged
    */
  def step(means: util.ArrayList[Mean], inputData: Array[Array[Float]]): Boolean = {
    for (i <- (means.size() - 1) to 0) {
      val mean = means.get(i)
      mean.mClosestItems.clear()
    }
    for (i <- (inputData.length - 1) to 0) {
      val current = inputData(i)
      val nearest = nearestMean(current, means)
      nearest.mClosestItems.add(current)
    }
    var converged = true
    for (i <- (means.size() - 1) to 0) {
      var means2 = means
      var mean = means2.get(i)
      val oldCentroid = mean.mCentroid
      means2 = converge(i, means2)
      mean = means2.get(i)
      if (sqDistance(oldCentroid, mean.mCentroid) > mSqConvergenceEpsilon) converged = false
    }
    converged
  }

  def converge(i: Int, means: util.ArrayList[Mean]): util.ArrayList[Mean] = {
    val mean: Mean = means.get(i)
    if (mean.mClosestItems.size() == 0) {
      return means
    }
    val oldCentroid: Array[Float] = mean.mCentroid
    mean.mCentroid = new Array[Float](oldCentroid.length)
    for (i <- 0 to mean.mClosestItems.size()) {
      for (j <- 0 to mean.mCentroid.length) {
        mean.mCentroid(j) += mean.mClosestItems.get(i)(j)
      }
    }
    for (i <- 0 to mean.mCentroid.length) {
      mean.mCentroid(i) = mean.mCentroid(i) / mean.mClosestItems.size()
    }
    means
  }

  def nearestMean(point: Array[Float], means: util.ArrayList[Mean]): Mean = {
    var nearest: Mean = null
    var nearestDistance: Float = Float.MaxValue
    val meanCount: Int = means.size()
    for (i <- 0 to meanCount) {
      var next: Mean = means.get(i)
      var nextDistance = sqDistance(point, next.mCentroid)
      if (nextDistance < nearestDistance) {
        nearest = next
        nearestDistance = nextDistance
      }
    }
    nearest
  }

  def sqDistance(a: Array[Float], b: Array[Float]): Float = {
    var dist: Float = 0
    val length: Int = a.length
    for (i <- 0 to length) {
      dist += (a(i) - b(i)) * (a(i) - b(i))
    }
    dist
  }

  /**
    * Definition of a mean, contains a centroid and points on its cluster.
    */
  class Mean {
    var mCentroid: Array[Float] = null
    final val mClosestItems = new util.ArrayList[Array[Float]]

    def this(dimension: Int) {
      this()
      mCentroid = new Array[Float](dimension)
    }

    def this(centroid: Float*) {
      this()
      mCentroid = centroid.toArray
    }

    def getCentroid: Array[Float] = mCentroid

    def getItems: util.ArrayList[Array[Float]] = mClosestItems

    override def toString: String = "Mean(centroid: " + util.Arrays.toString(mCentroid) + ", size: " + mClosestItems.size + ")"
  }
}

