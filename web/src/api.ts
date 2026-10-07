// API client with token persistence

const BASE_URL = '/api'

export interface WordEntry {
  id: string
  group_id: string
  word: string
  meaning: string
  note: string
  phonetic: string
  created_at: string
  updated_at: string
}

export interface Group {
  id: string
  name: string
  note: string
  created_at: string
  updated_at: string
  entries: WordEntry[]
}

export interface User {
  id: number
  username: string
  is_admin: boolean
  created_at: string
}

export interface PaginatedGroups {
  items: Group[]
  total_groups: number
  total_entries: number
  page: number
  page_size: number
  total_pages: number
}

export interface CandidateGroup {
  groupId: string
  groupName: string
  score: number
  matchedWord: string
  matchedIpa: string
  siblings: string[]
  allWords: string[]
}

export interface GroupChip {
  id: string
  name: string
}

class ApiService {
  private token: string | null = null

  constructor() {
    this.token = localStorage.getItem('wm_token')
  }

  setToken(tok: string | null) {
    this.token = tok
    if (tok) {
      localStorage.setItem('wm_token', tok)
    } else {
      localStorage.removeItem('wm_token')
    }
  }

  getToken(): string | null {
    return this.token
  }

  private async request<T>(path: string, options: RequestInit = {}): Promise<T> {
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      ...(options.headers as Record<string, string>),
    }

    if (this.token) {
      headers['Authorization'] = `Bearer ${this.token}`
    }

    const res = await fetch(`${BASE_URL}${path}`, {
      ...options,
      headers,
    })

    if (!res.ok) {
      const err = await res.json().catch(() => ({ detail: res.statusText }))
      throw new Error(err.detail || '请求失败')
    }

    return res.json()
  }

  // Auth
  async login(username: string, password: string): Promise<{ access_token: string; user: User }> {
    const data = await this.request<{ access_token: string; user: User }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
    this.setToken(data.access_token)
    return data
  }

  async register(username: string, password: string): Promise<{ access_token: string; user: User }> {
    const data = await this.request<{ access_token: string; user: User }>('/auth/register', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
    this.setToken(data.access_token)
    return data
  }

  async getMe(): Promise<User> {
    return this.request<User>('/auth/me')
  }

  async logout(): Promise<void> {
    try {
      await this.request('/auth/logout', { method: 'POST' })
    } finally {
      this.setToken(null)
    }
  }

  // Groups
  async getGroups(q?: string, page = 1, page_size?: number, sort = 'default', group_id?: string, all = false): Promise<PaginatedGroups> {
    const params = new URLSearchParams()
    if (q) params.set('q', q)
    params.set('page', String(page))
    if (page_size) params.set('page_size', String(page_size))
    if (sort) params.set('sort', sort)
    if (group_id) params.set('group_id', group_id)
    if (all) params.set('all', 'true')
    return this.request<PaginatedGroups>(`/groups?${params.toString()}`)
  }

  async getGroupChips(sort = 'default'): Promise<GroupChip[]> {
    return this.request<GroupChip[]>(`/groups/chips?sort=${encodeURIComponent(sort)}`)
  }

  async getSyncVersion(): Promise<{ version: number }> {
    return this.request<{ version: number }>('/sync/version')
  }

  async getRecommendedGroups(word: string, limit = 8): Promise<CandidateGroup[]> {
    return this.request<CandidateGroup[]>(`/groups/recommend?word=${encodeURIComponent(word)}&limit=${limit}`)
  }

  // Settings
  async getSettings(): Promise<{ default_page_size: number }> {
    return this.request<{ default_page_size: number }>('/settings')
  }

  async updateSettings(default_page_size: number): Promise<{ default_page_size: number }> {
    return this.request<{ default_page_size: number }>('/settings', {
      method: 'PUT',
      body: JSON.stringify({ default_page_size }),
    })
  }

  async createGroup(name: string, note = ''): Promise<Group> {
    return this.request<Group>('/groups', {
      method: 'POST',
      body: JSON.stringify({ name, note }),
    })
  }

  async updateGroup(id: string, name?: string, note?: string): Promise<Group> {
    return this.request<Group>(`/groups/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ name, note }),
    })
  }

  async deleteGroup(id: string): Promise<{ ok: boolean }> {
    return this.request<{ ok: boolean }>(`/groups/${id}`, {
      method: 'DELETE',
    })
  }

  // Entries
  async createEntry(group_id: string, word: string, meaning = '', note = '', phonetic = ''): Promise<WordEntry> {
    return this.request<WordEntry>('/entries', {
      method: 'POST',
      body: JSON.stringify({ group_id, word, meaning, note, phonetic }),
    })
  }

  async updateEntry(id: string, word?: string, meaning?: string, note?: string, phonetic?: string): Promise<WordEntry> {
    return this.request<WordEntry>(`/entries/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ word, meaning, note, phonetic }),
    })
  }

  async deleteEntry(id: string): Promise<{ ok: boolean }> {
    return this.request<{ ok: boolean }>(`/entries/${id}`, {
      method: 'DELETE',
    })
  }

  // Phonetics
  async getPhonetic(word: string): Promise<{ word: string; ipa: string }> {
    return this.request<{ word: string; ipa: string }>(`/phonetics?word=${encodeURIComponent(word)}`)
  }

  // Transfer
  async exportLibrary(): Promise<any> {
    return this.request<any>('/transfer/export')
  }

  async importLibrary(data: any): Promise<{ ok: boolean; imported_groups: number; imported_entries: number }> {
    return this.request('/transfer/import', {
      method: 'POST',
      body: JSON.stringify(data),
    })
  }
}

export const api = new ApiService()
