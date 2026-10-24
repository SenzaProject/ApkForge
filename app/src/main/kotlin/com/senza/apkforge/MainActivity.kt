package com.senza.apkforge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    private lateinit var store: ProjectStore
    private lateinit var builder: ForgeBuilder
    private val handler = Handler(Looper.getMainLooper())

    private var project: String? = null
    private var file: File? = null
    private var lastApk: File? = null
    private var onEditor = false
    private var loading = false
    @Volatile private var building = false
    private val logBuf = StringBuilder()

    // view editor
    private lateinit var editorPage: LinearLayout
    private lateinit var projTitle: TextView
    private lateinit var fileSub: TextView
    private lateinit var chipRow: LinearLayout
    private lateinit var editor: EditText
    private lateinit var panel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelSub: TextView
    private lateinit var progress: ProgressBar
    private lateinit var panelBtns: LinearLayout
    private lateinit var detailBtn: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logView: TextView
    private lateinit var buildBtn: TextView

    private val hlRunnable = Runnable { highlight() }
    private val saveRunnable = Runnable { saveCurrent() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Pal.primaryDark
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        store = ProjectStore(this)
        builder = ForgeBuilder(this, { appendLog(it) }, { n, text -> runOnUiThread { onBuildStep(n, text) } })
        try { if (builder.hasAndroidJar()) builder.prepareTools() } catch (_: Throwable) {}
        try {
            if (store.list().isEmpty()) store.create("Aplikasi Pertama", "com.example.aplikasipertama")
        } catch (_: Throwable) {}
        buildEditor()
        showHome()
    }

    override fun onPause() {
        super.onPause()
        saveCurrent()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (onEditor) showHome() else super.onBackPressed()
    }

    // =====================================================================
    //  BERANDA
    // =====================================================================

    private fun showHome() {
        saveCurrent()
        onEditor = false
        val prefs = getPreferences(MODE_PRIVATE)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Pal.primary)
            setPadding(dp(20), dp(20), dp(14), dp(20))
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("ApkForge", 26f, Color.WHITE, true))
                addView(label("Buat aplikasi Android langsung dari HP", 13f, 0xFFE0E7FF.toInt()))
            }
            addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(label("?", 20f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = shape(0x33FFFFFF, 20)
                isClickable = true
                setOnClickListener { prefs.edit().putBoolean("guide_hidden", false).apply(); showHome() }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        body.addView(statusCard(), lp(b = 12))
        if (!prefs.getBoolean("guide_hidden", false)) body.addView(guideCard(prefs), lp(b = 12))
        body.addView(label("Proyek saya", 16f, Pal.text, true), lp(t = 4, b = 8))
        val names = store.list()
        if (names.isEmpty()) body.addView(emptyState(), lp())
        else names.forEach { body.addView(projectCard(it), lp(b = 10)) }

        val bottom = LinearLayout(this).apply {
            setBackgroundColor(Pal.card)
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(button("＋  Buat aplikasi baru", Pal.primary) { newProjectDialog() }, lp())
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            addView(header, lp())
            addView(ScrollView(context).apply { addView(body) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bottom, lp())
        }
        setContentView(page)
    }

    private fun statusCard(): LinearLayout {
        val checks = listOf(
            "Compiler Java" to true,
            "Pustaka Android" to builder.hasAndroidJar(),
            "Alat resource (aapt2)" to builder.hasAapt2()
        )
        val ready = checks.all { it.second }
        return card(if (ready) Pal.okBg else Pal.warnBg).apply {
            addView(label(if (ready) "✅ Siap membuat aplikasi" else "⚠️ Ada alat yang belum siap", 15f,
                if (ready) Pal.ok else Pal.warn, true))
            checks.forEach { (name, ok) ->
                addView(label((if (ok) "✓  " else "✗  ") + name, 13f, if (ok) Pal.ok else Pal.err), lp(t = 4))
            }
            if (!ready) addView(button("Cara memperbaiki", Pal.warn, sp = 14f) { showFixDialog() }, lp(t = 10))
        }
    }

    private fun guideCard(prefs: SharedPreferences): LinearLayout = card().apply {
        addView(label("Cara pakai — 3 langkah", 15f, Pal.text, true))
        listOf(
            "1️⃣  Buat proyek baru atau buka proyek yang ada",
            "2️⃣  Tulis / ubah kode di editor",
            "3️⃣  Ketuk ▶ Bangun APK, lalu Pasang"
        ).forEach { addView(label(it, 14f, Pal.sub), lp(t = 6)) }
        addView(button("Mengerti", Pal.primary, sp = 14f) {
            prefs.edit().putBoolean("guide_hidden", true).apply(); showHome()
        }, lp(t = 12))
    }

    private fun emptyState(): LinearLayout = card().apply {
        addView(label("📱", 40f).apply { gravity = Gravity.CENTER }, lp())
        addView(label("Belum ada proyek", 16f, Pal.text, true).apply { gravity = Gravity.CENTER }, lp(t = 6))
        addView(label("Ketuk tombol di bawah untuk membuat aplikasi pertamamu.", 13f, Pal.sub)
            .apply { gravity = Gravity.CENTER }, lp(t = 4))
    }

    private fun projectCard(name: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(Pal.card, 16)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(14), dp(8), dp(14))
            isClickable = true
            setOnClickListener { openProject(name); showEditor() }
        }
        val avatar = label(name.take(1).uppercase(), 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = shape(Pal.primary, 12)
        }
        row.addView(avatar, LinearLayout.LayoutParams(dp(44), dp(44)))
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(name, 16f, Pal.text, true))
            addView(label(store.packageOf(name), 12f, Pal.sub))
        }
        row.addView(mid, lp(0, WRAP, l = 12, weight = 1f))
        row.addView(label("⋮", 22f, Pal.sub, true).apply {
            setPadding(dp(14), dp(4), dp(14), dp(4))
            isClickable = true
            setOnClickListener { confirm("Hapus proyek '$name' beserta isinya?") { deleteProject(name) } }
        })
        return row
    }

    private fun showFixDialog() {
        val msg = buildString {
            if (!builder.hasAapt2()) {
                append("Alat 'aapt2' belum ada di dalam ApkForge. Alat ini wajib untuk memproses resource aplikasi.\n\n")
                append("Cara memperbaiki:\n")
                append("1. Siapkan file aapt2 versi Android ARM64 (berdiri sendiri).\n")
                append("2. Ganti namanya menjadi libaapt2.so dan taruh di:\n   app/src/main/jniLibs/arm64-v8a/\n   pada proyek ApkForge di GitHub.\n")
                append("3. Push ke GitHub, tunggu Actions selesai, lalu pasang APK ApkForge yang terbaru.\n\n")
            }
            if (!builder.hasAndroidJar()) {
                append("Pustaka Android (android.jar) tidak ikut terpasang. Pasang ApkForge dari hasil build GitHub Actions (workflow menambahkannya otomatis).\n")
            }
        }.trim()
        AlertDialog.Builder(this).setTitle("Cara memperbaiki").setMessage(msg)
            .setPositiveButton("Mengerti", null).show()
    }

    private fun newProjectDialog() {
        val nameEt = EditText(this).apply { hint = "Nama aplikasi, mis. Kalkulatorku"; setSingleLine() }
        val pkgEt = EditText(this).apply { setSingleLine(); setText(ProjectStore.suggestPackage("")) }
        var auto = true
        var setting = false
        pkgEt.onChanged { if (!setting) auto = false }
        nameEt.onChanged {
            if (auto) {
                setting = true
                pkgEt.setText(ProjectStore.suggestPackage(it.toString()))
                setting = false
            }
        }
        val pkgBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(label("Package (ID unik aplikasi)", 12f, Pal.sub))
            addView(pkgEt)
        }
        val adv = label("Pengaturan lanjutan ▾", 13f, Pal.primary, true).apply {
            setPadding(0, dp(14), 0, dp(4))
            isClickable = true
            setOnClickListener {
                pkgBox.visibility = if (pkgBox.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(label("Beri nama aplikasimu. Package dibuat otomatis.", 13f, Pal.sub))
            addView(nameEt)
            addView(adv)
            addView(pkgBox)
        }
        val dlg = AlertDialog.Builder(this).setTitle("Buat aplikasi baru").setView(box)
            .setPositiveButton("Buat", null).setNegativeButton("Batal", null).create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = nameEt.text.toString().trim()
                try {
                    store.create(n, pkgEt.text.toString().trim())
                    dlg.dismiss()
                    openProject(n)
                    showEditor()
                } catch (e: Exception) {
                    toast(e.message ?: "Gagal membuat proyek")
                }
            }
        }
        dlg.show()
    }

    private fun deleteProject(name: String) {
        if (project == name) { project = null; file = null }
        store.dir(name).deleteRecursively()
        showHome()
    }

    // =====================================================================
    //  EDITOR
    // =====================================================================

    private fun buildEditor() {
        val back = label("←", 24f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            setOnClickListener { showHome() }
        }
        projTitle = label("", 17f, Color.WHITE, true).apply { setSingleLine() }
        fileSub = label("", 12f, 0xFFE0E7FF.toInt()).apply { setSingleLine() }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(projTitle)
            addView(fileSub)
        }
        val more = label("⋮", 24f, Color.WHITE, true).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            setOnClickListener { showEditorMenu() }
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Pal.primary)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            elevation = dp(4).toFloat()
            addView(back)
            addView(titles, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(more)
        }

        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Pal.card)
            addView(chipRow)
        }

        editor = EditText(this).apply {
            setBackgroundColor(Pal.editorBg)
            setTextColor(Pal.editorFg)
            setHintTextColor(0xFF6B7280.toInt())
            hint = "Ketik kode di sini…"
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            highlightColor = 0x554F46E5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (Build.VERSION.SDK_INT >= 29) {
                textCursorDrawable = GradientDrawable().apply { setColor(Color.WHITE); setSize(dp(2), -1) }
            }
            onChanged {
                if (!loading) {
                    handler.removeCallbacks(hlRunnable)
                    handler.postDelayed(hlRunnable, 300)
                    handler.removeCallbacks(saveRunnable)
                    handler.postDelayed(saveRunnable, 1500)
                }
            }
        }

        // panel hasil build
        panelTitle = label("", 15f, Pal.text, true)
        panelSub = label("", 13f, Pal.sub)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = ForgeBuilder.TOTAL_STEPS
            progressTintList = ColorStateList.valueOf(Pal.primary)
        }
        panelBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        detailBtn = label("Lihat detail ▾", 13f, Pal.primary, true).apply {
            setPadding(0, dp(10), 0, dp(4))
            isClickable = true
            setOnClickListener { toggleDetail() }
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Pal.text)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            addView(logView)
            visibility = View.GONE
            background = shape(0xFFF3F4F6.toInt(), 8)
        }
        panel = card(Pal.card, 14).apply {
            visibility = View.GONE
            addView(panelTitle)
            addView(panelSub, lp(t = 2))
            addView(progress, lp(MATCH, dp(10), t = 10))
            addView(panelBtns, lp(t = 10))
            addView(detailBtn)
            addView(logScroll, LinearLayout.LayoutParams(MATCH, dp(180)))
        }

        // baris tombol simbol
        val symRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        listOf(
            "Tab" to "    ", "{ }" to "{}", "( )" to "()", "[ ]" to "[]", "\" \"" to "\"\"",
            ";" to ";", "." to ".", "," to ",", "=" to "=", "<" to "<", ">" to ">", "/" to "/",
            "!" to "!", "+" to "+", "-" to "-", "*" to "*", "&" to "&", "|" to "|", "_" to "_", "@" to "@"
        ).forEach { (lab, ins) ->
            val backBy = if (ins.length == 2 && lab.contains(' ')) 1 else 0
            symRow.addView(label(lab, 15f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                minWidth = dp(40)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = pressable(0xFF3B3B52.toInt(), 8)
                isClickable = true
                setOnClickListener { insert(ins, backBy) }
            }, lp(WRAP, WRAP, r = 6))
        }
        val symScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(0xFF2A2A3C.toInt())
            addView(symRow)
        }

        buildBtn = button("▶  Bangun APK", Pal.ok) { startBuild() }
        val bottom = LinearLayout(this).apply {
            setBackgroundColor(Pal.card)
            elevation = dp(8).toFloat()
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(buildBtn, lp())
        }

        editorPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.bg)
            addView(toolbar, lp())
            addView(chipScroll, lp())
            addView(editor, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(panel, lp(l = 8, t = 8, r = 8, b = 4))
            addView(symScroll, lp())
            addView(bottom, lp())
        }
    }

    private fun showEditor() {
        onEditor = true
        (editorPage.parent as? ViewGroup)?.removeView(editorPage)
        setContentView(editorPage)
    }

    private fun openProject(name: String) {
        saveCurrent()
        project = name
        lastApk = null
        hidePanel()
        projTitle.text = name
        val files = store.files(name)
        val first = files.firstOrNull { it.endsWith(".java") } ?: files.firstOrNull()
        if (first != null) openFile(first) else {
            file = null
            setEditorText("")
            fileSub.text = "(belum ada file)"
            refreshChips()
        }
    }

    private fun openFile(rel: String) {
        val p = project ?: return
        saveCurrent()
        val f = File(store.dir(p), rel)
        file = f
        setEditorText(f.readText())
        fileSub.text = rel
        refreshChips()
        editor.scrollTo(0, 0)
    }

    private fun refreshChips() {
        chipRow.removeAllViews()
        val p = project ?: return
        val cur = file?.let { runCatching { it.relativeTo(store.dir(p)).path }.getOrNull() }
        store.files(p).forEach { rel ->
            val sel = rel == cur
            val chip = label(rel.substringAfterLast('/'), 13f, if (sel) Color.WHITE else Pal.text, sel).apply {
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = pressable(if (sel) Pal.primary else Pal.chip, 20)
                isClickable = true
                setOnClickListener { openFile(rel) }
                setOnLongClickListener {
                    confirm("Hapus ${rel.substringAfterLast('/')}?") { deleteFile(p, File(store.dir(p), rel)) }
                    true
                }
            }
            chipRow.addView(chip, lp(WRAP, WRAP, r = 8))
        }
        chipRow.addView(label("＋ File", 13f, Pal.primary, true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pressable(0xFFEDEBFF.toInt(), 20)
            isClickable = true
            setOnClickListener { newFileDialog(p) }
        }, lp(WRAP, WRAP))
    }

    private fun showEditorMenu() {
        val items = arrayOf("💾  Simpan sekarang", "＋  Tambah file", "🗑  Hapus file ini", "❓  Bantuan", "🏠  Kembali ke beranda")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> { saveCurrent(); toast("Tersimpan ✓") }
                1 -> project?.let { newFileDialog(it) }
                2 -> {
                    val p = project
                    val f = file
                    if (p != null && f != null) confirm("Hapus ${f.name}?") { deleteFile(p, f) }
                }
                3 -> showHelp()
                4 -> showHome()
            }
        }.show()
    }

    private fun showHelp() {
        val msg = "1. Pilih proyek di beranda, atau buat yang baru.\n" +
            "2. Ubah kode di editor. Perubahan tersimpan otomatis.\n" +
            "3. Ketuk ▶ Bangun APK dan tunggu sampai muncul ✅.\n" +
            "4. Ketuk 'Pasang sekarang'.\n\n" +
            "Tips:\n" +
            "• Ketuk nama file di atas editor untuk pindah file.\n" +
            "• Tekan lama nama file untuk menghapusnya.\n" +
            "• Baris tombol di bawah editor memudahkan mengetik { } ( ) ;\n" +
            "• Kalau ada error, ketuk 'Ke baris …' untuk melompat ke lokasi kesalahan."
        AlertDialog.Builder(this).setTitle("Bantuan").setMessage(msg).setPositiveButton("Mengerti", null).show()
    }

    private fun newFileDialog(p: String) {
        val types = arrayOf("Kelas Java (kode program)", "Layout (tampilan, .xml)", "Values (teks/warna, .xml)")
        AlertDialog.Builder(this).setTitle("Tambah file").setItems(types) { _, i -> askFileName(p, i, types[i]) }.show()
    }

    private fun askFileName(p: String, type: Int, title: String) {
        val et = EditText(this).apply {
            hint = if (type == 0) "Nama kelas, mis. Pengaturan" else "Nama file, mis. activity_main"
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(et, lp())
        }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton("Buat") { _, _ ->
                val n = et.text.toString().trim()
                try {
                    val rel = when (type) {
                        0 -> {
                            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(n)) {
                                "Nama kelas: huruf, angka, _ (tidak boleh diawali angka)."
                            }
                            "src/" + store.packageOf(p).replace('.', '/') + "/$n.java"
                        }
                        1 -> { requireResName(n); "res/layout/${n.lowercase()}.xml" }
                        else -> { requireResName(n); "res/values/${n.lowercase()}.xml" }
                    }
                    store.newFile(p, rel)
                    openFile(rel)
                } catch (e: Exception) {
                    toast(e.message ?: "Gagal membuat file")
                }
            }
            .setNegativeButton("Batal", null).show()
    }

    private fun requireResName(n: String) =
        require(Regex("[A-Za-z][A-Za-z0-9_]*").matches(n)) { "Nama file: huruf, angka, _ (diawali huruf)." }

    private fun deleteFile(p: String, f: File) {
        f.delete()
        if (file == f) file = null
        val files = store.files(p)
        if (files.isNotEmpty()) openFile(files.first()) else {
            setEditorText("")
            fileSub.text = "(belum ada file)"
            refreshChips()
        }
    }

    private fun insert(s: String, back: Int) {
        val st = editor.selectionStart.coerceAtLeast(0)
        val en = editor.selectionEnd.coerceAtLeast(0)
        val a = minOf(st, en)
        editor.text.replace(a, maxOf(st, en), s)
        editor.setSelection(a + s.length - back)
    }

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

    // =====================================================================
    //  BUILD
    // =====================================================================

    private fun showPanel(bg: Int) {
        panel.background = shape(bg, 16)
        panel.visibility = View.VISIBLE
    }

    private fun hidePanel() { panel.visibility = View.GONE }

    private fun toggleDetail() {
        val show = logScroll.visibility != View.VISIBLE
        logScroll.visibility = if (show) View.VISIBLE else View.GONE
        detailBtn.text = if (show) "Sembunyikan detail ▴" else "Lihat detail ▾"
    }

    private fun startBuild() {
        val p = project ?: return toast("Buka proyek dulu")
        if (building) return toast("Sedang membangun, tunggu sebentar…")
        saveCurrent()
        synchronized(logBuf) { logBuf.setLength(0) }
        logView.text = ""
        building = true
        buildBtn.alpha = 0.5f
        showPanel(Pal.card)
        panelTitle.text = "Membangun aplikasi…"
        panelTitle.setTextColor(Pal.text)
        panelSub.text = "Mohon tunggu, jangan tutup aplikasi."
        panelSub.setTextColor(Pal.sub)
        progress.visibility = View.VISIBLE
        progress.progress = 0
        panelBtns.removeAllViews()
        logScroll.visibility = View.GONE
        detailBtn.text = "Lihat detail ▾"
        Thread(null, {
            try {
                val apk = builder.build(store.dir(p))
                lastApk = apk
                runOnUiThread { showSuccess(apk) }
            } catch (e: BuildError) {
                runOnUiThread { showFailure(p, e) }
            } catch (e: Throwable) {
                appendLog(e.stackTraceToString().take(2000))
                runOnUiThread {
                    showFailure(p, BuildError(BuildError.Kind.OTHER,
                        "Terjadi kesalahan tak terduga: ${e.message ?: e.javaClass.simpleName}"))
                }
            } finally {
                building = false
                runOnUiThread { buildBtn.alpha = 1f }
            }
        }, "forge-build", 64L * 1024 * 1024).start()
    }

    private fun onBuildStep(n: Int, text: String) {
        progress.progress = n
        panelTitle.text = "Membangun… ($n dari ${ForgeBuilder.TOTAL_STEPS})"
        panelSub.text = text
    }

    private fun showSuccess(apk: File) {
        showPanel(Pal.okBg)
        panelTitle.text = "✅ APK siap dipasang!"
        panelTitle.setTextColor(Pal.ok)
        panelSub.text = "Ukuran ${apk.length() / 1024} KB. Ketuk Pasang, lalu izinkan jika diminta."
        panelSub.setTextColor(Pal.text)
        progress.visibility = View.GONE
        panelBtns.removeAllViews()
        panelBtns.addView(button("Pasang sekarang", Pal.ok, sp = 14f) { installApk(apk) }, lp(0, WRAP, r = 8, weight = 1f))
        panelBtns.addView(button("Tutup", Pal.sub, sp = 14f) { hidePanel() }, lp(0, WRAP, weight = 1f))
    }

    private fun showFailure(p: String, e: BuildError) {
        showPanel(Pal.errBg)
        panelTitle.text = when (e.kind) {
            BuildError.Kind.CODE -> "❌ Ada kesalahan di kodemu"
            BuildError.Kind.RESOURCE -> "❌ Ada kesalahan di file .xml"
            BuildError.Kind.TOOLS -> "⚠️ Alat build belum lengkap"
            BuildError.Kind.OTHER -> "❌ Build gagal"
        }
        panelTitle.setTextColor(Pal.err)
        panelSub.text = e.message
        panelSub.setTextColor(Pal.text)
        progress.visibility = View.GONE
        panelBtns.removeAllViews()
        val f = e.file
        val l = e.line
        if (f != null && l != null) {
            panelBtns.addView(button("Ke baris $l", Pal.err, sp = 14f) { goToLine(p, f, l) }, lp(0, WRAP, r = 8, weight = 1f))
        }
        if (e.kind == BuildError.Kind.TOOLS) {
            panelBtns.addView(button("Cara memperbaiki", Pal.warn, sp = 14f) { showFixDialog() }, lp(0, WRAP, r = 8, weight = 1f))
        }
        panelBtns.addView(button("Salin log", Pal.sub, sp = 14f) { copyLog() }, lp(0, WRAP, weight = 1f))
    }

    private fun goToLine(p: String, path: String, line: Int) {
        val rel = runCatching { File(path).relativeTo(store.dir(p)).path }.getOrNull()
        if (rel == null || !store.files(p).contains(rel)) return toast("File itu tidak bisa dibuka dari sini")
        openFile(rel)
        val text = editor.text.toString()
        var idx = 0
        repeat(line - 1) {
            val n = text.indexOf('\n', idx)
            if (n >= 0) idx = n + 1
        }
        editor.requestFocus()
        editor.setSelection(idx.coerceAtMost(text.length))
    }

    private fun copyLog() {
        val s = synchronized(logBuf) { logBuf.toString() }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("log", s))
        toast("Log disalin")
    }

    private fun installApk(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            toast("Izinkan 'pasang dari sumber ini', lalu ketuk Pasang lagi.")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
        startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // =====================================================================
    //  UTIL
    // =====================================================================

    private fun appendLog(s: String) {
        synchronized(logBuf) { logBuf.append(s).append('\n') }
        runOnUiThread {
            logView.append(s + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton("Ya") { _, _ -> ok() }
            .setNegativeButton("Batal", null).show()
    }
}
