package mycelium.client

import monix.execution.Ack
import monix.reactive.observers.CacheUntilConnectSubscriber
import monix.reactive.subjects.PublishSubject
import monix.reactive.{Observable, Observer}
import mycelium.core.EventualResult
import mycelium.core.message._

import scala.concurrent.{Future, Promise}

class ResponseObserver[Payload, ErrorType](promise: Promise[EventualResult[Payload, ErrorType]]) extends Observer[ServerMessage[Payload, ErrorType] with ServerResponse] {
  @volatile private var observerOpt: Option[Observer[Payload]] = None

  override def onError(ex: Throwable): Unit = observerOpt.foreach(_.onError(ex))
  override def onComplete(): Unit = observerOpt.foreach(_.onComplete())

  override def onNext(elem: ServerMessage[Payload, ErrorType] with ServerResponse): Future[Ack] = observerOpt match {
    case None => elem match {
      case SingleResponse(_, result) =>
        promise trySuccess EventualResult.Single(result)
        Ack.Stop
      case StreamResponse(_, result) =>
        val underlying = PublishSubject[Payload]()
        val observer = CacheUntilConnectSubscriber(subject)
        val observable = underlying.doAfterSubscribe(() => observer.connect())
        observerOpt = Some(observer)
        promise trySuccess EventualResult.Stream(observable)
        observer.onNext(result)
      case StreamCloseResponse(_) =>
        promise trySuccess EventualResult.Stream(Observable.empty)
        Ack.Stop
      case ErrorResponse(_, msg) =>
        promise trySuccess EventualResult.Error(msg)
        Ack.Stop
      case ExceptionResponse(_) =>
        promise tryFailure RequestException.ExceptionResponse
        Ack.Stop
    }
    case Some(observer) => elem match {
      case StreamResponse(_, result) =>
        observer.onNext(result)
      case StreamCloseResponse(_) =>
        observer.onComplete()
        Ack.Stop
      case ExceptionResponse(_) =>
        observer.onError(RequestException.ExceptionResponse)
        Ack.Stop
      case response =>
        observer.onError(RequestException.IllegalResponse(response))
        Ack.Stop
    }
  }
}
