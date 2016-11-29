package ly.stealth.mesos.kafka.mesos

import java.util.Date
import java.util.concurrent.{Future, TimeUnit}
import ly.stealth.mesos.kafka.{Broker, ClockComponent, Cluster, Config}
import org.apache.log4j.Logger
import org.apache.mesos.Protos.{TaskID, TaskState, TaskStatus}
import scala.collection.JavaConversions._

trait TaskReconcilerComponent {
  val taskReconciler : TaskReconciler

  trait TaskReconciler {
    def start(): Future[_]
    def isReconciling: Boolean

    def attempts: Int
    def lastReconcile: Date
  }
}

trait ClusterComponent {
  def cluster: Cluster
}

trait TaskReconcilerComponentImpl extends TaskReconcilerComponent {
  this: ClusterComponent
    with SchedulerDriverComponent
    with ClockComponent
    with EventLoopComponent =>

  val taskReconciler = new TaskReconcilerImpl

  class TaskReconcilerImpl extends TaskReconciler {
    import ly.stealth.mesos.kafka.RunnableConversions._

    private[this] val logger = Logger.getLogger(classOf[TaskReconciler])

    private var _attempts: Int = 1
    private var _lastAttempt: Date = new Date(0)

    def attempts: Int = _attempts
    def lastReconcile: Date = _lastAttempt

    def isReconciling: Boolean =
      cluster.getBrokers.exists(b => b.task != null && b.task.reconciling)

    private def scheduleRetry() = eventLoop.schedule(
      retryReconciliation _,
      Config.reconciliationTimeout.ms,
      TimeUnit.MILLISECONDS)

    def retryReconciliation(): Unit = {
      if (!isReconciling) {
        return
      }

      _attempts += 1
      _lastAttempt = clock.now()

      val brokersReconciling = cluster.getBrokers.filter(b => b.task != null && b.task.reconciling)
      brokersReconciling.foreach(broker => {
        logger.info(
          s"Reconciling $attempts/${ Config.reconciliationAttempts } state of broker" +
          s" ${ broker.id }, task ${ broker.task.id }")
      })

      if (attempts > Config.reconciliationAttempts) {
        for (broker <- brokersReconciling) {
          logger.info(
            s"Reconciling exceeded ${ Config.reconciliationAttempts } tries " +
              s"for broker ${ broker.id }, sending killTask for task ${ broker.task.id }")
          Driver.call(_.killTask(TaskID.newBuilder().setValue(broker.task.id).build()))
          broker.task.state = Broker.State.STOPPING
        }
        cluster.save()
      }
      else {
        val statuses = brokersReconciling
          .map(broker => {
            TaskStatus.newBuilder()
              .setTaskId(TaskID.newBuilder().setValue(broker.task.id))
              .setState(TaskState.TASK_STAGING)
              .build()
          })

        if (statuses.nonEmpty) {
          Driver.call(_.reconcileTasks(statuses))
          scheduleRetry()
        }
      }
    }

    def start(): Future[_] = {
      eventLoop.submit(() =>
        if (isReconciling) {
          logger.warn("Reconcile already in progress, skipping.")
        } else {
          startImpl()
        }
      )
    }

    private def startImpl(): Future[_] = {
      logger.info("Starting reconciliation")
      val brokersToReconcile = cluster.getBrokers.filter(_.task != null)
      brokersToReconcile.foreach(broker => {
        broker.task.state = Broker.State.RECONCILING
        logger.info(s"Reconciling $attempts/${ Config.reconciliationAttempts } " +
          s"state of broker ${ broker.id }, task ${ broker.task.id }")
      })

      Driver.call(_.reconcileTasks(Seq()))
      cluster.save()

      scheduleRetry()
    }
  }
}
