package org.codejive.tproxy;

import static org.assertj.core.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Tests for CertificateAuthority. */
@DisplayName("Certificate Authority Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CertificateAuthorityTest {

    private static final String TEST_HOSTNAME = "example.com";

    @TempDir Path tempDir;

    private CertificateAuthority ca;

    @BeforeEach
    public void setUp() throws Exception {
        // Create new CA using temporary directory
        ca = new CertificateAuthority(tempDir);
    }

    @Test
    @Order(1)
    @DisplayName("Should generate CA certificate on first use")
    public void testGenerateCA() {
        X509Certificate caCert = ca.caCertificate();

        assertThat(caCert).isNotNull();
        assertThat(caCert.getSubjectX500Principal().getName()).contains("TProxy CA");
        assertThat(caCert.getBasicConstraints()).isGreaterThanOrEqualTo(0); // Is a CA
    }

    @Test
    @Order(2)
    @DisplayName("Should persist CA certificate to disk")
    public void testPersistCA() throws Exception {
        File keystoreFile = tempDir.resolve("tproxy-ca.p12").toFile();
        File certFile = tempDir.resolve("tproxy-ca.crt").toFile();

        assertThat(keystoreFile).exists();
        assertThat(certFile).exists();
    }

    @Test
    @Order(3)
    @DisplayName("Should reuse existing CA certificate")
    public void testReuseCA() throws Exception {
        X509Certificate firstCert = ca.caCertificate();

        // Create a new CA instance (should load existing)
        CertificateAuthority ca2 = new CertificateAuthority(tempDir);
        X509Certificate secondCert = ca2.caCertificate();

        assertThat(secondCert.getSerialNumber()).isEqualTo(firstCert.getSerialNumber());
        assertThat(secondCert.getEncoded()).isEqualTo(firstCert.getEncoded());
    }

    @Test
    @Order(4)
    @DisplayName("Should generate server certificate for hostname")
    public void testGenerateServerCertificate() throws Exception {
        KeyStore serverKeyStore = ca.generateServerCertificate(TEST_HOSTNAME);

        assertThat(serverKeyStore).isNotNull();
        assertThat(serverKeyStore.containsAlias(TEST_HOSTNAME)).isTrue();

        X509Certificate serverCert = (X509Certificate) serverKeyStore.getCertificate(TEST_HOSTNAME);
        assertThat(serverCert).isNotNull();
        assertThat(serverCert.getSubjectX500Principal().getName()).contains(TEST_HOSTNAME);
        assertThat(serverCert.getBasicConstraints()).isEqualTo(-1); // Not a CA

        // Verify certificate chain
        assertThat(serverKeyStore.getCertificateChain(TEST_HOSTNAME)).hasSize(2);
    }

    @Test
    @Order(5)
    @DisplayName("Should include Subject Alternative Name")
    public void testSubjectAlternativeName() throws Exception {
        KeyStore serverKeyStore = ca.generateServerCertificate(TEST_HOSTNAME);
        X509Certificate serverCert = (X509Certificate) serverKeyStore.getCertificate(TEST_HOSTNAME);

        var sanCollection = serverCert.getSubjectAlternativeNames();
        assertThat(sanCollection).isNotNull();
        assertThat(sanCollection).isNotEmpty();

        // SAN should contain the hostname (type 2 = dNSName)
        boolean foundHostname =
                sanCollection.stream()
                        .anyMatch(
                                san -> {
                                    Integer type = (Integer) san.get(0);
                                    String value = (String) san.get(1);
                                    return type == 2 && TEST_HOSTNAME.equals(value);
                                });

        assertThat(foundHostname).isTrue();
    }

    @Test
    @Order(6)
    @DisplayName("Should verify server certificate with CA")
    public void testVerifyServerCertificate() throws Exception {
        KeyStore serverKeyStore = ca.generateServerCertificate(TEST_HOSTNAME);
        X509Certificate serverCert = (X509Certificate) serverKeyStore.getCertificate(TEST_HOSTNAME);
        X509Certificate caCert = ca.caCertificate();

        // Verify the server certificate was signed by the CA
        assertThatCode(() -> serverCert.verify(caCert.getPublicKey())).doesNotThrowAnyException();
    }

    @Test
    @Order(7)
    @DisplayName("Should generate different certificates for different hostnames")
    public void testDifferentHostnames() throws Exception {
        KeyStore keyStore1 = ca.generateServerCertificate("example.com");
        KeyStore keyStore2 = ca.generateServerCertificate("test.com");

        X509Certificate cert1 = (X509Certificate) keyStore1.getCertificate("example.com");
        X509Certificate cert2 = (X509Certificate) keyStore2.getCertificate("test.com");

        assertThat(cert1.getSerialNumber()).isNotEqualTo(cert2.getSerialNumber());
        assertThat(cert1.getSubjectX500Principal().getName()).contains("example.com");
        assertThat(cert2.getSubjectX500Principal().getName()).contains("test.com");
    }
}
