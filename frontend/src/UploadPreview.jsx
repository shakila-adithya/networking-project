import React, { useEffect, useRef, useState } from 'react'
import Icon from './Icon'
import './upload-preview.css'

function sizeLabel(bytes) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`
}

function previewKind(file) {
  const ext = file.name.split('.').pop().toLowerCase()
  if (file.type.startsWith('image/') || /^(png|jpe?g|gif|webp|bmp|avif|heic|heif|svg)$/.test(ext)) return 'image'
  if (file.type.startsWith('video/') || /^(mp4|webm|mov|mkv|avi)$/.test(ext)) return 'video'
  if (file.type.startsWith('audio/') || /^(mp3|wav|ogg|m4a|flac)$/.test(ext)) return 'audio'
  if (file.type === 'application/pdf' || ext === 'pdf') return 'pdf'
  if (file.type.startsWith('text/') || /^(txt|md|csv|json|xml|js|jsx|ts|tsx|java|py|css|html|log)$/.test(ext)) return 'text'
  return 'unsupported'
}

function LocalPreview({ file }) {
  const [source, setSource] = useState('')
  const [text, setText] = useState(null)
  const [failed, setFailed] = useState(false)
  const kind = previewKind(file)
  const textLimit = 128 * 1024
  useEffect(() => {
    let active = true
    if (kind === 'text') {
      file.slice(0, textLimit).text().then(value => { if (active) setText(value) })
        .catch(() => { if (active) setFailed(true) })
      return () => { active = false }
    }
    if (kind === 'unsupported') return
    const url = URL.createObjectURL(file)
    setSource(url)
    return () => { URL.revokeObjectURL(url) }
  }, [file, kind])
  if (failed || kind === 'unsupported') return <div className="preview-unavailable"><Icon name="files" size={32}/><b>Preview unavailable for this format</b><p>You can still upload this file. Some image and media formats require browser codec support.</p></div>
  if (kind === 'text') return <><pre className="preview-text">{text ?? 'Loading preview…'}</pre>{file.size > textLimit && <p className="preview-hint">Showing only the first 128 KB. The full file will be uploaded.</p>}</>
  if (!source) return <p>Loading preview…</p>
  if (kind === 'image') return <img className="preview-image" src={source} alt={`Preview of ${file.name}`} onError={() => setFailed(true)}/>
  if (kind === 'video') return <video className="preview-video" controls preload="metadata" src={source} onError={() => setFailed(true)}>Your browser cannot preview this video.</video>
  if (kind === 'audio') return <audio className="preview-audio" controls preload="metadata" src={source} onError={() => setFailed(true)}>Your browser cannot preview this audio.</audio>
  return <><object className="preview-pdf" data={source} type="application/pdf" aria-label={`PDF preview of ${file.name}`}><p>Your browser cannot display this PDF preview. You can still upload the file.</p></object><p className="preview-hint">PDF preview depends on your browser’s built-in PDF viewer.</p></>
}

export default function UploadPreview({ files, onCancel, onUpload }) {
  const dialog = useRef(null)
  const [selected, setSelected] = useState(0)
  const file = files[selected]
  useEffect(() => { dialog.current.showModal() }, [])
  return <dialog ref={dialog} className="file-details-dialog upload-preview-dialog" aria-labelledby="upload-preview-title" onCancel={onCancel} onClose={onCancel}>
    <div className="file-details-heading"><div><h2 id="upload-preview-title">Preview before uploading</h2><small>Files stay on this computer until you select Upload.</small></div><button className="icon-btn" type="button" onClick={onCancel} aria-label="Close preview"><Icon name="close" size={16}/></button></div>
    {files.length > 1 && <div className="preview-file-list" aria-label="Files selected for upload">{files.map((item, index) => <button type="button" className={`btn ${index === selected ? 'primary' : 'secondary'}`} aria-pressed={index === selected} onClick={() => setSelected(index)} key={index}>{item.name}</button>)}</div>}
    <div className="preview-file-info"><b>{file.name}</b><span>{sizeLabel(file.size)} · {file.type || 'Unknown file type'}</span></div>
    <div className="preview-content"><LocalPreview key={selected} file={file}/></div>
    <div className="preview-footer"><span>{files.length} {files.length === 1 ? 'file' : 'files'} selected · {sizeLabel(files.reduce((total, item) => total + item.size, 0))}</span><div><button type="button" className="btn secondary" autoFocus onClick={onCancel}>Cancel</button><button type="button" className="btn primary" onClick={onUpload}><Icon name="upload" size={16}/>{files.length === 1 ? 'Upload file' : `Upload all ${files.length} files`}</button></div></div>
  </dialog>
}
