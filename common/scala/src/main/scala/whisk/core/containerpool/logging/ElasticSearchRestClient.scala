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

package whisk.core.containerpool.logging

import scala.concurrent.Future

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.stream.scaladsl.Flow

import scala.concurrent.Promise
import scala.util.Try

import spray.json._

import whisk.http.PoolingRestClient

trait EsQueryMethod
trait EsOrder
trait EsRange
trait EsAgg
trait EsMatch

case object EsOrderAsc extends EsOrder { override def toString = "asc" }
case object EsOrderDesc extends EsOrder { override def toString = "desc" }
case object EsRangeGte extends EsRange { override def toString = "gte" }
case object EsRangeGt extends EsRange { override def toString = "gt" }
case object EsRangeLte extends EsRange { override def toString = "lte" }
case object EsRangeLt extends EsRange { override def toString = "lt" }
case object EsAggMax extends EsAgg { override def toString = "max" }
case object EsAggMin extends EsAgg { override def toString = "min" }
case object EsMatchPhrase extends EsMatch { override def toString = "phrase" }
case object EsMatchPhrasePrefix extends EsMatch { override def toString = "phrase_prefix" }

// Schema of ES queries
case class EsQueryAggs(aggField: String, agg: EsAgg, field: String)
case class EsQueryRange(key: String, range: EsRange, value: String)
case class EsQueryBoolMatch(key: String, value: String)
case class EsQueryOrder(field: String, kind: EsOrder)
case class EsQuerySize(size: Integer)
case class EsQueryAll() extends EsQueryMethod
case class EsQueryMust(matches: Array[EsQueryBoolMatch], range: Option[EsQueryRange] = None) extends EsQueryMethod
case class EsQueryMatch(field: String, value: String, matchType: Option[EsMatch] = None) extends EsQueryMethod
case class EsQueryTerm(key: String, value: String) extends EsQueryMethod
case class EsQueryString(queryString: String) extends EsQueryMethod
case class EsQuery(query: EsQueryMethod,
                   sort: Option[EsQueryOrder] = None,
                   size: Option[EsQuerySize] = None,
                   aggs: Option[EsQueryAggs] = None)

// Schema of ES query results
case class EsSearchHit(source: JsObject)
case class EsSearchHits(hits: Vector[EsSearchHit])
case class EsSearchResult(hits: EsSearchHits)

object ElasticSearchJsonProtocol extends DefaultJsonProtocol {

  implicit object EsQueryMatchJsonFormat extends RootJsonFormat[EsQueryMatch] {
    def read(query: JsValue) = ???
    def write(query: EsQueryMatch) = {
      query.matchType match {
        case Some(matchType) =>
          JsObject(
            "match" -> JsObject(
              query.field -> JsObject("query" -> query.value.toJson, "type" -> matchType.toString.toJson)))
        case None => JsObject("match" -> JsObject(query.field -> JsObject("query" -> query.value.toJson)))
      }
    }
  }

  implicit object EsQueryTermJsonFormat extends RootJsonFormat[EsQueryTerm] {
    def read(query: JsValue) = ???
    def write(query: EsQueryTerm) = JsObject("term" -> JsObject(query.key -> query.value.toJson))
  }

  implicit object EsQueryStringJsonFormat extends RootJsonFormat[EsQueryString] {
    def read(query: JsValue) = ???
    def write(query: EsQueryString) =
      JsObject("query_string" -> JsObject("query" -> query.queryString.toJson))
  }

  implicit object EsQueryRangeJsonFormat extends RootJsonFormat[EsQueryRange] {
    def read(query: JsValue) = ???
    def write(query: EsQueryRange) =
      JsObject("range" -> JsObject(query.key -> JsObject(query.range.toString -> query.value.toJson)))
  }

  implicit object EsQueryBoolMatchJsonFormat extends RootJsonFormat[EsQueryBoolMatch] {
    def read(query: JsValue) = ???
    def write(query: EsQueryBoolMatch) = JsObject("match" -> JsObject(query.key -> query.value.toJson))
  }

  implicit object EsQueryMustJsonFormat extends RootJsonFormat[EsQueryMust] {
    def read(query: JsValue) = ???
    def write(query: EsQueryMust) = query.range match {
      case Some(range) =>
        JsObject("bool" -> JsObject("must" -> query.matches.toJson, "filter" -> range.toJson))
      case None =>
        JsObject("bool" -> JsObject("must" -> query.matches.toJson))
    }
  }

  implicit object EsQueryOrderJsonFormat extends RootJsonFormat[EsQueryOrder] {
    def read(query: JsValue) = ???
    def write(query: EsQueryOrder) =
      JsArray(JsObject(query.field -> JsObject("order" -> query.kind.toString.toJson)))
  }

  implicit object EsQuerySizeJsonFormat extends RootJsonFormat[EsQuerySize] {
    def read(query: JsValue) = ???
    def write(query: EsQuerySize) = JsNumber(query.size)
  }

  implicit object EsQueryAggsJsonFormat extends RootJsonFormat[EsQueryAggs] {
    def read(query: JsValue) = ???
    def write(query: EsQueryAggs) =
      JsObject(query.aggField -> JsObject(query.agg.toString -> JsObject("field" -> query.field.toJson)))
  }

  implicit object EsQueryAllJsonFormat extends RootJsonFormat[EsQueryAll] {
    def read(query: JsValue) = ???
    def write(query: EsQueryAll) = JsObject("match_all" -> JsObject())
  }

  implicit object EsQueryMethod extends RootJsonFormat[EsQueryMethod] {
    def read(query: JsValue) = ???
    def write(method: EsQueryMethod) = method match {
      case queryTerm: EsQueryTerm     => queryTerm.toJson
      case queryString: EsQueryString => queryString.toJson
      case queryMatch: EsQueryMatch   => queryMatch.toJson
      case queryMust: EsQueryMust     => queryMust.toJson
      case queryAll: EsQueryAll       => queryAll.toJson
    }
  }

  implicit val esQueryFormat = jsonFormat4(EsQuery.apply)
  implicit val esSearchHitFormat = jsonFormat(EsSearchHit.apply _, "_source")
  implicit val esSearchHitsFormat = jsonFormat1(EsSearchHits.apply)
  implicit val esSearchResultFormat = jsonFormat1(EsSearchResult.apply)
}

class ElasticSearchRestClient(
  protocol: String,
  host: String,
  port: Int,
  httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None)(
  implicit system: ActorSystem)
    extends PoolingRestClient(protocol, host, port, 16 * 1024, httpFlow) {

  import ElasticSearchJsonProtocol._

  private val baseHeaders: List[HttpHeader] = List(Accept(MediaTypes.`application/json`))

  /**
   * Method to perform an ElasticSearch query that expects JSON response. If a payload is provided, a POST request will
   * be performed. Otherwise, a GET request will be performed.
   *
   * @param uri     relative path to be used in the request to Elasticsearch
   * @param headers list of HTTP request headers
   * @param payload Optional JSON to be sent in the request
   * @return Future with either the JSON response or the status code of the request, if the request is unsuccessful
   */
  def query(uri: Uri,
            headers: List[HttpHeader] = List.empty,
            payload: Option[EsQuery] = None): Future[Either[StatusCode, JsObject]] = {
    payload match {
      case Some(payload) =>
        requestJson[JsObject](mkJsonRequest(HttpMethods.POST, uri, payload.toJson.asJsObject, baseHeaders ++ headers))
      case None => requestJson[JsObject](mkRequest(HttpMethods.GET, uri, baseHeaders ++ headers))
    }
  }
}
