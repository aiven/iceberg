/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.aws;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.rest.HTTPClient;
import org.apache.iceberg.rest.RESTClient;
import org.apache.iceberg.rest.auth.AuthManager;
import org.apache.iceberg.rest.auth.AuthManagers;
import org.apache.iceberg.rest.auth.AuthProperties;
import org.apache.iceberg.rest.auth.AuthSession;
import org.apache.iceberg.rest.auth.OAuth2Util;
import org.apache.iceberg.rest.responses.ConfigResponse;
import org.apache.iceberg.rest.responses.OAuthTokenResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Parameter;
import org.mockserver.model.ParameterBody;
import org.mockserver.verify.VerificationTimes;
import software.amazon.awssdk.auth.signer.internal.SignerConstant;

public class TestRESTSigV4Signer {
  private static ClientAndServer mockServer;
  private static RESTClient client;
  private static AuthManager authManager;

  @BeforeAll
  public static void beforeClass() {
    mockServer = ClientAndServer.startClientAndServer();

    Map<String, String> properties =
        ImmutableMap.of(
            AuthProperties.AUTH_TYPE,
            AuthProperties.AUTH_TYPE_SIGV4,
            // CI environment doesn't have credentials, but a value must be set for signing
            AwsProperties.REST_SIGNER_REGION,
            "us-west-2",
            AwsProperties.REST_ACCESS_KEY_ID,
            "id",
            AwsProperties.REST_SECRET_ACCESS_KEY,
            "secret",
            // OAuth2 token to test relocation of conflicting auth header
            "token",
            "existing_token");

    HTTPClient httpClient =
        HTTPClient.builder(properties)
            .uri("http://localhost:" + mockServer.getLocalPort())
            .build()
            .withAuthSession(AuthSession.EMPTY);

    authManager = AuthManagers.loadAuthManager("test", properties);
    AuthSession authSession = authManager.catalogSession(httpClient, properties);

    client = httpClient.withAuthSession(authSession);
  }

  @AfterAll
  public static void afterClass() throws IOException {
    mockServer.stop();
    authManager.close();
    client.close();
  }

  @BeforeEach
  public void before() {
    mockServer.reset();
  }

  @Test
  public void signRequestWithoutBody() {
    HttpRequest request =
        HttpRequest.request()
            .withMethod("GET")
            .withPath("/v1/config")
            // Require SigV4 Authorization
            .withHeader(Header.header(HttpHeaders.AUTHORIZATION, "AWS4-HMAC-SHA256.*"))
            // Require that conflicting auth header is relocated
            .withHeader(
                Header.header(
                    RESTSigV4AuthSession.RELOCATED_HEADER_PREFIX + HttpHeaders.AUTHORIZATION,
                    "Bearer existing_token"))
            // Require the empty body checksum
            .withHeader(
                Header.header(
                    SignerConstant.X_AMZ_CONTENT_SHA256, RESTSigV4AuthSession.EMPTY_BODY_SHA256));

    mockServer
        .when(request)
        .respond(HttpResponse.response().withStatusCode(HttpStatus.SC_OK).withBody("{}"));

    ConfigResponse response =
        client.get("v1/config", ConfigResponse.class, ImmutableMap.of(), e -> {});

    mockServer.verify(request, VerificationTimes.exactly(1));
    assertThat(response).isNotNull();
  }

  @Test
  public void signRequestWithBody() {
    HttpRequest request =
        HttpRequest.request()
            .withMethod("POST")
            .withPath("/v1/oauth/token")
            .withBody(
                ParameterBody.params(
                    Parameter.param("client_id", "asdfasd"),
                    Parameter.param("client_secret", "asdfasdf"),
                    Parameter.param("scope", "catalog")))
            // Require SigV4 Authorization
            .withHeader(Header.header(HttpHeaders.AUTHORIZATION, "AWS4-HMAC-SHA256.*"))
            // Require that conflicting auth header is relocated
            .withHeader(
                Header.header(
                    RESTSigV4AuthSession.RELOCATED_HEADER_PREFIX + HttpHeaders.AUTHORIZATION,
                    "Bearer existing_token"))
            // Require a body checksum is set
            .withHeader(Header.header(SignerConstant.X_AMZ_CONTENT_SHA256));

    mockServer
        .when(request)
        .respond(
            HttpResponse.response()
                .withStatusCode(HttpStatus.SC_OK)
                .withBody(
                    OAuth2Util.tokenResponseToJson(
                        OAuthTokenResponse.builder()
                            .withToken("fake_token")
                            .withTokenType("bearer")
                            .withIssuedTokenType("n/a")
                            .build())));

    Map<String, String> formData = Maps.newHashMap();
    formData.put("client_id", "asdfasd");
    formData.put("client_secret", "asdfasdf");
    formData.put("scope", "catalog");

    OAuthTokenResponse response =
        client.postForm(
            "v1/oauth/token", formData, OAuthTokenResponse.class, ImmutableMap.of(), e -> {});

    mockServer.verify(request, VerificationTimes.exactly(1));
    assertThat(response).isNotNull();
  }
}
