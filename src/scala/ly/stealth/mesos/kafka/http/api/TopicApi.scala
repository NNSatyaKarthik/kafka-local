package ly.stealth.mesos.kafka.http.api

import java.lang.{Integer => JInt}
import javax.ws.rs.core.{MediaType, Response}
import javax.ws.rs.{DefaultValue, GET, POST, Path, PathParam, Produces}
import ly.stealth.mesos.kafka.http.BothParam
import ly.stealth.mesos.kafka.{Expr, ListTopicsResponse, RebalanceStartResponse, Rebalancer}
import ly.stealth.mesos.kafka.mesos.ClusterComponent
import net.elodina.mesos.util.Period
import scala.collection.JavaConversions._
import scala.util.Try

trait TopicApiComponent {
  val topicApi: TopicApi
  trait TopicApi {}
}

trait TopicApiComponentImpl extends TopicApiComponent {
  this: ClusterComponent =>

  val topicApi: TopicApi = new TopicApiImpl

  @Path("/topic")
  class TopicApiImpl extends TopicApi {

    @Path("list")
    @GET
    @Produces(Array(MediaType.APPLICATION_JSON))
    def list(
      @DefaultValue("*") @BothParam("topic") expr: String
    ): Response = {
      val names = Expr.expandTopics(expr).toSet
      Response.ok(ListTopicsResponse(
        cluster.topics.getTopics.filter(t => names.contains(t.name))
      )).build()
    }

    @Path("list")
    @POST
    @Produces(Array(MediaType.APPLICATION_JSON))
    def listPost(
      @DefaultValue("*") @BothParam("topic") expr: String
    ) = list(expr)

    @Path("{op: (add|update)}")
    @POST
    @Produces(Array(MediaType.APPLICATION_JSON))
    def addTopic(
      @PathParam("op") operation: String,
      @BothParam("topic") topicExpr: String,
      @BothParam("broker") brokerExpr: String,
      @DefaultValue("1") @BothParam("partitions") partitions: JInt,
      @DefaultValue("1") @BothParam("replicas") replicas: JInt,
      @DefaultValue("-1") @BothParam("fixedStartIndex") fixedStartIndex: Int,
      @DefaultValue("-1") @BothParam("startPartitionId") startPartitionId: Int,
      @BothParam("options") options: StringMap
    ): Response = {
      val add = operation == "add"
      if (topicExpr == null)
        return Status.BadRequest("topic required")

      val brokerIds = Option(brokerExpr).map(b => Expr.expandBrokers(cluster, b, sortByAttrs = true).map(_.toInt))
      if (!add && options == null)
        return Status.BadRequest("options required")
      if (options != null)
        Option(cluster.topics.validateOptions(options)) match {
          case Some(err) => return Status.BadRequest(err)
          case _ =>
        }

      val topics = Expr.expandTopics(topicExpr)
      val topicErrors = topics.flatMap(topic => Option(cluster.topics.getTopic(topic)) match {
        case Some(_) if add => Some(s"Topic $topic already exists")
        case None if !add => Some(s"Topic $topic not found")
        case _ => None
      })
      if (topicErrors.nonEmpty)
        return Status.BadRequest(topicErrors.mkString("; "))

      val result = if (add) {
        topics.map(t => cluster.topics.addTopic(
          t,
          cluster.topics.fairAssignment(
            partitions,
            replicas,
            brokerIds.orNull,
            fixedStartIndex,
            startPartitionId),
          options
        ))
      } else {
        topics.map(t => cluster.topics.updateTopic(cluster.topics.getTopic(t), options))
      }
      Response.ok(ListTopicsResponse(result)).build()
    }

    @Path("rebalance")
    @POST
    @Produces(Array(MediaType.APPLICATION_JSON))
    def rebalance(
      @BothParam("topic") topicExpr: String,
      @DefaultValue("*") @BothParam("broker") brokerExpr: String,
      @DefaultValue("-1") @BothParam("replicas") replicas: Int,
      @DefaultValue("-1") @BothParam("fixedStartIndex") fixedStartIndex: Int,
      @DefaultValue("-1") @BothParam("startPartitionId") startPartitionId: Int,
      @BothParam("timeout") timeout: Period,
      @DefaultValue("false") @BothParam("realign") realign: Boolean
    ): Response = {
      val topics = Expr.expandTopics(topicExpr)
      if (topics != null && cluster.rebalancer.running)
        return Status.BadRequest("rebalance is already running")
      if (topics != null && topics.isEmpty)
        return Status.BadRequest("no topics specified")

      val brokers = Expr.expandBrokers(cluster, brokerExpr, sortByAttrs = true)
      if (realign && startPartitionId != -1) {
        return Status.BadRequest("startPartitionId is incompatible with realign")
      }

      val (status, error) =
        if (topics != null) {
          Try(
            cluster.rebalancer
              .start(topics, brokers, replicas, fixedStartIndex, startPartitionId, realign)
          ).map(_ => {
            if (timeout != null) {
              if (!cluster.rebalancer.waitFor(running = false, timeout))
                "timeout" -> None
              else
                "completed" -> None
            } else {
              if (realign && !cluster.rebalancer.running)
                ("idle (no-op)", None)
              else ("started", None)
            }
          }).recover({
            case e: Rebalancer.Exception => ("failed", Some(e.getMessage))
          }).get
        }
        else
          (if (cluster.rebalancer.running) "running" else "idle") -> None

      Response.ok(RebalanceStartResponse(status, cluster.rebalancer.state, error)).build()
    }
  }
}
