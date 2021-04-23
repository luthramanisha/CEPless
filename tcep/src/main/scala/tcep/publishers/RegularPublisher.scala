package tcep.publishers

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import tcep.data.Events
import tcep.data.Events._

import scala.concurrent.duration.FiniteDuration

/**
  * Publishes the events at regular interval
  * @param waitTime the interval for publishing the events in MILLISECONDS
  * @param createEventFromId function to convert Id to an event
  */
case class RegularPublisher(waitTime: Long, createEventFromId: Integer => Event) extends Publisher {

  val publisherName: String = self.path.name
  val id: AtomicInteger = new AtomicInteger(0)
  val scheduler =  context.system.scheduler.schedule(
                            FiniteDuration(waitTime, TimeUnit.MILLISECONDS),
                            FiniteDuration(waitTime, TimeUnit.MILLISECONDS),
                            runnable = () => {
                              val eventMessage = id.incrementAndGet()
                              val event: Event = createEventFromId(eventMessage)
                              Events.initializeMonitoringData(log, event, 1000.0d / waitTime , this.cluster.selfMember.address)
                              subscribers.foreach(_ ! event)
                              log.info(s"Send event $event")
                            }
                          )

  override def postStop(): Unit = {
    scheduler.cancel()
    super.postStop()
  }
}
