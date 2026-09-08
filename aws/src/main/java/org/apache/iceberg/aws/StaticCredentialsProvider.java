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

import java.util.Map;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.utils.Validate;

/**
 * An implementation of {@link AwsCredentialsProvider} that returns a set implementation of {@link
 * AwsCredentials}.
 *
 * <p>This code delegates to the {@link
 * software.amazon.awssdk.auth.credentials.StaticCredentialsProvider} which has the same
 * implementation but is missing the @link {@link #create(Map)} factory method
 */
@SdkPublicApi
public final class StaticCredentialsProvider implements AwsCredentialsProvider {
  private static final String ACCESS_KEY_ID = "access-key-id";
  private static final String SECRET_ACCESS_KEY = "secret-access-key";
  private final software.amazon.awssdk.auth.credentials.StaticCredentialsProvider inner;

  private StaticCredentialsProvider(Map<String, String> credentials) {
    Validate.notNull(credentials, "Credentials must not be null.");
    this.inner =
        software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
            AwsBasicCredentials.create(
                credentials.get(ACCESS_KEY_ID), credentials.get(SECRET_ACCESS_KEY)));
  }

  /** Create a credentials provider that always returns the provided set of credentials. */
  public static StaticCredentialsProvider create(Map<String, String> credentials) {
    return new StaticCredentialsProvider(credentials);
  }

  @Override
  public AwsCredentials resolveCredentials() {
    return inner.resolveCredentials();
  }

  @Override
  public String toString() {
    return inner.toString();
  }
}
