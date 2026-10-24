package com.senza.apkforge

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    private lateinit var store: ProjectStore
    private lateinit var builder: ForgeBuilder
    private lateinit var editor: EditText
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var titleView: TextView

    private var project: String? = null
    private var file: File? = null
    private var lastApk: File? = null
    private var loading = false
    @Volatile private var building = false
    private val handler = Handler(Looper.getMainLooper())
    private val hlRunnable = Runnable { highlight() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ProjectStore(this)
        builder = ForgeBuilder(this) { appendLog(it) }

        titleView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(16, 4, 16, 4)
        }
        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (loading) return
                    handler.removeCallbacks(hlRunnable)
                    handler.postDelayed(hlRunnable, 300)
                }
            })
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 8, 16, 8)
        }
        scroll = ScrollView(this).apply { addView(logView) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(btn("PROYEK") { showProjectMenu() }, btn("FILE") { showFileMenu() }, btn("SIMPAN") { save() }))
            addView(row(btn("BUILD") { build() }, btn("INSTALL") { installLast() }))
            addView(titleView)
            addView(editor, LinearLayout.LayoutParams(-1, 0, 2f))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)

        try { builder.prepareTools() } catch (e: Throwable) {
            appendLog("android.jar tidak ditemukan di assets: ${e.message}")
        }
        try { if (store.list().isEmpty()) store.create("Hello", "com.example.hello") } catch (e: Throwable) {
            appendLog("Gagal membuat proyek awal: ${e.message}")
        }
        val last = getPreferences(MODE_PRIVATE).getString("project", null)
        val name = store.list().firstOrNull { it == last } ?: store.list().firstOrNull()
        if (name != null) openProject(name)
    }

    override fun onPause() {
        super.onPause()
        saveCurrent()
    }

    // ---------- UI helper ----------

    private fun btn(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    private fun row(vararg b: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        b.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Ya") { _, _ -> ok() }
            .setNegativeButton("Batal", null).show()
    }

    private fun appendLog(s: String) = runOnUiThread {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------- proyek & file ----------

    private fun highlight() {
        Highlighter.apply(editor.text, file?.name?.endsWith(".xml") == true)
    }

    private fun setEditorText(s: String) {
        loading = true
        editor.setText(s)
        loading = false
        highlight()
    }

    private fun saveCurrent() {
        file?.let { if (it.exists()) it.writeText(editor.text.toString()) }
    }

    private fun save() {
        saveCurrent()
        toast("Tersimpan")
    }

    private fun openProject(name: String) {
        saveCurrent()
        project = name
        getPreferences(MODE_PRIVATE).edit().putString("project", name).apply()
        lastApk = null
        val files = store.files(name)
        val first = files.firstOrNull { it.endsWith(".java") } ?: files.firstOrNull()
        if (first != null) openFile(first) else {
            file = null
            setEditorText("")
            titleView.text = name
        }
    }

    private fun openFile(rel: String) {
        val p = project ?: return
        saveCurrent()
        val f = File(store.dir(p), rel)
        file = f
        setEditorText(f.readText())
        titleView.text = "$p / $rel"
    }

    private fun showProjectMenu() {
        val names = store.list()
        val items = listOf("+ Proyek baru") + names + listOf("Hapus proyek ini")
        AlertDialog.Builder(this).setTitle("Proyek")
            .setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> newProjectDialog()
                    items.lastIndex -> project?.let { p -> confirm("Hapus proyek '$p' beserta isinya?") { deleteProject(p) } }
                    else -> openProject(names[i - 1])
                }
            }.show()
    }

    private fun newProjectDialog() {
        val name = EditText(this).apply { hint = "Nama proyek (mis. MyApp)"; setSingleLine() }
        val pkg = EditText(this).apply {
            hint = "Package (mis. com.contoh.myapp)"
            setSingleLine()
            setText("com.example.")
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(name)
            addView(pkg)
        }
        AlertDialog.Builder(this).setTitle("Proyek baru").setView(box)
            .setPositiveButton("Buat") { _, _ ->
                val n = name.text.toString().trim()
                try {
                    store.create(n, pkg.text.toString().trim())
                    openProject(n)
                } catch (e: Exception) {
                    toast(e.message ?: "Gagal membuat proyek")
                }
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun deleteProject(p: String) {
        file = null
        store.dir(p).deleteRecursively()
        project = null
        val next = store.list().firstOrNull()
        if (next != null) openProject(next) else {
            setEditorText("")
            titleView.text = "(belum ada proyek)"
        }
    }

    private fun showFileMenu() {
        val p = project ?: return toast("Pilih proyek dulu")
        val files = store.files(p)
        val items = listOf("+ File baru") + files + listOf("Hapus file ini")
        AlertDialog.Builder(this).setTitle("File")
            .setItems(items.toTypedArray()) { _, i ->
                when (i) {
                    0 -> newFileDialog(p)
                    items.lastIndex -> file?.let { f -> confirm("Hapus ${f.name}?") { deleteFile(p, f) } }
                    else -> openFile(files[i - 1])
                }
            }.show()
    }

    private fun newFileDialog(p: String) {
        val cur = file?.relativeTo(store.dir(p))?.parent
        val input = EditText(this).apply {
            hint = "src/com/contoh/Foo.java atau res/layout/main.xml"
            setSingleLine()
            setText(if (cur == null) "src/" else "$cur/")
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply { setPadding(48, 16, 48, 0); addView(input, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle("File baru").setView(box)
            .setPositiveButton("Buat") { _, _ ->
                val rel = input.text.toString().trim()
                try {
                    store.newFile(p, rel)
                    openFile(rel)
                } catch (e: Exception) {
                    toast(e.message ?: "Gagal membuat file")
                }
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun deleteFile(p: String, f: File) {
        f.delete()
        file = null
        val files = store.files(p)
        if (files.isNotEmpty()) openFile(files.first()) else {
            setEditorText("")
            titleView.text = p
        }
    }

    // ---------- build & install ----------

    private fun build() {
        val p = project ?: return toast("Pilih proyek dulu")
        if (building) return toast("Build sedang berjalan")
        saveCurrent()
        logView.text = ""
        building = true
        // stack besar: ECJ & D8 cukup rakus
        Thread(null, {
            try {
                lastApk = builder.build(store.dir(p))
            } catch (e: Throwable) {
                appendLog("GAGAL: ${e.message}")
                appendLog(e.stackTraceToString().take(1500))
            } finally {
                building = false
            }
        }, "forge-build", 64L * 1024 * 1024).start()
    }

    private fun installLast() {
        val p = project ?: return toast("Pilih proyek dulu")
        val apk = lastApk ?: builder.apkOf(store.dir(p)).takeIf { it.exists() }
        if (apk == null) return toast("Belum ada APK. Tekan BUILD dulu.")
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            appendLog("Izinkan 'install dari sumber ini', lalu tekan INSTALL lagi.")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
