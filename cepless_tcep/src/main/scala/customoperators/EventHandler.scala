package customoperators

trait EventHandler {
  def processElement(value: String)
}

case class OperatorAddress(addrIn: String, addrOut: String)
