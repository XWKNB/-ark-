@file:OptIn(ExperimentalUnsignedTypes::class)

package com.xwk.glacierark

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater

object ArkEngine {

    private const val DELTA = 0x9E3779B9u
    private const val MASK = 0xFFFFFFFFu
    private val KEY = uintArrayOf(
        0x0132944Fu, 0x00025BA1u, 0x0132944Fu, 0x009988B5u
    )

    private const val ENTRY_SIZE = 168
    private const val NAME_SIZE = 128
    private const val OFF_OFF = 0x80
    private const val ORIG_OFF = 0x84
    private const val COMP_OFF = 0x88
    private const val STORED_OFF = 0x8C
    private const val TAG_OFF = 0x90
    private const val HASH_OFF = 0x94

    // ---------- XXTEA ----------
    private fun mx(s: UInt, y: UInt, z: UInt, p: Int, e: Int, key: UIntArray): UInt {
        val a = ((z shr 5) xor (y shl 2)) and MASK
        val b = ((y shr 3) xor (z shl 4)) and MASK
        return (((a + b) and MASK) xor ((s xor y) + (key[(p and 3) xor e] xor z)))
    }

    fun xxteaDecrypt(data: ByteArray, key: UIntArray = KEY): ByteArray {
        val n = data.size / 4
        if (n < 2) return data
        val v = UIntArray(n)
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) v[i] = bb.int.toUInt()

        val rounds = 6 + 52 / n
        var s = (rounds * DELTA.toInt()).toUInt() and MASK
        var y = v[0]
        repeat(rounds) {
            val e = ((s shr 2) and 3u).toInt()
            for (p in n - 1 downTo 1) {
                val z = v[p - 1]
                v[p] = (v[p] - mx(s, y, z, p, e, key)) and MASK
                y = v[p]
            }
            val z = v[n - 1]
            v[0] = (v[0] - mx(s, y, z, 0, e, key)) and MASK
            y = v[0]
            s = (s - DELTA) and MASK
        }

        val out = ByteArray(n * 4)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) ob.putInt(v[i].toInt())
        return out
    }

    fun xxteaEncrypt(data: ByteArray, key: UIntArray = KEY): ByteArray {
        val pad = (4 - (data.size % 4)) % 4
        val padded = if (pad > 0) data.copyOf(data.size + pad) else data
        val n = padded.size / 4
        if (n < 2) return padded

        val v = UIntArray(n)
        val bb = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) v[i] = bb.int.toUInt()

        val rounds = 6 + 52 / n
        var s = 0u
        var z = v[n - 1]
        repeat(rounds) {
            s = (s + DELTA) and MASK
            val e = ((s shr 2) and 3u).toInt()
            for (p in 0 until n - 1) {
                val y = v[p + 1]
                v[p] = (v[p] + mx(s, y, z, p, e, key)) and MASK
                z = v[p]
            }
            val y = v[0]
            v[n - 1] = (v[n - 1] + mx(s, y, z, n - 1, e, key)) and MASK
            z = v[n - 1]
        }

        val out = ByteArray(n * 4)
        val ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) ob.putInt(v[i].toInt())
        return out
    }

    // ---------- zlib（默认 level=6，速度优先） ----------
    fun unzlib(data: ByteArray): ByteArray {
        try {
            val inf = Inflater()
            inf.setInput(data)
            val out = ByteArray(data.size * 4 + 1024)
            val len = inf.inflate(out)
            inf.end()
            return out.copyOf(len)
        } catch (_: Exception) {
        }
        try {
            val inf = Inflater(true)
            inf.setInput(data)
            val out = ByteArray(data.size * 4 + 1024)
            val len = inf.inflate(out)
            inf.end()
            return out.copyOf(len)
        } catch (_: Exception) {
            return data
        }
    }

    fun zlibCompress(data: ByteArray, level: Int = 6): ByteArray {
        val def = Deflater(level)
        def.setInput(data)
        def.finish()
        val out = ByteArray(data.size + 64)
        val len = def.deflate(out)
        def.end()
        return out.copyOf(len)
    }

    // ---------- 读取 ARK ----------
    fun readArk(blob: ByteArray): Triple<ArkHeader, List<ArkEntry>, ByteArray> {
        val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.getInt(0)
        val dirOff = bb.getInt(4).toLong() and 0xFFFFFFFFL
        val verBytes = blob.copyOfRange(8, 16)
        val version = verBytes.takeWhile { it != 0.toByte() }
            .toByteArray().toString(Charsets.ISO_8859_1)

        val dirPlain = xxteaDecrypt(blob.copyOfRange(dirOff.toInt(), blob.size))

        val entries = mutableListOf<ArkEntry>()
        for (i in 0 until count) {
            val b = i * ENTRY_SIZE
            val name = dirPlain.copyOfRange(b, b + NAME_SIZE)
                .takeWhile { it != 0.toByte() }.toByteArray()
                .toString(Charsets.ISO_8859_1)
            entries.add(
                ArkEntry(
                    name = name,
                    offset = readU32(dirPlain, b + OFF_OFF),
                    orig = readU32(dirPlain, b + ORIG_OFF),
                    comp = readU32(dirPlain, b + COMP_OFF),
                    stored = readU32(dirPlain, b + STORED_OFF),
                    tag = dirPlain.copyOfRange(b + TAG_OFF, b + TAG_OFF + 4)
                        .joinToString("") { "%02x".format(it) },
                    hash = dirPlain.copyOfRange(b + HASH_OFF, b + HASH_OFF + 16)
                        .toString(Charsets.ISO_8859_1)
                )
            )
        }
        return Triple(ArkHeader(count, dirOff, version), entries, blob)
    }

    private fun readU32(buf: ByteArray, off: Int): Long {
        return (buf[off].toLong() and 0xFF) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)
    }

    // ---------- 解包单个条目 ----------
    fun extractEntry(blob: ByteArray, e: ArkEntry): ByteArray {
        val stLen = if (e.stored > 0) e.stored else e.comp
        val raw = blob.copyOfRange(e.offset.toInt(), (e.offset + stLen).toInt())
        return if (e.stored > 0) {
            val dec = xxteaDecrypt(raw)
            unzlib(dec.copyOfRange(0, e.comp.toInt()))
        } else {
            unzlib(raw)
        }
    }

    fun md5Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}