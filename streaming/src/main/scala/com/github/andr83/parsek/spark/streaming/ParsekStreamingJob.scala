package com.github.andr83.parsek.spark.streaming

import com.github.andr83.parsek.PipeContext
import com.github.andr83.parsek.spark.SparkPipeContext.{LongCountersParam, StringTuple2}
import com.github.andr83.parsek.spark.sink.Sink
import com.github.andr83.parsek.spark.streaming.pipe.DStreamPipe
import com.github.andr83.parsek.spark.streaming.source.StreamingSource
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable.{HashMap => MutableHashMap}


/**
  * @author andr83
  */
object ParsekStreamingJob extends StreamingJob {
  val DefaultFlow = "default"

  override def job(): Unit = {
    val flows = (config.as[List[Config]]("sources")
      .map(_.as[Option[String]]("flow") getOrElse DefaultFlow) ++
      config.as[List[Config]]("sinks")
        .map(_.as[Option[String]]("flow") getOrElse DefaultFlow) ++
      config.as[List[Config]]("pipes")
        .flatMap(c=> c.as[Option[String]]("toFlow") map(f=>Seq(f)) orElse c.as[Option[Seq[String]]]("toFlows") getOrElse Seq.empty[String]))
      .toSet

    val accumulators = flows.map (flow=> {
      val acc = ssc.sparkContext.accumulable(MutableHashMap.empty[StringTuple2, Long])(LongCountersParam)
      flow -> acc
    }).toMap

    val repository = new StreamFlowRepository(accumulators)

    val sourcesByFlow = config.as[List[Config]]("sources")
      .groupBy(_.as[Option[String]]("flow") getOrElse DefaultFlow)
      .mapValues(_.map(StreamingSource.apply))

    sourcesByFlow foreach {
      case (flow, sources) =>
        val streams = sources.map(_ (this))
        val stream = streams.tail.foldRight(streams.head)(_.union(_))

        val pipeContext = repository.getContext(flow)

        stream foreachRDD (rdd => {
          pipeContext.getCounter(PipeContext.InfoGroup, PipeContext.InputRowsGroup) += rdd.count()
        })

        repository += (flow -> stream)
    }

    val pipeConfigs = config.as[Option[List[Config]]]("pipes") getOrElse List.empty[Config]

    nextPipe(pipeConfigs, repository)

    val sinkConfigs = config.as[List[Config]]("sinks") groupBy (_.as[Option[String]]("flow") getOrElse DefaultFlow)
    val sinkFlows = sinkConfigs.keySet

    repository.streams filterKeys sinkFlows.contains foreach {
      case (flow, stream) => stream.foreachRDD((rdd, time) => {
        val sinks = sinkConfigs.get(flow).get map Sink.apply
        val cachedRdd = rdd.persist(StorageLevel.MEMORY_AND_DISK)

        val pipeContext = repository.getContext(flow)
        val counter = pipeContext.getCounter(PipeContext.InfoGroup, PipeContext.OutputRowsGroup)
        counter += cachedRdd.count()

        sinks.foreach(_.sink(cachedRdd, time.milliseconds))

        logger.info(s"Flow $flow counters:")
        pipeContext.getCounters.toSeq.sortWith(_._1.toString() < _._1.toString()) foreach { case (key, count) =>
          logger.info(s"$key: $count")
        }
      })
    }

  }

  def nextPipe(pipes: List[Config], repository: StreamFlowRepository): Unit = pipes match {
    case head :: tail =>
      runPipe(head, repository)
      nextPipe(tail, repository)
    case Nil =>
  }

  def runPipe(pipeConfig: Config, repository: StreamFlowRepository): Unit = {
    val pipe = DStreamPipe(pipeConfig)
    val flow = pipeConfig.as[Option[String]]("flow") getOrElse DefaultFlow
    pipe.run(flow, repository)
  }
}
