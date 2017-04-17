/*
 * Copyright 2015-2016 IBM Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package whisk.core.controller.v2


import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import akka.http.scaladsl.server.directives._
import akka.http.scaladsl.server.directives.AuthenticationResult
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Route

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

import whisk.common.TransactionId
import whisk.core.database.NoDocumentException
import whisk.core.entity.UUID
import whisk.core.entity.types.AuthStore
import whisk.core.entity.WhiskAuthStore
import whisk.core.entity.AuthKey
import whisk.core.entity.Identity
import whisk.core.entity.Secret
import whisk.core.entity.UUID

object Authenticate {
    /** Required properties for this component */
    def requiredProperties = WhiskAuthStore.requiredProperties
}

/** A common trait for secured routes */
trait Authenticate /*extends Logging*/ {

    protected implicit val executionContext: ExecutionContext

    //protected implicit val logging: Logging

    /** Database service to lookup credentials */
    protected val authStore: AuthStore

    def validateCredentials(credentials: Option[BasicHttpCredentials])(implicit transid: TransactionId): Future[Option[Identity]] = {
        credentials flatMap { pw =>
            Try {
                // authkey deserialization is wrapped in a try to guard against malformed values
                val authkey = AuthKey(UUID(pw.username), Secret(pw.password))
                //logging.info(this, s"authenticate: ${authkey.uuid}")
                val future = Identity.get(authStore, authkey) map { result =>
                    if (authkey == result.authkey) {
                        //logging.info(this, s"authentication valid")
                        Some(result)
                    } else {
                        //logging.info(this, s"authentication not valid")
                        None
                    }
                } recover {
                    case _: NoDocumentException | _: IllegalArgumentException =>
                        //logging.info(this, s"authentication not valid")
                        None
                }
                //future onFailure { case t => logging.error(this, s"authentication error: $t") }
                future
            }.toOption
        } getOrElse {
            //credentials.foreach(_ => logging.info(this, s"credentials are malformed"))
            Future.successful(None)
        }
    }

    def customBasicAuth[A](realm: String, verify: Option[BasicHttpCredentials] => Future[Option[A]]) = {
        authenticateOrRejectWithChallenge[BasicHttpCredentials, A] { creds =>
            verify(creds).map {
                case Some(t) => AuthenticationResult.success(t)
                case None => AuthenticationResult.failWithChallenge(HttpChallenges.basic(realm))
            }
        }
    }
}

/** A trait for authenticated routes. */
trait AuthenticatedRouteProvider {
    def routes(user: Identity)(implicit transid: TransactionId): Route
}

/*
/** A common trait for secured routes */
trait AuthenticatedRoute {

    /** An execution context for futures */
    protected implicit val executionContext: ExecutionContext

    /** Creates HTTP BasicAuth handler */
    protected def basicauth(implicit transid: TransactionId) = {
        new BasicHttpAuthenticator[Identity](realm = "whisk rest service", validateCredentials _) {
            override def apply(ctx: RequestContext) = {
                super.apply(ctx) recover {
                    case t: IllegalStateException => Left(CustomRejection(InternalServerError))
                    case t                        => Left(CustomRejection(ServiceUnavailable))
                }
            }
        }
    }

    /** Validates credentials against database of subjects */
    protected def validateCredentials(userpass: Option[UserPass])(implicit transid: TransactionId): Future[Option[Identity]]
}

/** A trait for authenticated routes. */
trait AuthenticatedRouteProvider {
    def routes(user: Identity)(implicit transid: TransactionId): Route
}

 */