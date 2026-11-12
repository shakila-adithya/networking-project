const storageKey = 'storage-console-theme'

function readTheme() {
  try {
    const saved = localStorage.getItem(storageKey)
    if (saved === 'light' || saved === 'dark') return saved
  } catch { /* Storage can be disabled in private or restricted browsers. */ }
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function applyTheme(theme) {
  document.documentElement.dataset.theme = theme
  document.documentElement.style.colorScheme = theme
  try { localStorage.setItem(storageKey, theme) } catch { /* Keep the toggle usable without storage. */ }
}

export const startingTheme = readTheme()
applyTheme(startingTheme)
