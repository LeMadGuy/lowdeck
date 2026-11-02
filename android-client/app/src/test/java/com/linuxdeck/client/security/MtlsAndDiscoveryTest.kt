package com.linuxdeck.client.security

import com.linuxdeck.client.network.DiscoveredHost
import com.linuxdeck.client.network.ExponentialBackoffPolicy
import com.linuxdeck.client.protocol.v1.Envelope
import com.linuxdeck.client.protocol.v1.HandshakeRequest
import com.linuxdeck.client.protocol.v1.HandshakeResponse
import com.linuxdeck.client.protocol.v1.SessionStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MtlsAndDiscoveryTest {

    @Test
    fun `parse QR JSON payload extracts all daemon pairing fields`() {
        val rawJson = """
            {
              "host": "192.168.15.42",
              "port": 8443,
              "pairing_port": 8444,
              "server_fp": "SHA256:AA:BB:ef0123456789abcdef0123456789abcdef0123456789abcdef0123456789ab",
              "token": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "expires_at": 1893456000
            }
        """.trimIndent()

        val parsed = PairingQrPayload.parse(rawJson)
        assertEquals("192.168.15.42", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals(8444, parsed.pairingPort)
        assertEquals(64, parsed.serverFp.length)
        assertTrue(parsed.serverFp.startsWith("aabbef01"))
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", parsed.token)
        assertEquals(1893456000L, parsed.expiresAt)
    }

    @Test
    fun `MtlsKeyManager generates persistent ECDSA P-256 X509 client identity`() = runTest {
        val tempDir = Files.createTempDirectory("mtls-test").toFile()
        try {
            val mgr1 = MtlsKeyManager(storageDir = tempDir, preferAndroidKeyStore = false)
            val id1 = mgr1.ensureClientIdentity("Pixel Test")
            assertEquals(64, id1.fingerprintSha256.length)
            assertTrue(id1.certificatePem.startsWith("-----BEGIN CERTIFICATE-----"))
            assertEquals("EC", id1.certificate.publicKey.algorithm)

            // Reload from disk and verify identity persistence.
            val mgr2 = MtlsKeyManager(storageDir = tempDir, preferAndroidKeyStore = false)
            val id2 = mgr2.ensureClientIdentity("Pixel Test")
            assertEquals(id1.fingerprintSha256, id2.fingerprintSha256)

            val hostCfg = PairedHostConfig(
                host = "100.64.0.10",
                port = 8443,
                pairingPort = 8444,
                serverFingerprintSha256 = "a".repeat(64),
                deviceId = "pixel-01",
                deviceName = "Pixel Test",
                pairedAtEpochSec = 1700000000L,
            )
            mgr2.savePairedHost(hostCfg)
            assertEquals("100.64.0.10", mgr2.pairedHost.value?.host)

            mgr2.updateEndpointAddress("127.0.0.1", 8443)
            assertEquals("127.0.0.1", mgr2.pairedHost.value?.host)
            assertEquals("a".repeat(64), mgr2.pairedHost.value?.serverFingerprintSha256)

            mgr2.clearPairedHost()
            assertNull(mgr2.pairedHost.value)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `mDNS TXT attribute parser and exponential backoff match daemon contract`() {
        val txt = DiscoveredHost.parseTxtAttributes(
            mapOf(
                "version" to "1".toByteArray(),
                "hostname" to "arch-workstation".toByteArray(),
                "fp" to "67C11A9459492A37".toByteArray(),
            ),
        )
        assertEquals("1", txt.version)
        assertEquals("arch-workstation", txt.hostname)
        assertEquals("67c11a9459492a37", txt.shortFingerprint)

        val usb = DiscoveredHost.usbAdbReverse()
        assertEquals("127.0.0.1", usb.hostAddress)
        assertEquals(8443, usb.port)
        assertTrue(usb.isUsbLoopback)

        assertEquals(1_000L, ExponentialBackoffPolicy.computeDelayMs(1, jitterMs = 0L))
        assertEquals(2_000L, ExponentialBackoffPolicy.computeDelayMs(2, jitterMs = 0L))
        assertEquals(4_000L, ExponentialBackoffPolicy.computeDelayMs(3, jitterMs = 0L))
        assertEquals(30_000L, ExponentialBackoffPolicy.computeDelayMs(10, jitterMs = 200L))
    }

    @Test
    fun `Protobuf Envelope HandshakeRequest and HandshakeResponse round-trip`() {
        val reqEnv = Envelope.newBuilder()
            .setRequestId("hs-test-1")
            .setTimestampMs(1700000001L)
            .setHandshakeRequest(
                HandshakeRequest.newBuilder()
                    .setProtocolVersion(1)
                    .setDeviceId("android-dev-01")
                    .setDeviceName("Pixel 9")
                    .setClientVersion("1.0.0")
                    .build(),
            )
            .build()

        val parsedReq = Envelope.parseFrom(reqEnv.toByteArray())
        assertEquals(Envelope.PayloadCase.HANDSHAKE_REQUEST, parsedReq.payloadCase)
        assertEquals("android-dev-01", parsedReq.handshakeRequest.deviceId)

        val respEnv = Envelope.newBuilder()
            .setRequestId("hs-test-1")
            .setTimestampMs(1700000002L)
            .setHandshakeResponse(
                HandshakeResponse.newBuilder()
                    .setProtocolVersion(1)
                    .setDeviceName("linux-host")
                    .setSessionId("sess-xyz")
                    .setStatus(SessionStatus.SESSION_ACTIVE)
                    .setAccepted(true)
                    .setMessage("session established")
                    .build(),
            )
            .build()

        val parsedResp = Envelope.parseFrom(respEnv.toByteArray())
        assertNotNull(parsedResp.handshakeResponse)
        assertTrue(parsedResp.handshakeResponse.accepted)
        assertEquals(SessionStatus.SESSION_ACTIVE, parsedResp.handshakeResponse.status)
    }
}

