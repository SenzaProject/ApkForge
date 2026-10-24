package com.senza.apkforge

import android.content.Context
import java.io.File

/** Mengelola banyak proyek di filesDir/forge/projects/<nama>. */
class ProjectStore(private val ctx: Context) {

    val root = File(ctx.filesDir, "forge/projects").apply { mkdirs() }

    fun list(): List<String> =
        root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun dir(name: String) = File(root, name)

    fun create(name: String, pkg: String): File {
        require(Regex("[A-Za-z0-9_-]{1,40}").matches(name)) {
            "Nama proyek: huruf, angka, _ atau - (maks 40 karakter)."
        }
        require(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+").matches(pkg)) {
            "Package tidak valid. Contoh: com.contoh.app"
        }
        require(pkg.split('.').none { it in Highlighter.JAVA_KEYWORDS }) {
            "Package tidak boleh memakai kata kunci Java."
        }
        val d = dir(name)
        require(!d.exists()) { "Proyek '$name' sudah ada." }
        copyTemplate("template", d, "", pkg, name)
        return d
    }

    /** Semua file .java dan .xml di proyek (tanpa folder out). */
    fun files(name: String): List<String> {
        val d = dir(name)
        return d.walkTopDown()
            .onEnter { it == d || it.name != "out" }
            .filter { it.isFile && (it.extension == "java" || it.extension == "xml") }
            .map { it.relativeTo(d).path }
            .sorted()
            .toList()
    }

    fun newFile(project: String, rel: String): File {
        require(rel.isNotBlank() && !rel.contains("..") && !rel.startsWith("/")) { "Path tidak valid." }
        require(rel.endsWith(".java") || rel.endsWith(".xml")) { "Hanya file .java atau .xml." }
        require(!rel.endsWith(".java") || rel.startsWith("src/")) { "File .java harus di dalam src/." }
        val f = File(dir(project), rel)
        require(!f.exists()) { "File sudah ada." }
        f.parentFile?.mkdirs()
        f.writeText(skeleton(rel))
        return f
    }

    private fun skeleton(rel: String): String {
        if (rel.endsWith(".java")) {
            val parts = rel.removePrefix("src/").removeSuffix(".java").split('/')
            val cls = parts.last()
            val pkg = parts.dropLast(1).joinToString(".")
            val header = if (pkg.isEmpty()) "" else "package $pkg;\n\n"
            return "${header}public class $cls {\n\n}\n"
        }
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        return if (rel.startsWith("res/values/")) "$xml<resources>\n\n</resources>\n" else xml
    }

    private fun copyTemplate(assetPath: String, destRoot: File, rel: String, pkg: String, name: String) {
        val children = ctx.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            val outRel = rel.replace("com/example/hello", pkg.replace('.', '/'))
            val text = ctx.assets.open(assetPath).bufferedReader().use { it.readText() }
                .replace("com.example.hello", pkg)
                .replace("Hello Forge", name)
            File(destRoot, outRel).apply { parentFile?.mkdirs() }.writeText(text)
        } else {
            children.forEach {
                copyTemplate("$assetPath/$it", destRoot, if (rel.isEmpty()) it else "$rel/$it", pkg, name)
            }
        }
    }
}
