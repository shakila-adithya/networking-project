import React from 'react'

// Live picture of the cluster: one node per peer, linked to every other peer.
export default function ClusterMesh({ peers = [] }) {
  const list = peers.slice(0, 8)
  const cx = 150, cy = 105, r = list.length > 1 ? 74 : 0
  const nodes = list.map((peer, i) => {
    const angle = (Math.PI * 2 * i) / Math.max(list.length, 1) - Math.PI / 2
    return { peer, x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) }
  })
  const links = []
  nodes.forEach((a, i) => nodes.slice(i + 1).forEach(b => links.push([a, b])))
  return <svg className="mesh" viewBox="0 0 300 210" role="img" aria-label={`Cluster map with ${list.length} peers`}>
    {links.map(([a, b], i) => {
      const ok = a.peer.status === 'ALIVE' && b.peer.status === 'ALIVE'
      return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} className={ok ? 'link' : 'link off'}/>
    })}
    {nodes.map(({ peer, x, y }) => {
      const ok = peer.status === 'ALIVE'
      return <g key={peer.peerId} transform={`translate(${x} ${y})`} className={ok ? 'node' : 'node off'}>
        {ok && <circle className="pulse" r="16"/>}
        <circle className="disc" r="16"/>
        <text textAnchor="middle" dy="4">{String(peer.peerId).slice(0, 3)}</text>
      </g>
    })}
    {!nodes.length && <text x="150" y="108" textAnchor="middle" className="mesh-empty">Waiting for peers</text>}
  </svg>
}
