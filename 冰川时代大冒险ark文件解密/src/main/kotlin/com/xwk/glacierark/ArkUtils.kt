@file:OptIn(ExperimentalUnsignedTypes::class)

package com.xwk.glacierark

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ArkUtils {

    // ============================================================
    // 解包（流式）
    // ============================================================
    fun writeUnpackedFromFile(
        arkFile: File,
        outDir: File,
        header: ArkHeader,
        entries: List<ArkEntry>,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): File {
        outDir.mkdirs()
        val manifestEntries = mutableListOf<String>()
        val total = entries.size

        RandomAccessFile(arkFile, "r").use { raf ->
            entries.forEachIndexed { index, e ->
                val stLen = if (e.stored > 0) e.stored else e.comp
                raf.seek(e.offset)
                val raw = ByteArray(stLen.toInt())
                raf.readFully(raw)

                val plain: ByteArray = if (e.stored > 0) {
                    val dec = ArkEngine.xxteaDecrypt(raw)
                    ArkEngine.unzlib(dec.copyOfRange(0, e.comp.toInt()))
                } else {
                    ArkEngine.unzlib(raw)
                }

                val f = File(outDir, e.name)
                f.parentFile?.mkdirs()
                BufferedOutputStream(f.outputStream()).use { it.write(plain) }

                val md5 = ArkEngine.md5Hex(plain)
                manifestEntries.add(
                    """{"name":"${e.name}","offset":${e.offset},"orig":${e.orig},""" +
                    """"comp":${e.comp},"stored":${e.stored},""" +
                    """"encrypted":${e.stored > 0},"tag":"${e.tag}","hash":"${e.hash}",""" +
                    """"md5_plain":"$md5"}"""
                )

                onProgress?.invoke(index + 1, total, e.name)
            }
        }

        val manifest = """
            {
              "ark_version":"${header.version}",
              "count":${header.count},
              "source_ark":"${outDir.name}",
              "entries":[${manifestEntries.joinToString(",")}]
            }
        """.trimIndent()

        val mf = File(outDir, "_ark_manifest.json")
        mf.writeText(manifest)
        return mf
    }

    // ============================================================
    // 打包（流式 + 速度优化）
    // ============================================================
    fun packArk(
        srcDir: File,
        outFile: File,
        originalArk: File,
        version: String = "1.6",
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Int {
        val mf = File(srcDir, "_ark_manifest.json")
        if (!mf.exists()) throw IllegalStateException("找不到 _ark_manifest.json")

        val entries = parseManifest(mf.readText())
        val total = entries.size

        if (!originalArk.exists() || !originalArk.isFile) {
            throw IllegalStateException("原始 ARK 不存在: ${originalArk.absolutePath}")
        }

        val srcRaf = RandomAccessFile(originalArk, "r")
        try {
            data class EntryPlan(
                val e: ManifestEntry,
                val storedData: ByteArray?,
                val orig: Int,
                val comp: Int,
                val stored: Int,
                val hashAscii: String,
                val offset: Long
            )

            val plans = ArrayList<EntryPlan>(total)
            var curOffset = 16L
            var count = 0
            var changed = 0
            var unchanged = 0
            var processed = 0

            // ---------- 第一遍 ----------
            entries.forEach { e ->
                processed++
                val f = File(srcDir, e.name)
                val plain: ByteArray = if (f.exists()) f.readBytes() else ByteArray(0)

                val curMd5 = if (plain.isNotEmpty()) ArkEngine.md5Hex(plain) else ""
                val isUnchanged = (curMd5 == e.md5Plain)

                val storedData: ByteArray?
                val orig: Int
                val comp: Int
                val stored: Int
                val hashAscii: String

                if (isUnchanged) {
                    storedData = null
                    orig = e.orig.toInt()
                    comp = e.comp.toInt()
                    stored = e.stored.toInt()
                    hashAscii = e.hash
                    unchanged++
                } else {
                    changed++
                    if (e.encrypted) {
                        val r = compressAndEncrypt(plain)
                        storedData = r.first
                        orig = plain.size
                        comp = r.second
                        stored = r.first.size
                    } else {
                        if (e.comp != e.orig) {
                            val cs = compressOnly(plain)
                            storedData = cs
                            orig = plain.size
                            comp = cs.size
                            stored = 0
                        } else {
                            storedData = plain
                            orig = plain.size
                            comp = plain.size
                            stored = 0
                        }
                    }
                    hashAscii = curMd5.take(16)
                }

                val thisStoredLen = if (stored > 0) stored else comp
                plans.add(
                    EntryPlan(
                        e = e,
                        storedData = storedData,
                        orig = orig,
                        comp = comp,
                        stored = stored,
                        hashAscii = hashAscii,
                        offset = curOffset
                    )
                )
                curOffset += thisStoredLen
                count++

                onProgress?.invoke(processed, total, e.name)
            }

            // ---------- 第二遍 ----------
            val dirOffset = curOffset
            val dirBuf = ByteArrayOutputStream()

            outFile.parentFile?.mkdirs()

            BufferedOutputStream(outFile.outputStream(), 1024 * 1024).use { bos ->
                bos.write(intToBytes(count))
                bos.write(intToBytes(dirOffset.toInt()))
                val verBytes = ByteArray(8)
                val vb = version.toByteArray(Charsets.ISO_8859_1)
                System.arraycopy(vb, 0, verBytes, 0, minOf(vb.size, 8))
                bos.write(verBytes)

                plans.forEach { plan ->
                    if (plan.storedData != null) {
                        bos.write(plan.storedData)
                    } else {
                        val stLen = if (plan.stored > 0) plan.stored else plan.comp
                        srcRaf.seek(plan.e.offset)
                        val buf = ByteArray(64 * 1024)
                        var remain = stLen.toLong()
                        while (remain > 0) {
                            val toRead = minOf(buf.size.toLong(), remain).toInt()
                            val n = srcRaf.read(buf, 0, toRead)
                            if (n <= 0) break
                            bos.write(buf, 0, n)
                            remain -= n
                        }
                    }

                    val nameBytes = ByteArray(128)
                    val nb = plan.e.name.toByteArray(Charsets.ISO_8859_1)
                    System.arraycopy(nb, 0, nameBytes, 0, minOf(nb.size, 127))
                    dirBuf.write(nameBytes)

                    dirBuf.write(intToBytes(plan.offset.toInt()))
                    dirBuf.write(intToBytes(plan.orig))
                    dirBuf.write(intToBytes(plan.comp))
                    dirBuf.write(intToBytes(plan.stored))

                    val tagBytes = ByteArray(4)
                    val tagHex = plan.e.tag.padEnd(8, '0').take(8)
                    for (i in 0 until 4) {
                        tagBytes[i] = tagHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }
                    dirBuf.write(tagBytes)

                    val hashBytes = ByteArray(16)
                    val hb = plan.hashAscii.toByteArray(Charsets.ISO_8859_1)
                    System.arraycopy(hb, 0, hashBytes, 0, minOf(hb.size, 16))
                    dirBuf.write(hashBytes)

                    dirBuf.write(ByteArray(4))
                }

                val dirPlain = dirBuf.toByteArray()
                val dirEnc = ArkEngine.xxteaEncrypt(dirPlain)
                bos.write(dirEnc)
                bos.flush()
            }

            println("打包完成: 总数=$count, 改动=$changed, 无损复制=$unchanged")
            return count
        } finally {
            srcRaf.close()
        }
    }

    // ============================================================
    private fun compressAndEncrypt(plain: ByteArray): Triple<ByteArray, Int, Int> {
        val cs = ArkEngine.zlibCompress(plain, 6)
        val enc = ArkEngine.xxteaEncrypt(cs)
        return Triple(enc, cs.size, plain.size)
    }

    private fun compressOnly(plain: ByteArray): ByteArray {
        return ArkEngine.zlibCompress(plain, 6)
    }

    private fun intToBytes(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun parseManifest(json: String): List<ManifestEntry> {
        val list = mutableListOf<ManifestEntry>()
        val body = json.substringAfter("\"entries\":[", "")
            .substringBeforeLast("]")
            .trim()
        if (body.isEmpty()) return list

        val re = Regex(
            """"name"\s*:\s*"([^"]*)".*?"offset"\s*:\s*(\d+).*?"orig"\s*:\s*(\d+).*?""" +
            """"comp"\s*:\s*(\d+).*?"stored"\s*:\s*(\d+).*?"encrypted"\s*:\s*(true|false).*?""" +
            """"tag"\s*:\s*"([^"]*)".*?"hash"\s*:\s*"([^"]*)".*?"md5_plain"\s*:\s*"([^"]*)""""
        )

        var depth = 0
        var start = 0
        val chunks = mutableListOf<String>()
        body.forEachIndexed { i, c ->
            when (c) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0) chunks.add(body.substring(start, i + 1))
                }
            }
        }

        chunks.forEach { chunk ->
            val m = re.find(chunk) ?: return@forEach
            list.add(
                ManifestEntry(
                    name = m.groupValues[1],
                    offset = m.groupValues[2].toLong(),
                    orig = m.groupValues[3].toLong(),
                    comp = m.groupValues[4].toLong(),
                    stored = m.groupValues[5].toLong(),
                    encrypted = m.groupValues[6] == "true",
                    tag = m.groupValues[7],
                    hash = m.groupValues[8],
                    md5Plain = m.groupValues[9]
                )
            )
        }
        return list
    }

    data class ManifestEntry(
        val name: String,
        val offset: Long,
        val orig: Long,
        val comp: Long,
        val stored: Long,
        val encrypted: Boolean,
        val tag: String,
        val hash: String,
        val md5Plain: String
    )

    fun zipDir(srcDir: File, zipFile: File) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            srcDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    val rel = f.relativeTo(srcDir).path.replace('\\', '/')
                    zos.putNextEntry(ZipEntry(rel))
                    zos.write(f.readBytes())
                    zos.closeEntry()
                }
            }
        }
    }
}