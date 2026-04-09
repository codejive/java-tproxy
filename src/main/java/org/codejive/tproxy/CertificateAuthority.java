package org.codejive.tproxy;

import java.io.*;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Certificate Authority for generating SSL certificates on-the-fly for HTTPS interception.
 *
 * <p>This class manages a root CA certificate and generates host-specific certificates signed by
 * that CA. The root CA certificate is stored in the current directory and reused across restarts.
 */
public class CertificateAuthority {
    private static final Logger logger = LoggerFactory.getLogger(CertificateAuthority.class);

    private static final String CA_ALIAS = "tproxy-ca";
    private static final String CA_KEYSTORE_FILE = "tproxy-ca.p12";
    private static final String CA_CERT_FILE = "tproxy-ca.crt";
    private static final char[] KEYSTORE_PASSWORD = "changeit".toCharArray();
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";
    private static final int KEY_SIZE = 2048;
    private static final int VALIDITY_DAYS = 365;

    static {
        // Register BouncyCastle as a security provider
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final KeyStore caKeyStore;
    private final X509Certificate caCertificate;
    private final PrivateKey caPrivateKey;

    /**
     * Create a new Certificate Authority. If a CA keystore exists in the current directory, it will
     * be loaded. Otherwise, a new CA certificate will be generated.
     *
     * @throws IOException if there is an error loading or creating the CA
     */
    public CertificateAuthority() throws IOException {
        try {
            File keystoreFile = new File(CA_KEYSTORE_FILE);

            if (keystoreFile.exists()) {
                logger.info("Loading existing CA from {}", CA_KEYSTORE_FILE);
                caKeyStore = loadKeyStore(keystoreFile);
            } else {
                logger.info("Generating new CA certificate");
                caKeyStore = generateCACertificate();
                saveKeyStore(caKeyStore, keystoreFile);
            }

            // Load CA certificate and private key from keystore
            caCertificate = (X509Certificate) caKeyStore.getCertificate(CA_ALIAS);
            caPrivateKey = (PrivateKey) caKeyStore.getKey(CA_ALIAS, KEYSTORE_PASSWORD);

            if (caCertificate == null || caPrivateKey == null) {
                throw new IOException("CA certificate or private key not found in keystore");
            }

            // Export CA certificate if it was just generated
            if (!keystoreFile.exists() || !new File(CA_CERT_FILE).exists()) {
                exportCACertificate(caCertificate);
            }

            logger.info("CA initialized: {}", caCertificate.getSubjectX500Principal());

        } catch (Exception e) {
            throw new IOException("Failed to initialize Certificate Authority", e);
        }
    }

    /**
     * Generate a server certificate for the given hostname, signed by the CA.
     *
     * @param hostname the hostname for which to generate a certificate
     * @return KeyStore containing the generated certificate and private key
     * @throws IOException if there is an error generating the certificate
     */
    public KeyStore generateServerCertificate(String hostname) throws IOException {
        try {
            logger.debug("Generating certificate for hostname: {}", hostname);

            // Generate key pair for the server certificate
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(KEY_SIZE);
            KeyPair serverKeyPair = keyGen.generateKeyPair();

            // Build the certificate using the CA certificate as issuer
            X500Name subject =
                    new X500Name("CN=" + hostname + ", O=TProxy, OU=Generated Certificate");
            BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
            Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
            Instant notAfter = Instant.now().plus(VALIDITY_DAYS, ChronoUnit.DAYS);

            X509v3CertificateBuilder certBuilder =
                    new JcaX509v3CertificateBuilder(
                            caCertificate, // Use the CA certificate directly as issuer
                            serial,
                            Date.from(notBefore),
                            Date.from(notAfter),
                            subject,
                            serverKeyPair.getPublic());

            // Add Subject Alternative Name (SAN) for the hostname
            GeneralName[] subjectAltNames =
                    new GeneralName[] {new GeneralName(GeneralName.dNSName, hostname)};
            certBuilder.addExtension(
                    Extension.subjectAlternativeName, false, new GeneralNames(subjectAltNames));

            // Mark as not a CA
            certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

            // Add key usage for server certificate
            certBuilder.addExtension(
                    Extension.keyUsage,
                    true,
                    new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

            // Add extended key usage for TLS server authentication
            certBuilder.addExtension(
                    Extension.extendedKeyUsage,
                    false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

            // Sign the certificate with the CA private key
            ContentSigner signer =
                    new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                            .build(caPrivateKey);

            X509Certificate serverCert =
                    new JcaX509CertificateConverter()
                            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                            .getCertificate(certBuilder.build(signer));

            // Verify the generated certificate can be verified with the CA
            serverCert.verify(caCertificate.getPublicKey());

            // Create keystore with the server certificate and private key
            KeyStore serverKeyStore = KeyStore.getInstance("PKCS12");
            serverKeyStore.load(null, null);
            serverKeyStore.setKeyEntry(
                    hostname,
                    serverKeyPair.getPrivate(),
                    KEYSTORE_PASSWORD,
                    new Certificate[] {serverCert, caCertificate});

            logger.debug("Generated certificate for: {}", hostname);
            return serverKeyStore;

        } catch (Exception e) {
            throw new IOException("Failed to generate server certificate for " + hostname, e);
        }
    }

    /**
     * Get the CA certificate that clients should trust.
     *
     * @return the CA certificate
     */
    public X509Certificate getCACertificate() {
        return caCertificate;
    }

    /**
     * Generate a self-signed CA certificate.
     *
     * @return KeyStore containing the CA certificate and private key
     */
    private KeyStore generateCACertificate()
            throws NoSuchAlgorithmException,
                    OperatorCreationException,
                    CertificateException,
                    KeyStoreException,
                    IOException {

        // Generate key pair for CA
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(KEY_SIZE);
        KeyPair caKeyPair = keyGen.generateKeyPair();

        // Build CA certificate
        X500Name subject = new X500Name("CN=TProxy CA, O=TProxy, OU=Certificate Authority");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant notAfter = Instant.now().plus(VALIDITY_DAYS * 10, ChronoUnit.DAYS); // 10 years

        X509v3CertificateBuilder certBuilder =
                new JcaX509v3CertificateBuilder(
                        subject,
                        serial,
                        Date.from(notBefore),
                        Date.from(notAfter),
                        subject,
                        caKeyPair.getPublic());

        // Mark as CA
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));

        // Add key usage for CA
        certBuilder.addExtension(
                Extension.keyUsage,
                true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));

        // Self-sign the certificate
        ContentSigner signer =
                new JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .build(caKeyPair.getPrivate());

        X509Certificate caCert =
                new JcaX509CertificateConverter()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .getCertificate(certBuilder.build(signer));

        // Create keystore with CA certificate and private key
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(
                CA_ALIAS, caKeyPair.getPrivate(), KEYSTORE_PASSWORD, new Certificate[] {caCert});

        return keyStore;
    }

    /** Load a keystore from file. */
    private KeyStore loadKeyStore(File file) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(file)) {
            keyStore.load(fis, KEYSTORE_PASSWORD);
        }
        return keyStore;
    }

    /** Save a keystore to file. */
    private void saveKeyStore(KeyStore keyStore, File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            keyStore.store(fos, KEYSTORE_PASSWORD);
        }
        logger.info("Saved CA keystore to {}", file.getAbsolutePath());
    }

    /** Export CA certificate to PEM file for easy import into browsers. */
    private void exportCACertificate(X509Certificate cert) throws Exception {
        File certFile = new File(CA_CERT_FILE);
        try (FileWriter fw = new FileWriter(certFile)) {
            fw.write("-----BEGIN CERTIFICATE-----\n");
            fw.write(
                    java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                            .encodeToString(cert.getEncoded()));
            fw.write("\n-----END CERTIFICATE-----\n");
        }
        logger.info(
                "Exported CA certificate to {} (import this into your browser)",
                certFile.getAbsolutePath());
    }
}
