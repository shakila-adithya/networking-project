import { ConnectionGlyph, type ConnectionMethod } from "./connection-glyph";
import { Eyebrow } from "./ui";

const steps = [
  ["1", "Choose how to connect", "In the sidebar, open Connection setup and pick Bluetooth, Wi-Fi / LAN, Wi-Fi Direct or Hotspot."],
  ["2", "Scan nearby", "Press Scan nearby. Devices and meshes close to you appear on the map."],
  ["3", "Select and join", "Select a mesh or device on the map, then press Join or Connect. The first connection generates a password for the new mesh. After that, devices enter that password to join."],
] as const;

const ways: { kind: ConnectionMethod; title: string; text: string; when: string }[] = [
  { kind: "bluetooth", title: "Bluetooth", text: "Direct device-to-device radio link. No router and no internet.", when: "Best when nothing else is available. Short range, slower, and only a few links at a time." },
  { kind: "lan", title: "Wi-Fi / LAN", text: "Devices on the same Wi-Fi or Ethernet network find each other.", when: "Fastest. The router only needs to be on; it does not need internet." },
  { kind: "wifi-direct", title: "Wi-Fi Direct", text: "Two devices make their own Wi-Fi link without a router.", when: "Good for large files with no network around. Support depends on your device and system." },
  { kind: "hotspot", title: "Hotspot", text: "One device shares a network that others connect to.", when: "Use when there is no router: connect the devices first, then scan." },
];

const abilities = [
  ["Chat", "Send messages to one person or everyone in the mesh."],
  ["Share files", "Send files to chosen people. Transfers are checked on arrival and can resume."],
  ["Hop through others", "If someone is out of range, their messages pass through devices in between."],
  ["Wait and forward", "If a device is offline, messages can wait on other members and are delivered later."],
  ["Stay private", "Only devices with the mesh password can join. Links are encrypted."],
];

/** Short, plain-language guide on its own page. */
export function HowItWorks() {
  return <section className="how-it-works how-page" aria-labelledby="how-title">
    <div className="how-head"><Eyebrow gold>HOW RELAY WORKS</Eyebrow><h1 id="how-title">Chat and share files with no internet</h1>
      <p>Relay connects nearby devices directly into one mesh. Everything works offline, and the map shows who is connected and how.</p></div>
    <ol className="how-steps">{steps.map(([n, title, text]) => <li key={n}><span className="how-step-number" aria-hidden="true">{n}</span><div><b>{title}</b><p>{text}</p></div></li>)}</ol>
    <h3>Ways to connect</h3>
    <div className="how-ways">{ways.map(item => <article key={item.kind}><span className="how-icon" aria-hidden="true"><ConnectionGlyph kind={item.kind} /></span><div><b>{item.title}</b><p>{item.text}</p><small>{item.when}</small></div></article>)}</div>
    <div className="how-columns">
      <div><h3>What you can do</h3><ul className="how-list">{abilities.map(([title, text]) => <li key={title}><b>{title}</b><span>{text}</span></li>)}</ul></div>
      <div><h3>If signal or Wi-Fi goes down</h3><ul className="how-list">
        <li><b>No internet</b><span>Nothing changes. Relay never needs it.</span></li>
        <li><b>Router or Wi-Fi gone</b><span>Switch to Bluetooth, Wi-Fi Direct or a hotspot and scan again.</span></li>
        <li><b>A device drops out</b><span>The mesh looks for another path through the remaining devices.</span></li>
        <li><b>Nobody in range</b><span>Messages wait on other members and go out when a path returns. A mesh cannot cross a gap with no devices in it.</span></li>
      </ul></div>
    </div>
  </section>;
}
