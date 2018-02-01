package mycelium.client

import mycelium.core.message._
import chameleon._

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

case class WebsocketClientConfig(minReconnectDelay: FiniteDuration = 60.seconds, maxReconnectDelay: FiniteDuration = 1.seconds, delayReconnectFactor: Double = 1.3, connectingTimeout: FiniteDuration = 5.seconds, pingInterval: FiniteDuration = 45.seconds)

class WebsocketClientWithPayload[PickleType, Payload, Event, Failure](
  wsConfig: WebsocketClientConfig,
  ws: WebsocketConnection[PickleType],
  handler: IncidentHandler[Event],
  callRequests: CallRequests[Either[Failure, Payload]])(implicit
  serializer: Serializer[ClientMessage[Payload], PickleType],
  deserializer: Deserializer[ServerMessage[Payload, Event, Failure], PickleType]) {

  def send(path: List[String], payload: Payload, sendType: SendType, requestTimeout: FiniteDuration)(implicit ec: ExecutionContext): Future[Either[Failure, Payload]] = {
    val (seqId, promise) = callRequests.open()
    val request = CallRequest(seqId, path, payload)
    val pickledRequest = serializer.serialize(request)

    def startTimeout(): Unit = callRequests.startTimeout(promise, requestTimeout)
    def fail(): Unit = callRequests.drop(promise)

    val message = sendType match {
      case SendType.NowOrFail =>
        WebsocketMessage.Direct(pickledRequest, startTimeout _, fail _)
      case SendType.WhenConnected(priority) =>
        WebsocketMessage.Buffered(pickledRequest, startTimeout _, priority)
    }

    ws.send(message)

    promise.future.failed.foreach { err =>
      scribe.error(s"Request $seqId failed: $err")
    }

    promise.future
  }

  def run(location: String): Unit = ws.run(location, wsConfig, serializer.serialize(Ping()), new WebsocketListener[PickleType] {
    def onConnect() = handler.onConnect()
    def onClose() = handler.onClose()
    def onMessage(msg: PickleType): Unit = {
      deserializer.deserialize(msg) match {
        case Right(CallResponse(seqId, result)) =>
          callRequests.get(seqId) match {
            case Some(promise) =>
              val completed = promise trySuccess result
              if (!completed) scribe.warn(s"Ignoring incoming response ($seqId), it already timed out.")
            case None => scribe.warn(s"Ignoring incoming response ($seqId), unknown sequence id.")
          }
        case Right(Notification(events)) =>
          handler.onEvents(events)
        case Right(Pong()) =>
          // do nothing
        case Left(error) =>
          scribe.warn(s"Ignoring message. Deserializer failed: $error")
      }
    }
  })
}

object WebsocketClient {
  def apply[PickleType, Event, Failure](
    connection: WebsocketConnection[PickleType],
    config: WebsocketClientConfig,
    handler: IncidentHandler[Event])(implicit
    serializer: Serializer[ClientMessage[PickleType], PickleType],
    deserializer: Deserializer[ServerMessage[PickleType, Event, Failure], PickleType]) =
    withPayload[PickleType, PickleType, Event, Failure](connection, config, handler)

  def withPayload[PickleType, Payload, Event, Failure](
    connection: WebsocketConnection[PickleType],
    config: WebsocketClientConfig,
    handler: IncidentHandler[Event])(implicit
    serializer: Serializer[ClientMessage[Payload], PickleType],
    deserializer: Deserializer[ServerMessage[Payload, Event, Failure], PickleType]) = {
    val callRequests = new CallRequests[Either[Failure, Payload]]
    new WebsocketClientWithPayload(config, connection, handler, callRequests)
  }
}
