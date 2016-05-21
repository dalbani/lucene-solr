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
package org.apache.solr.util;

import java.io.File;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;

import javax.net.ssl.SSLContext;

import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.SSLContextBuilder;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.solr.client.solrj.embedded.SSLConfig;
import org.apache.solr.client.solrj.impl.HttpClientUtil;
import org.apache.solr.client.solrj.impl.HttpClientConfigurer;
import org.apache.solr.common.params.SolrParams;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.security.CertificateUtils;

public class SSLTestConfig extends SSLConfig {
  public static File TEST_KEYSTORE = ExternalPaths.SERVER_HOME == null ? null
      : new File(ExternalPaths.SERVER_HOME, "../etc/test/solrtest.keystore");
  
  private static String TEST_KEYSTORE_PATH = TEST_KEYSTORE != null
      && TEST_KEYSTORE.exists() ? TEST_KEYSTORE.getAbsolutePath() : null;
  private static String TEST_KEYSTORE_PASSWORD = "secret";
  
  public SSLTestConfig() {
    this(false, false);
  }
  
  public SSLTestConfig(boolean useSSL, boolean clientAuth) {
    this(useSSL, clientAuth, TEST_KEYSTORE_PATH, TEST_KEYSTORE_PASSWORD, TEST_KEYSTORE_PATH, TEST_KEYSTORE_PASSWORD);
  }
 
  public SSLTestConfig(boolean useSSL, boolean clientAuth, String keyStore, String keyStorePassword, String trustStore, String trustStorePassword) {
    super(useSSL, clientAuth, keyStore, keyStorePassword, trustStore, trustStorePassword);
  }
  
  /**
   * Creates a {@link HttpClientConfigurer} for HTTP <b>clients</b> to use when communicating with servers 
   * which have been configured based on the settings of this object.  When {@link #isSSLMode} is true, this 
   * <code>HttpClientConfigurer</code> will <i>only</i> support HTTPS (no HTTP scheme) using the 
   * appropriate certs.  When {@link #isSSLMode} is false, <i>only</i> HTTP (no HTTPS scheme) will be 
   * supported.
   */
  public HttpClientConfigurer getHttpClientConfigurer() {
    try {
      return isSSLMode() ? new SSLHttpClientConfigurer(buildClientSSLContext()) : HTTP_ONLY_NO_SSL_CONFIGURER;
    } catch (KeyManagementException | UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException e) {
      throw new IllegalStateException("Unable to setup HttpClientConfigurer test SSL", e);
    }
  }
  
  /**
   * Builds a new SSLContext for HTTP <b>clients</b> to use when communicating with servers which have 
   * been configured based on the settings of this object.  Also explicitly allows the use of self-signed 
   * certificates (since that's what is almost always used during testing).
   */
  public SSLContext buildClientSSLContext() throws KeyManagementException, 
    UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException {

    assert isSSLMode();
    
    SSLContextBuilder builder = SSLContexts.custom();

    // NOTE: KeyStore & TrustStore are swapped because they are from configured from server perspective...
    // we are a client - our keystore contains the keys the server trusts, and vice versa
    builder.loadTrustMaterial(buildKeyStore(getKeyStore(), getKeyStorePassword()), new TrustSelfSignedStrategy()).build();

    if (isClientAuthMode()) {
      builder.loadKeyMaterial(buildKeyStore(getTrustStore(), getTrustStorePassword()), getTrustStorePassword().toCharArray());
      
    }

    return builder.build();
  }
  
  /**
   * Constructs a KeyStore using the specified filename and password
   */
  protected static KeyStore buildKeyStore(String keyStoreLocation, String password) {
    try {
      return CertificateUtils.getKeyStore(Resource.newResource(keyStoreLocation), "JKS", null, password);
    } catch (Exception ex) {
      throw new IllegalStateException("Unable to build KeyStore from file: " + keyStoreLocation, ex);
    }
  }
  
  private static class SSLHttpClientConfigurer extends HttpClientConfigurer {
    private final SSLContext sslContext;
    public SSLHttpClientConfigurer(SSLContext sslContext) {
       this.sslContext = sslContext;
     }
    @SuppressWarnings("deprecation")
    public void configure(DefaultHttpClient httpClient, SolrParams config) {
      super.configure(httpClient, config);
      SchemeRegistry registry = httpClient.getConnectionManager().getSchemeRegistry();
      // Make sure no tests cheat by using HTTP
      registry.unregister("http");
      registry.register(new Scheme("https", 443, new SSLSocketFactory(sslContext)));
    }
  }

  private static final HttpClientConfigurer HTTP_ONLY_NO_SSL_CONFIGURER =
    new HttpClientConfigurer() {
      @SuppressWarnings("deprecation")
      public void configure(DefaultHttpClient httpClient, SolrParams config) {
        super.configure(httpClient, config);
        SchemeRegistry registry = httpClient.getConnectionManager().getSchemeRegistry();
        registry.unregister("https");
      }
    };
  
  /** 
   * Constructs a new SSLConnectionSocketFactory for HTTP <b>clients</b> to use when communicating 
   * with servers which have been configured based on the settings of this object. Will return null
   * unless {@link #isSSLMode} is true.
   */
  public SSLConnectionSocketFactory buildClientSSLConnectionSocketFactory() {
    if (!isSSLMode()) {
      return null;
    }
    SSLConnectionSocketFactory sslConnectionFactory;
    try {
      boolean sslCheckPeerName = toBooleanDefaultIfNull(toBooleanObject(System.getProperty(HttpClientUtil.SYS_PROP_CHECK_PEER_NAME)), true);
      SSLContext sslContext = buildClientSSLContext();
      if (sslCheckPeerName == false) {
        sslConnectionFactory = new SSLConnectionSocketFactory
          (sslContext, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
      } else {
        sslConnectionFactory = new SSLConnectionSocketFactory(sslContext);
      }
    } catch (KeyManagementException | UnrecoverableKeyException | NoSuchAlgorithmException | KeyStoreException e) {
      throw new IllegalStateException("Unable to setup https scheme for HTTPClient to test SSL.", e);
    }
    return sslConnectionFactory;
  }
  
  public static boolean toBooleanDefaultIfNull(Boolean bool, boolean valueIfNull) {
    if (bool == null) {
      return valueIfNull;
    }
    return bool.booleanValue() ? true : false;
  }
  
  public static Boolean toBooleanObject(String str) {
    if ("true".equalsIgnoreCase(str)) {
      return Boolean.TRUE;
    } else if ("false".equalsIgnoreCase(str)) {
      return Boolean.FALSE;
    }
    // no match
    return null;
  }
  
  /**
   * @deprecated this method has very little practical use, in most cases you'll want to use 
   * {@link SSLContext#setDefault} with {@link #buildClientSSLContext} instead.
   */
  @Deprecated
  public static void setSSLSystemProperties() {
    System.setProperty("javax.net.ssl.keyStore", TEST_KEYSTORE_PATH);
    System.setProperty("javax.net.ssl.keyStorePassword", TEST_KEYSTORE_PASSWORD);
    System.setProperty("javax.net.ssl.trustStore", TEST_KEYSTORE_PATH);
    System.setProperty("javax.net.ssl.trustStorePassword", TEST_KEYSTORE_PASSWORD);
  }
  
  /**
   * @deprecated this method has very little practical use, in most cases you'll want to use 
   * {@link SSLContext#setDefault} with {@link #buildClientSSLContext} instead.
   */
  @Deprecated
  public static void clearSSLSystemProperties() {
    System.clearProperty("javax.net.ssl.keyStore");
    System.clearProperty("javax.net.ssl.keyStorePassword");
    System.clearProperty("javax.net.ssl.trustStore");
    System.clearProperty("javax.net.ssl.trustStorePassword");
  }
  
}
