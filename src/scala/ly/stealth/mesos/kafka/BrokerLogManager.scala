package ly.stealth.mesos.kafka

import java.util.concurrent.{TimeUnit, TimeoutException}
import org.jboss.netty.util.{HashedWheelTimer, Timeout, TimerTask}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.Success

trait BrokerLogManagerComponent {
  val brokerLogManager: BrokerLogManager

  trait BrokerLogManager {
    def initLogRequest(timeout: Duration): (Long, Future[String])
    def putLog(requestId: Long, content: String): Unit
  }
}

trait BrokerLogManagerComponentImpl extends BrokerLogManagerComponent {
  val brokerLogManager = new BrokerLogManagerImpl

  class BrokerLogManagerImpl extends BrokerLogManager {
    private[this] val pendingLogs = mutable.Map[Long, Promise[String]]()
    private[this] val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)

    def putLog(requestId: Long, content: String): Unit = {
      synchronized {
        pendingLogs.get(requestId).foreach(log => {
          log.complete(Success(content))
        })
      }
    }

    private def scheduleTimeout(promise: Promise[String], timeout: Duration): Timeout = {
      timer.newTimeout(new TimerTask {
        override def run(timeout: Timeout): Unit = promise.tryFailure(new TimeoutException())
      }, timeout.length, timeout.unit)
    }

    def initLogRequest(timeout: Duration): (Long, Future[String]) = {
      val requestId = System.currentTimeMillis()
      val promise = Promise[String]()
      synchronized {
        pendingLogs.put(requestId, promise)
      }
      val timer = scheduleTimeout(promise, timeout)
      promise.future.onComplete(_ => {
        timer.cancel()
        synchronized {
          pendingLogs.remove(requestId)
        }
      })

      requestId -> promise.future
    }
  }
}

