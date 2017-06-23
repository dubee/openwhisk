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

package whisk.core.controller

import scala.language.postfixOps
import scala.util.Try
import scala.concurrent.Future

import akka.http.scaladsl.model.StatusCodes.RequestEntityTooLarge
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.server.Directive0
import akka.http.scaladsl.server.Directives
import akka.http.scaladsl.server.RequestContext
import akka.http.scaladsl.server.RouteResult
import akka.http.scaladsl.server.Route

import whisk.common.TransactionId
import whisk.core.entitlement.Privilege._
import whisk.core.entitlement.Privilege.Privilege
import whisk.core.entitlement.Privilege.READ
import whisk.core.entitlement.Resource
import whisk.core.entity._
import whisk.core.entity.ActivationEntityLimit
import whisk.core.entity.size._
import whisk.http.ErrorResponse.terminate
import whisk.http.Messages

protected[controller] trait ValidateRequestSize extends Directives {
    protected def validateSize(check: => Option[SizeError])(
        implicit tid: TransactionId) = new Directive0 {
        def tapply(f: Unit => Route) = {
            check map {
                case e: SizeError => terminate(RequestEntityTooLarge, Messages.entityTooBig(e))
            } getOrElse f(None)
        }
    }

    /** Checks if request entity is within allowed length range. */
    protected def isWhithinRange(length: Long) = {
        if (length <= allowedActivationEntitySize) {
            None
        } else Some {
            SizeError(fieldDescriptionForSizeError, length.B, allowedActivationEntitySize.B)
        }
    }

    protected val allowedActivationEntitySize: Long = ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT.toBytes
    protected val fieldDescriptionForSizeError = "Request"
}

/** A trait implementing the basic operations on WhiskEntities in support of the various APIs. */
trait WhiskCollectionAPI
    extends Directives
    with AuthenticatedRouteProvider
    with AuthorizedRouteProvider
    with ValidateRequestSize
    with ReadOps
    with WriteOps {

    /** The core collections require backend services to be injected in this trait. */
    services: WhiskServices =>

    /** Creates an entity, or updates an existing one, in namespace. Terminates HTTP request. */
    protected def create(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId): RequestContext => Future[RouteResult]

    /** Activates entity. Examples include invoking an action, firing a trigger, enabling/disabling a rule. */
    protected def activate(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(implicit transid: TransactionId): RequestContext => Future[RouteResult]

    /** Removes entity from namespace. Terminates HTTP request. */
    protected def remove(user: Identity, entityName: FullyQualifiedEntityName)(implicit transid: TransactionId): RequestContext => Future[RouteResult]

    /** Gets entity from namespace. Terminates HTTP request. */
    protected def fetch(user: Identity, entityName: FullyQualifiedEntityName, env: Option[Parameters])(implicit transid: TransactionId): RequestContext => Future[RouteResult]

    /** Gets all entities from namespace. If necessary filter only entities that are shared. Terminates HTTP request. */
    protected def list(user: Identity, path: EntityPath, excludePrivate: Boolean)(implicit transid: TransactionId): RequestContext => Future[RouteResult]

    /** Indicates if listing entities in collection requires filtering out private entities. */
    protected val listRequiresPrivateEntityFilter = false // currently supported on PACKAGES only

    /** Dispatches resource to the proper handler depending on context. */
    protected override def dispatchOp(user: Identity, op: Privilege, resource: Resource)(implicit transid: TransactionId) = {
        resource.entity match {
            case Some(EntityName(name)) => op match {
                case READ => fetch(user, FullyQualifiedEntityName(resource.namespace, name), resource.env)
                case PUT =>
                    entity(as[LimitedWhiskEntityPut]) { e =>
                        validateSize(e.isWithinSizeLimits)(transid) {
                            create(user, FullyQualifiedEntityName(resource.namespace, name))
                        }
                    }
                case ACTIVATE =>
                   extract(_.request.entity.contentLengthOption) { length =>
                        validateSize(isWhithinRange(length.getOrElse(0)))(transid) {
                            activate(user, FullyQualifiedEntityName(resource.namespace, name), resource.env)
                        }
                    }

                case DELETE => remove(user, FullyQualifiedEntityName(resource.namespace, name))
                case _      => reject
            }
            case None => op match {
                case READ =>
                    // the entitlement service will authorize any subject to list PACKAGES
                    // in any namespace regardless of ownership but the list operation CANNOT
                    // produce all entities in the requested namespace UNLESS the subject is
                    // entitled to them which for now means they own the namespace. If the
                    // subject does not own the namespace, then exclude packages that are private
                    val excludePrivate = listRequiresPrivateEntityFilter && resource.namespace.root != user.namespace
                    logging.info(this, s"[LIST] exclude private entities: required == $excludePrivate")
                    list(user, resource.namespace, excludePrivate)

                case _ => reject
            }
        }
    }

    /** Validates entity name from the matched path segment. */
    protected val segmentDescriptionForSizeError = "Name segement"

    protected override final def entityname(s: String) = {
        validate(isEntity(s), {
            if (s.length > EntityName.ENTITY_NAME_MAX_LENGTH) {
                Messages.entityNameTooLong(
                    SizeError(
                        segmentDescriptionForSizeError,
                        s.length.B,
                        EntityName.ENTITY_NAME_MAX_LENGTH.B))
            } else {
                Messages.entityNameIllegal
            }
        }) & extract(_ => s)
    }

    /** Confirms that a path segment is a valid entity name. Used to reject invalid entity names. */
    protected final def isEntity(n: String) = Try { EntityName(n) } isSuccess
}
