package tcep.publishers

import java.util.Properties
import java.util.concurrent.TimeUnit

import akka.pattern.ask
import akka.actor.ActorRef
import akka.util.Timeout
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecords, KafkaConsumer}
import org.apache.kafka.common.serialization.StringDeserializer
import tcep.data.Events.Event5

import scala.concurrent.duration.FiniteDuration

case class KafkaPublisher(waitTime: Long) extends Publisher {

  implicit val timeout = Timeout(5, TimeUnit.SECONDS)

  var running = false
  var consumer: Option[KafkaConsumer[String, String]] = null

  val props = new Properties()
  props.put("bootstrap.servers", System.getenv("KAFKA_HOST").toString + ":9092")
  props.put("group.id", "consumer-tutorial")
  props.put("key.deserializer", classOf[StringDeserializer].getName)
  props.put("value.deserializer", classOf[StringDeserializer].getName)
  consumer = Option(new KafkaConsumer[String, String](props))
  consumer.get.subscribe(java.util.Arrays.asList(sys.env("CUSTOM_OPERATOR")))
  // consumer.get.subscribe(java.util.Arrays.asList("op-test"))

  val thread = new Thread {
    override def run {
       try {
        while (running) {
          if (subscribers.size > 0) {
            val records: ConsumerRecords[String, String] = consumer.get.poll(1000)
            val it = records.iterator()
            while (it.hasNext) {
              val record = it.next()
              val value = record.value
              if (!value.isEmpty && value != null) {
                val parts = value.split(",")
                val event = Event5(parts(0), parts(1), parts(2), parts(3), System.nanoTime())
                for {
                  t <- subscribers.toList(0) ? event
                } yield {}
              }
            }
          }
        }
      } catch {
        case err: Exception => println(s"Error $err")
        case _ => println("undefined error")
      } finally {
        consumer.get.close()
      }
    }
  }

  private val scheduler = context.system.scheduler.scheduleOnce(
    FiniteDuration(30, TimeUnit.SECONDS),
    () => {
      println("Started Kafka receive")
      running = true
      thread.start()
    })

  override def postStop(): Unit = {
    super.postStop()
  }
}
