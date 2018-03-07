import java.nio.file.Paths

import cats.effect.IO
import com.typesafe.scalalogging.LazyLogging
import fs2.Stream.{emit, emits, iterateEval}
import fs2.StreamApp.ExitCode
import fs2.io.file
import fs2.{Stream, StreamApp}
import info.debatty.java.stringsimilarity.JaroWinkler
import io.circe.generic.semiauto._
import io.circe.jawn._
import io.circe.optics.JsonPath._
import jawn.Facade
import io.circe.{Json, _}
import jawnfs2._
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

  case class City(id: Long, name: String, country: String) {
    val terms = Seq(name.toLowerCase, name.toLowerCase + country.toLowerCase)
  }

  implicit val cityDecoder: Decoder[City] = deriveDecoder
  implicit val inlineQueryDecoder: Decoder[InlineQuery] = deriveDecoder

  def answerInlineQuery(query: InlineQuery,
                        client: Client[IO],
                        cities: Seq[City]): IO[Json] = {
    val queryTerm = query.query.toLowerCase
    val metric = new JaroWinkler
    val bestMatches = cities
      .map(city => (city, city.terms.map(metric.similarity(_, queryTerm)).max))
      .filter(_._2 > 0.8)
      .sortBy(-_._2)
      .take(5)
      .map(_._1)

    val response = Json.obj(
      "inline_query_id" -> Json.fromString(query.id),
      "cache_time" -> Json.fromInt(3),
      "results" -> Json.arr(
        bestMatches
          .map(city =>
            Json.obj(
              "type" -> Json.fromString("article"),
              "id" -> Json.fromString(s"${city.id + System.nanoTime()}"),
              "title" -> Json.fromString(
                s"Weather in ${city.name}, ${city.country}"),
              "input_message_content" -> Json.obj(
                "message_text" -> Json.fromString(s""" 
                     |*Weather in ${city.name}, ${city.country}*
                     |
                     |Condition: 🌧
                     |Temperature: 14 ºC
                     |
                """.stripMargin),
                "parse_mode" -> Json.fromString("Markdown")
              )
          ))
          .toArray: _*)
    )
    jsonIO(client, POST(botUri / "answerInlineQuery", response))
  }

  def stream(args: List[String],
             requestShutdown: IO[Unit]): Stream[IO, ExitCode] = {
    (for {
      codes <- loadCities()
      client <- Http1Client.stream[IO]()
      update <- updates(client)
      query <- emits(parseInlineQueries(update))
      answer <- Stream.eval(answerInlineQuery(query, client, codes))
    } yield answer).drain >> emit(ExitCode.Success)
  }

  private def loadCities(): Stream[IO, Seq[City]] = {
    implicit val facade: Facade[Json] = CirceSupportParser.facade
    val path = Paths.get(getClass.getResource("current.city.list.json").getPath)
    val json = file.readAllAsync[IO](path, 10000).chunks.parseJsonStream
    val cities = json.flatMap(js => emits(cityDecoder.decodeJson(js).toSeq))
    cities.filter(!_.name.isEmpty).fold(List[City]())((xs, x) => x :: xs)
  }

  private def parseInlineQueries(json: Json): List[InlineQuery] = {
    val inlineQueries = root.result.each.inline_query.json
    inlineQueries.getAll(json).flatMap(inlineQueryDecoder.decodeJson(_).toSeq)
  }

  private def updates(client: Client[IO]): Stream[IO, Json] = {

    def getUpdates(lastSeen: Option[Int]): IO[Request[IO]] = {
      val params = Json.obj(
        "offset" -> Json.fromInt(lastSeen.getOrElse(0) + 1),
        "timeout" -> Json.fromInt(5)
      )
      POST(botUri / "getUpdates", params)
    }

    def lastId: Json => Option[Int] = root.result.each.update_id.int.lastOption

    iterateEval[IO, Json](Json.obj())(js =>
      jsonIO(client, getUpdates(lastId(js))))
      .handleErrorWith(justLog("GetUpdates"))
  }

  private def jsonIO(client: Client[IO], request: IO[Request[IO]]): IO[Json] = {
    client.expect(request)(jsonDecoder)
  }

  private def justLog(message: String)(error: Throwable) = {
    logger.warn(message, error)
    Stream.empty
  }
}
