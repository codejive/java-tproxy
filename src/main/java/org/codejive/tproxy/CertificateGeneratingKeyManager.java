package org.codejive.tproxy;

import java.io.IOException;
import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * X509ExtendedKeyManager that generates server certificates on-the-fly based on the SNI hostname
 * from the client's TLS ClientHello.
 *
 * <p>When a TLS handshake begins, this key manager extracts the requested hostname from the SNI
 * extension, generates a certificate for that hostname using the provided CertificateAuthority, and
 * returns it for the handshake. Generated certificates are cached for reuse.
 */
class CertificateGeneratingKeyManager extends X509ExtendedKeyManager {
    private static final Logger logger =
            LoggerFactory.getLogger(CertificateGeneratingKeyManager.class);
    private static final char[] PASSWORD = "changeit".toCharArray();

    private final CertificateAuthority certificateAuthority;
    private final ConcurrentHashMap<String, KeyStore> certificateCache = new ConcurrentHashMap<>();

    CertificateGeneratingKeyManager(CertificateAuthority certificateAuthority) {
        this.certificateAuthority = certificateAuthority;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        if (engine == null) return null;
        try {
            SSLSession session = engine.getHandshakeSession();
            if (session instanceof ExtendedSSLSession extSession) {
                List<SNIServerName> serverNames = extSession.getRequestedServerNames();
                for (SNIServerName name : serverNames) {
                    if (name instanceof SNIHostName hostName) {
                        String hostname = hostName.getAsciiName();
                        ensureCertificateExists(hostname);
                        return hostname;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error choosing server alias from SNI", e);
        }
        return null;
    }

    @Override
    public PrivateKey getPrivateKey(String alias) {
        KeyStore ks = certificateCache.get(alias);
        if (ks != null) {
            try {
                return (PrivateKey) ks.getKey(alias, PASSWORD);
            } catch (GeneralSecurityException e) {
                logger.error("Error getting private key for alias: {}", alias, e);
            }
        }
        return null;
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        KeyStore ks = certificateCache.get(alias);
        if (ks != null) {
            try {
                java.security.cert.Certificate[] chain = ks.getCertificateChain(alias);
                if (chain != null) {
                    X509Certificate[] x509Chain = new X509Certificate[chain.length];
                    for (int i = 0; i < chain.length; i++) {
                        x509Chain[i] = (X509Certificate) chain[i];
                    }
                    return x509Chain;
                }
            } catch (KeyStoreException e) {
                logger.error("Error getting certificate chain for alias: {}", alias, e);
            }
        }
        return null;
    }

    private void ensureCertificateExists(String hostname) {
        certificateCache.computeIfAbsent(
                hostname,
                h -> {
                    try {
                        logger.debug("Generating certificate for: {}", h);
                        return certificateAuthority.generateServerCertificate(h);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to generate certificate for " + h, e);
                    }
                });
    }

    // Server-side only — client methods are unused
    @Override
    public String[] getClientAliases(String keyType, Principal[] issuers) {
        return null;
    }

    @Override
    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
        return null;
    }

    @Override
    public String[] getServerAliases(String keyType, Principal[] issuers) {
        return null;
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return null;
    }
}
