import React, { useEffect, useState } from 'react'
import { setupRequest } from './api'
import Icon from './Icon'

const blank = { peerId:'', clusterId:'', secret:'', address:'', peerPort:'', restPort:'', discoveryGroup:'239.255.42.99', discoveryPort:45555 }

export default function ClusterSetup() {
  const [open,setOpen] = useState(false)
  const [form,setForm] = useState(blank)
  const [networks,setNetworks] = useState([])
  const [status,setStatus] = useState(null)
  const [error,setError] = useState('')
  const [connectionError,setConnectionError] = useState('')
  const [message,setMessage] = useState('')
  const [busy,setBusy] = useState(false)
  const [valid,setValid] = useState(false)
  const [initialized,setInitialized] = useState(false)
  useEffect(() => {
    let alive = true
    const poll = async () => {
      try { const data = await setupRequest(); if (alive) { setStatus(data); setConnectionError('') } }
      catch (err) { if (alive) setConnectionError(err.message) }
    }
    poll()
    const timer = setInterval(poll,3000)
    return () => { alive = false; clearInterval(timer) }
  },[])
  const loadNetworks = async () => {
    setBusy(true); setError('')
    try {
      const data = await setupRequest('/networks')
      setNetworks(data.networks)
      setForm(current => ({...current, ...(!initialized && status?.settings ? status.settings : {}),
        secret:current.secret,
        address:data.networks.some(n => n.address === current.address) ? current.address : data.networks.length === 1 ? data.networks[0].address : '',
        peerPort:current.peerPort || data.ports.peerPort, restPort:current.restPort || data.ports.restPort }))
      setInitialized(true); setValid(false)
    } catch (err) { setError(err.message) }
    finally { setBusy(false) }
  }
  const edit = event => { setForm({...form,[event.target.name]:event.target.value}); setValid(false); setMessage('') }
  const submit = async apply => {
    setBusy(true); setError(''); setMessage('')
    try {
      const payload = {...form, peerPort:Number(form.peerPort), restPort:Number(form.restPort), discoveryPort:Number(form.discoveryPort)}
      const result = await setupRequest(apply ? '/apply' : '/validate',payload)
      setValid(true); setMessage(result.message)
      if (apply) { setForm(current => ({...current, secret:''})); setValid(false); setStatus(await setupRequest()) }
    } catch (err) { setError(err.message); setValid(false) }
    finally { setBusy(false) }
  }
  const restart = async () => {
    setBusy(true); setError('')
    try { const result = await setupRequest('/restart',{}); setMessage(result.message); setStatus(await setupRequest()) }
    catch (err) { setError(err.message) }
    finally { setBusy(false) }
  }
  const phaseTone = !status ? 'idle' : ['PEER_ID_CONFLICT','ERROR'].includes(status.phase) ? 'bad' : ['RESTART_REQUIRED','STARTING','DISCOVERING'].includes(status.phase) ? 'warn' : 'good'
  return <section className="content narrow">
    <div className="title"><div><h1>Settings</h1><p>Connect this device to your existing storage cluster.</p></div></div>
    <div className="card setup-card">
      <div className="setup-head"><div><h2>Cluster setup</h2><p>Devices A and B must already use authenticated discovery with matching cluster settings.</p></div>
        {!open && <button className="btn primary" type="button" onClick={() => {setOpen(true); loadNetworks()}} disabled={busy}>Join existing cluster</button>}
      </div>
      {error && <p className="notice bad" role="alert"><Icon name="alert" size={16}/>{error}</p>}
      {connectionError && <p className="notice bad" role="alert"><Icon name="alert" size={16}/>{connectionError}</p>}
      {open && <form onSubmit={event => {event.preventDefault(); submit(false)}}>
        <p className="setup-help">Use a unique ID such as C. The shared credential is submitted to this laptop only and is never saved in browser storage.</p>
        <div className="form">
          <label>Unique peer ID<input required name="peerId" value={form.peerId} onChange={edit} pattern="[A-Za-z0-9_-]{1,40}" autoComplete="off"/></label>
          <label>Cluster ID<input required name="clusterId" value={form.clusterId} onChange={edit} maxLength={80} autoComplete="off"/></label>
          <label className="setup-wide">Shared discovery secret<input required type="password" name="secret" value={form.secret} onChange={edit} minLength={16} maxLength={256} autoComplete="off"/></label>
          <label className="setup-wide">Local network<select required name="address" value={form.address} onChange={edit}><option value="">Choose the network shared with devices A and B</option>{networks.map(n => <option key={n.address} value={n.address}>{n.label}{n.privateLan ? ' (private LAN)' : ''}</option>)}</select></label>
          <label>Peer TCP port<input required type="number" min="1" max="65535" name="peerPort" value={form.peerPort} onChange={edit}/></label>
          <label>Internal REST port<input required type="number" min="1" max="65535" name="restPort" value={form.restPort} onChange={edit}/></label>
          <label>Discovery multicast group<input required name="discoveryGroup" value={form.discoveryGroup} onChange={edit}/></label>
          <label>Discovery UDP port<input required type="number" min="1" max="65535" name="discoveryPort" value={form.discoveryPort} onChange={edit}/></label>
        </div>
        {!networks.length && <p className="notice bad"><Icon name="alert" size={16}/>No usable IPv4 network detected. Connect to the same Wi-Fi or Ethernet network as A and B, then refresh networks.</p>}
        <div className="setup-actions"><button className="btn secondary" type="button" disabled={busy} onClick={loadNetworks}>Refresh networks</button><button className="btn secondary" disabled={busy || !networks.length} type="submit">{busy ? 'Working…' : 'Validate settings'}</button><button className="btn primary" type="button" disabled={busy || !valid} onClick={() => submit(true)}>Apply configuration</button></div>
        <p className="setup-help">Validate does not save changes. Apply saves this device’s configuration. Restart starts the saved node settings and interrupts any transfers on this node; finish transfers first.</p>
      </form>}
      {message && <p className="notice good" role="status"><Icon name="check" size={16}/>{message}</p>}
    </div>
    {status && <div className="card setup-card">
      <div className="setup-head"><h2>Connection status</h2><span className={`pill ${phaseTone}`} role="status"><i/>{status.phase.replaceAll('_',' ')}</span></div>
      {status.settings && <dl className="kv"><div><dt>Peer</dt><dd>{status.settings.peerId}</dd></div><div><dt>Cluster</dt><dd>{status.settings.clusterId}</dd></div><div><dt>Address</dt><dd className="mono">{status.settings.address}:{status.settings.peerPort}</dd></div></dl>}
      {status.phase === 'RESTART_REQUIRED' && <p className="notice warn"><Icon name="alert" size={16}/>Saved changes are not active yet. Finish current transfers, then restart the local node below.</p>}
      {status.phase === 'PEER_ID_CONFLICT' && <p className="notice bad"><Icon name="alert" size={16}/>Another authenticated device is using this peer ID. Open Join existing cluster, choose a different ID, then apply and restart.</p>}
      {status.error && <p className="notice bad" role="alert"><Icon name="alert" size={16}/>{status.error}</p>}
      {status.phase === 'STARTING' && <p className="setup-help">Starting peer, discovery, and heartbeat services…</p>}
      {status.phase === 'DISCOVERING' && <p className="setup-help">Waiting for authenticated peers and a successful heartbeat. Allow at least 15 seconds; this does not yet confirm that joining succeeded.</p>}
      {status.discovery && <p className="setup-help">Discovery: {status.discovery.running ? 'running' : 'stopped'} · accepted {status.discovery.accepted} · rejected {status.discovery.rejected} · last rejection: {status.discovery.lastRejectReason}</p>}
      {status.configured && <button className="btn secondary" disabled={busy || status.phase === 'STARTING'} onClick={restart}><Icon name="repairs" size={16}/>Restart local node with saved configuration</button>}
      <h3>Discovered peers</h3>
      {status.peers.length ? <ul className="setup-peers">{status.peers.map(peer => <li key={peer.id}><b>Peer {peer.id}</b><code className="mono">{peer.address}</code><span>{peer.status}</span><span className={`pill ${peer.verifiedHeartbeat ? 'good' : 'warn'}`}><i/>{peer.verifiedHeartbeat ? 'Heartbeat verified' : 'Awaiting heartbeat'}</span></li>)}</ul> : <p className="setup-help">No other peers discovered yet.</p>}
      <p className="setup-help">{status.guidance}</p><p className="setup-help">Replication factor remains 2. A successful join does not automatically redistribute existing healthy files to this device.</p>
    </div>}
  </section>
}
