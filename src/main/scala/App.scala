import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream.iterateEval
import fs2.StreamApp.ExitCode
import fs2.{Stream, StreamApp}
import io.circe.Json
import io.circe.optics.JsonPath._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.client.blaze.Http1Client
import org.http4s.client.dsl.io._
import org.http4s.dsl.io._
import org.http4s.{Request, Uri}

import scala.concurrent.ExecutionContext.Implicits.global

object App extends StreamApp[IO] with LazyLogging {

  case class InlineQuery(id: String, query: String)

  val token = ""
  val botUri: Uri = Uri.unsafeFromString(s"https://api.telegram.org/bot$token")

  private val httpClient: IO[Client[IO]] = Http1Client[IO]()

  def answerInlineQuery(query: InlineQuery): IO[Json] = {
    logger.info("{}", query)

    val response = Json.obj(
      "inline_query_id" -> Json.fromString(query.id),
      "cache_time" -> Json.fromInt(3),
      "results" -> Json.arr(
        Json.obj(
          "type" -> Json.fromString("article"),
          "id" -> Json.fromString(query.id),
          "title" -> Json.fromString(s"Weather in ${query.query}"),
          "input_message_content" -> Json.obj(
            "message_text" -> Json.fromString(s"""
                 |*Weather in ${query.query}*
                 |
                 |Condition: 🌧
                 |Temperature: 14 ºC
                 |
                """.stripMargin),
            "parse_mode" -> Json.fromString("Markdown")
          )
        ))
    )
    jsonIO(POST(botUri / "answerInlineQuery", response))
  }

  def stream(args: List[String],
             requestShutdown: IO[Unit]): Stream[IO, ExitCode] = {

    val inlineQueries: Stream[IO, InlineQuery] =
      updates().flatMap(json => Stream.emits(parseInlineQueries(json)))

    inlineQueries
      .observe(
        _.evalMap(answerInlineQuery)
          .handleErrorWith(justLog("AnswerInlineQuery"))
          .as())
      .drain >> Stream.emit(ExitCode.Success)
  }

  private def parseInlineQueries(json: Json): List[InlineQuery] = {
    val inlineQueries = root.result.each.inline_query.json
    inlineQueries.getAll(json).flatMap(parseInlineQuery)
  }

  private def parseInlineQuery(json: Json): Option[InlineQuery] = {
    for {
      id <- root.id.string.getOption(json)
      query <- root.query.string.getOption(json)
    } yield InlineQuery(id, query)
  }

  private def updates(): Stream[IO, Json] = {

    def getUpdates(lastSeen: Option[Int]): IO[Request[IO]] = {
      val params = Json.obj(
        "offset" -> Json.fromInt(lastSeen.getOrElse(0) + 1),
        "timeout" -> Json.fromInt(5)
      )
      POST(botUri / "getUpdates", params)
    }

    def lastId: Json => Option[Int] = root.result.each.update_id.int.lastOption

    iterateEval[IO, Json](Json.obj())(js => jsonIO(getUpdates(lastId(js))))
      .handleErrorWith(justLog("GetUpdates"))
  }

  private def jsonIO(request: IO[Request[IO]]): IO[Json] = {
    for {
      client <- httpClient
      response <- client.expect(request)(jsonDecoder)
      _ <- client.shutdown
    } yield response
  }

  private def justLog(message: String)(error: Throwable) = {
    logger.warn(message, error)
    Stream.empty
  }
}
