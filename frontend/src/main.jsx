import React, { useEffect, useState, useRef } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/plus-jakarta-sans'
import '@fontsource-variable/jetbrains-mono'
import './styles.css'
import { API } from './api'
import ClusterSetup from './ClusterSetup'
import ClusterMesh from './ClusterMesh'
import Icon, { LogoMark } from './Icon'
import { startingTheme, applyTheme } from './theme'
import './theme.css'
import UploadPreview from './UploadPreview'

const menu = [
  { name: 'Dashboard', icon: 'dashboard' },
  { name: 'Files', icon: 'files' },
  { name: 'Peers', icon: 'peers' },
  { name: 'Repairs', icon: 'repairs' },
  { name: 'Settings', icon: 'settings' },
]

function App() {
  const [theme, setTheme] = useState(startingTheme)
  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark'
    applyTheme(next)
    setTheme(next)
  }
  const [page, setPage] = useState('Dashboard')
  const [health, setHealth] = useState(null)
  const [files, setFiles] = useState([])
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const refresh = async () => {
    try {
      const [healthResponse, filesResponse] = await Promise.all([fetch(`${API}/health`), fetch(`${API}/files`)])
      if (!healthResponse.ok || !filesResponse.ok) throw new Error('Backend request failed')
      setHealth(await healthResponse.json())
      setFiles((await filesResponse.json()).files || [])
      setError('')
    } catch (err) { setError(`Backend unavailable at ${API}`) }
  }
  useEffect(() => { refresh(); const timer = setInterval(refresh, 5000); return () => clearInterval(timer) }, [])
  const notify = text => { setMessage(text); setTimeout(() => setMessage(''), 2800) }
  const localPeer = health?.peers?.find(peer => peer.source === 'local')
  const content = page === 'Dashboard' ? <Dashboard health={health} files={files} go={setPage}/>
    : page === 'Files' ? <Files files={files} refresh={refresh} notify={notify}/>
    : page === 'Peers' ? <Peers health={health}/>
    : page === 'Repairs' ? <Repairs files={files}/>
    : <Settings notify={notify}/>
  return <div className="app">
    <aside>
      <div className="logo"><LogoMark/><div><b>Storage Console</b><small>Self-healing cluster</small></div></div>
      <div className="node-card">
        <span className="node-card-label"><i className={health ? 'dot live' : 'dot'}/>This device</span>
        <b>{localPeer?.peerId ? `Peer ${localPeer.peerId}` : 'Not set up yet'}</b>
        <small>{localPeer?.address || 'Open Settings to join a cluster'}</small>
      </div>
      <nav aria-label="Main">
        {menu.map(item => <button className={page === item.name ? 'active' : ''} aria-current={page === item.name ? 'page' : undefined} onClick={() => setPage(item.name)} key={item.name}><Icon name={item.icon}/><span>{item.name}</span></button>)}
      </nav>
      <div className="aside-foot"><i className={health ? 'dot live' : 'dot'}/>Backend {health ? 'connected' : 'checking'}</div>
    </aside>
    <main>
      <header>
        <span className="crumbs">Cluster <em>/</em> <b>{page}</b></span>
        <div className="header-right">
          <button type="button" className="theme-toggle" onClick={toggleTheme} aria-label="Dark mode" aria-pressed={theme === 'dark'} title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}>
            <Icon name={theme === 'dark' ? 'sun' : 'moon'} size={17}/>
          </button>
          <span className={`pill ${health ? (health.healthy ? 'good' : 'warn') : 'idle'}`}><i/>{health?.status || 'Checking'}</span>
          {localPeer?.peerId && <span className="avatar" title={`Peer ${localPeer.peerId}`}>{String(localPeer.peerId).slice(0, 2)}</span>}
        </div>
      </header>
      {error && <div className="backend-error" role="alert"><Icon name="alert" size={18}/><span>{error}. Open Settings to join a cluster, or check that the Java node is running.</span></div>}
      {content}
    </main>
    {message && <div className="toast" role="status"><Icon name="check" size={16}/>{message}</div>}
  </div>
}

function Title({ title, sub, action }) {
  return <div className="title"><div><h1>{title}</h1><p>{sub}</p></div>{action}</div>
}

function Dashboard({ health, files, go }) {
  const peers = health?.peers || []
  const online = peers.filter(p => p.status === 'ALIVE').length
  const totalBytes = files.reduce((sum, f) => sum + (Number(f.size) || 0), 0)
  const tone = !health ? 'idle' : health.healthy ? 'good' : 'warn'
  return <section className="content">
    <Title title="Dashboard" sub="Live status from the Java storage backend." action={<button className="btn primary" onClick={() => go('Files')}><Icon name="upload" size={16}/>Upload files</button>}/>
    <div className={`hero ${tone}`}>
      <div className="hero-copy">
        <span className="hero-badge"><Icon name={health?.healthy === false ? 'alert' : 'check'} size={15}/>{!health ? 'Checking' : health.healthy ? 'All systems normal' : 'Needs attention'}</span>
        <h2>{health ? `${health.status} cluster` : 'Checking cluster'}</h2>
        <p>{health ? `${online} of ${peers.length} peers are responding to heartbeats.` : 'Waiting for backend health response.'}</p>
        <small>Refreshes every 5 seconds</small>
      </div>
      <ClusterMesh peers={peers}/>
    </div>
    <div className="stats">
      <Stat icon="peers" label="Connected peers" value={health ? `${online} / ${peers.length}` : '—'} note="Current heartbeat state"/>
      <Stat icon="files" label="Files stored" value={health ? files.length : '—'} note="Local inventory"/>
      <Stat icon="inbox" label="Data on this peer" value={health ? formatBytes(totalBytes) : '—'} note="Sum of listed file sizes"/>
      <Stat icon="repairs" label="Healthy replicas" value="—" note="Replica inventory pending"/>
    </div>
    <div className="two-col">
      <div className="card">
        <div className="card-title"><h3>Recent files</h3><button className="link-btn" onClick={() => go('Files')}>View all<Icon name="arrow" size={14}/></button></div>
        {files.length ? files.slice(0, 5).map(file => <FileLine file={file} key={file.fileId}/>) : <Empty icon="inbox" text="No files stored on this peer" hint="Uploaded files appear here."/>}
      </div>
      <div className="card">
        <div className="card-title"><h3>Peer health</h3><button className="link-btn" onClick={() => go('Peers')}>Details<Icon name="arrow" size={14}/></button></div>
        {peers.length ? peers.map(peer => <StatusLine label={`Peer ${peer.peerId}`} sub={peer.address} value={peer.status} good={peer.status === 'ALIVE'} key={peer.peerId}/>) : <Empty icon="peers" text="No peer health data" hint="Peers show up once the backend reports them."/>}
      </div>
    </div>
  </section>
}

function Stat({ icon, label, value, note }) {
  return <div className="stat"><span className="stat-icon"><Icon name={icon} size={17}/></span><small>{label}</small><strong>{value}</strong><span className="stat-note">{note}</span></div>
}
function Empty({ text, hint, icon = 'inbox' }) {
  return <div className="empty"><span className="empty-icon"><Icon name={icon} size={22}/></span><b>{text}</b>{hint && <small>{hint}</small>}</div>
}
function StatusLine({ label, sub, value, good }) {
  return <div className="status-line"><div><b>{label}</b>{sub && <small className="mono">{sub}</small>}</div><span className={`pill ${good ? 'good' : 'warn'}`}><i/>{value}</span></div>
}
function FileBadge({ name }) {
  const dot = String(name).lastIndexOf('.')
  const ext = dot > 0 ? String(name).slice(dot + 1).toLowerCase() : ''
  const kind = /^(png|jpe?g|gif|webp|svg|heic|bmp)$/.test(ext) ? 'image'
    : /^(mp4|mov|mkv|avi|webm|mp3|wav|flac|m4a)$/.test(ext) ? 'media'
    : /^(zip|rar|7z|tar|gz|tgz)$/.test(ext) ? 'archive'
    : /^(pdf|docx?|xlsx?|pptx?|txt|md|csv|rtf)$/.test(ext) ? 'doc'
    : /^(js|jsx|ts|tsx|java|py|json|xml|html|css|sh|c|cpp|go|rs)$/.test(ext) ? 'code' : 'other'
  return <span className="file-badge" data-kind={kind}>{ext ? ext.slice(0, 4) : 'file'}</span>
}
function FileLine({ file }) {
  return <div className="file-line">
    <FileBadge name={file.fileName}/>
    <div><b title={file.fileName}>{file.fileName}</b><small>{formatBytes(file.size)} · {file.locations?.join(', ') || 'local'}</small></div>
    <span className="pill good"><i/>Stored</span>
  </div>
}
function Status({ status }) {
  const good = status === 'Healthy' || status === 'ALIVE'
  return <span className={`pill ${good ? 'good' : 'warn'}`}><i/>{status}</span>
}

function Files({ files, refresh, notify }) {
  const [query, setQuery] = useState('')
  const [selectedFile, setSelectedFile] = useState(null)
  const [pendingFiles, setPendingFiles] = useState([])
  const [uploads, setUploads] = useState([])
  const [dragging, setDragging] = useState(false)
  const visible = files.filter(file => file.fileName.toLowerCase().includes(query.toLowerCase()))

  const startUploads = selectedFiles => {
    if (!selectedFiles.length) return
    selectedFiles.forEach(file => {
      const id = crypto.randomUUID()
      const update = changes => setUploads(current => current.map(item => item.id === id ? { ...item, ...changes } : item))
      setUploads(current => [...current, { id, name: file.name, total: file.size, loaded: 0, percent: 0, status: 'Uploading' }])
      const request = new XMLHttpRequest()
      request.open('POST', `${API}/files`)
      request.setRequestHeader('X-File-Name', encodeURIComponent(file.name))
      request.upload.onprogress = progress => {
        const total = progress.lengthComputable ? progress.total : file.size
        const percent = total ? Math.min(100, Math.floor(progress.loaded / total * 100)) : 0
        update({ loaded: Math.min(progress.loaded, file.size), percent, status: 'Uploading' })
      }
      request.upload.onload = () => update({ loaded: file.size, percent: 100, status: 'Processing' })
      request.onload = async () => {
        let result
        try { result = JSON.parse(request.responseText) } catch { result = {} }
        if (request.status >= 200 && request.status < 300) {
          const warning = result.replicationStatus === 'REPLICATION_FAILED'
          if (warning) {
            update({ loaded: file.size, percent: 100, status: 'Stored with warning',
              detail: 'File stored; replication failed. A second copy is not confirmed.' })
          } else {
            setUploads(current => current.filter(item => item.id !== id))
          }
          notify(warning ? `${file.name} stored; replication failed` : `${file.name} uploaded successfully`)
          await refresh()
        } else {
          update({ status: 'Failed', detail: result.message || `Upload rejected (HTTP ${request.status}).` })
          notify(`Upload failed: ${result.message || request.status}`)
        }
      }
      request.onerror = () => update({ status: 'Failed', detail: 'Connection lost. Check the file list before retrying; the backend may have received the file.' })
      request.onabort = () => update({ status: 'Failed', detail: 'Upload interrupted.' })
      request.send(file)
    })
  }
  const upload = event => {
    const selectedFiles = Array.from(event.target.files || [])
    event.target.value = ''
    setPendingFiles(selectedFiles)
  }
  const drop = event => {
    event.preventDefault(); setDragging(false)
    setPendingFiles(Array.from(event.dataTransfer?.files || []))
  }
  const remove = async file => {
    if (!window.confirm(`Delete ${file.fileName} from the cluster?`)) return
    try {
      const response = await fetch(`${API}/files/${file.fileId}`, { method: 'DELETE' })
      if (!response.ok) throw new Error((await response.json()).message || 'Delete failed')
      const result = await response.json()
      notify(result.failedPeers?.length ? `Deleted locally; failed peers: ${result.failedPeers.join(', ')}` : `${file.fileName} deleted`)
      await refresh()
    } catch (err) { notify(`Delete failed: ${err.message}`) }
  }
  return <section className="content">
    <Title title="Files" sub="Everything stored on this peer, read from the Java backend." action={<label className="btn primary"><Icon name="upload" size={16}/>Upload files<input type="file" multiple onChange={upload}/></label>}/>
    <div className={`dropzone ${dragging ? 'over' : ''}`} onDragOver={e => { e.preventDefault(); setDragging(true) }} onDragLeave={() => setDragging(false)} onDrop={drop}>
      <span className="drop-icon"><Icon name="upload" size={22}/></span>
      <div><b>Drop files here to upload</b><span>Each file is hashed and stored by the backend before it is confirmed.</span></div>
      <label className="btn secondary">Choose files<input type="file" multiple onChange={upload}/></label>
    </div>
    <UploadProgress uploads={uploads}/>
    <div className="toolbar">
      <label className="search"><Icon name="search" size={16}/><input placeholder="Search by filename" aria-label="Search by filename" value={query} onChange={e => setQuery(e.target.value)}/></label>
      <span className="count">{visible.length} {visible.length === 1 ? 'file' : 'files'}</span>
    </div>
    <div className="card table-card">
      <div className="table-scroll">
        <table className="files-table">
          <thead><tr><th>Name</th><th>Size</th><th>Created</th><th>Copies</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>{visible.map(file => <tr key={file.fileId}>
            <td><div className="name-cell"><FileBadge name={file.fileName}/><div><button type="button" className="file-details-link" onClick={() => setSelectedFile(file)} aria-label={`View details for ${file.fileName}`}>{file.fileName}</button><small>{file.locations?.join(', ') || 'local peer'}</small></div></div></td>
            <td className="num">{formatBytes(file.size)}</td>
            <td className="num">{formatDate(file.createdAt)}</td>
            <td>{file.replicaCount != null ? <span className={`pill ${file.replicaCount >= 2 ? 'good' : 'warn'}`}><i/>{file.replicaCount}</span> : <span className="faint">—</span>}</td>
            <td><div className="file-actions">
              <a className="icon-btn" href={`${API}/files/${encodeURIComponent(file.fileId)}/download`} aria-label={`Download ${file.fileName}`} title="Download"><Icon name="download" size={16}/></a>
              <button className="icon-btn danger" onClick={() => remove(file)} aria-label={`Delete ${file.fileName}`} title="Delete"><Icon name="trash" size={16}/></button>
            </div></td>
          </tr>)}</tbody>
        </table>
      </div>
      {!visible.length && <Empty icon="inbox" text={query ? 'No files match your search' : 'No files returned by the backend'} hint={query ? 'Try a different filename.' : 'Upload a file to get started.'}/>}
    </div>
    {selectedFile && <FileDetails file={selectedFile} onClose={() => setSelectedFile(null)}/>}
    {pendingFiles.length > 0 && <UploadPreview files={pendingFiles} onCancel={() => setPendingFiles([])} onUpload={() => {
      startUploads(pendingFiles)
      setPendingFiles([])
    }}/>}
  </section>
}

function Peers({ health }) {
  const peers = health?.peers || []
  return <section className="content">
    <Title title="Peers" sub="Live heartbeat state from the Java backend."/>
    <div className="stats three">
      <Stat icon="peers" label="Cluster members" value={peers.length || '—'} note="Reported by health endpoint"/>
      <Stat icon="check" label="Online" value={peers.filter(p => p.status === 'ALIVE').length || '—'} note="Heartbeat responding"/>
      <Stat icon="alert" label="Needs attention" value={peers.filter(p => p.status !== 'ALIVE').length || '0'} note="Suspected or dead"/>
    </div>
    <div className="card table-card">
      <div className="table-scroll">
        <table>
          <thead><tr><th>Peer</th><th>Endpoint</th><th>State</th><th>Failures</th><th>Source</th></tr></thead>
          <tbody>{peers.map(peer => <tr key={peer.peerId}>
            <td><div className="name-cell"><span className="peer-chip">{String(peer.peerId).slice(0, 3)}</span><b>Peer {peer.peerId}</b></div></td>
            <td><code className="mono">{peer.address}</code></td>
            <td><Status status={peer.status}/></td>
            <td className="num">{peer.failures}</td>
            <td>{peer.source}</td>
          </tr>)}</tbody>
        </table>
      </div>
      {!peers.length && <Empty icon="peers" text="No peer data returned by the backend" hint="Check that the Java node is running."/>}
    </div>
  </section>
}

function Repairs({ files }) {
  return <section className="content">
    <Title title="Replication & repairs" sub="Under-replicated files and automatic recovery status."/>
    <div className="stats three">
      <Stat icon="files" label="Required factor" value="2" note="Primary + replica"/>
      <Stat icon="inbox" label="Files visible" value={files.length || '—'} note="Local inventory"/>
      <Stat icon="repairs" label="Active repairs" value="—" note="Not exposed by API yet"/>
    </div>
    <div className="card"><div className="card-title"><h3>Repair monitoring</h3></div><Empty icon="repairs" text="Repair status is not exposed by the current REST API." hint="Repairs will appear here once the backend reports them."/></div>
  </section>
}

function Settings() { return <ClusterSetup/> }

function FileDetails({ file, onClose }) {
  const dialog = useRef(null)
  useEffect(() => { dialog.current.showModal() }, [])
  const date = new Date(file.createdAt)
  return <dialog ref={dialog} className="file-details-dialog" aria-labelledby="file-details-title" onCancel={onClose} onClose={onClose}>
    <div className="file-details-heading">
      <FileBadge name={file.fileName}/>
      <div><h2 id="file-details-title">File details</h2><small>{file.fileName}</small></div>
      <button type="button" className="icon-btn" onClick={() => dialog.current.close()} autoFocus aria-label="Close"><Icon name="close" size={16}/></button>
    </div>
    <dl>
      <dt>Filename</dt><dd>{file.fileName}</dd>
      <dt>Size</dt><dd>{formatBytes(file.size)} ({Number(file.size).toLocaleString()} bytes)</dd>
      <dt>Upload date</dt><dd>{Number.isNaN(date.getTime()) ? file.createdAt : date.toLocaleString()}<small>{file.createdAt}</small></dd>
      <dt>Reported copies</dt><dd>{file.replicaCount ?? 'Unavailable'}</dd>
      <dt>Reported storage locations</dt><dd>{file.locations?.length ? file.locations.join(', ') : 'Unavailable'}</dd>
    </dl>
    <p className="file-details-note">Copy count and locations reflect this API response; other peers may hold additional copies.</p>
    <a className="btn primary" href={`${API}/files/${encodeURIComponent(file.fileId)}/download`}><Icon name="download" size={16}/>Download file</a>
  </dialog>
}

function UploadProgress({ uploads }) {
  if (!uploads.length) return null
  return <div className="upload-progress-list" aria-label="File uploads">{uploads.map(item =>
    <div className={`upload-progress-item ${item.status === 'Failed' ? 'failed' : item.status === 'Stored with warning' ? 'warned' : ''}`} key={item.id}>
      <div className="upload-progress-heading"><b>{item.name}</b><span>{item.status} {item.status === 'Uploading' && `${item.percent}%`}</span></div>
      <progress value={item.percent} max="100" aria-label={`Upload progress for ${item.name}`}/>
      <small role="status">{item.status === 'Processing' ? '100% sent · Waiting for backend verification and replication…' : item.detail || `${formatBytes(item.loaded)} of ${formatBytes(item.total)} sent`}</small>
    </div>
  )}</div>
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}
function formatDate(value) {
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) + ' ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
}

createRoot(document.getElementById('root')).render(<App/>)
