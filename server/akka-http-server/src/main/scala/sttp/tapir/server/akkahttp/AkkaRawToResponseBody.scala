package sttp.tapir.server.akkahttp

import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model._
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, StreamConverters}
import akka.util.ByteString
import sttp.capabilities.akka.AkkaStreams
import sttp.model.{HasHeaders, HeaderNames, Part}
import sttp.tapir.internal.charset
import sttp.tapir.server.akkahttp.AkkaModel.parseHeadersOrThrow
import sttp.tapir.server.internal.RawToResponseBody
import sttp.tapir.{CodecFormat, RawBodyType, RawPart, WebSocketBodyOutput}

import java.nio.charset.{Charset, StandardCharsets}
import scala.concurrent.ExecutionContext
import scala.util.Try

class AkkaRawToResponseBody(implicit ec: ExecutionContext, m: Materializer)
    extends RawToResponseBody[Flow[Message, Message, Any], HttpEntity, AkkaStreams] {
  override val streams: AkkaStreams = AkkaStreams

  override def rawValueToBody[R](v: R, headers: HasHeaders, format: CodecFormat, bodyType: RawBodyType[R]): ResponseEntity =
    overrideContentTypeIfDefined(
      rawValueToResponseEntity(bodyType, formatToContentType(format, charset(bodyType)), headers.contentLength, v),
      headers
    )

  override def streamValueToBody(
      v: streams.BinaryStream,
      headers: HasHeaders,
      format: CodecFormat,
      charset: Option[Charset]
  ): ResponseEntity =
    overrideContentTypeIfDefined(streamToEntity(formatToContentType(format, charset), headers.contentLength, v), headers)

  override def webSocketPipeToBody[REQ, RESP](
      pipe: streams.Pipe[REQ, RESP],
      o: WebSocketBodyOutput[streams.Pipe[REQ, RESP], REQ, RESP, _, AkkaStreams]
  ): Flow[Message, Message, Any] = AkkaWebSockets.pipeToBody(pipe, o)

  private def rawValueToResponseEntity[CF <: CodecFormat, R](
      bodyType: RawBodyType[R],
      ct: ContentType,
      contentLength: Option[Long],
      r: R
  ): ResponseEntity = {
    bodyType match {
      case RawBodyType.StringBody(charset) =>
        ct match {
          case nb: ContentType.NonBinary => HttpEntity(nb, r)
          case _                         => HttpEntity(ct, r.getBytes(charset))
        }
      case RawBodyType.ByteArrayBody   => HttpEntity(ct, r)
      case RawBodyType.ByteBufferBody  => HttpEntity(ct, ByteString(r))
      case RawBodyType.InputStreamBody => streamToEntity(ct, contentLength, StreamConverters.fromInputStream(() => r))
      case RawBodyType.FileBody        => HttpEntity.fromPath(ct, r.toPath)
      case m: RawBodyType.MultipartBody =>
        val parts = (r: Seq[RawPart]).flatMap(rawPartToBodyPart(m, _))
        val body = Multipart.FormData(parts: _*)
        body.toEntity()
    }
  }

  private def streamToEntity(contentType: ContentType, contentLength: Option[Long], stream: AkkaStreams.BinaryStream): ResponseEntity = {
    contentLength match {
      case None    => HttpEntity(contentType, stream)
      case Some(l) => HttpEntity(contentType, l, stream)
    }
  }

  private def rawPartToBodyPart[T](m: RawBodyType.MultipartBody, part: Part[T]): Option[Multipart.FormData.BodyPart] = {
    m.partType(part.name).map { partType =>
      val partContentType = part.contentType.map(parseContentType).getOrElse(ContentTypes.`application/octet-stream`)
      val partContentLength = part.header(HeaderNames.ContentLength).flatMap(v => Try(v.toLong).toOption)
      val body = rawValueToResponseEntity(partType.asInstanceOf[RawBodyType[Any]], partContentType, partContentLength, part.body) match {
        case b: BodyPartEntity => overrideContentTypeIfDefined(b, part)
        case _                 => throw new IllegalArgumentException(s"$partType is not supported in multipart bodies")
      }

      Multipart.FormData
        .BodyPart(
          part.name,
          body,
          part.otherDispositionParams,
          parseHeadersOrThrow(part).filterNot(_.is(HeaderNames.ContentType.toLowerCase)).toList
        )
    }
  }

  private def formatToContentType(format: CodecFormat, charset: Option[Charset]): ContentType = {
    format match {
      case CodecFormat.Json()               => ContentTypes.`application/json`
      case CodecFormat.TextPlain()          => MediaTypes.`text/plain`.withCharset(charsetToHttpCharset(charset.getOrElse(StandardCharsets.UTF_8)))
      case CodecFormat.TextHtml()           => MediaTypes.`text/html`.withCharset(charsetToHttpCharset(charset.getOrElse(StandardCharsets.UTF_8)))
      case CodecFormat.OctetStream()        => MediaTypes.`application/octet-stream`
      case CodecFormat.Zip()                => MediaTypes.`application/zip`
      case CodecFormat.XWwwFormUrlencoded() => MediaTypes.`application/x-www-form-urlencoded`
      case CodecFormat.MultipartFormData()  => MediaTypes.`multipart/form-data`
      case f                                => parseContentType(f.mediaType.toString())
    }
  }

  private def parseContentType(ct: String): ContentType =
    ContentType.parse(ct).getOrElse(throw new IllegalArgumentException(s"Cannot parse content type: $ct"))

  private def charsetToHttpCharset(charset: Charset): HttpCharset = HttpCharset.custom(charset.name())

  private def overrideContentTypeIfDefined[RE <: ResponseEntity](re: RE, headers: HasHeaders): RE = {
    headers.contentType match {
      case Some(ct) => re.withContentType(parseContentType(ct)).asInstanceOf[RE]
      case None     => re
    }
  }
}
