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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var list: androidx.recyclerview.widget.RecyclerView
    private lateinit var empty: TextView
    private val adapter = DocAdapter()
    private lateinit var refresher: SwipeRefreshLayout
    private lateinit var sortLabel: TextView
    @Volatile private var scanning = false
    private val main = Handler(Looper.getMainLooper())

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::openPicked) }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tree = result.data?.data
        if (tree != null && WhatsAppFolder.accept(this, app.settings, tree)) scanAndRefresh()
        else if (tree != null) Messages.notice(this, getString(R.string.wa_failed), long = true)
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
        // Only the cards on screen are built (a long list of every PDF on the device stays quick to show).
        list = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@LibraryActivity)
            adapter = this@LibraryActivity.adapter
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(12))
            clipToPadding = false
            itemAnimator = null
            tag = "doc_list"
        }
        empty = ui.label(getString(R.string.library_empty), 15f, ui.colors.muted).apply {
            gravity = Gravity.CENTER; setPadding(ui.dp(24), ui.dp(48), ui.dp(24), ui.dp(48)); visibility = View.GONE
        }
        val stack = android.widget.FrameLayout(this).apply {
            addView(list, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(empty, android.widget.FrameLayout.LayoutParams(-1, -2))
        }
        refresher = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(ui.colors.accent)
            setProgressBackgroundColorSchemeColor(ui.colors.card)
            setOnRefreshListener { scanAndRefresh(userAsked = true) }
            setOnChildScrollUpCallback { _, _ -> list.canScrollVertically(-1) }
            addView(stack)
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

    /** The day the list was last drawn: after midnight "today"/"yesterday" labels must be redrawn. */
    private var shownDay: java.time.LocalDate? = null

    private fun refresh() {
        val order = app.settings.sortOrder
        val docs = order.apply(app.library.all(), java.text.Collator.getInstance())
        sortLabel.text = getString(R.string.library_sort_prefix) + " " + SortTexts.short(this, order) + "  ▾"
        empty.visibility = if (docs.isEmpty()) View.VISIBLE else View.GONE
        val today = java.time.LocalDate.now()
        val newDay = shownDay != null && shownDay != today
        shownDay = today
        // Only cards whose document changed are redrawn (coming back from reading usually changes one).
        adapter.submitList(docs) { if (newDay) adapter.notifyDataSetChanged() }
    }

    private inner class DocAdapter : androidx.recyclerview.widget.ListAdapter<Doc, DocHolder>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<Doc>() {
            override fun areItemsTheSame(a: Doc, b: Doc) = a.id == b.id
            override fun areContentsTheSame(a: Doc, b: Doc) = a == b
        }) {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) = DocHolder(card())
        override fun onBindViewHolder(holder: DocHolder, position: Int) = holder.bind(getItem(position))
    }

    private inner class DocHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        private val card = (view as LinearLayout).getChildAt(0) as LinearLayout
        private val cover = card.getChildAt(0) as ImageView
        private val texts = card.getChildAt(1) as LinearLayout
        private val more = card.getChildAt(2) as ImageButton

        fun bind(doc: Doc) {
            card.tag = "doc_" + doc.id
            card.setOnClickListener { ReaderActivity.open(this@LibraryActivity, doc) }
            more.setOnClickListener { showMenu(doc) }
            (texts.getChildAt(0) as TextView).text = doc.name
            (texts.getChildAt(1) as TextView).text =
                if (doc.pages > 0) getString(R.string.library_pages, receivedLabel(doc.receivedAt), doc.pages) else receivedLabel(doc.receivedAt)
            (texts.getChildAt(2) as TextView).text =
                if (doc.lastOpened > 0) getString(R.string.library_continue, doc.page + 1) else getString(R.string.library_unread)
            loadCover(doc, cover)
        }
    }

    /** One empty card; [DocHolder.bind] fills it. */
    private fun card(): View {
        val card = ui.row().apply {
            background = ui.rounded(ui.colors.card, 20f, border = ui.colors.border)
            setPadding(ui.dp(14), ui.dp(14), ui.dp(6), ui.dp(14))
            minimumHeight = ui.dp(104)
            isClickable = true
        }
        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.rounded(ui.colors.selection, 6f)
            contentDescription = null
        }
        card.addView(cover, LinearLayout.LayoutParams(ui.dp(52), ui.dp(72)).apply { marginEnd = ui.dp(14) })

        val text = ui.column()
        text.addView(ui.label("", 17f, ui.colors.ink, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        text.addView(ui.label("", 13f, ui.colors.muted).apply { setPadding(0, ui.dp(4), 0, 0) })
        text.addView(ui.label("", 13f, ui.colors.accent).apply { setPadding(0, ui.dp(2), 0, 0) })
        card.addView(text, LinearLayout.LayoutParams(0, -2, 1f))

        card.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(ui.colors.muted)
            background = null
            contentDescription = getString(R.string.library_more)
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))

        return LinearLayout(this).apply {
            setPadding(0, 0, 0, ui.dp(10))
            layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2)
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /**
     * "מיון לפי" (owner, 2026-10-16): what to sort by, then which way; the list follows each tap at once,
     * and the window closes with "סגירה".
     */
    private fun chooseSort() {
        val dialog = android.app.Dialog(this)
        val sheet = Messages.sheet(ui)
        fun fill() {
            sheet.removeAllViews()
            val settings = app.settings
            val order = settings.sortOrder
            val kinds = Messages.group(ui)
            kinds.addView(Messages.header(ui, getString(R.string.sort_title), null, null))
            com.yeshsade.otzarot.reader.data.SortOrder.offered(settings.sortBySize).forEach { by ->
                kinds.addView(Messages.divider(ui))
                kinds.addView(choiceRow(SortTexts.name(this, by), by == order.by, "sort_${by.key}") {
                    settings.sort = by.key; settings.sortReversed = false; refresh(); fill()
                })
            }
            sheet.addView(kinds)
            val ways = Messages.group(ui)
            ways.addView(Messages.header(ui, null, null, getString(R.string.sort_direction)))
            listOf(false, true).forEach { reversed ->
                ways.addView(Messages.divider(ui))
                ways.addView(choiceRow(SortTexts.direction(this, order.by, reversed), order.reversed == reversed, "dir_$reversed") {
                    settings.sortReversed = reversed; refresh(); fill()
                })
            }
            sheet.addView(ways, Messages.gap(ui))
            sheet.addView(Messages.cancel(ui, getString(R.string.action_close)) { dialog.dismiss() }.apply { tag = "sort_close" }, Messages.gap(ui))
        }
        fill()
        Messages.show(dialog, sheet, ui, cancelable = true)
        lastDialog = dialog
    }

    /** A row in the sort window: the chosen one in colour, with a check mark. */
    private fun choiceRow(label: String, chosen: Boolean, tag: String, onTap: () -> Unit) = ui.row().apply {
        minimumHeight = ui.dp(52)
        setPadding(ui.dp(20), 0, ui.dp(20), 0)
        isClickable = true
        this.tag = tag
        contentDescription = label
        addView(ui.label(label, 16f, if (chosen) ui.colors.accent else ui.colors.ink, bold = chosen), LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.label(if (chosen) "✓" else "", 18f, ui.colors.accent, bold = true))
        setOnClickListener { onTap() }
    }

    private fun receivedLabel(time: Long): String = when {
        DateUtils.isToday(time) -> getString(R.string.library_today)
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> getString(R.string.library_yesterday)
        else -> DateUtils.formatDateTime(this, time, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }

    /** The ⋮ next to a document: remove from the list, or delete from the device (owner, 2026-10-16). */
    private fun showMenu(doc: Doc): android.app.Dialog = Messages.menu(this, ui, doc.name, listOf(
        Messages.Item(getString(R.string.library_remove), tag = "menu_remove") { confirmRemove(doc) },
        Messages.Item(getString(R.string.library_delete), danger = true, tag = "menu_delete") { confirmDelete(doc) },
    ), cancel = getString(R.string.action_cancel)).also { lastDialog = it }

    /** The last menu or question shown (for tests and screenshots). */
    var lastDialog: android.app.Dialog? = null
        private set
    var lastAsk: Messages.Ask? = null
        private set

    /** Removes from the list only, and keeps scans from bringing it back. The file itself is not deleted. */
    private fun confirmRemove(doc: Doc) {
        lastAsk = Messages.ask(this, ui, getString(R.string.library_remove_confirm, isolated(doc.name)), getString(R.string.library_remove_note),
            yes = getString(R.string.action_remove), no = getString(R.string.action_cancel)) {
            app.io.execute { app.library.removeByReader(doc.id); forget(doc); main.post { refresh() } }
        }
        lastDialog = lastAsk!!.dialog
    }

    /** Deletes the file (or, for a read-only WhatsApp original, the app's copy) and the marks in it, after asking. */
    private fun confirmDelete(doc: Doc) {
        val copy = doc.kind == Doc.Kind.COPY
        lastAsk = Messages.ask(this, ui,
            getString(if (copy) R.string.library_delete_copy_confirm else R.string.library_delete_confirm, isolated(doc.name)),
            getString(if (copy) R.string.library_delete_copy_note else R.string.library_delete_note),
            yes = getString(R.string.action_delete), no = getString(R.string.action_cancel), danger = true) {
            app.saves.execute {
                // on the saves' thread: a save of this document still running finishes first
                val deleted = com.yeshsade.otzarot.reader.data.Files.deleteDocument(this, doc)
                if (deleted) { app.library.remove(doc.id); forget(doc) }
                main.post {
                    if (isDestroyed) return@post
                    if (!deleted) Messages.notice(this, getString(R.string.library_delete_failed), long = true)
                    refresh()
                }
            }
        }
        lastDialog = lastAsk!!.dialog
    }

    /** A file name inside a sentence: kept in one piece, so an English name does not scramble the Hebrew around it. */
    private fun isolated(name: String) = androidx.core.text.BidiFormatter.getInstance().unicodeWrap(name)

    /** The app's own copies for showing a document: its display copy and its cover. */
    private fun forget(doc: Doc) {
        app.covers.remove(doc.id)
        File(File(cacheDir, "covers"), doc.id + ".png").delete()
        File(File(cacheDir, "open"), doc.id + ".pdf").delete()
        File(File(cacheDir, "open"), doc.id + ".pdf.stamp").delete()
    }

    private fun loadCover(doc: Doc, view: ImageView) {
        view.tag = doc.id
        app.covers.get(doc.id)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        val file = File(File(cacheDir, "covers").apply { mkdirs() }, doc.id + ".png")
        // decoding and drawing covers never runs on the screen's thread, and never waits behind saves or scans
        app.quick.execute {
            if (file.exists()) {
                BitmapFactory.decodeFile(file.path)?.let { bitmap ->
                    app.covers.put(doc.id, bitmap)
                    main.post { if (view.tag == doc.id && !isDestroyed) view.setImageBitmap(bitmap) }
                    return@execute
                }
            }
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
            app.covers.put(doc.id, bitmap)
            main.post { if (view.tag == doc.id && !isDestroyed) view.setImageBitmap(bitmap) }
        }
    }

    private fun openPicked(uri: Uri) {
        app.io.execute {
            val doc = runCatching { Intake(this, app.library, app.settings).open(uri) }.getOrNull()
            main.post {
                if (doc == null) Messages.notice(this, getString(R.string.open_failed))
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
        allFilesDialog()
    }

    /** The explanation before Android's "access to all files" screen (wording approved 2026-10-14). */
    private fun allFilesDialog(): android.app.Dialog {
        val settings = app.settings
        return Messages.ask(this, ui, getString(R.string.all_files_title), getString(R.string.all_files_body),
            boldLine = getString(R.string.all_files_subtitle),
            yes = getString(R.string.all_files_allow), no = getString(R.string.wa_later), cancelable = false,
            onNo = { settings.allFilesPromptDismissed = true }) {
            settings.allFilesPromptDismissed = true
            AllFiles.request(this, REQUEST_FILES)
        }.dialog
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
