export const SESSION_KEY = 'management-session-v1'

// A tab-scoped credential only. User identity and permissions always come from /me.
export function createSessionPersistence(getStorage = () => globalThis.sessionStorage) {
  function clear() { try { getStorage()?.removeItem(SESSION_KEY) } catch {} }
  function read() {
    try {
      const raw = getStorage()?.getItem(SESSION_KEY)
      if (!raw) return null
      const value = JSON.parse(raw)
      if (typeof value?.token !== 'string' || !/^[A-Za-z0-9_.-]{1,4096}$/.test(value.token) ||
          typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt)) || Date.parse(value.expiresAt) <= Date.now()) {
        clear(); return null
      }
      return { token: value.token, expiresAt: value.expiresAt }
    } catch { clear(); return null }
  }
  function save(value) {
    try {
      if (!value?.token || !value.expiresAt || !Number.isFinite(Date.parse(value.expiresAt)) || Date.parse(value.expiresAt) <= Date.now()) { clear(); return false }
      const storage = getStorage()
      if (!storage) return false
      storage.setItem(SESSION_KEY, JSON.stringify({ token: value.token, expiresAt: value.expiresAt }))
      return true
    } catch { return false }
  }
  return { read, save, clear }
}
