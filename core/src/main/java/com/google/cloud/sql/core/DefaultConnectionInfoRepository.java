/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.sql.core;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.sqladmin.SQLAdmin;
import com.google.api.services.sqladmin.model.ConnectSettings;
import com.google.api.services.sqladmin.model.GenerateEphemeralCertRequest;
import com.google.api.services.sqladmin.model.GenerateEphemeralCertResponse;
import com.google.api.services.sqladmin.model.IpMapping;
import com.google.auth.oauth2.AccessToken;
import com.google.cloud.sql.AuthType;
import com.google.cloud.sql.IpType;
import com.google.common.base.CharMatcher;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.KeyStore.PasswordProtection;
import java.security.KeyStore.PrivateKeyEntry;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Class that encapsulates all logic for interacting with SQLAdmin API. */
class DefaultConnectionInfoRepository implements ConnectionInfoRepository {

  private static final Logger logger =
      Logger.getLogger(DefaultConnectionInfoRepository.class.getName());
  private final SQLAdmin apiClient;

  DefaultConnectionInfoRepository(SQLAdmin apiClient) {
    this.apiClient = apiClient;
  }

  private void checkDatabaseCompatibility(
      ConnectSettings instanceMetadata, AuthType authType, String connectionName) {
    if (authType == AuthType.IAM && instanceMetadata.getDatabaseVersion().contains("SQLSERVER")) {
      throw new IllegalArgumentException(
          String.format(
              "[%s] IAM Authentication is not supported for SQL Server instances.",
              connectionName));
    }
  }

  // Creates a Certificate object from a provided string.
  private Certificate createCertificate(String cert) throws CertificateException {
    byte[] certBytes = cert.getBytes(StandardCharsets.UTF_8);
    ByteArrayInputStream certStream = new ByteArrayInputStream(certBytes);
    return CertificateFactory.getInstance("X.509").generateCertificate(certStream);
  }

  private String generatePublicKeyCert(KeyPair keyPair) {
    // Format the public key into a PEM encoded Certificate.
    return "-----BEGIN RSA PUBLIC KEY-----\n"
        + BaseEncoding.base64().withSeparator("\n", 64).encode(keyPair.getPublic().getEncoded())
        + "\n"
        + "-----END RSA PUBLIC KEY-----\n";
  }

  /** Internal Use Only: Gets the instance data for the CloudSqlInstance from the API. */
  @Override
  public ListenableFuture<ConnectionInfo> getConnectionInfo(
      CloudSqlInstanceName instanceName,
      AccessTokenSupplier accessTokenSupplier,
      AuthType authType,
      ListeningScheduledExecutorService executor,
      ListenableFuture<KeyPair> keyPair) {

    ListenableFuture<Optional<AccessToken>> token = executor.submit(accessTokenSupplier::get);

    // Fetch the metadata
    ListenableFuture<InstanceMetadata> metadataFuture =
        executor.submit(() -> fetchMetadata(instanceName, authType));

    // Fetch the ephemeral certificates
    ListenableFuture<Certificate> ephemeralCertificateFuture =
        Futures.whenAllComplete(keyPair, token)
            .call(
                () ->
                    fetchEphemeralCertificate(
                        Futures.getDone(keyPair), instanceName, Futures.getDone(token), authType),
                executor);

    // Once the API calls are complete, construct the SSLContext for the sockets
    ListenableFuture<SslData> sslContextFuture =
        Futures.whenAllComplete(metadataFuture, ephemeralCertificateFuture)
            .call(
                () ->
                    createSslData(
                        Futures.getDone(keyPair),
                        Futures.getDone(metadataFuture),
                        Futures.getDone(ephemeralCertificateFuture),
                        instanceName,
                        authType),
                executor);

    // Once both the SSLContext and Metadata are complete, return the results
    ListenableFuture<ConnectionInfo> done =
        Futures.whenAllComplete(metadataFuture, ephemeralCertificateFuture, sslContextFuture)
            .call(
                () -> {

                  // Get expiration value for new cert
                  Certificate ephemeralCertificate = Futures.getDone(ephemeralCertificateFuture);
                  X509Certificate x509Certificate = (X509Certificate) ephemeralCertificate;
                  Instant expiration = x509Certificate.getNotAfter().toInstant();

                  if (authType == AuthType.IAM) {
                    expiration =
                        DefaultAccessTokenSupplier.getTokenExpirationTime(Futures.getDone(token))
                            .filter(
                                tokenExpiration ->
                                    x509Certificate
                                        .getNotAfter()
                                        .toInstant()
                                        .isAfter(tokenExpiration))
                            .orElse(x509Certificate.getNotAfter().toInstant());
                  }

                  logger.fine(String.format("[%s] INSTANCE DATA DONE", instanceName));

                  return new ConnectionInfo(
                      Futures.getDone(metadataFuture),
                      Futures.getDone(sslContextFuture),
                      expiration);
                },
                executor);

    done.addListener(
        () -> logger.fine(String.format("[%s] ALL FUTURES DONE", instanceName)), executor);
    return done;
  }

  String getApplicationName() {
    return apiClient.getApplicationName();
  }

  /** Fetches the latest version of the instance's metadata using the Cloud SQL Admin API. */
  private InstanceMetadata fetchMetadata(CloudSqlInstanceName instanceName, AuthType authType) {
    try {
      ConnectSettings instanceMetadata =
          apiClient
              .connect()
              .get(instanceName.getProjectId(), instanceName.getInstanceId())
              .execute();

      // Validate the instance will support the authenticated connection.
      if (!instanceMetadata.getRegion().equals(instanceName.getRegionId())) {
        throw new IllegalArgumentException(
            String.format(
                "[%s] The region specified for the Cloud SQL instance is"
                    + " incorrect. Please verify the instance connection name.",
                instanceName.getConnectionName()));
      }
      if (!instanceMetadata.getBackendType().equals("SECOND_GEN")) {
        throw new IllegalArgumentException(
            String.format(
                "[%s] Connections to Cloud SQL instance not supported - not a Second Generation "
                    + "instance.",
                instanceName.getConnectionName()));
      }

      checkDatabaseCompatibility(instanceMetadata, authType, instanceName.getConnectionName());

      Map<IpType, String> ipAddrs = new HashMap<>();
      if (instanceMetadata.getIpAddresses() != null) {
        // Update the IP addresses and types need to connect with the instance.
        for (IpMapping addr : instanceMetadata.getIpAddresses()) {
          if ("PRIVATE".equals(addr.getType())) {
            ipAddrs.put(IpType.PRIVATE, addr.getIpAddress());
          } else if ("PRIMARY".equals(addr.getType())) {
            ipAddrs.put(IpType.PUBLIC, addr.getIpAddress());
          }
          // otherwise, we don't know how to handle this type, ignore it.
        }
      }

      // resolve DnsName into IP address for PSC
      if (instanceMetadata.getDnsName() != null && !instanceMetadata.getDnsName().isEmpty()) {
        ipAddrs.put(IpType.PSC, instanceMetadata.getDnsName());
      }

      // Verify the instance has at least one IP type assigned that can be used to connect.
      if (ipAddrs.isEmpty()) {
        throw new IllegalStateException(
            String.format(
                "[%s] Unable to connect to Cloud SQL instance: instance does not have an assigned "
                    + "IP address.",
                instanceName.getConnectionName()));
      }

      // Update the Server CA certificate used to create the SSL connection with the instance.
      try {
        Certificate instanceCaCertificate =
            createCertificate(instanceMetadata.getServerCaCert().getCert());

        logger.fine(String.format("[%s] METADATA DONE", instanceName));

        return new InstanceMetadata(ipAddrs, instanceCaCertificate);
      } catch (CertificateException ex) {
        throw new RuntimeException(
            String.format(
                "[%s] Unable to parse the server CA certificate for the Cloud SQL instance.",
                instanceName.getConnectionName()),
            ex);
      }
    } catch (IOException ex) {
      throw addExceptionContext(
          ex,
          String.format(
              "[%s] Failed to update metadata for Cloud SQL instance.",
              instanceName.getConnectionName()),
          instanceName);
    }
  }

  /**
   * Uses the Cloud SQL Admin API to create an ephemeral SSL certificate that is authenticated to
   * connect the Cloud SQL instance for up to 60 minutes.
   */
  private Certificate fetchEphemeralCertificate(
      KeyPair keyPair,
      CloudSqlInstanceName instanceName,
      Optional<AccessToken> accessTokenOptional,
      AuthType authType) {

    // Use the SQL Admin API to create a new ephemeral certificate.
    GenerateEphemeralCertRequest request =
        new GenerateEphemeralCertRequest().setPublicKey(generatePublicKeyCert(keyPair));

    if (authType == AuthType.IAM && accessTokenOptional.isPresent()) {
      AccessToken accessToken = accessTokenOptional.get();

      String token = accessToken.getTokenValue();
      // TODO: remove this once issue with OAuth2 Tokens is resolved.
      // See: https://github.com/GoogleCloudPlatform/cloud-sql-jdbc-socket-factory/issues/565
      request.setAccessToken(CharMatcher.is('.').trimTrailingFrom(token));
    }
    GenerateEphemeralCertResponse response;
    try {
      response =
          apiClient
              .connect()
              .generateEphemeralCert(
                  instanceName.getProjectId(), instanceName.getInstanceId(), request)
              .execute();
    } catch (IOException ex) {
      throw addExceptionContext(
          ex,
          String.format(
              "[%s] Failed to create ephemeral certificate for the Cloud SQL instance.",
              instanceName.getConnectionName()),
          instanceName);
    }

    // Parse the certificate from the response.
    Certificate ephemeralCertificate;
    try {
      ephemeralCertificate = createCertificate(response.getEphemeralCert().getCert());
    } catch (CertificateException ex) {
      throw new RuntimeException(
          String.format(
              "[%s] Unable to parse the ephemeral certificate for the Cloud SQL instance.",
              instanceName.getConnectionName()),
          ex);
    }

    logger.fine(String.format("[%s %d] CERT DONE", instanceName, Thread.currentThread().getId()));

    return ephemeralCertificate;
  }

  /**
   * Creates a new SslData based on the provided parameters. It contains a SSLContext that will be
   * used to provide new SSLSockets authorized to connect to a Cloud SQL instance. It also contains
   * a KeyManagerFactory and a TrustManagerFactory that can be used by drivers to establish an SSL
   * tunnel.
   */
  private SslData createSslData(
      KeyPair keyPair,
      InstanceMetadata instanceMetadata,
      Certificate ephemeralCertificate,
      CloudSqlInstanceName instanceName,
      AuthType authType) {
    try {
      KeyStore authKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      authKeyStore.load(null, null);
      KeyStore.PrivateKeyEntry privateKey =
          new PrivateKeyEntry(keyPair.getPrivate(), new Certificate[] {ephemeralCertificate});
      authKeyStore.setEntry("ephemeral", privateKey, new PasswordProtection(new char[0]));
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(authKeyStore, new char[0]);

      KeyStore trustedKeyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustedKeyStore.load(null, null);
      trustedKeyStore.setCertificateEntry("instance", instanceMetadata.getInstanceCaCertificate());
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(
              TrustManagerFactory.getDefaultAlgorithm(), // jdk default PKIX
              SSLContext.getDefault().getProvider()      // jdk default SunJSSE
              );
      tmf.init(trustedKeyStore);
      SSLContext sslContext;

      try {
        sslContext = SSLContext.getInstance("TLSv1.3");
      } catch (NoSuchAlgorithmException ex) {
        if (authType == AuthType.IAM) {
          throw new RuntimeException(
              String.format(
                      "[%s] Unable to create a SSLContext for the Cloud SQL instance.",
                      instanceName.getConnectionName())
                  + " TLSv1.3 is not supported for your Java version and is required to connect"
                  + " using IAM authentication",
              ex);
        } else {
          logger.warning("TLSv1.3 is not supported for your Java version, fallback to TLSv1.2");
          sslContext = SSLContext.getInstance("TLSv1.2");
        }
      }

      sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

      logger.fine(
          String.format("[%s %d] SSL CONTEXT", instanceName, Thread.currentThread().getId()));

      return new SslData(sslContext, kmf, tmf);
    } catch (GeneralSecurityException | IOException ex) {
      throw new RuntimeException(
          String.format(
              "[%s] Unable to create a SSLContext for the Cloud SQL instance.",
              instanceName.getConnectionName()),
          ex);
    }
  }

  /**
   * Checks for common errors that can occur when interacting with the Cloud SQL Admin API, and adds
   * additional context to help the user troubleshoot them.
   *
   * @param ex exception thrown by the Admin API request
   * @param fallbackDesc generic description used as a fallback if no additional information can be
   *     provided to the user
   */
  private RuntimeException addExceptionContext(
      IOException ex, String fallbackDesc, CloudSqlInstanceName instanceName) {
    // Verify we are able to extract a reason from an exception, or fallback to a generic desc
    GoogleJsonResponseException gjrEx =
        ex instanceof GoogleJsonResponseException ? (GoogleJsonResponseException) ex : null;
    if (gjrEx == null
        || gjrEx.getDetails() == null
        || gjrEx.getDetails().getErrors() == null
        || gjrEx.getDetails().getErrors().isEmpty()) {
      return new RuntimeException(fallbackDesc, ex);
    }
    // Check for commonly occurring user errors and add additional context
    String reason = gjrEx.getDetails().getErrors().get(0).getReason();
    if ("accessNotConfigured".equals(reason)) {
      // This error occurs when the project doesn't have the "Cloud SQL Admin API" enabled
      String apiLink =
          "https://console.cloud.google.com/apis/api/sqladmin/overview?project="
              + instanceName.getProjectId();
      return new RuntimeException(
          String.format(
              "[%s] The Google Cloud SQL Admin API is not enabled for the project \"%s\". Please "
                  + "use the Google Developers Console to enable it: %s",
              instanceName.getConnectionName(), instanceName.getProjectId(), apiLink),
          ex);
    } else if ("notAuthorized".equals(reason)) {
      // This error occurs if the instance doesn't exist or the account isn't authorized
      // TODO(kvg): Add credential account name to error string.
      return new RuntimeException(
          String.format(
              "[%s] The Cloud SQL Instance does not exist or your account is not authorized to "
                  + "access it. Please verify the instance connection name and check the IAM "
                  + "permissions for project \"%s\" ",
              instanceName.getConnectionName(), instanceName.getProjectId()),
          ex);
    }
    // Fallback to the generic description
    return new RuntimeException(fallbackDesc, ex);
  }
}
