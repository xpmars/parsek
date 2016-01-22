package com.github.andr83.parsek.spark.streaming.pipe

import com.github.andr83.parsek._
import com.github.andr83.parsek.pipe.Pipe
import com.github.andr83.parsek.spark.streaming.StreamFlowRepository
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._

/**
  * DStreamPipe use parsek core library pipes to transform PValue's
  *
  * @param pipeConfig Configuration object for parsek pipe
  *
  * @author andr83
  */
case class ParsekDStreamPipe(pipeConfig: Config, toFlow: Option[String]) extends DStreamPipe {

  def this(config: Config) = this(
    pipeConfig = config,
    toFlow = config.as[Option[String]]("toFlow")
  )

  override def run(flow: String, repository: StreamFlowRepository):Unit = {
    val pipeType = pipeConfig.as[String]("pipe")
    val stream = repository.getStream(flow)
    implicit val context = repository.getContext(toFlow.getOrElse(flow), flow)

    repository += (toFlow.getOrElse(flow) -> stream.mapPartitions(it=> {
      val pipeline = new Pipeline(Pipe(Map("type" -> pipeType).withFallback(pipeConfig)))
      it.flatMap(pipeline.run)
    }))
  }
}