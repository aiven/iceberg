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
package org.apache.iceberg.gcp.gcs;

import com.google.api.gax.rpc.FixedHeaderProvider;
import com.google.api.services.storage.StorageScopes;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.iceberg.EnvironmentContext;
import org.apache.iceberg.gcp.GCPAuthUtils;
import org.apache.iceberg.gcp.GCPProperties;
import org.apache.iceberg.io.CloseableGroup;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Strings;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.util.SerializableSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class PrefixedStorage implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(PrefixedStorage.class);
  private static final String GCS_FILE_IO_USER_AGENT = "gcsfileio/" + EnvironmentContext.get();
  private final String storagePrefix;
  private final GCPProperties gcpProperties;
  private SerializableSupplier<Storage> storage;
  private CloseableGroup closeableGroup;
  private transient volatile Storage storageClient;

  PrefixedStorage(
      String storagePrefix, Map<String, String> properties, SerializableSupplier<Storage> storage) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(storagePrefix), "Invalid storage prefix: null or empty");
    Preconditions.checkArgument(null != properties, "Invalid properties: null");
    this.storagePrefix = storagePrefix;
    this.storage = storage;
    this.gcpProperties = new GCPProperties(properties);

    if (null == storage) {
      this.storage =
          () -> {
            StorageOptions.Builder builder =
                StorageOptions.newBuilder()
                    .setHeaderProvider(
                        FixedHeaderProvider.create(
                            ImmutableMap.of("User-agent", GCS_FILE_IO_USER_AGENT)));

            gcpProperties.projectId().ifPresent(builder::setProjectId);
            gcpProperties.clientLibToken().ifPresent(builder::setClientLibToken);
            gcpProperties.serviceHost().ifPresent(builder::setHost);

            // Google Cloud APIs default to automatically detect the credentials to use, which is
            // in most cases the convenient way, especially in GCP.
            // See javadoc of com.google.auth.oauth2.GoogleCredentials.getApplicationDefault()
            if (gcpProperties.noAuth()) {
              // Explicitly allow "no credentials" for testing purposes
              LOG.info("Using no credentials for prefix {} (for testing)", storagePrefix);
              builder.setCredentials(NoCredentials.getInstance());
            }

            if (gcpProperties.credentialsPath().isPresent()) {
              LOG.info(
                  "Using Google credentials from path: {} for prefix {}",
                  gcpProperties.credentialsPath().get(),
                  storagePrefix);
              try (FileInputStream credentialsStream =
                  new FileInputStream(gcpProperties.credentialsPath().get())) {
                builder.setCredentials(
                    GoogleCredentials.fromStream(credentialsStream)
                        .createScoped(StorageScopes.DEVSTORAGE_READ_WRITE));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }

            if (gcpProperties.credentialsJson().isPresent()) {
              LOG.info(
                  "Using embedded Google credentials from configuration for prefix{}",
                  storagePrefix);
              try (ByteArrayInputStream credentialsStream =
                  new ByteArrayInputStream(
                      gcpProperties.credentialsJson().get().getBytes(StandardCharsets.UTF_8))) {
                builder.setCredentials(
                    GoogleCredentials.fromStream(credentialsStream)
                        .createScoped(StorageScopes.DEVSTORAGE_READ_WRITE));
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            }

            if (gcpProperties.oauth2Token().isPresent()) {
              LOG.info("Using OAuth2 token from configuration for prefix {}", storagePrefix);
              this.closeableGroup = new CloseableGroup();
              builder.setCredentials(
                  GCPAuthUtils.oauth2CredentialsFromGcpProperties(gcpProperties, closeableGroup));
            }

            return builder.build().getService();
          };
    }
  }

  public String storagePrefix() {
    return storagePrefix;
  }

  public Storage storage() {
    if (null == storageClient) {
      synchronized (this) {
        if (null == storageClient) {
          this.storageClient = storage.get();
        }
      }
    }

    return storageClient;
  }

  public GCPProperties gcpProperties() {
    return gcpProperties;
  }

  @Override
  public void close() {
    if (null != closeableGroup) {
      try {
        closeableGroup.close();
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }

    if (null != storage) {
      // GCS Storage does not appear to be closable, so release the reference
      storage = null;
    }
  }
}
