package storage.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.agreement.jpake.*;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import storage.transport.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** J-PAKE (BC NIST 3072-bit group), explicit key confirmation, then TLS 1.2 PSK AES-128-GCM.
 * Passwords never cross the carrier. TLS supplies record authentication, nonces and replay protection.
 * Each connection uses fresh PAKE ephemerals and a new TLS session; no PSK/password verifier is advertised.
 */
public final class MeshSecurity {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte[] IDENTITY = "Relay-v2".getBytes(StandardCharsets.US_ASCII);
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r ->
            Thread.ofPlatform().daemon().name("authentication-deadlines").unstarted(r));
    private final String meshId;
    private final char[] password;

    public MeshSecurity(String meshId, String password) {
        if (meshId == null || !meshId.matches("[A-Za-z0-9_.-]{1,80}"))
            throw new IllegalArgumentException("Invalid mesh ID");
        if (password == null || password.length() < 16 || password.length() > 256)
            throw new IllegalArgumentException("Set a mesh password of 16–256 characters; unauthenticated peers must update their setup.");
        this.meshId = meshId;
        this.password = password.toCharArray();
    }
    public String meshId() { return meshId; }
    @Override public String toString() { return "MeshSecurity[credentials redacted]"; }

    // Explicit bounded JSON DTOs: never deserialize Java objects from the network.
    public record Round(String protocol, String id, BigInteger gx1, BigInteger gx2,
                        BigInteger[] proof1, BigInteger[] proof2, BigInteger a, BigInteger mac) { }
    private void send(Link link, Round round) throws IOException { link.send(JSON.writeValueAsBytes(round)); }
    private Round receive(Link link) throws IOException {
        byte[] bytes = link.receive();
        if (bytes == null || bytes.length > 16_384) throw new IOException("Authentication interrupted");
        try {
            Round r = JSON.readValue(bytes, Round.class);
            if (!"Relay-v2".equals(r.protocol()) || r.id() == null || r.id().length() > 150
                    || !r.id().startsWith(meshId + "/")) throw new IOException("Wrong mesh or old unauthenticated peer; update Relay on both devices.");
            for (BigInteger value : new BigInteger[]{r.gx1(), r.gx2(), r.a(), r.mac()}) checkInteger(value);
            for (BigInteger[] proof : new BigInteger[][]{r.proof1(), r.proof2()}) {
                if (proof != null) {
                    if (proof.length != 2) throw new IOException("Invalid authentication proof");
                    for (BigInteger value : proof) { if (value == null) throw new IOException("Missing proof"); checkInteger(value); }
                }
            }
            return r;
        } catch (RuntimeException e) { throw new IOException("Invalid authentication message; update Relay on both devices.", e); }
    }
    private static void checkInteger(BigInteger value) throws IOException {
        if (value != null && value.bitLength() > 3072) throw new IOException("Authentication value exceeds group size");
    }

    /** Returns an encrypted frame link. The caller owns the raw carrier and must close it on failure. */
    public Link authenticate(Link raw, boolean client, String purpose) throws IOException {
        ScheduledFuture<?> deadline = DEADLINES.schedule(raw::close, 15, TimeUnit.SECONDS);
        byte[] key = null;
        try {
            String id = meshId + "/" + UUID.randomUUID();
            char[] copy = password.clone();
            JPAKEParticipant participant;
            try { participant = new JPAKEParticipant(id, copy); }
            finally { Arrays.fill(copy, '\0'); }
            JPAKERound1Payload one = participant.createRound1PayloadToSend();
            send(raw, new Round("Relay-v2", id, one.getGx1(), one.getGx2(),
                    one.getKnowledgeProofForX1(), one.getKnowledgeProofForX2(), null, null));
            Round remote = receive(raw);
            String remoteId = remote.id();
            participant.validateRound1PayloadReceived(new JPAKERound1Payload(remoteId, remote.gx1(), remote.gx2(), remote.proof1(), remote.proof2()));
            JPAKERound2Payload two = participant.createRound2PayloadToSend();
            send(raw, new Round("Relay-v2", id, null, null, two.getKnowledgeProofForX2s(), null, two.getA(), null));
            remote = receive(raw);
            if (!remoteId.equals(remote.id())) throw new IOException("Authentication identity changed");
            participant.validateRound2PayloadReceived(new JPAKERound2Payload(remote.id(), remote.a(), remote.proof1()));
            BigInteger material = participant.calculateKeyingMaterial();
            JPAKERound3Payload three = participant.createRound3PayloadToSend(material);
            send(raw, new Round("Relay-v2", id, null, null, null, null, null, three.getMacTag()));
            remote = receive(raw);
            if (!remoteId.equals(remote.id())) throw new IOException("Authentication identity changed");
            participant.validateRound3PayloadReceived(new JPAKERound3Payload(remote.id(), remote.mac()), material);
            byte[] materialBytes = material.toByteArray();
            HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(new HKDFParameters(materialBytes, null,
                    ("Relay-v2/" + meshId + "/" + purpose).getBytes(StandardCharsets.UTF_8)));
            key = new byte[32]; hkdf.generateBytes(key, 0, key.length);
            Arrays.fill(materialBytes, (byte)0);
            BcTlsCrypto crypto = new BcTlsCrypto(RANDOM);
            InputStream input = LinkStreams.input(raw);
            OutputStream output = LinkStreams.output(raw);
            TlsProtocol tls;
            if (client) {
                TlsClientProtocol protocol = new TlsClientProtocol(input, output);
                protocol.connect(new PSKTlsClient(crypto, new BasicTlsPSKIdentity(IDENTITY, key)) {
                    @Override protected ProtocolVersion[] getSupportedVersions() { return new ProtocolVersion[]{ProtocolVersion.TLSv12}; }
                    @Override protected int[] getSupportedCipherSuites() { return new int[]{CipherSuite.TLS_DHE_PSK_WITH_AES_128_GCM_SHA256}; }
                });
                tls = protocol;
            } else {
                byte[] sessionKey = key;
                TlsServerProtocol protocol = new TlsServerProtocol(input, output);
                protocol.accept(new PSKTlsServer(crypto, new TlsPSKIdentityManager() {
                    public byte[] getHint() { return IDENTITY.clone(); }
                    public byte[] getPSK(byte[] identity) { return Arrays.equals(identity, IDENTITY) ? sessionKey.clone() : null; }
                }) {
                    @Override protected ProtocolVersion[] getSupportedVersions() { return new ProtocolVersion[]{ProtocolVersion.TLSv12}; }
                    @Override protected int[] getSupportedCipherSuites() { return new int[]{CipherSuite.TLS_DHE_PSK_WITH_AES_128_GCM_SHA256}; }
                });
                tls = protocol;
            }
            return new FramedStreamLink(tls.getInputStream(), tls.getOutputStream(), raw::close, raw.remoteAddress());
        } catch (Exception e) {
            raw.close();
            throw new IOException("Mesh authentication failed: wrong password, wrong mesh, interrupted connection, or old peer. Update Relay on both devices.", e);
        } finally {
            deadline.cancel(false);
            if (key != null) Arrays.fill(key, (byte)0);
        }
    }
}
