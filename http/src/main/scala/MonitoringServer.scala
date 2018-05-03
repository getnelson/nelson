//: ----------------------------------------------------------------------------
//: Copyright (C) 2017 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package nelson
package http

import java.io.{OutputStream, OutputStreamWriter}
import java.net.InetSocketAddress
import java.util.concurrent.{Executors, ExecutorService, ThreadFactory}

import com.sun.net.httpserver.{HttpExchange, HttpHandler, HttpServer}
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import journal.Logger

import scala.concurrent.duration._

import cats.effect.IO

object MonitoringServer {
  /**
   * Returns a task to start a monitoring server.  The resulting
   * server can be shut down by running its `stop` task.
   */
  def apply(
    registry: CollectorRegistry = CollectorRegistry.defaultRegistry,
    port: Int = 5775,
    keyTTL: Duration = 36.hours): IO[MonitoringServer] = IO {
    val svr = (new MonitoringServer(registry, port, keyTTL))
    svr.start()
    svr
  }

  private val serverPool: ExecutorService =
    Executors.newCachedThreadPool(daemonThreads("monitoring-server"))

  private def daemonThreads(name: String) = new ThreadFactory {
    def newThread(r: Runnable) = {
      val t = Executors.defaultThreadFactory.newThread(r)
      t.setDaemon(true)
      t.setName(name)
      t
    }
  }
}

class MonitoringServer private (
  registry: CollectorRegistry,
  port: Int,
  keyTTL: Duration = 36.hours) {

  import MonitoringServer._

  private[this] val log: Logger = Logger[MonitoringServer]

  private val server = HttpServer.create(new InetSocketAddress(port), 0)

  private def start(): Unit = {
    server.setExecutor(serverPool)
    val _ = server.createContext("/", handler)

    server.start()
    log.debug(s"server started on port: $port")
  }

  /**
   * Returns a Task to stop this server
   */
  def stop: IO[Unit] = IO { server.stop(0) }

  protected def handler = new HttpHandler {
    def handle(req: HttpExchange): Unit = try {
      log.debug("requested path: " + req.getRequestURI.getPath)
      val path = req.getRequestURI.getPath match {
        case "/" =>
          Nil
        case p =>
          p.split("/").toList.drop(1)
      }
      path match {
        case "metrics" :: Nil =>
          log.debug("calling metrics handler")
          handleMetrics(req)
        case _ =>
          log.debug("calling catch-all handler")
          handleStatus(req)
      }
    }
    catch {
      case e: Exception => log.error("fatal error: " + e)
    }
    finally req.close
  }

  protected def handleStatus(req: HttpExchange): Unit = {
    val emptyResponse = "[]".getBytes

    val contentType = "application/json"
    log.debug("setting content type to: " + contentType)
    req.getResponseHeaders.set("Content-Type", contentType)

    val accessControl = "*"
    log.debug("setting access control to: " + accessControl)
    req.getResponseHeaders.set("Access-Control-Allow-Origin", accessControl)


    val responseCode: Int = 200
    val responseLength = emptyResponse.length
    log.debug("setting response code to: " + responseCode)
    log.debug("setting response length to: " + responseLength)
    req.sendResponseHeaders(responseCode, responseLength.toLong)

    log.debug("writing body: " + emptyResponse)
    req.getResponseBody.write(emptyResponse)
  }

  private def handleMetrics(req: HttpExchange): Unit = {
    val contentType = TextFormat.CONTENT_TYPE_004
    log.debug("setting content type to: " + contentType)
    req.getResponseHeaders.set("Content-Type", contentType)

    val accessControl = "*"
    log.debug("setting access control to: " + accessControl)
    req.getResponseHeaders.set("Access-Control-Allow-Origin", accessControl)

    // A content length of 0L implies chunked transfer encoding:
    // https://docs.oracle.com/javase/7/docs/jre/api/net/httpserver/spec/com/sun/net/httpserver/HttpExchange.html#sendResponseHeaders(int,%20long)
    val contentLength = 0L
    val responseCode: Int = 200
    log.debug("setting response code to: " + responseCode)
    log.debug("setting content length to: " + contentLength)
    req.sendResponseHeaders(responseCode, contentLength)

    val outputStream: OutputStream = req.getResponseBody
    val writer: OutputStreamWriter = new OutputStreamWriter(outputStream)

    log.debug("writing registry's metric family samples to output stream")
    TextFormat.write004(writer, registry.metricFamilySamples())

    log.debug("flushing output stream")
    writer.flush()
  }
}
