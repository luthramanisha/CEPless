package customoperators.repository

import tcep.ClusterActor

trait EventRepository {
  def listen(addr: String): Unit
  def send(addr: String, item: String): Unit
}
