// Lean Otzarot reader. License: GPLv3.
package com.yeshsade.otzarot.reader.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.shockwave.pdfium.PdfiumCore
import com.yeshsade.otzarot.reader.R
import com.yeshsade.otzarot.reader.ReaderApp
import com.yeshsade.otzarot.reader.app
import com.yeshsade.otzarot.reader.data.AllFiles
import com.yeshsade.otzarot.reader.data.Doc
import com.yeshsade.otzarot.reader.data.Intake
import com.yeshsade.otzarot.reader.data.WhatsAppFolder
import java.io.File

/** List of documents with small covers, newest received first. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var ui: Ui
    private lateinit var list: LinearLayout
    private lateinit var refresher: SwipeRefreshLayout
    private lateinit var sortLabel: TextView
    @Volatile private var scanning = false
    private val main = Handler(Looper.getMainLooper())

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::openPicked) }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tree = result.data?.data
        if (tree != null && WhatsAppFolder.accept(this, app.settings, tree)) scanAndRefresh()
        else if (tree != null) Toast.makeText(this, R.string.wa_failed, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        buildScreen()
        // Owner's decision: by default the app opens the document read last; the list stays underneath.
        if (savedInstanceState == null && !intent.getBooleanExtra(EXTRA_STAY, false) && app.settings.startScreen == "last") {
            app.library.lastRead()?.let { ReaderActivity.open(this, it) }
        }
        if (savedInstanceState == null) maybeAskForAllFiles()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        scanAndRefresh()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as ReaderApp).windowPolicy.touched(this)
    }

    private fun buildScreen() {
        val root = ui.column().apply { setBackgroundColor(ui.colors.canvas) }
        val (header, _) = ui.header(getString(R.string.library_title))
        header.addView(ui.headerButton(if (ui.night) R.drawable.otzr_ic_sun else R.drawable.otzr_ic_moon,
            getString(if (ui.night) R.string.library_day else R.string.library_night)) {
            app.settings.night = !app.settings.night
            ReaderApp.applyTheme(app.settings)
        })
        header.addView(ui.headerButton(R.drawable.ic_settings, getString(R.string.library_settings)) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(header)

        sortLabel = ui.label("", 13f, ui.colors.accent, true).apply {
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(6))
            minimumHeight = ui.dp(44)
            isClickable = true
            setOnClickListener { chooseSort() }
        }
        root.addView(sortLabel)
        list = ui.column().apply { setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(12)) }
        refresher = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(ui.colors.accent)
            setProgressBackgroundColorSchemeColor(ui.colors.card)
            setOnRefreshListener { scanAndRefresh(userAsked = true) }
            addView(ScrollView(this@LibraryActivity).apply { addView(list); clipToPadding = false; isFillViewport = true })
        }
        root.addView(refresher, LinearLayout.LayoutParams(-1, 0, 1f))

        val open = ui.row().apply {
            gravity = Gravity.CENTER
            background = ui.rounded(ui.colors.button, 20f)
            minimumHeight = ui.dp(58)
            isClickable = true
            setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        }
        open.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_add_file)
            imageTintList = ColorStateList.valueOf(ui.colors.buttonInk)
        }, LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(10) })
        open.addView(ui.label(getString(R.string.library_open_pdf), 17f, ui.colors.buttonInk, true))
        root.addView(open, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(14)) })
        ui.install(this, root, header)
    }

    private fun refresh() {
        list.removeAllViews()
        val sort = Sort.of(app.settings.sort)
        sortLabel.text = getString(R.string.library_sort_prefix) + " " + getString(sort.label) + "  ▾"
        val docs = sort.apply(app.library.all())
        if (docs.isEmpty()) {
            list.addView(ui.label(getString(R.string.library_empty), 15f, ui.colors.muted).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(48), ui.dp(24), ui.dp(48))
            })
            return
        }
        docs.forEach { list.addView(card(it)) }
    }

    private fun card(doc: Doc): View {
        val card = ui.row().apply {
            background = ui.rounded(ui.colors.card, 20f, border = ui.colors.border)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(6), ui.dp(14))
            minimumHeight = ui.dp(104)
            isClickable = true
            setOnClickListener { ReaderActivity.open(this@LibraryActivity, doc) }
            tag = "doc_" + doc.id
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.rounded(ui.colors.selection, 6f)
            contentDescription = null
        }
        card.addView(cover, LinearLayout.LayoutParams(ui.dp(52), ui.dp(72)).apply { marginEnd = ui.dp(14) })
        loadCover(doc, cover)

        val text = ui.column()
        text.addView(ui.label(doc.name, 17f, ui.colors.ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val details = if (doc.pages > 0) getString(R.string.library_pages, receivedLabel(doc.receivedAt), doc.pages) else receivedLabel(doc.receivedAt)
        text.addView(ui.label(details, 13f, ui.colors.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        val progress = if (doc.lastOpened > 0) getString(R.string.library_continue, doc.page + 1) else getString(R.string.library_unread)
        text.addView(ui.label(progress, 13f, ui.colors.accent).apply { setPadding(0, ui.dp(2), 0, 0) })
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        card.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(ui.colors.muted)
            background = null
            contentDescription = getString(R.string.library_remove)
            setOnClickListener { confirmRemove(doc) }
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))

        return LinearLayout(this).apply {
            setPadding(0, 0, 0, ui.dp(10))
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** Ways to order the list. Default: newest received first (owner's requirement); the choice is remembered. */
    enum class Sort(val key: String, val label: Int) {
        RECEIVED("received", R.string.sort_received),
        NAME("name", R.string.sort_name),
        SIZE("size", R.string.sort_size),
        LAST_READ("last_read", R.string.sort_last_read),
        PAGES("pages", R.string.sort_pages);

        fun apply(docs: List<Doc>): List<Doc> = when (this) {
            RECEIVED -> docs.sortedByDescending { it.receivedAt }
            NAME -> docs.sortedWith(compareBy(java.text.Collator.getInstance()) { it.name })
            SIZE -> docs.sortedByDescending { it.size }
            LAST_READ -> docs.sortedWith(compareByDescending<Doc> { it.lastOpened }.thenByDescending { it.receivedAt })
            PAGES -> docs.sortedByDescending { it.pages }
        }

        companion object {
            fun of(key: String) = entries.firstOrNull { it.key == key } ?: RECEIVED
        }
    }

    private fun chooseSort() {
        val options = Sort.entries
        val current = options.indexOf(Sort.of(app.settings.sort))
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(options.map { getString(it.label) }.toTypedArray(), current) { dialog, which ->
                app.settings.sort = options[which].key
                dialog.dismiss()
                refresh()
            }
            .show()
    }

    private fun receivedLabel(time: Long): String = when {
        DateUtils.isToday(time) -> getString(R.string.library_today)
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> getString(R.string.library_yesterday)
        else -> DateUtils.formatDateTime(this, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }

    /** Removes from the list only. The file itself (WhatsApp's or the working copy) is not deleted. */
    private fun confirmRemove(doc: Doc) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.library_remove_confirm, doc.name))
            .setPositiveButton(R.string.action_remove) { _, _ ->
                app.io.execute { app.library.remove(doc.id); main.post { refresh() } }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun loadCover(doc: Doc, view: ImageView) {
        val file = File(File(cacheDir, "covers").apply { mkdirs() }, doc.id + ".png")
        if (file.exists()) { view.setImageBitmap(BitmapFactory.decodeFile(file.path)); return }
        app.io.execute {
            val bitmap = runCatching {
                contentResolver.openFileDescriptor(Uri.parse(doc.uri), "r")!!.use { fd ->
                    val core = PdfiumCore(this)
                    val pdf = core.newDocument(fd)
                    try {
                        core.openPage(pdf, 0)
                        val w = core.getPageWidthPoint(pdf, 0).coerceAtLeast(1)
                        val h = core.getPageHeightPoint(pdf, 0).coerceAtLeast(1)
                        val width = ui.dp(104)
                        val height = (width.toLong() * h / w).toInt().coerceIn(1, width * 3)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            core.renderPageBitmap(pdf, it, 0, 0, 0, width, height)
                        }
                    } finally {
                        core.closeDocument(pdf)
                    }
                }
            }.getOrNull() ?: return@execute
            runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) } }
            main.post { if (view.isAttachedToWindow) view.setImageBitmap(bitmap) }
        }
    }

    private fun openPicked(uri: Uri) {
        app.io.execute {
            val doc = runCatching { Intake(this, app.library, app.settings).open(uri) }.getOrNull()
            main.post {
                if (doc == null) Toast.makeText(this, R.string.open_failed, Toast.LENGTH_LONG).show()
                else { refresh(); ReaderActivity.open(this, doc) }
            }
        }
    }

    /** Looks for new PDFs on the device (or in the WhatsApp folder). Pull down on the list to run it by hand. */
    private fun scanAndRefresh(userAsked: Boolean = false) {
        if (scanning) { if (userAsked) refresher.isRefreshing = true; return }
        scanning = true
        if (userAsked) refresher.isRefreshing = true
        app.io.execute {
            val changed = runCatching { Intake(this, app.library, app.settings).scanDevice() }.getOrDefault(false)
            main.post {
                scanning = false
                if (isDestroyed) return@post
                refresher.isRefreshing = false
                if (changed || userAsked) refresh()
            }
        }
    }

    /** One-time explanation, then Android's own "access to all files" switch for this app. */
    private fun maybeAskForAllFiles() {
        val settings = app.settings
        if (settings.allFilesPromptDismissed || AllFiles.granted(this)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.all_files_title)
            .setMessage(R.string.all_files_body)
            .setPositiveButton(R.string.all_files_allow) { _, _ ->
                settings.allFilesPromptDismissed = true
                AllFiles.request(this, REQUEST_FILES)
            }
            .setNegativeButton(R.string.wa_later) { _, _ -> settings.allFilesPromptDismissed = true }
            .setCancelable(false)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_FILES) scanAndRefresh(userAsked = true)
    }

    companion object {
        /** Set when coming back from a document, so the list is shown instead of reopening it. */
        const val EXTRA_STAY = "stay"
        private const val REQUEST_FILES = 7
    }
}
