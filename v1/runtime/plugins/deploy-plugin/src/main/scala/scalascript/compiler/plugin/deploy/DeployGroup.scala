package scalascript.compiler.plugin.deploy

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import java.util.concurrent.{CountDownLatch, ConcurrentHashMap, Executors, TimeUnit}

// ── Group topology ──────────────────────────────────────────────────────────

enum ExecMode:
  case Parallel
  case Sequence
  case Pipeline(stages: List[List[String]])

case class DeployGroup(
  name:           String,
  members:        List[String],
  mode:           ExecMode,
  deps:           Map[String, List[String]],
  onFailure:      FailurePolicy,
  maxParallelism: Option[Int]
)

// ── DAG utilities ───────────────────────────────────────────────────────────

object DeployDag:

  def topoSort(members: List[String], deps: Map[String, List[String]]): List[String] =
    val inDegree = mutable.Map(members.map(_ -> 0)*)
    val adj      = mutable.Map(members.map(_ -> mutable.ListBuffer.empty[String])*)
    for
      m <- members
      d <- deps.getOrElse(m, Nil)
      if members.contains(d)
    do
      adj(d) += m
      inDegree(m) = inDegree(m) + 1
    val queue  = mutable.Queue(inDegree.collect { case (k, 0) => k }.toSeq*)
    val result = mutable.ListBuffer.empty[String]
    while queue.nonEmpty do
      val n = queue.dequeue()
      result += n
      for m <- adj(n) do
        inDegree(m) = inDegree(m) - 1
        if inDegree(m) == 0 then queue.enqueue(m)
    if result.size != members.size then
      val cycle = members.filterNot(result.contains)
      throw DeployError(s"[deploy/dag-cycle] Dependency cycle detected among: ${cycle.mkString(", ")}")
    result.toList

  def toStages(sorted: List[String], deps: Map[String, List[String]]): List[List[String]] =
    val done  = mutable.Set.empty[String]
    val all   = sorted.toSet
    val remaining = mutable.ListBuffer(sorted*)
    val stages = mutable.ListBuffer.empty[List[String]]
    while remaining.nonEmpty do
      val ready = remaining.filter(t => deps.getOrElse(t, Nil).forall(d => !all.contains(d) || done.contains(d)))
      if ready.isEmpty then
        throw DeployError(s"[deploy/dag-cycle] Cannot make progress — remaining: ${remaining.mkString(", ")}")
      stages += ready.toList
      done ++= ready
      remaining --= ready
    stages.toList

// ── Orchestrator ─────────────────────────────────────────────────────────────

class DeployError(msg: String) extends RuntimeException(msg)

object DeployOrchestrator:

  def run(
    group:     DeployGroup,
    runTarget: (String, Map[String, String]) => Option[Map[String, String]],
    emit:      DeployEvent => Unit,
  ): (List[String], List[String], List[String]) =

    val sorted = DeployDag.topoSort(group.members, group.deps)
    val stages: List[List[String]] = group.mode match
      case ExecMode.Parallel           => DeployDag.toStages(sorted, group.deps)
      case ExecMode.Sequence           => sorted.map(List(_))
      case ExecMode.Pipeline(explicit) => explicit

    // Thread-safe sets using ConcurrentHashMap backing; accessed via .asScala
    val collectedJ = new ConcurrentHashMap[String, Map[String, String]]()
    val failedJ    = ConcurrentHashMap.newKeySet[String]()
    val succeededJ = ConcurrentHashMap.newKeySet[String]()
    val skippedJ   = ConcurrentHashMap.newKeySet[String]()

    for stage <- stages do
      if failedJ.isEmpty || group.onFailure == FailurePolicy.ContinueRemaining then
        val pool  = Executors.newVirtualThreadPerTaskExecutor()
        val latch = CountDownLatch(stage.size)
        for target <- stage do
          pool.submit { () =>
            try
              val blocked = group.deps.getOrElse(target, Nil).find(failedJ.contains)
              if blocked.isDefined then
                emit(DeployEvent.SkippedDependency(target, blocked.get))
                skippedJ.add(target)
              else if !failedJ.isEmpty && group.onFailure == FailurePolicy.AbortRemaining then
                skippedJ.add(target)
              else
                val prereqOutputs: Map[String, String] = group.deps.getOrElse(target, Nil)
                  .flatMap(d => Option(collectedJ.get(d)).getOrElse(Map.empty))
                  .toMap
                runTarget(target, prereqOutputs) match
                  case Some(outs) =>
                    collectedJ.put(target, outs)
                    succeededJ.add(target)
                  case None =>
                    failedJ.add(target)
            catch case e: Throwable =>
              emit(DeployEvent.Failed(target, e.getMessage))
              failedJ.add(target)
            finally
              latch.countDown()
          }
        pool.shutdown()
        latch.await(30, TimeUnit.MINUTES)

    if !failedJ.isEmpty && group.onFailure == FailurePolicy.RollbackAll then
      val toRollback = succeededJ.asScala.toList.reverse
      for t <- toRollback do emit(DeployEvent.RolledBack(t))

    val s  = succeededJ.asScala.toList.sortBy(sorted.indexOf)
    val f  = failedJ.asScala.toList.sortBy(sorted.indexOf)
    val sk = skippedJ.asScala.toList.sortBy(sorted.indexOf)
    emit(DeployEvent.GroupComplete(group.name, f.isEmpty, s.map(t => t -> "deployed").toMap))
    (s, f, sk)
