package com.senza.apkforge

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : Activity() {

    private lateinit var editor: EditText
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var builder: ForgeBuilder
    private var lastApk: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        builder = ForgeBuilder(this) { appendLog(it) }

        val buildBtn = Button(this).apply { text = "BUILD" }
        val installBtn = Button(this).apply { text = "INSTALL" }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(buildBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(installBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            gravity = Gravity.TOP or Gravity.START
            setHorizontallyScrolling(true)
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 8, 16, 8)
        }
        scroll = ScrollView(this).apply { addView(logView) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar)
            addView(editor, LinearLayout.LayoutParams(-1, 0, 2f))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)

        try {
            builder.prepare()
            editor.setText(builder.sourceFile.readText())
        } catch (e: Throwable) {
            appendLog("Setup gagal: ${e.message}\n(pastikan android.jar ada di assets)")
        }

        buildBtn.setOnClickListener {
            builder.sourceFile.writeText(editor.text.toString())
            logView.text = ""
            // stack besar: ECJ & D8 cukup rakus
            Thread(null, {
                try {
                    lastApk = builder.build()
                } catch (e: Throwable) {
                    appendLog("GAGAL: ${e.message}")
                    appendLog(e.stackTraceToString().take(1500))
                }
            }, "forge-build", 64L * 1024 * 1024).start()
        }

        installBtn.setOnClickListener {
            val apk = lastApk ?: builder.signedApk.takeIf { it.exists() }
            if (apk == null) { appendLog("Belum ada APK. Tekan BUILD dulu."); return@setOnClickListener }
            install(apk)
        }
    }

    private fun install(apk: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
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

    private fun appendLog(s: String) = runOnUiThread {
        logView.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
