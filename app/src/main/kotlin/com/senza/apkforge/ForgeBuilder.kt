package com.senza.apkforge

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Pipeline build di perangkat:
 * aapt2 compile -> aapt2 link -> ECJ -> D8 -> gabung dex ke APK -> sign (apksig)
 */
class ForgeBuilder(private val ctx: Context, private val log: (String) -> Unit) {

    private val root = File(ctx.filesDir, "forge")
    private val proj = File(root, "hello")
    private val androidJar = File(root, "tools/android.jar")

    val sourceFile = File(proj, "src/com/example/hello/MainActivity.java")
    val signedApk = File(proj, "out/app-signed.apk")

    /** Salin template proyek & android.jar (hanya jika belum ada). */
    fun prepare() {
        if (!proj.exists()) copyAssets("template", proj)
        if (!androidJar.exists()) {
            androidJar.parentFile?.mkdirs()
            ctx.assets.open("android.jar").use { i -> androidJar.outputStream().use { i.copyTo(it) } }
        }
    }

    fun build(): File {
        prepare()
        val aapt2 = File(ctx.applicationInfo.nativeLibraryDir, "libaapt2.so")
        require(aapt2.exists()) {
            "libaapt2.so tidak ditemukan. Taruh binary aapt2 ARM64 di app/src/main/jniLibs/arm64-v8a/libaapt2.so lalu build ulang ApkForge."
        }

        val out = File(proj, "out").apply { deleteRecursively(); mkdirs() }
        val gen = File(out, "gen").apply { mkdirs() }
        val classes = File(out, "classes").apply { mkdirs() }
        val dex = File(out, "dex").apply { mkdirs() }
        val resZip = File(out, "res.zip")
        val base = File(out, "base.apk")
        val unsigned = File(out, "app-unsigned.apk")

        log("[1/6] aapt2 compile")
        exec(listOf(aapt2.path, "compile", "--dir", File(proj, "res").path, "-o", resZip.path))

        log("[2/6] aapt2 link")
        exec(listOf(
            aapt2.path, "link",
            "-o", base.path,
            "-I", androidJar.path,
            "--manifest", File(proj, "AndroidManifest.xml").path,
            "--java", gen.path,
            "--min-sdk-version", "26",
            "--target-sdk-version", "28",   // 28 -> tidak wajib zipalign/arsc-aligned
            "--version-code", "1",
            "--version-name", "1.0",
            resZip.path
        ))

        log("[3/6] ECJ (Java -> class)")
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
        if (sw.toString().isNotBlank()) log(sw.toString())
        check(ok) { "Kompilasi Java gagal (lihat log di atas)." }

        log("[4/6] D8 (class -> dex)")
        val classFiles = classes.walk().filter { it.isFile && it.extension == "class" }.map { it.toPath() }.toList()
        D8.run(
            D8Command.builder()
                .addProgramFiles(classFiles)
                .addLibraryFiles(androidJar.toPath())
                .setOutput(dex.toPath(), OutputMode.DexIndexed)
                .setMinApiLevel(26)
                .build()
        )

        log("[5/6] Gabung dex ke APK")
        mergeDex(base, dex, unsigned)

        log("[6/6] Sign APK")
        sign(unsigned, signedApk)

        log("Selesai: ${signedApk.length() / 1024} KB")
        return signedApk
    }

    // ---------- helper ----------

    private fun exec(cmd: List<String>) {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine { log(it) }
        val code = p.waitFor()
        check(code == 0) { "Perintah gagal (exit $code): ${cmd.first().substringAfterLast('/')} ${cmd.getOrNull(1)}" }
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

    private fun copyAssets(path: String, dest: File) {
        val children = ctx.assets.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> dest.outputStream().use { i.copyTo(it) } }
        } else {
            dest.mkdirs()
            children.forEach { copyAssets("$path/$it", File(dest, it)) }
        }
    }
}
