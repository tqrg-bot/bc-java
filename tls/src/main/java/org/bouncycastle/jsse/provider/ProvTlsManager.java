package org.bouncycastle.jsse.provider;

import java.io.IOException;
import java.security.Principal;
import java.security.cert.X509Certificate;

interface ProvTlsManager
{
    void checkClientTrusted(X509Certificate[] chain, String authType) throws IOException;

    void checkServerTrusted(X509Certificate[] chain, String authType) throws IOException;

    String chooseClientAlias(String[] keyType, Principal[] issuers);

    String chooseServerAlias(String keyType, Principal[] issuers);

    ProvSSLContextSpi getContext();

    boolean getEnableSessionCreation();

    ContextData getContextData();

    String getPeerHost();

    int getPeerPort();

    void notifyHandshakeComplete(ProvSSLConnection connection);

    void notifyHandshakeSession(ProvSSLSessionBase handshakeSession);
}
