package org.bouncycastle.tls;

import java.io.IOException;

import org.bouncycastle.tls.crypto.TlsCrypto;
import org.bouncycastle.tls.crypto.TlsNonceGenerator;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.Pack;
import org.bouncycastle.util.Times;

abstract class AbstractTlsContext
    implements TlsContext
{
    private static long counter = Times.nanoTime();

    private synchronized static long nextCounterValue()
    {
        return ++counter;
    }

    private static TlsNonceGenerator createNonceGenerator(TlsCrypto crypto, int connectionEnd)
    {
        byte[] additionalSeedMaterial = new byte[16];
        Pack.longToBigEndian(nextCounterValue(), additionalSeedMaterial, 0);
        Pack.longToBigEndian(Times.nanoTime(), additionalSeedMaterial, 8);
        additionalSeedMaterial[0] = (byte)connectionEnd;

        return crypto.createNonceGenerator(additionalSeedMaterial);
    }

    private TlsCrypto crypto;
    private int connectionEnd;
    private TlsNonceGenerator nonceGenerator;
    private SecurityParameters securityParametersHandshake = null;
    private SecurityParameters securityParametersConnection = null;

    private ProtocolVersion[] clientSupportedVersions = null;
    private ProtocolVersion clientVersion = null;
    private TlsSession session = null;
    private Object userObject = null;

    AbstractTlsContext(TlsCrypto crypto, int connectionEnd)
    {
        this.crypto = crypto;
        this.connectionEnd = connectionEnd;
        this.nonceGenerator = createNonceGenerator(crypto, connectionEnd);
    }

    synchronized void handshakeBeginning(TlsPeer peer) throws IOException
    {
        if (null != securityParametersHandshake)
        {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        securityParametersHandshake = new SecurityParameters();
        securityParametersHandshake.entity = connectionEnd;

        if (null != securityParametersConnection)
        {
            securityParametersHandshake.renegotiating = true;
            securityParametersHandshake.secureRenegotiation = securityParametersConnection.isSecureRenegotiation();
            securityParametersHandshake.negotiatedVersion = securityParametersConnection.getNegotiatedVersion();
        }

        peer.notifyHandshakeBeginning();
    }

    synchronized void handshakeComplete(TlsPeer peer, TlsSession session) throws IOException
    {
        if (null == securityParametersHandshake)
        {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        this.session = session;

        securityParametersConnection = securityParametersHandshake;
        securityParametersHandshake = null;

        peer.notifyHandshakeComplete();
    }

    public TlsCrypto getCrypto()
    {
        return crypto;
    }

    public TlsNonceGenerator getNonceGenerator()
    {
        return nonceGenerator;
    }

    public synchronized SecurityParameters getSecurityParameters()
    {
        return null != securityParametersHandshake
            ?   securityParametersHandshake
            :   securityParametersConnection;
    }

    public synchronized SecurityParameters getSecurityParametersConnection()
    {
        return securityParametersConnection;
    }

    public synchronized SecurityParameters getSecurityParametersHandshake()
    {
        return securityParametersHandshake;
    }

    public ProtocolVersion[] getClientSupportedVersions()
    {
        return clientSupportedVersions;
    }

    public void setClientSupportedVersions(ProtocolVersion[] clientSupportedVersions)
    {
        this.clientSupportedVersions = clientSupportedVersions;
    }

    public ProtocolVersion getClientVersion()
    {
        return clientVersion;
    }

    void setClientVersion(ProtocolVersion clientVersion)
    {
        this.clientVersion = clientVersion;
    }

    public ProtocolVersion getServerVersion()
    {
        return getSecurityParameters().getNegotiatedVersion();
    }

    public TlsSession getResumableSession()
    {
        TlsSession session = getSession();
        if (session == null || !session.isResumable())
        {
            return null;
        }
        return session;
    }

    public TlsSession getSession()
    {
        return session;
    }

    public Object getUserObject()
    {
        return userObject;
    }

    public void setUserObject(Object userObject)
    {
        this.userObject = userObject;
    }

    public byte[] exportChannelBinding(int channelBinding)
    {
        SecurityParameters sp = getSecurityParametersConnection();
        if (null == sp)
        {
            throw new IllegalStateException("Export of channel bindings unavailable before handshake completion");
        }

        switch (channelBinding)
        {
        case ChannelBinding.tls_server_end_point:
        {
            byte[] tlsServerEndPoint = sp.getTLSServerEndPoint();

            return tlsServerEndPoint.length < 1 ? null : Arrays.clone(tlsServerEndPoint);
        }

        case ChannelBinding.tls_unique:
        {
            return Arrays.clone(sp.getTLSUnique());
        }

        case ChannelBinding.tls_unique_for_telnet:
        default:
            throw new UnsupportedOperationException();
        }
    }

    public byte[] exportKeyingMaterial(String asciiLabel, byte[] context_value, int length)
    {
        if (context_value != null && !TlsUtils.isValidUint16(context_value.length))
        {
            throw new IllegalArgumentException("'context_value' must have length less than 2^16 (or be null)");
        }

        SecurityParameters sp = getSecurityParametersConnection();
        if (null == sp)
        {
            throw new IllegalStateException("Export of key material unavailable before handshake completion");
        }
        if (!sp.isExtendedMasterSecret())
        {
            /*
             * RFC 7627 5.4. If a client or server chooses to continue with a full handshake without
             * the extended master secret extension, [..] the client or server MUST NOT export any
             * key material based on the new master secret for any subsequent application-level
             * authentication. In particular, it MUST disable [RFC5705] [..].
             */
            throw new IllegalStateException("cannot export keying material without extended_master_secret");
        }

        byte[] cr = sp.getClientRandom(), sr = sp.getServerRandom();

        int seedLength = cr.length + sr.length;
        if (context_value != null)
        {
            seedLength += (2 + context_value.length);
        }

        byte[] seed = new byte[seedLength];
        int seedPos = 0;

        System.arraycopy(cr, 0, seed, seedPos, cr.length);
        seedPos += cr.length;
        System.arraycopy(sr, 0, seed, seedPos, sr.length);
        seedPos += sr.length;
        if (context_value != null)
        {
            TlsUtils.writeUint16(context_value.length, seed, seedPos);
            seedPos += 2;
            System.arraycopy(context_value, 0, seed, seedPos, context_value.length);
            seedPos += context_value.length;
        }

        if (seedPos != seedLength)
        {
            throw new IllegalStateException("error in calculation of seed for export");
        }

        return TlsUtils.PRF(this, sp.getMasterSecret(), asciiLabel, seed, length).extract();
    }
}
