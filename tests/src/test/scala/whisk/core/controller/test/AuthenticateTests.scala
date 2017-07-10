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

package whisk.core.controller.test

import scala.concurrent.Await

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import akka.http.scaladsl.model.headers.BasicHttpCredentials

import whisk.core.controller.Authenticate
import whisk.core.entity._

/**
 * Tests authentication handler which guards API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class AuthenticateTests extends ControllerTestCommon with Authenticate {
    behavior of "Authenticate"

    it should "authorize a known user" in {
        implicit val tid = transid()
        val creds = createTempCredentials._1
        val pass = BasicHttpCredentials(creds.authkey.uuid.asString, creds.authkey.key.asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user.get should be(creds)
    }

    it should "authorize a known user from cache" in {
        val creds = createTempCredentials(transid())._1
        val pass = BasicHttpCredentials(creds.authkey.uuid.asString, creds.authkey.key.asString)

        // first query will be served from datastore
        val user = Await.result(validateCredentials(Some(pass))(transid()), dbOpTimeout)
        user.get should be(creds)
        stream.toString should include regex (s"serving from datastore: ${creds.authkey.uuid.asString}")
        stream.reset()

        // repeat query, should be served from cache
        val cachedUser = Await.result(validateCredentials(Some(pass))(transid()), dbOpTimeout)
        cachedUser.get should be(creds)
        stream.toString should include regex (s"serving from cache: ${creds.authkey.uuid.asString}")
        stream.reset()
    }

    it should "not authorize a known user with an invalid key" in {
        implicit val tid = transid()
        val creds = createTempCredentials._1
        val pass = BasicHttpCredentials(creds.authkey.uuid.asString, Secret().asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize an unknown user" in {
        implicit val tid = transid()
        val creds = WhiskAuth(Subject(), AuthKey())
        println(creds.authkey.uuid.asString)
        println(creds.authkey.key.asString)
        val pass = BasicHttpCredentials(creds.authkey.uuid.asString, creds.authkey.key.asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when no user creds are provided" in {
        implicit val tid = transid()
        val user = Await.result(validateCredentials(None), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed user is provided" in {
        implicit val tid = transid()
        val pass = BasicHttpCredentials("x", Secret().asString)
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed secret is provided" in {
        implicit val tid = transid()
        val pass = BasicHttpCredentials(UUID().asString, "x")
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }

    it should "not authorize when malformed creds are provided" in {
        implicit val tid = transid()
        val pass = BasicHttpCredentials("x", "y")
        val user = Await.result(validateCredentials(Some(pass)), dbOpTimeout)
        user should be(None)
    }
}
