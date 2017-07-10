/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.http

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.concurrent.Await
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.event.Logging
import akka.japi.Creator
import akka.util.Timeout
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.directives.DebuggingDirectives
import akka.http.scaladsl.server.directives.LogEntry
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.RejectionHandler
import akka.http.scaladsl.server.UnacceptedResponseContentTypeRejection
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.RouteResult.Rejected

import whisk.common.LogMarker
import whisk.common.LogMarkerToken
import whisk.common.Logging
import whisk.common.LoggingMarkers
import whisk.common.TransactionCounter
import whisk.common.TransactionId
import akka.stream.ActorMaterializer

/**
 * This trait extends the Akka Directives and Actor with logging and transaction counting
 * facilities common to all OpenWhisk REST services.
 */
trait BasicHttpService extends Directives with Actor with TransactionCounter {

    /**
     * Gets the actor context.
     */
    //implicit def actorRefFactory: ActorContext

    /**
     * Gets the logging
     */
    implicit def logging: Logging
    implicit val mat = ActorMaterializer()

    /** Rejection handler to terminate connection on a bad request. Delegates to Akka handler. */
    implicit def customRejectionHandler(implicit transid: TransactionId) =
        RejectionHandler.default.mapRejectionResponse {
                case resp @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
                    val timeout = 1.second
                    val marshalledError = Marshal(ErrorResponse(ent.data.utf8String, transid)).to[ResponseEntity]
                    val marshalledRes = Await.result(marshalledError.flatMap(_.toStrict(timeout)), timeout)
                    resp.withEntity(marshalledRes)
                case x => x
            }

    /**
     * Gets the routes implemented by the HTTP service.
     *
     * @param transid the id for the transaction (every request is assigned an id)
     */
    def routes(implicit transid: TransactionId): Route

    /**
     * Gets the log level for a given route. The default is
     * InfoLevel so override as needed.
     *
     * @param the route
     * @return a log level for the route
     */
    def loglevelForRoute(route: String): Logging.LogLevel = Logging.InfoLevel

    /** Rejection handler to terminate connection on a bad request. Delegates to Akka handler. */
    val prioritizeRejections = recoverRejections { rejections =>
        val priorityRejection = rejections.find(_.isInstanceOf[UnacceptedResponseContentTypeRejection]).getOrElse(null)

        if (priorityRejection != null) {
            Rejected(Seq(priorityRejection))
        } else {
            Rejected(rejections)
        }
    }

    val toStrict = mapInnerRoute { innerRoute =>
        extractRequest { req =>
            onSuccess(req.toStrict(1.second)) { strictReq =>
                mapRequest(_ => strictReq) {
                    innerRoute
                }
            }
        }
    }

    /**
     * Receives a message and runs the router.
     */
    def route: Route = {
        assignId { implicit transid =>
            handleRejections(customRejectionHandler) {
                prioritizeRejections {
                    DebuggingDirectives.logRequest(logRequestInfo _) {
                        DebuggingDirectives.logRequestResult(logResponseInfo _) {
                            toStrict {
                                routes
                            }
                        }
                    }
                }
            }
        }
    }

    def receive = {
        case _ =>
    }

    /** Assigns transaction id to every request. */
    protected val assignId = extract(_ => transid())

    /** Generates log entry for every request. */
    protected def logRequestInfo(req: HttpRequest)(implicit tid: TransactionId): LogEntry = {
        val m = req.method.toString
        val p = req.uri.path.toString
        val q = req.uri.query().toString
        val l = loglevelForRoute(p)
        LogEntry(s"[$tid] $m $p $q", l)
    }

    protected def logResponseInfo(req: HttpRequest)(implicit tid: TransactionId): Any => Option[LogEntry] = {
        case res: HttpResponse =>
            val m = req.method.toString
            val p = req.uri.path.toString
            val l = loglevelForRoute(p)

            val name = "BasicHttpService"

            val token = LogMarkerToken("http", s"${m.toLowerCase}.${res.status.intValue}", LoggingMarkers.count)
            val marker = LogMarker(token, tid.deltaToStart, Some(tid.deltaToStart))

            Some(LogEntry(s"[$tid] [$name] $marker", l))
        case _ => None // other kind of responses
    }
}

object BasicHttpService extends Directives {
    def startService[T <: Actor](system: ActorSystem, name: String, interface: String, port: Integer, service: Creator[T]) = {
        val actor = system.actorOf(Props.create(service), s"$name-service")

        implicit val timeout = Timeout(5 seconds)
        //IO(Http)(system) ? Http.Bind(actor, interface, port)
    }


}
