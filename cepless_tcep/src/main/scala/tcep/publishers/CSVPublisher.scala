package tcep.publishers

import java.util.concurrent.{ScheduledThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration

import tcep.data.Events
import tcep.data.Events._

import scala.collection.mutable.ArrayBuffer

/**
  * Publishes the events at regular interval
  * @param waitTime the interval for publishing the events in MILLISECONDS
  * @param path the path to the CSV file that should be send
  */
case class CSVPublisher(waitTime: Long, path: String) extends Publisher {

  private val id: AtomicInteger = new AtomicInteger(0)
  private val rows = ArrayBuffer[Array[String]]()
  private val bufferedSource = scala.io.Source.fromFile("/app/data/cardtransactions-reduced.csv")

  for (line <- bufferedSource.getLines) {
    rows += line.split(",").map(_.trim)
  }
  bufferedSource.close

  val ex = new ScheduledThreadPoolExecutor(1)
  val task = new Runnable {
    def run() = {
      print("Throughput " + sendCount)
      sendCount = 0
    }
  }
  val f = ex.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS)

  private var sendCount = 0

  def address =  this.cluster.selfMember.address
  val thread = new Thread {
    override def run {
      for (row <- rows) {
        sendCount += 1
        val eventMessage = id.incrementAndGet()
        val event = Event5(System.currentTimeMillis(), eventMessage, waitTime, row(1), row(2))
        Events.initializeMonitoringData(log, event, 1000.0d / waitTime , address)
        subscribers.foreach(_ ! event)
        log.info(s"Send event $event")
        busySleep(waitTime)
      }

      def busySleep(nanos: Long): Unit = {
        var elapsed = 0L
        val startTime = System.nanoTime
        do elapsed = System.nanoTime - startTime while ( {
          elapsed < nanos
        })
      }
    }
  }

  private val scheduler = context.system.scheduler.scheduleOnce(
    FiniteDuration(1, TimeUnit.MINUTES),
    () => {
      thread.start()
    })


  /*private val scheduler =  context.system.scheduler.schedule(
    FiniteDuration(waitTime, TimeUnit.MICROSECONDS),
    FiniteDuration(waitTime, TimeUnit.MICROSECONDS),
    runnable = () => {
      if (sendCount >= rows.length) {
        log.info("Send all events from file")
        postStop()
      } else {
        val row = rows(sendCount)
        sendCount += 1
        val eventMessage = id.incrementAndGet()
        val event = Event5(System.currentTimeMillis(), eventMessage, waitTime, row(1), row(2))
        Events.initializeMonitoringData(log, event, 1000.0d / waitTime , this.cluster.selfMember.address)
        subscribers.foreach(_ ! event)
        log.info(s"Send event $event")
      }
    }
  )*/

  override def postStop(): Unit = {
    super.postStop()
  }
}
