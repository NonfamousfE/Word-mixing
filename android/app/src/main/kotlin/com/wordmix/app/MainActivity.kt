package com.wordmix.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.content.pm.PackageManager
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * 主界面。
 *
 * 遵循与桌面端一致的设计语言：
 *   - 默认浅色配色（白卡片、浅灰底、主题绿工具栏），可切换深色
 *   - 顶部提供横向滚动的分组筛选标签（点分组过滤，再点取消）
 *   - 新增词条支持相似度实时推荐并入分组，或新建分组
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var lib: Library
    private lateinit var state: SyncState
    private lateinit var cfg: SyncConfig
    private lateinit var adapter: CardAdapter
    private lateinit var listView: ListView
    private lateinit var searchBox: EditText
    private lateinit var statusBar: TextView
    private lateinit var groupChipsContainer: LinearLayout
    private lateinit var maskBtn: Button

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    enum class GroupSortMode(val code: Int, val label: String) {
        DEFAULT(0, "最新修改"),
        WORD_COUNT_DESC(1, "词数降序"),
        NAME_ASC(2, "字典序"),
    }

    private var groupSortMode = GroupSortMode.DEFAULT
    private var isDarkTheme = false
    private var maskMeanings = true
    private val revealed = mutableSetOf<String>()
    private var query = ""
    private var activeGroupId: String? = null
    private var syncing = false

    private val appVersionCode: Long
        get() = try {
            packageManager.getPackageInfo(packageName, 0).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode
                else @Suppress("DEPRECATION") it.versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("wordmix", Context.MODE_PRIVATE)
        // 默认浅色主题（和电脑端一致），存储在配置中
        isDarkTheme = prefs.getBoolean("dark_theme", false)
        val sortCode = prefs.getInt("group_sort_mode", 0)
        groupSortMode = GroupSortMode.values().firstOrNull { it.code == sortCode } ?: GroupSortMode.DEFAULT

        runCatching {
            Phonetics.load {
                try {
                    assets.open("phonetic.txt")
                } catch (_: Exception) {
                    assets.open("phonetic.txt.gz")
                }
            }
        }
        Speech.init(this)

        store = Store(File(filesDir, "data"))
        lib = store.loadLibrary()
        state = store.loadState()
        cfg = store.loadConfig()

        setContentView(buildUi())
        render()
        renderGroupsBar()
        status("就绪")

        if (cfg.configured) {
            checkUpdateIfAny(silent = true)
            if (!prefs.getBoolean("clean_synced_v11", false)) {
                prefs.edit().putBoolean("clean_synced_v11", true).apply()
                ui.postDelayed({ forceCleanResetFromServer(silent = true) }, 1200)
            }
        } else {
            ui.postDelayed({ showSettings() }, 400)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Speech.shutdown()
        io.shutdownNow()
    }

    private var pendingInstallApk: File? = null

    override fun onResume() {
        super.onResume()
        val f = pendingInstallApk
        if (f != null && f.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                pendingInstallApk = null
                installApk(f)
            }
        }
    }

    // ------------------------------------------------------------------
    // 配色与尺寸辅助
    // ------------------------------------------------------------------

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun primaryColor(): Int =
        if (isDarkTheme) Color.parseColor("#4CC38A") else Color.parseColor("#2F8F5B")

    private fun bgColor(): Int =
        if (isDarkTheme) Color.parseColor("#14171D") else Color.parseColor("#F5F6F8")

    private fun surfaceColor(): Int =
        if (isDarkTheme) Color.parseColor("#1B1F27") else Color.parseColor("#FFFFFF")

    private fun surface2Color(): Int =
        if (isDarkTheme) Color.parseColor("#20242D") else Color.parseColor("#FAFBFC")

    private fun textColor(): Int =
        if (isDarkTheme) Color.parseColor("#EEF1F6") else Color.parseColor("#1F2430")

    private fun text2Color(): Int =
        if (isDarkTheme) Color.parseColor("#B6BDC9") else Color.parseColor("#4A5361")

    private fun text3Color(): Int =
        if (isDarkTheme) Color.parseColor("#828B99") else Color.parseColor("#8B93A1")

    private fun borderColor(): Int =
        if (isDarkTheme) Color.parseColor("#2B313C") else Color.parseColor("#E3E6EA")

    private fun primarySoftColor(): Int =
        if (isDarkTheme) Color.parseColor("#1D2F27") else Color.parseColor("#E8F5EE")

    // ------------------------------------------------------------------
    // 界面搭建
    // ------------------------------------------------------------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
        }

        // ---- 1. 顶部工具栏 ----
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(primaryColor())
            setPadding(dp(14f), dp(10f), dp(8f), dp(10f))
        }
        val title = TextView(this).apply {
            text = "单词混记"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        fun toolBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            background = null
            setPadding(dp(8f), 0, dp(8f), 0)
            setOnClickListener { onClick() }
        }

        bar.addView(toolBtn("+ 加词") { showAddWord() })
        bar.addView(toolBtn("同步") { syncNow() })
        bar.addView(toolBtn("菜单") {
            handleMenuEasterEggClick(null)
            showMainMenu()
        })
        root.addView(bar)

        // ---- 2. 搜索框与遮挡控制行 ----
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(4f))
        }

        searchBox = EditText(this).apply {
            hint = "搜索单词或释义"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor())
            setHintTextColor(text3Color())
            setSingleLine()
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(surfaceColor())
                setStroke(dp(1f), borderColor())
            }
            background = bg
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    render()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        searchRow.addView(searchBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        maskBtn = Button(this).apply {
            text = if (maskMeanings) "👁 遮挡" else "👁 显示"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(primaryColor())
            val btnBg = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(surfaceColor())
                setStroke(dp(1f), borderColor())
            }
            background = btnBg
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            setOnClickListener {
                if (maskMeanings) {
                    revealed.addAll(lib.aliveEntries().map { Items.id(it) })
                    maskMeanings = false
                } else {
                    maskMeanings = true
                    revealed.clear()
                }
                text = if (maskMeanings) "👁 遮挡" else "👁 显示"
                render()
            }
        }
        searchRow.addView(maskBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.leftMargin = dp(8f) })
        root.addView(searchRow)

        // ---- 3. 分组筛选标签条（横向滚动）----
        val groupScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(12f), dp(4f), dp(12f), dp(6f))
        }
        groupChipsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        groupScroll.addView(groupChipsContainer)
        root.addView(groupScroll)

        // ---- 4. 词条卡片列表 ----
        listView = ListView(this).apply {
            divider = null
            setBackgroundColor(bgColor())
        }
        adapter = CardAdapter(
            ctx = this,
            rows = emptyList(),
            isDarkTheme = { isDarkTheme },
            onReveal = { row -> onRowTapped(row) },
            onSpeak = { word -> Speech.say(this, word) },
        )
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.getItem(position) as CardAdapter.Row
            if (row.isGroup && row.group != null) {
                showGroupMenu(row.group)
            } else {
                onRowTapped(row)
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            val row = adapter.getItem(position) as CardAdapter.Row
            if (row.isGroup && row.group != null) {
                showGroupMenu(row.group)
                true
            } else if (!row.isGroup && row.entry != null) {
                showEntryMenu(row.entry)
                true
            } else false
        }
        root.addView(listView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---- 5. 底部状态栏 ----
        statusBar = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12f), dp(6f), dp(12f), dp(6f))
            setTextColor(text3Color())
            setBackgroundColor(bgColor())
        }
        root.addView(statusBar)
        return root
    }

    private fun status(text: String) {
        statusBar.text = text
    }

    private fun groupEntryOrderTime(g: Map<String, Any?>): String {
        val entries = lib.entriesOf(Items.id(g))
        if (entries.isNotEmpty()) {
            return entries.map { e ->
                val syncStamp = (e["_syncKey"] as? List<*>)?.firstOrNull() as? String ?: ""
                AppJson.str(e, "updatedAt")
                    .ifEmpty { AppJson.str(e, "createdAt") }
                    .ifEmpty { syncStamp }
            }.maxOrNull() ?: (
                AppJson.str(g, "updatedAt").ifEmpty { AppJson.str(g, "createdAt") }
            )
        }
        val syncStamp = (g["_syncKey"] as? List<*>)?.firstOrNull() as? String ?: ""
        return AppJson.str(g, "updatedAt")
            .ifEmpty { AppJson.str(g, "createdAt") }
            .ifEmpty { syncStamp }
    }

    private fun sortedAliveGroups(): List<Map<String, Any?>> {
        val groups = lib.aliveGroups()
        return when (groupSortMode) {
            GroupSortMode.DEFAULT -> groups.sortedWith { g1, g2 ->
                // 从新到旧 (降序)
                groupEntryOrderTime(g2).compareTo(groupEntryOrderTime(g1))
            }
            GroupSortMode.WORD_COUNT_DESC -> groups.sortedWith(
                compareByDescending<Map<String, Any?>> { lib.entriesOf(Items.id(it)).size }
                    .thenBy { AppJson.str(it, "name").lowercase() }
            )
            GroupSortMode.NAME_ASC -> {
                val collator = java.text.Collator.getInstance(java.util.Locale.getDefault())
                groups.sortedWith { g1, g2 ->
                    collator.compare(AppJson.str(g1, "name"), AppJson.str(g2, "name"))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 分组筛选标签条渲染
    // ------------------------------------------------------------------

    private fun renderGroupsBar() {
        groupChipsContainer.removeAllViews()
        val groups = sortedAliveGroups()

        fun makeChip(label: String, selected: Boolean, onClick: () -> Unit): View {
            return TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(14f).toFloat()
                    if (selected) {
                        setColor(primaryColor())
                    } else {
                        setColor(surfaceColor())
                        setStroke(dp(1f), borderColor())
                    }
                }
                background = bg
                setTextColor(if (selected) Color.WHITE else text2Color())
                if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { onClick() }
            }
        }

        // 排序方式快捷标签（点击弹出排序方式选择）
        val sortChip = TextView(this).apply {
            text = "⇅ " + groupSortMode.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10f), dp(4f), dp(10f), dp(4f))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(surfaceColor())
                setStroke(dp(1f), borderColor())
            }
            background = bg
            setTextColor(primaryColor())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { showSortModeDialog() }
        }
        groupChipsContainer.addView(sortChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.rightMargin = dp(6f) })

        // "全部" 标签
        val allSelected = activeGroupId == null
        val allChip = makeChip("全部", allSelected) {
            if (activeGroupId != null) {
                activeGroupId = null
                render()
                renderGroupsBar()
            }
        }
        groupChipsContainer.addView(allChip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.rightMargin = dp(6f) })

        // 各分组标签
        for (g in groups) {
            val gid = Items.id(g)
            val gname = AppJson.str(g, "name")
            val isSelected = activeGroupId == gid
            val chip = makeChip(gname, isSelected) {
                activeGroupId = if (activeGroupId == gid) null else gid
                render()
                renderGroupsBar()
            }
            chip.setOnLongClickListener {
                showGroupMenu(g)
                true
            }
            groupChipsContainer.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.rightMargin = dp(6f) })
        }
    }

    // ------------------------------------------------------------------
    // 词条卡片列表渲染
    // ------------------------------------------------------------------

    private fun render() {
        val rows = ArrayList<CardAdapter.Row>()
        val q = query.lowercase()
        val allAliveGroups = sortedAliveGroups()

        // 如果选中了某个分组，只显示该分组
        val targetGroups = if (activeGroupId != null) {
            allAliveGroups.filter { Items.id(it) == activeGroupId }
        } else {
            allAliveGroups
        }

        for (g in targetGroups) {
            val gid = Items.id(g)
            var items = lib.entriesOf(gid).sortedWith { e1, e2 ->
                val t1 = AppJson.str(e1, "createdAt").ifEmpty { (e1["_syncKey"] as? List<*>)?.firstOrNull() as? String ?: "" }
                val t2 = AppJson.str(e2, "createdAt").ifEmpty { (e2["_syncKey"] as? List<*>)?.firstOrNull() as? String ?: "" }
                t1.compareTo(t2)
            }

            if (q.isNotEmpty()) {
                items = items.filter {
                    Items.word(it).lowercase().contains(q) ||
                            Items.meaning(it).lowercase().contains(q)
                }
            }
            // 筛选单个分组时不显示标题（与桌面端一致）；显示全部时若有多个组则显示标题
            val showGroup = activeGroupId == null && (q.isNotEmpty() || allAliveGroups.size > 1)
            if (showGroup) {
                rows.add(CardAdapter.Row(true, label = AppJson.str(g, "name"), group = g))
            }
            for (e in items) {
                val id = Items.id(e)
                rows.add(
                    CardAdapter.Row(
                        isGroup = false,
                        entry = e,
                        ipa = Phonetics.ipa(Items.word(e)),
                        hidden = maskMeanings && !revealed.contains(id),
                        isChild = AppJson.str(e, "parentId").isNotEmpty(),
                    )
                )
            }
        }
        adapter.submit(rows)

        val totalWords = lib.aliveEntries().size
        val countInfo = if (activeGroupId != null) {
            val curName = allAliveGroups.firstOrNull { Items.id(it) == activeGroupId }?.let { AppJson.str(it, "name") } ?: ""
            "$curName · ${rows.filter { !it.isGroup }.size} 词"
        } else {
            "${allAliveGroups.size} 组 · $totalWords 词"
        }
        status("$countInfo    " + Syncer.statusText(state, cfg))
    }

    private fun onRowTapped(row: CardAdapter.Row) {
        val e = row.entry ?: return
        if (maskMeanings && row.hidden) {
            revealed.add(Items.id(e))
            render()
        }
    }

    // ------------------------------------------------------------------
    // 菜单项（默认普通模式，连续点击《菜单》7次开启调试维护模式）
    // ------------------------------------------------------------------

    private var isDebugMode = false
    private var menuClickCount = 0
    private var lastMenuClickTime = 0L

    private fun handleMenuEasterEggClick(dialogToDismiss: DialogInterface? = null) {
        val now = System.currentTimeMillis()
        if (now - lastMenuClickTime > 2500L) {
            menuClickCount = 1
        } else {
            menuClickCount++
        }
        lastMenuClickTime = now

        if (!isDebugMode) {
            if (menuClickCount >= 7) {
                isDebugMode = true
                menuClickCount = 0
                toast("已开启调试维护模式 🛠️")
                dialogToDismiss?.dismiss()
                showMainMenu()
            } else if (menuClickCount in 4..6) {
                toast("再点击 ${7 - menuClickCount} 次进入调试模式")
            }
        } else {
            if (menuClickCount >= 7) {
                isDebugMode = false
                menuClickCount = 0
                toast("已退出调试模式")
                dialogToDismiss?.dismiss()
                showMainMenu()
            }
        }
    }

    private fun showMainMenu() {
        val themeLabel = if (isDarkTheme) "切换为浅色模式" else "切换为深色模式"
        val sortLabel = "词组排序（当前：${groupSortMode.label}）"

        val normalItems = mutableListOf(
            "新建分组",
            "管理分组",
            sortLabel,
            themeLabel,
            "检查更新"
        )

        val debugItems = if (isDebugMode) {
            listOf(
                "── 调试维护选项 ──",
                "从云端重置全量词库（清空本地缓存）",
                "全量上传本地词库到云端",
                "同步设置",
                "关闭调试模式"
            )
        } else emptyList()

        val allItems = (normalItems + debugItems).toTypedArray()

        var currentDialog: AlertDialog? = null

        val titleView = TextView(this).apply {
            text = if (isDebugMode) "菜单 🛠️ (调试模式)" else "菜单"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(24f), dp(20f), dp(24f), dp(10f))
            setTextColor(if (isDebugMode) Color.parseColor("#d97706") else textColor())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                handleMenuEasterEggClick(currentDialog)
            }
        }

        currentDialog = AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setItems(allItems) { _, which ->
                val selected = allItems[which]
                when (selected) {
                    "新建分组" -> showAddGroup()
                    "管理分组" -> showManageGroupsDialog()
                    sortLabel -> showSortModeDialog()
                    themeLabel -> toggleTheme()
                    "检查更新" -> checkUpdateIfAny(silent = false)
                    "从云端重置全量词库（清空本地缓存）" -> promptCleanResetFromServer()
                    "全量上传本地词库到云端" -> forceFullUpload()
                    "同步设置" -> showSettings()
                    "关闭调试模式" -> {
                        isDebugMode = false
                        toast("已关闭调试模式")
                    }
                    else -> {} // 忽略分隔条
                }
            }
            .setNegativeButton("关闭", null)
            .create()

        currentDialog.show()
    }

    private fun promptCleanResetFromServer() {
        AlertDialog.Builder(this)
            .setTitle("重置并统一数据")
            .setMessage("此操作将清空本地全部历史缓存，直接向云服务器拉取权威词库进行统一对齐（共 66 组 133 词）。是否继续？")
            .setPositiveButton("立即重置") { _, _ -> forceCleanResetFromServer(silent = false) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun forceCleanResetFromServer(silent: Boolean = false) {
        if (!cfg.configured) {
            if (!silent) showSettings()
            return
        }
        val progress = if (!silent) {
            AlertDialog.Builder(this)
                .setTitle("全量数据重置与统一")
                .setMessage("正在清空本地缓存，从服务器拉取权威词库...")
                .setCancelable(false)
                .show()
        } else null

        io.execute {
            var errMsg: String? = null
            var groupCount = 0
            var wordCount = 0
            try {
                // 1. 请求服务端权威快照
                val (code, text) = ServerApi.request(cfg, "/api/transfer/export", "GET")
                if (code != 200) {
                    errMsg = "服务器返回 HTTP $code"
                } else {
                    val root = AppJson.parseObject(text)
                    val rawGroups = AppJson.list(root, "groups")
                    val rawEntries = AppJson.list(root, "entries")

                    // 2. 获取当前同步 head 游标，使后续增量从最新版本开始，避免历史脏操作重放
                    val (_, pullText) = ServerApi.request(cfg, "/sync/pull?since=0&limit=1", "GET")
                    val pullObj = try { AppJson.parseObject(pullText) } catch (_: Exception) { emptyMap() }
                    val head = AppJson.num(pullObj, "head")

                    // 3. 彻底清空并重构本地 Library
                    lib.groups.clear()
                    lib.entries.clear()

                    for (gItem in rawGroups) {
                        val m = gItem as? Map<String, Any?> ?: continue
                        val gid = AppJson.str(m, "id")
                        val name = AppJson.str(m, "name")
                        val note = AppJson.str(m, "note")
                        val cAt = AppJson.str(m, "createdAt").ifEmpty { Clock.nowIso() }
                        val uAt = AppJson.str(m, "updatedAt").ifEmpty { cAt }
                        val gMap: LinkedHashMap<String, Any?> = linkedMapOf(
                            "id" to gid,
                            "name" to name,
                            "note" to note,
                            "createdAt" to cAt,
                            "updatedAt" to uAt,
                        )
                        lib.groups.add(gMap)
                    }

                    for (eItem in rawEntries) {
                        val m = eItem as? Map<String, Any?> ?: continue
                        val eid = AppJson.str(m, "id")
                        val gid = AppJson.str(m, "groupId")
                        val word = AppJson.str(m, "word")
                        val meaning = AppJson.str(m, "meaning")
                        val note = AppJson.str(m, "note")
                        val cAt = AppJson.str(m, "createdAt").ifEmpty { Clock.nowIso() }
                        val uAt = AppJson.str(m, "updatedAt").ifEmpty { cAt }
                        val eMap: LinkedHashMap<String, Any?> = linkedMapOf(
                            "id" to eid,
                            "groupId" to gid,
                            "fields" to linkedMapOf<String, Any?>(
                                F_WORD to word,
                                F_MEANING to meaning,
                                F_NOTE to note
                            ),
                            "createdAt" to cAt,
                            "updatedAt" to uAt,
                        )
                        lib.entries.add(eMap)
                    }

                    // 4. 重置同步状态（彻底清空旧 pending、seenOps，对齐 head 游标）
                    state.pending.clear()
                    state.seenOps.clear()
                    state.lastVersion = head
                    state.lastSyncAt = Clock.nowIso()
                    state.lastError = ""

                    // 5. 持久化到本地磁盘
                    store.saveLibrary(lib)
                    store.saveState(state)

                    groupCount = lib.aliveGroups().size
                    wordCount = lib.aliveEntries().size
                }
            } catch (e: Exception) {
                errMsg = e.message ?: "网络或数据解析异常"
            }

            ui.post {
                progress?.dismiss()
                if (errMsg != null) {
                    if (!silent) toast("重置失败：$errMsg")
                } else {
                    revealed.clear()
                    activeGroupId = null
                    render()
                    renderGroupsBar()
                    toast("本地已清理并与服务器完成统一：共 $groupCount 组，$wordCount 词！")
                }
            }
        }
    }

    private fun forceFullUpload() {
        state.seenOps.clear()
        state.pending.clear()
        toast("正在全量上传本地全部词条与分组...")
        syncNow()
    }

    private fun toggleTheme() {
        isDarkTheme = !isDarkTheme
        getSharedPreferences("wordmix", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("dark_theme", isDarkTheme)
            .apply()
        recreate()
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    private fun syncNow() {
        if (!cfg.configured) {
            showSettings()
            return
        }
        if (syncing) {
            toast("正在同步中…")
            return
        }
        syncing = true
        status("正在同步…")
        io.execute {
            val r = Syncer.syncOnce(cfg, state, lib)
            if (r.changed) store.saveLibrary(lib)
            store.saveState(state)
            ui.post {
                syncing = false
                if (r.ok) {
                    render()
                    renderGroupsBar()
                    val msg = when {
                        r.pushed > 0 && (r.pulled > 0 || r.applied > 0) -> "同步成功：上传 ${r.pushed} 项，更新 ${r.applied} 项"
                        r.pushed > 0 -> "同步成功：已向云端上传 ${r.pushed} 项词条！"
                        r.pulled > 0 || r.applied > 0 -> "同步完成：更新 ${r.applied} 项"
                        else -> "同步完成：已是最新"
                    }
                    toast(msg)
                } else {
                    status("同步失败：" + r.error)
                    toast("同步失败：" + r.error)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 自更新
    // ------------------------------------------------------------------

    private fun checkUpdateIfAny(silent: Boolean) {
        if (!cfg.configured) return
        io.execute {
            val info = Updater.check(cfg)
            ui.post {
                if (info == null) {
                    if (!silent) toast("已经是最新版")
                    return@post
                }
                if (info.versionCode <= appVersionCode) {
                    if (!silent) toast("已经是最新版（v${info.versionName}）")
                    return@post
                }
                promptInstall(info)
            }
        }
    }

    private fun promptInstall(info: UpdateInfo) {
        val mb = String.format("%.2f", info.size.toDouble() / 1024 / 1024)
        val msg = "发现新版本：v${info.versionName}（${mb} MB）\n\n" +
                (if (info.changelog.isNotEmpty()) "更新说明：\n${info.changelog}\n\n" else "") +
                "是否立即下载安装？"
        AlertDialog.Builder(this)
            .setTitle("软件更新")
            .setMessage(msg)
            .setPositiveButton("下载安装") { _, _ -> startDownload(info) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startDownload(info: UpdateInfo) {
        val targetDir = File(filesDir, "apk").apply { mkdirs() }
        val progress = AlertDialog.Builder(this)
            .setTitle("正在下载更新")
            .setMessage("准备中…")
            .setCancelable(false)
            .create()
        progress.show()

        io.execute {
            var file: File? = null
            var errorMsg: String? = null
            try {
                file = Updater.download(info, targetDir) { done, total ->
                    ui.post {
                        val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                        val doneMb = String.format("%.1f", done.toDouble() / 1024 / 1024)
                        val totalMb = String.format("%.1f", total.toDouble() / 1024 / 1024)
                        progress.setMessage("已下载 $doneMb MB / $totalMb MB ($pct%)")
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: "下载失败"
            }
            ui.post {
                progress.dismiss()
                if (file != null && file.exists()) {
                    installApk(file)
                } else {
                    toast(errorMsg ?: "下载或校验失败，请重试")
                }
            }
        }
    }

    private fun logInstall(msg: String) {
        val logDir = File(filesDir, "logs").apply { mkdirs() }
        val logFile = File(logDir, "install.log")
        val line = "[" + Clock.nowIso() + "] " + msg + "\n"
        try {
            logFile.appendText(line)
        } catch (_: Exception) {}
        Log.i("WordMixInstall", msg)
    }

    private fun installApk(file: File) {
        logInstall("尝试安装 APK: ${file.absolutePath}, 存在: ${file.exists()}, 大小: ${file.length()}")

        if (!file.exists() || !file.isFile || file.length() < 50_000L) {
            val err = "APK 文件不存在或大小不正确 (${file.length()} 字节)"
            logInstall("错误: $err")
            AlertDialog.Builder(this)
                .setTitle("安装失败")
                .setMessage(err)
                .setPositiveButton("确定", null)
                .show()
            reportInstallError(file, err, null)
            return
        }

        // 校验 APK 文件头为 ZIP (PK\x03\x04)
        try {
            file.inputStream().use { input ->
                val b = ByteArray(4)
                val read = input.read(b)
                if (read < 4 || b[0] != 0x50.toByte() || b[1] != 0x4B.toByte() || b[2] != 0x03.toByte() || b[3] != 0x04.toByte()) {
                    val err = "APK 文件头不是 ZIP 格式（非完整安装包）"
                    logInstall("错误: $err")
                    AlertDialog.Builder(this)
                        .setTitle("安装包解析错误")
                        .setMessage(err + "，请尝试重新下载。")
                        .setPositiveButton("确定", null)
                        .show()
                    reportInstallError(file, err, null)
                    return
                }
            }
        } catch (e: Exception) {
            logInstall("读取文件头失败: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                pendingInstallApk = file
                logInstall("未获得未知应用安装权限，弹窗引导用户开启")
                AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("为完成应用自动更新，请在接下来的系统设置中允许本应用安装应用。\n开启后返回本应用即可继续安装。")
                    .setPositiveButton("前往设置") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:$packageName")
                            })
                        } catch (e: Exception) {
                            toast("打开设置失败: ${e.message}")
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }

        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            logInstall("获取到 FileProvider URI: $uri")

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (resolveInfo in resInfoList) {
                grantUriPermission(resolveInfo.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)
            logInstall("已成功调用系统安装器 startActivity")
        } catch (e: Throwable) {
            val errMsg = e.javaClass.simpleName + ": " + (e.message ?: "未知异常")
            logInstall("调用安装器异常: $errMsg\n${Log.getStackTraceString(e)}")
            AlertDialog.Builder(this)
                .setTitle("安装器启动失败")
                .setMessage("无法唤起系统安装器：\n$errMsg\n\n文件路径：${file.absolutePath}\n错误信息已自动上报给服务器便于排查。")
                .setPositiveButton("复制文件路径") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("apk", file.absolutePath))
                    toast("已复制文件路径")
                }
                .setNegativeButton("关闭", null)
                .show()

            reportInstallError(file, errMsg, e)
        }
    }

    private fun reportInstallError(file: File, message: String, throwable: Throwable?) {
        io.execute {
            val data = mutableMapOf<String, Any?>(
                "type" to "install_error",
                "deviceId" to state.deviceId,
                "model" to Build.MODEL,
                "manufacturer" to Build.MANUFACTURER,
                "sdk" to Build.VERSION.SDK_INT,
                "versionCode" to appVersionCode,
                "filePath" to file.absolutePath,
                "fileSize" to file.length(),
                "error" to message,
                "stackTrace" to if (throwable != null) Log.getStackTraceString(throwable) else "",
                "time" to Clock.nowIso(),
            )
            ServerApi.reportError(cfg, data)
        }
    }

    // ------------------------------------------------------------------
    // 同步设置
    // ------------------------------------------------------------------

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        }
        val urlIn = EditText(this).apply {
            hint = "http://服务器IP:18080"
            setText(cfg.url)
            setSingleLine()
        }
        val tokIn = EditText(this).apply {
            hint = "访问令牌"
            setText(cfg.token)
            setSingleLine()
        }
        box.addView(TextView(this).apply { text = "服务器地址" })
        box.addView(urlIn)
        box.addView(TextView(this).apply { text = "访问令牌（token）"; setPadding(0, dp(10f), 0, 0) })
        box.addView(tokIn)
        box.addView(TextView(this).apply {
            text = "token 在服务器上执行 cat /var/lib/wordmix/token.txt 查看"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(text3Color())
        })

        val dlg = AlertDialog.Builder(this)
            .setTitle("同步设置")
            .setView(box)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .setNeutralButton("测试连接", null)
            .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                cfg.url = urlIn.text.toString().trim().trimEnd('/')
                cfg.token = tokIn.text.toString().trim()
                if (cfg.url.isNotEmpty() &&
                    !cfg.url.startsWith("http://") && !cfg.url.startsWith("https://")
                ) {
                    toast("地址要以 http:// 或 https:// 开头")
                    return@setOnClickListener
                }
                store.saveConfig(cfg)
                dlg.dismiss()
                render()
                toast("已保存")
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val test = SyncConfig(
                    urlIn.text.toString().trim().trimEnd('/'),
                    tokIn.text.toString().trim()
                )
                if (!test.configured) {
                    toast("请先填地址和 token")
                    return@setOnClickListener
                }
                toast("正在测试…")
                io.execute {
                    val p = ServerApi.ping(test)
                    val result = if (!AppJson.bool(p, "ok")) {
                        "连不上：" + AppJson.str(p, "error")
                    } else {
                        var msg = "服务器可达 ✓\n已有改动 " + AppJson.num(p, "ops") + " 条"
                        try {
                            val (code, _) = ServerApi.request(test, "/sync/pull?since=0")
                            msg += if (code == 200) "\ntoken 可用" else "\ntoken 有问题（HTTP $code）"
                        } catch (e: Exception) {
                            msg += "\n" + ServerApi.describe(e)
                        }
                        msg
                    }
                    ui.post {
                        AlertDialog.Builder(this)
                            .setTitle("连接测试")
                            .setMessage(result)
                            .setPositiveButton("好", null)
                            .show()
                    }
                }
            }
        }
        dlg.show()
    }

    // ------------------------------------------------------------------
    // 新建分组
    // ------------------------------------------------------------------

    private fun showAddGroup() {
        val input = EditText(this).apply { hint = "分组名称" }
        AlertDialog.Builder(this)
            .setTitle("新建分组")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val g = Items.newGroup(name)
                lib.groups.add(g)
                Syncer.record(state, "group.add", g)
                store.saveLibrary(lib)
                store.saveState(state)
                render()
                renderGroupsBar()
                syncNow()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 分组管理、编辑与删除
    // ------------------------------------------------------------------

    private fun showSortModeDialog() {
        val options = arrayOf(
            "默认（按单词修改/添加 从新到旧）",
            "按词组包含的单词数降序",
            "按词组名称字典序 (A-Z)"
        )
        AlertDialog.Builder(this)
            .setTitle("词组排序方式")
            .setSingleChoiceItems(options, groupSortMode.code) { dialog, which ->
                val newMode = when (which) {
                    1 -> GroupSortMode.WORD_COUNT_DESC
                    2 -> GroupSortMode.NAME_ASC
                    else -> GroupSortMode.DEFAULT
                }
                if (newMode != groupSortMode) {
                    groupSortMode = newMode
                    getSharedPreferences("wordmix", Context.MODE_PRIVATE)
                        .edit()
                        .putInt("group_sort_mode", newMode.code)
                        .apply()
                    render()
                    renderGroupsBar()
                    toast("词组已按「${newMode.label}」排序")
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showManageGroupsDialog() {
        val groups = sortedAliveGroups()
        if (groups.isEmpty()) {
            toast("暂无分组")
            return
        }
        val items = groups.map { g ->
            val count = lib.entriesOf(Items.id(g)).size
            "${AppJson.str(g, "name")}  ($count 词)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("管理分组（点击编辑或删除）")
            .setItems(items) { _, which ->
                showGroupMenu(groups[which])
            }
            .setPositiveButton("新建分组") { _, _ ->
                showAddGroup()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showGroupMenu(g: Map<String, Any?>) {
        val group = g as? MutableMap<String, Any?> ?: return
        val gid = Items.id(group)
        val gname = AppJson.str(group, "name")
        val wordsCount = lib.entriesOf(gid).size
        val options = arrayOf("重命名分组", "删除分组")

        AlertDialog.Builder(this)
            .setTitle("分组：$gname ($wordsCount 词)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditGroup(group)
                    1 -> confirmDeleteGroup(group)
                }
            }
            .show()
    }

    private fun showEditGroup(group: MutableMap<String, Any?>) {
        val oldName = AppJson.str(group, "name")
        val input = EditText(this).apply {
            setText(oldName)
            setSelection(oldName.length)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("重命名分组")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == oldName) return@setPositiveButton
                group["name"] = newName
                group["updatedAt"] = Clock.nowIso()
                Syncer.record(state, "group.update", group)
                store.saveLibrary(lib)
                store.saveState(state)
                render()
                renderGroupsBar()
                syncNow()
                toast("分组已重命名为「$newName」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDeleteGroup(group: MutableMap<String, Any?>) {
        val gid = Items.id(group)
        val gname = AppJson.str(group, "name")
        val entries = lib.entriesOf(gid)
        val count = entries.size

        if (count == 0) {
            AlertDialog.Builder(this)
                .setTitle("删除分组")
                .setMessage("确定删除空分组「$gname」？")
                .setPositiveButton("删除") { _, _ ->
                    deleteGroupInternal(group, deleteEntries = false)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            val options = arrayOf(
                "删除分组及组内全部 $count 个词",
                "仅删除分组，保留单词（移入其它组）"
            )
            AlertDialog.Builder(this)
                .setTitle("删除分组「$gname」")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> deleteGroupInternal(group, deleteEntries = true)
                        1 -> promptMoveAndGroupDelete(group, entries)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun promptMoveAndGroupDelete(group: MutableMap<String, Any?>, entries: List<Map<String, Any?>>) {
        val gid = Items.id(group)
        val otherGroups = lib.aliveGroups().filter { Items.id(it) != gid }
        if (otherGroups.isEmpty()) {
            val defaultGroup = Items.newGroup("默认分组")
            lib.groups.add(defaultGroup)
            Syncer.record(state, "group.add", defaultGroup)
            val newGid = Items.id(defaultGroup)
            val now = Clock.nowIso()
            for (e in entries) {
                val m = e as? MutableMap<String, Any?> ?: continue
                m["groupId"] = newGid
                m["updatedAt"] = now
                Syncer.record(state, "entry.update", m)
            }
            deleteGroupInternal(group, deleteEntries = false)
            toast("已删除分组，单词已移入「默认分组」")
        } else {
            val names = otherGroups.map { AppJson.str(it, "name") }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("将 ${entries.size} 个单词移入哪个分组？")
                .setItems(names) { _, which ->
                    val targetGroup = otherGroups[which]
                    val targetGid = Items.id(targetGroup)
                    val targetName = AppJson.str(targetGroup, "name")
                    val now = Clock.nowIso()
                    for (e in entries) {
                        val m = e as? MutableMap<String, Any?> ?: continue
                        m["groupId"] = targetGid
                        m["updatedAt"] = now
                        Syncer.record(state, "entry.update", m)
                    }
                    deleteGroupInternal(group, deleteEntries = false)
                    toast("已删除分组，单词已移入「$targetName」")
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun deleteGroupInternal(group: MutableMap<String, Any?>, deleteEntries: Boolean) {
        val gid = Items.id(group)
        val now = Clock.nowIso()

        group["deleted"] = true
        group["deletedAt"] = now
        group["updatedAt"] = now
        Syncer.record(state, "group.remove", group)

        if (deleteEntries) {
            val entries = lib.entriesOf(gid)
            for (e in entries) {
                val m = e as? MutableMap<String, Any?> ?: continue
                m["deleted"] = true
                m["deletedAt"] = now
                m["updatedAt"] = now
                Syncer.record(state, "entry.remove", m)
            }
        }

        if (activeGroupId == gid) {
            activeGroupId = null
        }

        store.saveLibrary(lib)
        store.saveState(state)
        render()
        renderGroupsBar()
        syncNow()
        toast("已删除分组")
    }

    // ------------------------------------------------------------------
    // 新增词条（含相似词推荐并入）
    // ------------------------------------------------------------------

    private fun showAddWord() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(4f))
        }

        val wordIn = EditText(this).apply {
            hint = "单词，例如 garment"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setSingleLine()
        }
        val meanIn = EditText(this).apply {
            hint = "释义，例如 衣服，服装"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setSingleLine()
        }
        box.addView(wordIn)
        box.addView(meanIn)

        val hintLabel = TextView(this).apply {
            text = "输入单词后，下面会自动推荐可并入的组："
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(text3Color())
            setPadding(0, dp(6f), 0, dp(4f))
        }
        box.addView(hintLabel)

        // 候选项可滚动列表
        val candScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170f)
            ).also { it.topMargin = dp(2f); it.bottomMargin = dp(4f) }
            val scrollBg = GradientDrawable().apply {
                cornerRadius = dp(6f).toFloat()
                setColor(surface2Color())
                setStroke(dp(1f), borderColor())
            }
            background = scrollBg
        }
        val candContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        candScroll.addView(candContainer)
        box.addView(candScroll)

        // "新建一组" 勾选框 + 自定义组名
        val newGroupCb = CheckBox(this).apply {
            text = "新建一组（这个单词单独构成一组）"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor())
            setPadding(dp(4f), 0, 0, 0)
        }
        val newGroupIn = EditText(this).apply {
            hint = "留空则用单词本身作为组名"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setSingleLine()
            visibility = View.GONE
        }
        box.addView(newGroupCb)
        box.addView(newGroupIn)

        var selectedCandidate: Similarity.Candidate? = null
        var candidateRowViews = mutableListOf<Pair<Similarity.Candidate, Triple<View, TextView, TextView>>>()

        fun updateCandidateHighlight() {
            for ((cand, views) in candidateRowViews) {
                val (rowView, markView, headView) = views
                val isSelected = selectedCandidate === cand
                val rowBg = GradientDrawable().apply {
                    cornerRadius = dp(4f).toFloat()
                    setColor(if (isSelected) primarySoftColor() else surfaceColor())
                }
                rowView.background = rowBg
                markView.text = if (isSelected) "✓" else ""
                headView.setTextColor(if (isSelected) primaryColor() else textColor())
            }
        }

        fun renderCandidates(candidates: List<Similarity.Candidate>) {
            candContainer.removeAllViews()
            candidateRowViews.clear()

            if (candidates.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = if (wordIn.text.toString().trim().length < 2)
                        "输入 2 个字母以上将自动推荐可并入的组"
                    else
                        "（没有找到相似的词 —— 可以勾选下方的「新建一组」）"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(text3Color())
                    setPadding(dp(8f), dp(16f), dp(8f), dp(16f))
                    gravity = Gravity.CENTER
                }
                candContainer.addView(emptyTv)
                return
            }

            for (cand in candidates) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
                    val rowBg = GradientDrawable().apply {
                        cornerRadius = dp(4f).toFloat()
                        setColor(if (selectedCandidate === cand) primarySoftColor() else surfaceColor())
                    }
                    background = rowBg
                }

                val txtCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                }

                val word = Items.word(cand.entry)
                val ipa = Phonetics.ipa(word)
                val headText = if (ipa.isNotEmpty()) "$word   $ipa" else word
                val headView = TextView(this).apply {
                    text = headText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (selectedCandidate === cand) primaryColor() else textColor())
                }
                txtCol.addView(headView)

                val sibs = cand.siblings.map { Items.word(it) }.filter { it != word }
                var subText = "组：${cand.groupName}   相似度 ${(cand.score * 100).toInt()}%"
                if (sibs.isNotEmpty()) {
                    subText += "   同组：${sibs.take(3).joinToString("、")}"
                }
                val subView = TextView(this).apply {
                    text = subText
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(text3Color())
                }
                txtCol.addView(subView)

                row.addView(txtCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val markView = TextView(this).apply {
                    text = if (selectedCandidate === cand) "✓" else ""
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(primaryColor())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(4f), 0, dp(4f), 0)
                }
                row.addView(markView)

                row.setOnClickListener {
                    selectedCandidate = if (selectedCandidate === cand) null else cand
                    if (selectedCandidate != null && newGroupCb.isChecked) {
                        newGroupCb.isChecked = false
                        newGroupIn.visibility = View.GONE
                    }
                    updateCandidateHighlight()
                }

                candContainer.addView(row)
                candidateRowViews.add(Pair(cand, Triple(row, markView, headView)))
            }
        }

        wordIn.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val w = s?.toString()?.trim() ?: ""
                if (w.length >= 2) {
                    val list = Similarity.similarEntries(lib, w, limit = 8, minScore = 0.34)
                    renderCandidates(list)
                } else {
                    renderCandidates(emptyList())
                }
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        newGroupCb.setOnCheckedChangeListener { _, isChecked ->
            newGroupIn.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                selectedCandidate = null
                updateCandidateHighlight()
            }
        }

        renderCandidates(emptyList())

        AlertDialog.Builder(this)
            .setTitle("新增词条")
            .setView(box)
            .setPositiveButton("加入") { _, _ ->
                val word = wordIn.text.toString().trim()
                if (word.isEmpty()) {
                    toast("单词不能为空")
                    return@setPositiveButton
                }
                val meaning = meanIn.text.toString().trim()

                val targetGroupId: String
                if (newGroupCb.isChecked) {
                    // 新建分组
                    val gname = newGroupIn.text.toString().trim().ifEmpty { word }
                    var g = lib.aliveGroups().firstOrNull { AppJson.str(it, "name") == gname }
                    if (g == null) {
                        g = Items.newGroup(gname)
                        lib.groups.add(g)
                        Syncer.record(state, "group.add", g)
                    }
                    targetGroupId = Items.id(g)
                } else if (selectedCandidate != null) {
                    // 并入选中候选词所在分组
                    targetGroupId = selectedCandidate!!.groupId
                } else if (activeGroupId != null) {
                    // 并入当前正在查看的分组
                    targetGroupId = activeGroupId!!
                } else {
                    // 默认行为：以单词本身新建分组
                    val g = Items.newGroup(word)
                    lib.groups.add(g)
                    Syncer.record(state, "group.add", g)
                    targetGroupId = Items.id(g)
                }

                val e = Items.newEntry(targetGroupId, word, meaning)
                lib.entries.add(e)
                Syncer.record(state, "entry.add", e)
                store.saveLibrary(lib)
                store.saveState(state)
                revealed.add(Items.id(e))
                render()
                renderGroupsBar()
                syncNow()
                toast("已添加「$word」")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------
    // 编辑与删除
    // ------------------------------------------------------------------

    private fun showEntryMenu(e: Map<String, Any?>) {
        val editable = e as? MutableMap<String, Any?> ?: return
        val options = arrayOf("编辑", "朗读", "删除")
        AlertDialog.Builder(this)
            .setTitle(Items.word(e))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditEntry(editable)
                    1 -> Speech.say(this, Items.word(e))
                    2 -> confirmDelete(editable)
                }
            }
            .show()
    }

    private fun showEditEntry(e: MutableMap<String, Any?>) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
        }
        val wordIn = EditText(this).apply { setText(Items.word(e)) }
        val meanIn = EditText(this).apply { setText(Items.meaning(e)) }
        box.addView(wordIn)
        box.addView(meanIn)
        AlertDialog.Builder(this)
            .setTitle("编辑词条")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val f = AppJson.obj(e, "fields") ?: mutableMapOf()
                f[F_WORD] = wordIn.text.toString().trim()
                f[F_MEANING] = meanIn.text.toString().trim()
                e["fields"] = f
                e["updatedAt"] = Clock.nowIso()
                Syncer.record(state, "entry.update", e)
                store.saveLibrary(lib)
                store.saveState(state)
                render()
                syncNow()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(e: MutableMap<String, Any?>) {
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("确定删除「" + Items.word(e) + "」？")
            .setPositiveButton("删除") { _, _ ->
                e["deleted"] = true
                e["deletedAt"] = Clock.nowIso()
                e["updatedAt"] = e["deletedAt"]
                Syncer.record(state, "entry.remove", e)
                store.saveLibrary(lib)
                store.saveState(state)
                render()
                renderGroupsBar()
                syncNow()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
