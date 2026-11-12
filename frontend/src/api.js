// Same origin works for a browser on this laptop or another device.
// Vite proxies /api to the setup agent; built UI is served by the agent itself.
export const API = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
export async function setupRequest(path = '', body) {
  const response = await fetch(`${API}/setup${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: body === undefined ? {} : {'Content-Type':'application/json'},
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
  })
  const result = await response.json().catch(() => ({}))
  if (!response.ok) throw new Error(result.message || 'Setup agent unavailable. Start Java with --setup and open http://localhost:6080.')
  return result
}
