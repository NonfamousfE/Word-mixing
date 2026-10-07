<template>
  <div class="app-container">
    <!-- Header -->
    <header class="header">
      <div class="header-inner">
        <div class="brand">
          <BookOpen :size="24" color="#2563eb" />
          <span>单词混记</span>
          <span class="badge">Web C-S v2.0</span>
        </div>

        <!-- Search Bar -->
        <div class="search-box">
          <Search class="search-icon" :size="18" />
          <input
            v-model="searchQuery"
            type="text"
            placeholder="搜索单词、中文释义或分组... (快捷键 /)"
            ref="searchInputRef"
            @input="onSearchInput"
          />
        </div>

        <!-- Actions -->
        <div class="actions">
          <button class="btn" @click="toggleGlobalMask" :title="globalMask ? '全部揭开' : '全部遮挡'">
            <EyeOff v-if="globalMask" :size="16" />
            <Eye v-else :size="16" />
            <span>{{ globalMask ? '揭开释义' : '遮挡释义' }}</span>
          </button>

          <!-- ⭐ 核心操作：新增词条（而不是新增分组！） -->
          <button class="btn btn-primary" @click="openAddWordModal">
            <Plus :size="16" />
            <span>新增词条</span>
          </button>

          <button class="btn" @click="openTransferModal" title="导入与导出词库">
            <ArrowUpDown :size="16" />
            <span>备份与恢复</span>
          </button>

          <a class="btn" href="/app/download/wordmix.apk" download title="下载安卓客户端 APK">
            <Smartphone :size="16" />
            <span>安卓端下载</span>
          </a>

          <div v-if="currentUser" class="user-menu">
            <button class="btn" @click="logout" title="退出登录">
              <UserCheck :size="16" />
              <span>{{ currentUser.username }}</span>
            </button>
          </div>
          <div v-else>
            <button class="btn btn-primary" @click="showAuthModal = true">
              <LogIn :size="16" />
              <span>登录</span>
            </button>
          </div>
        </div>
      </div>
    </header>

    <!-- Main Content -->
    <main class="main">
      <!-- 搜索与分组筛选条 (1:1 对齐安卓原生设计) -->
      <div class="groups-filter-bar">
        <!-- 词组排序按钮 -->
        <button class="chip-btn chip-sort" @click="showSortDialog = true" title="点击更改词组排序方式">
          <span>⇅ {{ sortModeLabel }}</span>
        </button>

        <!-- "全部" 标签 -->
        <button
          class="chip-pill"
          :class="{ active: activeGroupId === null }"
          @click="selectGroupChip(null)"
        >
          全部
        </button>

        <!-- 各分组胶囊标签 (横向平滑滚动) -->
        <div class="chips-scroll-wrap">
          <button
            v-for="chip in allGroupChips"
            :key="chip.id"
            class="chip-pill"
            :class="{ active: activeGroupId === chip.id }"
            @click="selectGroupChip(chip.id)"
          >
            {{ chip.name }}
          </button>
        </div>
      </div>

      <div class="status-bar">
        <div>
          共 <strong>{{ totalGroups }}</strong> 个混淆词组，<strong>{{ totalEntries }}</strong> 个单词
          <span v-if="activeGroupId" style="color: #15803d; margin-left: 8px; font-weight: 600;">
            (筛选词组: "{{ allGroupChips.find(c => c.id === activeGroupId)?.name || activeGroupId }}")
          </span>
          <span v-if="searchQuery" style="color: #2563eb; margin-left: 8px;">
            (筛选条件: "{{ searchQuery }}")
          </span>
        </div>
        <div>
          <span style="font-size: 0.8rem; color: #94a3b8;">
            💡 提示：点击上方标签筛选词组；点击遮挡条揭开释义；点击喇叭朗读单词
          </span>
        </div>
      </div>

      <!-- Empty State -->
      <div v-if="loading" class="empty-state">
        <p>正在从服务器同步词库数据...</p>
      </div>
      <div v-else-if="filteredGroups.length === 0" class="empty-state">
        <FolderOpen :size="48" style="margin: 0 auto 1rem; color: #cbd5e1;" />
        <h3>没有找到相关的混淆词组</h3>
        <p v-if="searchQuery">尝试更换搜索词，或者点击上方「新增词条」加入新词</p>
        <p v-else>点击右上角的「新增词条」开始记录第一个混淆单词吧！</p>
      </div>

      <!-- Cards Grid -->
      <div v-else class="card-grid">
        <div v-for="group in displayedGroups" :key="group.id" class="card">
          <!-- Card Header -->
          <div class="card-header">
            <div>
              <div class="group-title">
                {{ group.name }}
                <span style="font-size: 0.75rem; color: #64748b; font-weight: normal;">
                  ({{ group.entries.length }} 词)
                </span>
              </div>
              <div v-if="group.note" class="group-note">{{ group.note }}</div>
            </div>
            <div class="group-actions">
              <button class="btn btn-sm" @click="openAddWordToGroup(group)" title="向此组添加单词">
                <Plus :size="14" />
                <span>加词</span>
              </button>
              <button class="btn btn-sm btn-icon" @click="openEditGroupModal(group)" title="重命名/编辑分组">
                <Edit2 :size="14" />
              </button>
              <button class="btn btn-sm btn-icon btn-danger" @click="deleteGroup(group)" title="删除整个词组">
                <Trash2 :size="14" />
              </button>
            </div>
          </div>

          <!-- Entries List -->
          <div class="entry-list">
            <div
              v-for="entry in (group.entries || []).slice().sort((a, b) => (a.created_at || '').localeCompare(b.created_at || ''))"
              :key="entry.id"
              class="entry-item"
              @mouseenter="hoverEntryId = entry.id"
              @mouseleave="hoverEntryId = null"
            >
              <div class="entry-row-top">
                <div class="word-wrap">
                  <span class="word-text">{{ entry.word }}</span>
                  <span v-if="entry.phonetic" class="word-phonetic">{{ entry.phonetic }}</span>
                  <button class="speak-btn" @click.stop="speakWord(entry.word)" title="朗读发音">
                    <Volume2 :size="15" />
                  </button>
                </div>
                <div class="entry-actions">
                  <button class="btn btn-sm btn-icon" @click="openEditEntryModal(entry)" title="编辑词条">
                    <Edit2 :size="12" />
                  </button>
                  <button class="btn btn-sm btn-icon btn-danger" @click="deleteEntry(entry)" title="删除词条">
                    <Trash2 :size="12" />
                  </button>
                </div>
              </div>

              <!-- Meaning with Masking Bar -->
              <div class="meaning-container">
                <div
                  v-if="isEntryMasked(entry.id)"
                  class="mask-bar"
                  @click="toggleEntryMask(entry.id)"
                  title="点击揭开释义"
                >
                  <span>点击显示释义</span>
                </div>
                <div
                  v-else
                  class="meaning-revealed"
                  @click="toggleEntryMask(entry.id)"
                  title="点击重新遮挡"
                >
                  {{ entry.meaning || '（暂无释义）' }}
                </div>
              </div>

              <div v-if="entry.note" class="entry-note">
                💡 {{ entry.note }}
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- ⭐ 视觉层懒加载哨兵与状态 (纯内存切片，0 网络延迟) -->
      <div ref="sentinelRef" class="scroll-sentinel"></div>

      <div class="infinite-status-bar">
        <div v-if="filteredGroups.length > 0 && !hasMore" class="infinite-end">
          已展示全部 {{ filteredGroups.length }} 个混淆词组（共 {{ totalEntries }} 个单词）
        </div>
      </div>
    </main>

    <!-- ⭐ Modal: 新增词条 (1:1 对齐安卓原生设计与交互) -->
    <div v-if="showAddWordDialog" class="modal-overlay" @click.self="showAddWordDialog = false">
      <div class="modal-dialog" style="max-width: 480px;">
        <div class="modal-header">
          <div class="modal-title">{{ isEditingEntry ? '编辑词条' : '新增词条' }}</div>
          <button class="btn btn-icon btn-sm" @click="showAddWordDialog = false"><X :size="16" /></button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">单词，例如 garment *</label>
            <input
              v-model="wordInput"
              class="form-control"
              placeholder="单词，例如 garment"
              @input="onWordInputChanged"
              autofocus
            />
          </div>

          <div class="form-group">
            <label class="form-label">释义，例如 衣服，服装</label>
            <input
              v-model="meaningInput"
              class="form-control"
              placeholder="释义，例如 衣服，服装"
            />
          </div>

          <!-- 仅在新增模式下展示并入组推荐 -->
          <div v-if="!isEditingEntry" class="group-recommend-section">
            <div class="recommend-title-label">
              输入单词后，下面会自动推荐可并入的组：
            </div>

            <!-- 推荐候选滚动框 -->
            <div class="recommend-box">
              <div v-if="wordInput.trim().length < 2" class="recommend-hint">
                输入 2 个字母以上将自动推荐可并入的组
              </div>
              <div v-else-if="recommendedCandidates.length === 0" class="recommend-hint">
                （没有找到相似的词 —— 可以勾选下方的「新建一组」）
              </div>
              <div v-else class="candidate-list">
                <div
                  v-for="cand in recommendedCandidates"
                  :key="cand.groupId"
                  class="candidate-row"
                  :class="{ selected: selectedCandidate?.groupId === cand.groupId && !createNewGroupChecked }"
                  @click="toggleCandidate(cand)"
                >
                  <div class="candidate-text-col">
                    <div class="candidate-head">
                      <strong class="cand-word">{{ cand.matchedWord }}</strong>
                      <span v-if="cand.matchedIpa" class="cand-ipa">{{ cand.matchedIpa }}</span>
                    </div>
                    <div class="candidate-sub">
                      组：{{ cand.groupName }}   相似度 {{ Math.round(cand.score * 100) }}%<template v-if="cand.siblings && cand.siblings.length">   同组：{{ cand.siblings.slice(0, 3).join('、') }}</template>
                    </div>
                  </div>
                  <div class="candidate-mark">
                    <span v-if="selectedCandidate?.groupId === cand.groupId && !createNewGroupChecked">✓</span>
                  </div>
                </div>
              </div>
            </div>

            <!-- "新建一组" 勾选框 + 自定义组名 -->
            <div class="new-group-row">
              <label class="new-group-label">
                <input type="checkbox" v-model="createNewGroupChecked" @change="onToggleNewGroup" />
                <span>新建一组（这个单词单独构成一组）</span>
              </label>
              <input
                v-if="createNewGroupChecked"
                v-model="newGroupNameInput"
                class="form-control new-group-input"
                placeholder="留空则用单词本身作为组名"
              />
            </div>
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn" @click="showAddWordDialog = false">取消</button>
          <button class="btn btn-primary" @click="submitAddWord">
            {{ isEditingEntry ? '保存' : '加入' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Modal: Group Edit Form (仅在编辑组名时使用) -->
    <div v-if="showGroupEditModal" class="modal-overlay" @click.self="showGroupEditModal = false">
      <div class="modal-dialog">
        <div class="modal-header">
          <div class="modal-title">编辑分组信息</div>
          <button class="btn btn-icon btn-sm" @click="showGroupEditModal = false"><X :size="16" /></button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">分组名称 *</label>
            <input v-model="groupEditForm.name" class="form-control" autofocus />
          </div>
          <div class="form-group">
            <label class="form-label">备注 / 记忆特征</label>
            <textarea v-model="groupEditForm.note" class="form-control" rows="2"></textarea>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showGroupEditModal = false">取消</button>
          <button class="btn btn-primary" @click="saveGroupEdit">保存</button>
        </div>
      </div>
    </div>

    <!-- Modal: Transfer (Export / Import) -->
    <div v-if="showTransferModal" class="modal-overlay" @click.self="showTransferModal = false">
      <div class="modal-dialog" style="max-width: 540px;">
        <div class="modal-header">
          <div class="modal-title">词库备份与恢复</div>
          <button class="btn btn-icon btn-sm" @click="showTransferModal = false"><X :size="16" /></button>
        </div>
        <div class="modal-body">
          <div style="display: flex; gap: 1rem; border-bottom: 1px solid var(--border); padding-bottom: 0.75rem;">
            <button
              class="btn"
              :class="{ 'btn-primary': transferTab === 'export' }"
              @click="transferTab = 'export'"
            >
              导出备份 (Export)
            </button>
            <button
              class="btn"
              :class="{ 'btn-primary': transferTab === 'import' }"
              @click="transferTab = 'import'"
            >
              恢复导入 (Import)
            </button>
          </div>

          <div v-if="transferTab === 'export'" style="display: flex; flex-direction: column; gap: 1rem; margin-top: 0.5rem;">
            <p style="font-size: 0.9rem; color: #64748b;">
              将当前云端保存的全部混淆词与分组导出为标准 JSON 格式，方便离线备份或多设备转移。
            </p>
            <button class="btn btn-primary" @click="handleExportDownload">
              <Download :size="16" />
              <span>下载词库 JSON 文件</span>
            </button>
          </div>

          <div v-else style="display: flex; flex-direction: column; gap: 1rem; margin-top: 0.5rem;">
            <p style="font-size: 0.9rem; color: #64748b;">
              粘贴词库 JSON 内容进行导入，将自动匹配同名分组并无损合并。
            </p>
            <textarea
              v-model="importJsonText"
              class="form-control"
              rows="6"
              placeholder="在此粘贴导出的 JSON 字符串..."
            ></textarea>
            <button class="btn btn-primary" @click="handleImportSubmit">
              <Upload :size="16" />
              <span>确认导入</span>
            </button>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showTransferModal = false">关闭</button>
        </div>
      </div>
    </div>

    <!-- Modal: Auth (Login/Register) -->
    <div v-if="showAuthModal" class="modal-overlay" @click.self="showAuthModal = false">
      <div class="modal-dialog">
        <div class="modal-header">
          <div class="modal-title">{{ isRegistering ? '用户注册' : '用户登录' }}</div>
          <button class="btn btn-icon btn-sm" @click="showAuthModal = false"><X :size="16" /></button>
        </div>
        <div class="modal-body">
          <div class="form-group">
            <label class="form-label">用户名</label>
            <input v-model="authForm.username" class="form-control" placeholder="输入用户名 (默认测试: admin)" autofocus />
          </div>
          <div class="form-group">
            <label class="form-label">密码</label>
            <input v-model="authForm.password" type="password" class="form-control" placeholder="输入密码 (默认测试: admin123)" />
          </div>
          <div v-if="authError" style="color: #ef4444; font-size: 0.85rem;">
            {{ authError }}
          </div>
          <div style="font-size: 0.85rem; color: #64748b; cursor: pointer; text-decoration: underline;" @click="isRegistering = !isRegistering">
            {{ isRegistering ? '已有账号？点击返回登录' : '没有账号？点击注册新账号' }}
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn" @click="showAuthModal = false">取消</button>
          <button class="btn btn-primary" @click="handleAuthSubmit">
            {{ isRegistering ? '立即注册' : '立即登录' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ⭐ Modal: 词组排序方式 (1:1 对齐安卓原生设计与交互) -->
    <div v-if="showSortDialog" class="modal-overlay" @click.self="showSortDialog = false">
      <div class="modal-dialog sort-dialog">
        <div class="sort-dialog-header">
          <div class="sort-dialog-title">词组排序方式</div>
        </div>
        <div class="sort-dialog-body">
          <div class="sort-radio-row" @click="changeSortMode('default')">
            <span class="custom-radio" :class="{ checked: sortMode === 'default' }">
              <span class="radio-inner" v-if="sortMode === 'default'"></span>
            </span>
            <span class="sort-row-label">默认（按单词修改/添加时间 从新到旧）</span>
          </div>
          <div class="sort-radio-row" @click="changeSortMode('word_count_desc')">
            <span class="custom-radio" :class="{ checked: sortMode === 'word_count_desc' }">
              <span class="radio-inner" v-if="sortMode === 'word_count_desc'"></span>
            </span>
            <span class="sort-row-label">按词组包含的单词数降序</span>
          </div>
          <div class="sort-radio-row" @click="changeSortMode('name_asc')">
            <span class="custom-radio" :class="{ checked: sortMode === 'name_asc' }">
              <span class="radio-inner" v-if="sortMode === 'name_asc'"></span>
            </span>
            <span class="sort-row-label">按词组名称字典序 (A-Z)</span>
          </div>
        </div>
        <div class="sort-dialog-footer">
          <button class="btn-link-cancel" @click="showSortDialog = false">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import {
  BookOpen, Search, Eye, EyeOff, Plus, ArrowUpDown, UserCheck, LogIn,
  FolderOpen, Edit2, Trash2, Volume2, X, Download, Upload, Smartphone, ShieldCheck, Loader2
} from 'lucide-vue-next'
import { api, Group, WordEntry, User, CandidateGroup, GroupChip } from './api'

// Core In-Memory State (全量响应式 Store)
const allGroups = ref<Group[]>([])
const currentSyncVersion = ref(0)
const loading = ref(false)
const searchQuery = ref('')
const currentUser = ref<User | null>(null)
const globalMask = ref(true)
const individualMaskOverrides = ref<Record<string, boolean>>({})
const hoverEntryId = ref<string | null>(null)
const searchInputRef = ref<HTMLInputElement | null>(null)
const sentinelRef = ref<HTMLElement | null>(null)

// 词组标签与筛选（对齐安卓原生设计）
const activeGroupId = ref<string | null>(null)

// 词组排序方式（对齐安卓原生设计）
type SortMode = 'default' | 'word_count_desc' | 'name_asc'
const sortMode = ref<SortMode>((localStorage.getItem('wm_sort_mode') as any) || 'default')
const sortModeLabel = computed(() => {
  if (sortMode.value === 'word_count_desc') return '单词数降序'
  if (sortMode.value === 'name_asc') return '字典序 (A-Z)'
  return '添加顺序'
})
const showSortDialog = ref(false)

// 默认排序依据：以词组内单词的最新修改/添加时间为准（从新到旧），若组内暂无词条则使用词组的更新/创建时间
function getGroupLatestUpdateTime(g: Group): string {
  if (g.entries && g.entries.length > 0) {
    return g.entries.reduce((max, e) => {
      const t = e.updated_at || e.created_at || ''
      return t > max ? t : max
    }, '') || g.updated_at || g.created_at || ''
  }
  return g.updated_at || g.created_at || ''
}

// 纯前端 0ms 实时过滤与排序
const filteredGroups = computed(() => {
  let list = allGroups.value

  // 1. 单组筛选
  if (activeGroupId.value) {
    list = list.filter(g => g.id === activeGroupId.value)
  }

  // 2. 关键词模糊检索 (匹配组名、词条单词、中文释义、备注)
  const q = searchQuery.value.trim().toLowerCase()
  if (q) {
    list = list.filter(g => {
      if (g.name.toLowerCase().includes(q) || (g.note && g.note.toLowerCase().includes(q))) {
        return true
      }
      return g.entries && g.entries.some(e =>
        e.word.toLowerCase().includes(q) ||
        (e.meaning && e.meaning.toLowerCase().includes(q)) ||
        (e.note && e.note.toLowerCase().includes(q))
      )
    })
  }

  // 3. 排序 (纯内存瞬时重排，0 网络延迟)
  if (sortMode.value === 'word_count_desc') {
    return [...list].sort((a, b) => (b.entries?.length || 0) - (a.entries?.length || 0) || a.name.localeCompare(b.name))
  } else if (sortMode.value === 'name_asc') {
    return [...list].sort((a, b) => a.name.localeCompare(b.name, 'en', { sensitivity: 'base' }))
  } else {
    // 默认：按组内单词修改/添加时间从新到旧 (降序)
    return [...list].sort((a, b) => getGroupLatestUpdateTime(b).localeCompare(getGroupLatestUpdateTime(a)))
  }
})

// 标签条（按当前排序方式纯内存派生，0 网络延迟）
const allGroupChips = computed(() => {
  let list = [...allGroups.value]
  if (sortMode.value === 'word_count_desc') {
    list.sort((a, b) => (b.entries?.length || 0) - (a.entries?.length || 0) || a.name.localeCompare(b.name))
  } else if (sortMode.value === 'name_asc') {
    list.sort((a, b) => a.name.localeCompare(b.name, 'en', { sensitivity: 'base' }))
  } else {
    list.sort((a, b) => getGroupLatestUpdateTime(b).localeCompare(getGroupLatestUpdateTime(a)))
  }
  return list.map(g => ({ id: g.id, name: g.name }))
})

// 总数统计（纯内存计算）
const totalGroups = computed(() => allGroups.value.length)
const totalEntries = computed(() => allGroups.value.reduce((acc, g) => acc + (g.entries?.length || 0), 0))

// 视觉层懒加载切片（防 DOM 一次性渲染掉帧，纯内存切片，无网络等待）
const displayedCount = ref(20)
const displayedGroups = computed(() => filteredGroups.value.slice(0, displayedCount.value))
const hasMore = computed(() => displayedCount.value < filteredGroups.value.length)

// ⭐ 新增词条对话框状态 (1:1 对齐安卓原生设计与交互)
const showAddWordDialog = ref(false)
const isEditingEntry = ref(false)
const editingEntryId = ref<string | null>(null)
const wordInput = ref('')
const meaningInput = ref('')
const selectedCandidate = ref<CandidateGroup | null>(null)
const createNewGroupChecked = ref(false)
const newGroupNameInput = ref('')
const recommendedCandidates = ref<CandidateGroup[]>([])

// 分组编辑弹窗
const showGroupEditModal = ref(false)
const editingGroup = ref<Group | null>(null)
const groupEditForm = ref({ name: '', note: '' })

// 备份恢复
const showTransferModal = ref(false)
const transferTab = ref<'export' | 'import'>('export')
const importJsonText = ref('')

// 鉴权
const showAuthModal = ref(false)
const isRegistering = ref(false)
const authForm = ref({ username: 'admin', password: 'admin123' })
const authError = ref('')

// Masking logic
function isEntryMasked(entryId: string): boolean {
  if (hoverEntryId.value === entryId) {
    return false
  }
  if (entryId in individualMaskOverrides.value) {
    return individualMaskOverrides.value[entryId]
  }
  return globalMask.value
}

function toggleEntryMask(entryId: string) {
  const current = isEntryMasked(entryId)
  individualMaskOverrides.value[entryId] = !current
}

function toggleGlobalMask() {
  globalMask.value = !globalMask.value
  individualMaskOverrides.value = {}
}

// Pronunciation
function speakWord(word: string) {
  if (!('speechSynthesis' in window)) return
  window.speechSynthesis.cancel()
  const utterance = new SpeechSynthesisUtterance(word)
  utterance.lang = 'en-US'
  utterance.rate = 0.9
  window.speechSynthesis.speak(utterance)
}

function selectGroupChip(gid: string | null) {
  activeGroupId.value = activeGroupId.value === gid ? null : gid
  displayedCount.value = 20
}

function changeSortMode(mode: SortMode) {
  sortMode.value = mode
  localStorage.setItem('wm_sort_mode', mode)
  showSortDialog.value = false
  displayedCount.value = 20
}

function onSearchInput() {
  displayedCount.value = 20
}

function loadMoreVisible() {
  if (hasMore.value) {
    displayedCount.value += 20
  }
}

// ⭐ 触底流式懒加载（从前端内存切片取出，0 网络等待）
let observer: IntersectionObserver | null = null

function setupIntersectionObserver() {
  if (observer) observer.disconnect()
  if (!sentinelRef.value) return
  observer = new IntersectionObserver((entries) => {
    const entry = entries[0]
    if (entry && entry.isIntersecting) {
      loadMoreVisible()
    }
  }, { rootMargin: '300px' })
  observer.observe(sentinelRef.value)
}

function handleWindowScroll() {
  if (window.innerHeight + window.scrollY >= document.documentElement.scrollHeight - 350) {
    loadMoreVisible()
  }
}

// ⭐ 全量数据拉取与增量同步探测
async function fetchAllGroups() {
  loading.value = true
  try {
    const res = await api.getGroups(undefined, 1, undefined, 'default', undefined, true)
    allGroups.value = res.items || []
    try {
      const v = await api.getSyncVersion()
      currentSyncVersion.value = v.version
    } catch {}
  } catch (err: any) {
    console.error('拉取词库失败', err)
  } finally {
    loading.value = false
  }
}

async function checkSyncVersion() {
  try {
    const v = await api.getSyncVersion()
    if (v.version > currentSyncVersion.value) {
      currentSyncVersion.value = v.version
      const res = await api.getGroups(undefined, 1, undefined, 'default', undefined, true)
      allGroups.value = res.items || []
    }
  } catch {}
}

let pollTimer: any = null

// Lifecycle
onMounted(async () => {
  window.addEventListener('keydown', (e) => {
    if (e.key === '/' && document.activeElement?.tagName !== 'INPUT' && document.activeElement?.tagName !== 'TEXTAREA') {
      e.preventDefault()
      searchInputRef.value?.focus()
    }
  })

  window.addEventListener('scroll', handleWindowScroll, { passive: true })
  window.addEventListener('focus', checkSyncVersion)
  pollTimer = setInterval(checkSyncVersion, 25000)

  try {
    currentUser.value = await api.getMe()
  } catch {
    try {
      const res = await api.login('admin', 'admin123')
      currentUser.value = res.user
    } catch {
      // 允许访客免登直接查看
    }
  }

  await fetchAllGroups()
  nextTick(() => {
    setupIntersectionObserver()
  })
})

onUnmounted(() => {
  if (observer) observer.disconnect()
  if (pollTimer) clearInterval(pollTimer)
  window.removeEventListener('scroll', handleWindowScroll)
  window.removeEventListener('focus', checkSyncVersion)
})

// ⭐ 新增词条核心流程 (1:1 对齐安卓原生设计与交互)
function openAddWordModal() {
  isEditingEntry.value = false
  editingEntryId.value = null
  wordInput.value = ''
  meaningInput.value = ''
  selectedCandidate.value = null
  createNewGroupChecked.value = false
  newGroupNameInput.value = ''
  recommendedCandidates.value = []
  showAddWordDialog.value = true
}

function openAddWordToGroup(group: Group) {
  openAddWordModal()
  selectedCandidate.value = {
    groupId: group.id,
    groupName: group.name,
    score: 1.0,
    matchedWord: group.name,
    matchedIpa: '',
    siblings: group.entries ? group.entries.map(e => e.word) : [],
    allWords: group.entries ? group.entries.map(e => e.word) : [group.name],
  }
}

function openEditEntryModal(entry: WordEntry) {
  isEditingEntry.value = true
  editingEntryId.value = entry.id
  wordInput.value = entry.word
  meaningInput.value = entry.meaning
  selectedCandidate.value = null
  createNewGroupChecked.value = false
  showAddWordDialog.value = true
}

function toggleCandidate(cand: CandidateGroup) {
  if (selectedCandidate.value?.groupId === cand.groupId) {
    selectedCandidate.value = null
  } else {
    selectedCandidate.value = cand
    if (createNewGroupChecked.value) {
      createNewGroupChecked.value = false
    }
  }
}

function onToggleNewGroup() {
  if (createNewGroupChecked.value) {
    selectedCandidate.value = null
  }
}

let inputTimer: any = null
function onWordInputChanged() {
  clearTimeout(inputTimer)
  inputTimer = setTimeout(async () => {
    const w = wordInput.value.trim()
    if (w.length < 2) {
      recommendedCandidates.value = []
      return
    }

    // 调用服务器端推荐计算 (降低客户端压力与流量)
    try {
      const list = await api.getRecommendedGroups(w, 8)
      recommendedCandidates.value = list
      if (list.length > 0 && !createNewGroupChecked.value && !selectedCandidate.value) {
        selectedCandidate.value = list[0]
      }
    } catch (e) {
      console.warn('获取候选推荐失败', e)
    }
  }, 250)
}

async function submitAddWord() {
  const w = wordInput.value.trim()
  if (!w) {
    alert('单词不能为空')
    return
  }

  try {
    if (isEditingEntry.value && editingEntryId.value) {
      // 编辑词条
      const updated = await api.updateEntry(editingEntryId.value, w, meaningInput.value.trim())
      for (const g of allGroups.value) {
        if (!g.entries) continue
        const idx = g.entries.findIndex(e => e.id === editingEntryId.value)
        if (idx !== -1) {
          g.entries[idx] = updated
          break
        }
      }
    } else {
      // 新增词条
      let targetGid = ''
      if (createNewGroupChecked.value) {
        const gname = newGroupNameInput.value.trim() || w
        const newGroup = await api.createGroup(gname, '')
        targetGid = newGroup.id
        newGroup.entries = []
        allGroups.value.push(newGroup)
      } else if (selectedCandidate.value) {
        targetGid = selectedCandidate.value.groupId
      } else {
        const newGroup = await api.createGroup(w, '')
        targetGid = newGroup.id
        newGroup.entries = []
        allGroups.value.push(newGroup)
      }

      const created = await api.createEntry(targetGid, w, meaningInput.value.trim())
      const targetGroup = allGroups.value.find(g => g.id === targetGid)
      if (targetGroup) {
        if (!targetGroup.entries) targetGroup.entries = []
        targetGroup.entries.push(created)
      }
    }

    showAddWordDialog.value = false
    displayedCount.value = 20
  } catch (err: any) {
    alert(err.message || '操作失败')
  }
}

async function deleteEntry(entry: WordEntry) {
  if (!confirm(`确定删除单词 "${entry.word}" 吗？`)) return
  try {
    await api.deleteEntry(entry.id)
    for (const g of allGroups.value) {
      if (g.entries) {
        g.entries = g.entries.filter(e => e.id !== entry.id)
      }
    }
  } catch (err: any) {
    alert(err.message)
  }
}

// 分组编辑与删除
function openEditGroupModal(g: Group) {
  editingGroup.value = g
  groupEditForm.value = { name: g.name, note: g.note }
  showGroupEditModal.value = true
}

async function saveGroupEdit() {
  if (!editingGroup.value || !groupEditForm.value.name.trim()) return
  try {
    const updated = await api.updateGroup(editingGroup.value.id, groupEditForm.value.name, groupEditForm.value.note)
    const g = allGroups.value.find(x => x.id === editingGroup.value?.id)
    if (g) {
      g.name = updated.name
      g.note = updated.note
    }
    showGroupEditModal.value = false
  } catch (err: any) {
    alert(err.message)
  }
}

async function deleteGroup(g: Group) {
  if (!confirm(`确定要删除混淆词组 "${g.name}" 及其下全部单词吗？`)) return
  try {
    await api.deleteGroup(g.id)
    allGroups.value = allGroups.value.filter(x => x.id !== g.id)
    if (activeGroupId.value === g.id) activeGroupId.value = null
  } catch (err: any) {
    alert(err.message)
  }
}

// Transfer
function openTransferModal() {
  transferTab.value = 'export'
  importJsonText.value = ''
  showTransferModal.value = true
}

async function handleExportDownload() {
  try {
    const data = await api.exportLibrary()
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `wordmix-backup-${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  } catch (err: any) {
    alert('导出失败: ' + err.message)
  }
}

async function handleImportSubmit() {
  if (!importJsonText.value.trim()) return
  try {
    const json = JSON.parse(importJsonText.value.trim())
    const res = await api.importLibrary(json)
    alert(`成功导入 ${res.imported_groups} 个分组，${res.imported_entries} 个词条！`)
    showTransferModal.value = false
    await fetchAllGroups()
  } catch (err: any) {
    alert('导入失败: ' + err.message)
  }
}

// Auth
async function handleAuthSubmit() {
  authError.value = ''
  try {
    let res
    if (isRegistering.value) {
      res = await api.register(authForm.value.username, authForm.value.password)
    } else {
      res = await api.login(authForm.value.username, authForm.value.password)
    }
    currentUser.value = res.user
    showAuthModal.value = false
    await fetchAllGroups()
  } catch (err: any) {
    authError.value = err.message
  }
}

async function logout() {
  await api.logout()
  currentUser.value = null
  allGroups.value = []
  showAuthModal.value = true
}
</script>
