package com.senza.apkforge

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Error build dengan jenis, supaya UI bisa menampilkan pesan & tombol yang pas. */
class BuildError(
    val kind: Kind,
    message: String,
    val file: String? = null,
    val line: Int? = null
) : Exception(message) {
    enum class Kind { TOOLS, CODE, RESOURCE, OTHER }
}

/**
 * Pipeline build di perangkat:
 * aapt2 compile -> aapt2 link -> ECJ -> D8 -> gabung dex ke APK -> sign (apksig)
 */
class ForgeBuilder(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onStep: (Int, String) -> Unit
) {
    companion object { const val TOTAL_STEPS = 6 }

    private val androidJar = File(File(ctx.filesDir, "forge"), "tools/android.jar")

    fun apkOf(proj: File) = File(proj, "out/app-signed.apk")
    fun aapt2File() = File(ctx.applicationInfo.nativeLibraryDir, "libaapt2.so")
    fun hasAapt2() = aapt2File().exists()

    fun hasAndroidJar(): Boolean {
        if (androidJar.exists()) return true
        return try { ctx.assets.open("android.jar").close(); true } catch (e: IOException) { false }
    }

    /** Salin android.jar dari assets (hanya jika belum ada). */
    fun prepareTools() {
        if (!androidJar.exists()) {
            androidJar.parentFile?.mkdirs()
            ctx.assets.open("android.jar").use { i -> androidJar.outputStream().use { i.copyTo(it) } }
        }
    }

    fun build(proj: File): File {
        if (!hasAndroidJar()) {
            throw BuildError(BuildError.Kind.TOOLS, "Pustaka Android (android.jar) tidak ada di dalam aplikasi ini.")
        }
        prepareTools()
        val aapt2 = aapt2File()
        if (!aapt2.exists()) {
            throw BuildError(BuildError.Kind.TOOLS, "Alat aapt2 belum terpasang di ApkForge, jadi resource belum bisa diproses.")
        }

        val signedApk = apkOf(proj)
        val out = File(proj, "out").apply { deleteRecursively(); mkdirs() }
        val gen = File(out, "gen").apply { mkdirs() }
        val classes = File(out, "classes").apply { mkdirs() }
        val dex = File(out, "dex").apply { mkdirs() }
        val resZip = File(out, "res.zip")
        val base = File(out, "base.apk")
        val unsigned = File(out, "app-unsigned.apk")

        onStep(1, "Membaca resource (teks, layout, gambar)")
        execAapt(listOf(aapt2.path, "compile", "--dir", File(proj, "res").path, "-o", resZip.path), "membaca resource")

        onStep(2, "Menyusun resource dan manifest")
        execAapt(listOf(
            aapt2.path, "link",
            "-o", base.path,
            "-I", androidJar.path,
            "--manifest", File(proj, "AndroidManifest.xml").path,
            "--java", gen.path,
            "--min-sdk-version", "26",
            "--target-sdk-version", "28",
            "--version-code", "1",
            "--version-name", "1.0",
            resZip.path
        ), "menyusun resource")

        onStep(3, "Memeriksa dan mengompilasi kode Java")
        val javaFiles = (File(proj, "src").walk() + gen.walk())
            .filter { it.isFile && it.extension == "java" }.map { it.path }.toList()
        val args = arrayOf(
            "-1.8", "-proc:none", "-nowarn",
            "-bootclasspath", androidJar.path,
            "-d", classes.path
        ) + javaFiles
        val sw = StringWriter()
        val pw = PrintWriter(sw, true)
        val ok = org.eclipse.jdt.internal.compiler.batch.Main(pw, pw, false, null, null).compile(args)
        pw.flush()
        val text = sw.toString()
        if (text.isNotBlank()) log(text)
        if (!ok) {
            val m = Regex("ERROR in (.+?) \\(at line (\\d+)\\)\\R(?:.*\\R){2}(.*)").find(text)
            if (m != null) {
                val path = m.groupValues[1]
                val line = m.groupValues[2].toInt()
                throw BuildError(
                    BuildError.Kind.CODE,
                    "${File(path).name}, baris $line:\n${friendlyJava(m.groupValues[3].trim())}",
                    path, line
                )
            }
            throw BuildError(BuildError.Kind.CODE, "Kode Java tidak bisa dikompilasi. Ketuk 'Lihat detail'.")
        }

        onStep(4, "Mengubah kode ke format Android (dex)")
        try {
            val classFiles = classes.walk().filter { it.isFile && it.extension == "class" }.map { it.toPath() }.toList()
            D8.run(
                D8Command.builder()
                    .addProgramFiles(classFiles)
                    .addLibraryFiles(androidJar.toPath())
                    .setOutput(dex.toPath(), OutputMode.DexIndexed)
                    .setMinApiLevel(26)
                    .build()
            )
        } catch (e: Exception) {
            throw BuildError(BuildError.Kind.OTHER, "Gagal mengubah kode ke format Android: ${e.message}")
        }

        onStep(5, "Merakit APK")
        mergeDex(base, dex, unsigned)

        onStep(6, "Menandatangani APK")
        try {
            sign(unsigned, signedApk)
        } catch (e: Exception) {
            throw BuildError(BuildError.Kind.OTHER, "Gagal menandatangani APK: ${e.message}")
        }

        log("Selesai: ${signedApk.length() / 1024} KB")
        return signedApk
    }

    // ---------- helper ----------

    private fun friendlyJava(msg: String): String = when {
        msg.contains("Syntax error") -> "$msg\n(Cek tanda ; ) } yang kurang atau berlebih.)"
        msg.contains("cannot be resolved to a type") -> "$msg\n(Mungkin kurang 'import' atau salah ketik nama kelas.)"
        msg.contains("cannot be resolved") -> "$msg\n(Nama ini belum dibuat atau salah ketik.)"
        msg.contains("is undefined") -> "$msg\n(Fungsi ini tidak ada. Cek ejaan dan huruf besar/kecil.)"
        else -> msg
    }

    private fun execAapt(cmd: List<String>, what: String) {
        val p = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: IOException) {
            throw BuildError(
                BuildError.Kind.TOOLS,
                "aapt2 tidak bisa dijalankan di perangkat ini. Pastikan itu versi Android ARM64 yang berdiri sendiri.\n${e.message}"
            )
        }
        val outText = StringBuilder()
        p.inputStream.bufferedReader().forEachLine { outText.appendLine(it); log(it) }
        val code = p.waitFor()
        if (code == 0) return
        if (outText.contains("CANNOT LINK EXECUTABLE") || code == 126 || code == 127) {
            throw BuildError(
                BuildError.Kind.TOOLS,
                "File aapt2 tidak cocok dengan perangkat ini (butuh versi Android ARM64 yang berdiri sendiri)."
            )
        }
        val m = Regex("(\\S+?):(\\d+): error: (.+)").find(outText)
        if (m != null) {
            throw BuildError(
                BuildError.Kind.RESOURCE,
                "${File(m.groupValues[1]).name}, baris ${m.groupValues[2]}:\n${m.groupValues[3]}",
                m.groupValues[1], m.groupValues[2].toInt()
            )
        }
        throw BuildError(BuildError.Kind.RESOURCE, "Gagal $what (kode $code). Ketuk 'Lihat detail'.")
    }

    private fun mergeDex(base: File, dexDir: File, dest: File) {
        ZipFile(base).use { zf ->
            ZipOutputStream(dest.outputStream().buffered()).use { zo ->
                for (e in zf.entries()) {
                    val bytes = zf.getInputStream(e).use { it.readBytes() }
                    putEntry(zo, e.name, bytes, e.method == ZipEntry.STORED)
                }
                dexDir.listFiles { f -> f.extension == "dex" }?.sortedBy { it.name }?.forEach {
                    putEntry(zo, it.name, it.readBytes(), false)
                }
            }
        }
    }

    private fun putEntry(zo: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean) {
        val ze = ZipEntry(name)
        if (stored) {
            ze.method = ZipEntry.STORED
            ze.size = bytes.size.toLong()
            ze.compressedSize = bytes.size.toLong()
            ze.crc = CRC32().also { it.update(bytes) }.value
        }
        zo.putNextEntry(ze)
        zo.write(bytes)
        zo.closeEntry()
    }

    private fun sign(input: File, output: File) {
        val pass = "android".toCharArray()
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open("debug.p12").use { ks.load(it, pass) }
        val alias = ks.aliases().nextElement()
        val key = ks.getKey(alias, pass) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        val cfg = ApkSigner.SignerConfig.Builder("forge", key, listOf(cert)).build()
        ApkSigner.Builder(listOf(cfg))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(26)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
            .sign()
    }
}
