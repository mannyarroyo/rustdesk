package com.carriez.flutter_hbb

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * Android 5-6 accessibility services cannot inject taps or keys, and TV
 * launchers often expose tiles that accessibility cannot click. On those
 * boxes, run `input` commands through the device's own network ADB
 * (127.0.0.1:5555), which has shell privileges. The first connection shows
 * the system "Allow USB debugging?" prompt for this app's key.
 */
object LocalAdb {
    private const val TAG = "local adb"
    private const val A_CNXN = 0x4e584e43
    private const val A_AUTH = 0x48545541
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257
    private const val VERSION = 0x01000000
    private const val MAX_DATA = 4096
    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSAPUBLICKEY = 3
    private const val STALE_MS = 3000L

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private val queue = LinkedBlockingQueue<Pair<String, Long>>()
    private var worker: Thread? = null
    private var keyDir: File? = null
    private var nextLocalId = 1

    @Volatile
    var ready = false
        private set

    @Synchronized
    fun start(context: Context) {
        keyDir = context.filesDir
        if (worker?.isAlive == true) return
        worker = Thread({ loop() }, "local-adb").apply {
            isDaemon = true
            start()
        }
    }

    /** Queues a shell command. Returns false when no ADB session is up. */
    fun run(command: String): Boolean {
        if (!ready) return false
        queue.offer(command to SystemClock.uptimeMillis())
        return true
    }

    private fun loop() {
        while (true) {
            try {
                session()
            } catch (e: Exception) {
                Log.d(TAG, "session ended: $e")
            }
            ready = false
            SystemClock.sleep(10000)
        }
    }

    private fun session() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", 5555), 3000)
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val keys = loadKeys()
            write(out, A_CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
            var sentSignature = false
            // No timeout while the user may be looking at the approval prompt.
            socket.soTimeout = 0
            while (true) {
                val m = read(input)
                if (m.cmd == A_CNXN) break
                if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) continue
                if (!sentSignature) {
                    sentSignature = true
                    write(out, A_AUTH, AUTH_SIGNATURE, 0, sign(keys, m.data))
                } else {
                    Log.d(TAG, "key not yet trusted, asking the user")
                    write(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, adbPublicKey(keys.public as RSAPublicKey))
                }
            }
            Log.d(TAG, "connected")
            queue.clear()
            ready = true
            socket.soTimeout = 15000
            while (true) {
                val (command, queuedAt) = queue.poll(60, TimeUnit.SECONDS) ?: continue
                if (SystemClock.uptimeMillis() - queuedAt > STALE_MS) continue
                exec(input, out, command)
            }
        }
    }

    private fun exec(input: DataInputStream, out: OutputStream, command: String) {
        val localId = nextLocalId++
        write(out, A_OPEN, localId, 0, "shell:$command\u0000".toByteArray())
        while (true) {
            val m = read(input)
            if (m.arg1 != localId) continue
            when (m.cmd) {
                A_WRTE -> write(out, A_OKAY, localId, m.arg0, null)
                A_CLSE -> {
                    write(out, A_CLSE, localId, m.arg0, null)
                    return
                }
            }
        }
    }

    private fun write(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray?) {
        val len = data?.size ?: 0
        var sum = 0
        data?.forEach { sum += it.toInt() and 0xff }
        val buf = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd.inv())
        if (data != null) buf.put(data)
        out.write(buf.array())
        out.flush()
    }

    private fun read(input: DataInputStream): Msg {
        val header = ByteArray(24)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val arg0 = b.int
        val arg1 = b.int
        val len = b.int
        if (len < 0 || len > 1024 * 1024) throw IllegalStateException("bad length $len")
        val data = ByteArray(len)
        input.readFully(data)
        return Msg(cmd, arg0, arg1, data)
    }

    private fun loadKeys(): KeyPair {
        val dir = keyDir ?: throw IllegalStateException("not started")
        val privFile = File(dir, "local_adb_key")
        val pubFile = File(dir, "local_adb_key.pub")
        val factory = KeyFactory.getInstance("RSA")
        if (privFile.exists() && pubFile.exists()) {
            try {
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(pubFile.readBytes())),
                    factory.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                )
            } catch (e: Exception) {
                Log.d(TAG, "regenerating key: $e")
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        privFile.writeBytes(pair.private.encoded)
        pubFile.writeBytes(pair.public.encoded)
        return pair
    }

    // adbd verifies with RSA_verify(NID_sha1, token), so the token is signed
    // as if it were a SHA-1 digest: PKCS#1 v1.5 type 1 padding + DigestInfo.
    private fun sign(keys: KeyPair, token: ByteArray): ByteArray {
        val digestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val padded = ByteBuffer.allocate(256)
        padded.put(0.toByte()).put(1.toByte())
        repeat(256 - 3 - digestInfo.size - token.size) { padded.put(0xff.toByte()) }
        padded.put(0.toByte()).put(digestInfo).put(token)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keys.private)
        return cipher.doFinal(padded.array())
    }

    private fun adbPublicKey(key: RSAPublicKey): ByteArray {
        val r32 = BigInteger.ZERO.setBit(32)
        var n = key.modulus
        val n0inv = n.remainder(r32).modInverse(r32).negate()
        var rr = BigInteger.ZERO.setBit(2048).modPow(BigInteger.valueOf(2), n)
        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv.toInt())
        repeat(64) {
            val qr = n.divideAndRemainder(r32)
            n = qr[0]
            buf.putInt(qr[1].toInt())
        }
        repeat(64) {
            val qr = rr.divideAndRemainder(r32)
            rr = qr[0]
            buf.putInt(qr[1].toInt())
        }
        buf.putInt(key.publicExponent.toInt())
        val encoded = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$encoded RustDesk@box\u0000".toByteArray()
    }
}
