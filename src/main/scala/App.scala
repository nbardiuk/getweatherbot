import cats.effect.IO
import fs2.Stream.iterateEval
import fs2.StreamApp.ExitCode
import fs2.io._
import fs2.text.utf8Encode
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

object App extends StreamApp[IO] {

  case class InlineQuery(id: String, query: String)

  val token = ""
  val botBase = s"https://api.telegram.org/bot$token"
  val botUri: Uri = Uri.unsafeFromString(botBase)

  private val httpClient: IO[Client[IO]] = Http1Client[IO]()

  def answer(q: InlineQuery): IO[Unit] = {
    println(q)

    def answerQuery(): IO[Request[IO]] = {
      val response = Json.obj(
        "inline_query_id" -> Json.fromString(q.id),
        "cache_time" -> Json.fromInt(3),
        "results" -> Json.arr(
          Json.obj(
            "type" -> Json.fromString("article"),
            "id" -> Json.fromString(q.id),
            "title" -> Json.fromString(s"Weather in ${q.query}"),
            "input_message_content" -> Json.obj(
              "message_text" -> Json.fromString(s"""
                  |*Weather in ${q.query}*
                  |
                  |Condition: 🌧
                  |Temperature: 14 ºC
                  |
                """.stripMargin),
              "parse_mode" -> Json.fromString("Markdown")
            )
          ))
      )

      POST(botUri / "answerInlineQuery", response)
    }

    for {
      client <- httpClient
      response <- client.expect(answerQuery())(jsonDecoder)
      _ <- client.shutdown
    } yield response
  }

  def stream(args: List[String],
             requestShutdown: IO[Unit]): Stream[IO, ExitCode] = {

    val inlineQueries: Stream[IO, InlineQuery] =
      updates().flatMap(json => Stream.emits(parseInlineQueries(json)))

    inlineQueries
      .observe(_.evalMap(answer).handleErrorWith { e =>
        println(e)
        Stream.empty
      })
      // Why stream does not work without through????????
      .map(_ + "\n")
      .through(utf8Encode)
      .to(stdout)
      //run
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
      POST(botUri / "getUpdates",
           Json.obj(
             "offset" -> Json.fromInt(lastSeen.getOrElse(0) + 1),
             "timeout" -> Json.fromInt(1)
           ))
    }

    def lastId(json: Json): Option[Int] = {
      root.result.each.update_id.int.lastOption(json)
    }

    iterateEval[IO, Json](Json.obj())(js => jsonIO(getUpdates(lastId(js))))
      .handleErrorWith { e =>
        println(s"error: ${e}")
        updates()
      }
  }

  private def jsonIO(request: IO[Request[IO]]): IO[Json] = {
    for {
      client <- httpClient
      response <- client.expect(request)(jsonDecoder)
      _ <- client.shutdown
    } yield response
  }
}
