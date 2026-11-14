# figma2ai · WxH: number=px, fill=stretch to parent, hug=fit content · p:T,R,B,L · r:TL,TR,BR,BL
# text: "content" [text, Family size/weight, color, lh=line-height px, ls=letter-spacing px]
# xN = N repeated siblings — Template is the first instance, rows below list only how each other instance differs from it
# leaves: [icon: name, WxH, file] · [img, WxH, fit] · [divider axis, span, thickness color] · [decorative] = no content
# {Key=Value} = Figma component variant · @Name = named text style, replacing family size/weight
# names like navy/800 or grey/50 in place of a literal value = design token — both resolved in @tokens/@styles

@page 01 / Offline Mesh Network Map / Improved [1440×960, bg:#0B1014, overflow:hidden]

[icon: Relay logo, 36×36, icons/relay-mark.svg, #FFC765]
"relay" [text, Inter 25/700, #F3F5F0, lh:36]
"OFFLINE MESH" [text, Inter 10/700, #92A4AA, lh:15, ls:1.15]
Main navigation (nav) [row, 476×54, bg:#121A20, gap:4, px:6, r:16, border:1 #2B3940, align:center, justify:center]
  Navigation / Network map (nav) [row, 162×42, bg:#FFC765, gap:8, px:8, r:11, align:center, justify:center]
    [icon: map icon, 17×17, icons/map-icon-5.svg]
    "Network map" [text, Inter 12/700, #0B1014, lh:17]
  Navigation / Messages (nav) [row, 144×42, gap:8, px:8, r:11, align:center, justify:center]
    [icon: chat icon, 17×17, icons/chat-icon.svg]
    "Messages" [text, Inter 12/500, #92A4AA, lh:17]
  Navigation / Share files (nav) [row, 150×42, gap:8, px:8, r:11, align:center, justify:center]
    [icon: file icon, 17×17, icons/file-icon.svg]
    "Share files" [text, Inter 12/500, #92A4AA, lh:17]
Bluetooth active [ellipse, 6×6, bg:#69DFE8]
"Bluetooth active" [text, Inter 12/500, #F3F5F0, lh:17]
"Internet unavailable" [text, Inter 10, #92A4AA, lh:15]
Avatar / Y (avatar) [38×38, bg:#182229, r:19]
  "Y" [text, Inter 11/700, #FFC765, lh:16]
[divider h, 1384, 1 #2B3940B2]
Live Bluetooth mesh topology [1440×865, overflow:hidden]
  Ambient glow ×701 [ellipse, 400×400, bg:#FFC7650D]
    Variants (12 unique of 701):
      (as Template)
      bg:#69DFE809, w:290, h:290
      bg:#A9DDB706, w:250, h:250
      ×690 · bg:#61747B3D, w:1, h:1
      w:250, h:155, border:1 #61747B1B, bg:none
      w:320, h:200, border:1 #61747B1B, bg:none
      w:390, h:245, border:1 #61747B1B, bg:none
      w:460, h:290, border:1 #61747B1B, bg:none
      w:530, h:335, border:1 #61747B1B, bg:none
      w:600, h:380, border:1 #61747B1B, bg:none
      w:670, h:425, border:1 #61747B1B, bg:none
      w:740, h:470, border:1 #61747B1B, bg:none
  "LIVE NETWORK TOPOLOGY" [text, Inter 10/700, #FFC765, lh:15, ls:1.15]
  "The mesh,
in motion." [text, Inter 32/500, #F3F5F0, lh:38]
  "See how devices connect, relay and adapt." [text, Inter 12, #92A4AA, lh:17]
  Mesh active status [row, 128×27, bg:#182229, gap:7, px:10, r:13, align:center, justify:center]
    Active dot [ellipse, 6×6, bg:#A9DDB7]
    "MESH ACTIVE" [text, Inter 10/500, #A9DDB7, lh:15]
  Nearby discovery status [row, 176×27, bg:#182229, gap:7, px:10, r:13, align:center, justify:center]
    [icon: bluetooth icon, 13×13, icons/bluetooth-icon-4.svg]
    "Discovering nearby" [text, Inter 10/500, #69DFE8, lh:15]
  Find device [239×42, bg:#121A20, r:11, border:1 #2B3940]
    [icon: search icon, 17×17, icons/search-icon-3.svg]
    "Find a device" [text, Inter 12, #92A4AA, lh:17]
  Mesh connections and traffic [1050×760]
    [decorative, 1050×760]
    [decorative, 1050×760]
    [decorative, 1050×760]
    [decorative, 1050×760]
    [decorative, 1050×760]
    Unavailable link / Nimal to Peer 07 (nav) [1050×760, overflow:hidden]
      [decorative, 196×35, #E79B918C]
    Alternative link / Ravi to Kasun [1050×760, overflow:hidden]
      [decorative, 342×123, #A9DDB7BF]
    Nearby device / discovered [1050×760, overflow:hidden]
      [decorative, 127×131, #61747B80]
    Message packet / hop 1 glow ×10 [ellipse, 18×18, bg:#69DFE81A]
      Variants (6 unique of 10):
        ×2
        ×2 · bg:#69DFE8, w:5, h:5
        ×2 · bg:#FFC7651A
        ×2 · bg:#FFC765, w:6, h:6
        bg:#A9DDB71A
        bg:#A9DDB7, w:5, h:5
  Message forwarding indicator [row, 135×27, bg:#121A20, gap:6, px:9, r:8, border:1 #69DFE840, align:center, justify:center]
    [icon: chat icon, 12×12, icons/chat-icon-4.svg]
    "Message · 2 hops" [text, Inter 10/500, #69DFE8, lh:15]
  Outgoing file indicator [row, 143×27, bg:#121A20, gap:6, px:9, r:8, border:1 #FFC76540, align:center, justify:center]
    [icon: file icon, 12×12, icons/file-icon-6.svg]
    "Outgoing file · 68%" [text, Inter 10/500, #FFC765, lh:15]
  Alternative route indicator [row, 142×27, bg:#121A20, gap:6, px:9, r:8, border:1 #A9DDB740, align:center, justify:center]
    [icon: refresh icon, 12×12, icons/refresh-icon-4.svg]
    "Alternative route" [text, Inter 10/500, #A9DDB7, lh:15]
  Unavailable link indicator (nav) [row, 112×27, bg:#121A20, gap:6, px:9, r:8, border:1 #E79B9140, align:center, justify:center]
    [icon: signal icon, 12×12, icons/signal-icon.svg]
    "Link lost" [text, Inter 10/500, #E79B91, lh:15]
  Devices in the mesh [1050×760]
    Your device / outer signal [ellipse, 126×126, border:1 #FFC76529]
    Your device / inner signal [ellipse, 100×100, border:1 #FFC76547]
    Device / You [58×58, bg:#FFC765, r:19, border:1 #FFC765, shadow:0 0 28 2 #FFC76526]
      [icon: phone icon, 24×24, icons/phone-icon.svg]
      Device state [ellipse, 8×8, bg:#A9DDB7]
    "You" [text, Inter 13/700, #FFC765, lh:19]
    "This device" [text, Inter 10, #92A4AA, lh:15]
    Device / Maya [58×58, bg:#121A20, r:19, border:1 #A9DDB799]
      [icon: phone icon, 24×24, icons/phone-icon-2.svg]
      Device state [ellipse, 8×8, bg:#A9DDB7]
    "Maya" [text, Inter 13/700, #F3F5F0, lh:19]
    "Direct connection" [text, Inter 10, #92A4AA, lh:15]
    Device / Nimal [58×58, bg:#121A20, r:19, border:1 #A9DDB799]
      [icon: phone icon, 24×24, icons/phone-icon-2.svg]
      Device state [ellipse, 8×8, bg:#A9DDB7]
    "Nimal" [text, Inter 13/700, #F3F5F0, lh:19]
    "Direct connection" [text, Inter 10, #92A4AA, lh:15]
    Device / Amaya [58×58, bg:#121A20, r:19, border:1 #69DFE899]
      [icon: phone icon, 24×24, icons/phone-icon-3.svg]
      Device state [ellipse, 8×8, bg:#69DFE8]
    "Amaya" [text, Inter 13/700, #F3F5F0, lh:19]
    "Via Maya · 2 hops" [text, Inter 10, #92A4AA, lh:15]
    Device / Ravi [58×58, bg:#121A20, r:19, border:1 #69DFE899]
      [icon: laptop icon, 24×24, icons/laptop-icon.svg]
      Device state [ellipse, 8×8, bg:#69DFE8]
    "Ravi" [text, Inter 13/700, #F3F5F0, lh:19]
    "Via Nimal · 2 hops" [text, Inter 10, #92A4AA, lh:15]
    Device / Kasun [58×58, bg:#121A20, r:19, border:1 #A9DDB799]
      [icon: phone icon, 24×24, icons/phone-icon-2.svg]
      Device state [ellipse, 8×8, bg:#A9DDB7]
    "Kasun" [text, Inter 13/700, #F3F5F0, lh:19]
    "Direct connection" [text, Inter 10, #92A4AA, lh:15]
    Device / Peer 07 [58×58, bg:#121A20, r:19, border:1 #E79B9199]
      [icon: phone icon, 24×24, icons/phone-icon-4.svg]
      Device state [ellipse, 8×8, bg:#E79B91]
    "Peer 07" [text, Inter 13/700, #92A4AA, lh:19]
    "Unavailable" [text, Inter 10, #92A4AA, lh:15]
    Device / Nearby device [58×58, bg:#121A20, r:19, border:1 #61747B99]
      [icon: phone icon, 24×24, icons/phone-icon-5.svg]
      Device state [ellipse, 8×8, bg:#61747B]
    "Nearby device" [text, Inter 13/700, #92A4AA, lh:19]
    "Discovered" [text, Inter 10, #92A4AA, lh:15]
  Network inspector [308×686, bg:#121A20, r:20, border:1 #2B3940]
    "NETWORK OVERVIEW" [text, Inter 10/700, #92A4AA, lh:15, ls:1.15]
    [icon: more icon, 19×19, icons/more-icon-3.svg]
    [icon: Bluetooth mesh icon, 46×46, icons/bluetooth-mesh-icon.svg, #FFC765]
    "Offline mesh" [text, Inter 18/700, #F3F5F0, lh:26]
    "Bluetooth connections" [text, Inter 11, #92A4AA, lh:16]
    Mesh status [row, 104×26, bg:#182229, gap:7, px:10, r:13, align:center, justify:center]
      Mesh active [ellipse, 6×6, bg:#A9DDB7]
      "Active" [text, Inter 10/500, #A9DDB7, lh:15]
    Discovery status [row, 156×26, bg:#182229, gap:7, px:10, r:13, align:center, justify:center]
      [icon: bluetooth icon, 12×12, icons/bluetooth-icon-5.svg]
      "Discovering" [text, Inter 10/500, #69DFE8, lh:15]
    [divider h, 268, 1 #2B3940]
    CONNECTED ×13 [text, 73×15]
      Variants (13 unique of 13):
        CONNECTED
        w:17, h:38, color:#F3F5F0, fontSize:26, fontWeight:500 · 6
        w:41, h:16, fontSize:11, fontWeight:400 · devices
        w:81 · DIRECT LINKS
        w:17, h:38, color:#F3F5F0, fontSize:26, fontWeight:500 · 3
        w:46, h:16, fontSize:11, fontWeight:400 · from you
        w:75 · DISCOVERED
        w:53, h:19, color:#F3F5F0, fontSize:13, fontWeight:500 · 1 nearby
        w:88, fontWeight:400 · Not connected yet
        w:70 · REACHABLE
        w:70, h:19, color:#F3F5F0, fontSize:13, fontWeight:500 · 2 via relays
        w:64, fontWeight:400 · Multiple hops
        w:86 · SELF-HEALING
    Route recovery status [268×82, bg:#182229, r:11]
      [icon: refresh icon, 17×17, icons/refresh-icon-5.svg]
      "Alternative route available" [text, Inter 11/700, #F3F5F0, lh:16]
      "A lost connection was bypassed
through another connected device." [text, Inter 10, #92A4AA, lh:15]
    "RECENT NETWORK EVENTS" [text, Inter 10/700, #92A4AA, lh:15, ls:1.15]
    Route restored dot [ellipse, 7×7, bg:#A9DDB7]
    "Route restored" [text, Inter 11/500, #F3F5F0, lh:16]
    "Kasun ↔ Ravi" [text, Inter 10, #92A4AA, lh:15]
    "Now" [text, Inter 9, #61747B, lh:13]
    Link unavailable dot (nav) [ellipse, 7×7, bg:#E79B91]
    "Link unavailable" [text, Inter 11/500, #F3F5F0, lh:16]
    "Nimal ↔ Peer 07" [text, Inter 10, #92A4AA, lh:15]
    "1m" [text, Inter 9, #61747B, lh:13]
    Device discovered dot [ellipse, 7×7, bg:#69DFE8]
    "Device discovered" [text, Inter 11/500, #F3F5F0, lh:16]
    "Nearby device found" [text, Inter 10, #92A4AA, lh:15]
    "2m" [text, Inter 9, #61747B, lh:13]
  Network map legend [row, 575×42, bg:#121A20, gap:9, px:11, r:11, border:1 #2B3940, align:center, justify:center]
    Legend / Connected [row, 102×24, gap:6, align:center, justify:center]
      Legend line [rect, 12×2, bg:#FFC765, r:1]
      "Connected" [text, Inter 10, #92A4AA, lh:15]
    Legend / Forwarding [row, 102×24, gap:6, align:center, justify:center]
      Legend line [rect, 12×2, bg:#69DFE8, r:1]
      "Forwarding" [text, Inter 10, #92A4AA, lh:15]
    Legend / New route [row, 102×24, gap:6, align:center, justify:center]
      Legend line [rect, 12×2, bg:#A9DDB7, r:1]
      "New route" [text, Inter 10, #92A4AA, lh:15]
    Legend / Link lost [row, 102×24, gap:6, align:center, justify:center]
      Legend line [rect, 12×2, bg:#E79B91, r:1]
      "Link lost" [text, Inter 10, #92A4AA, lh:15]
    Legend / Discovered [row, 102×24, gap:6, align:center, justify:center]
      Legend line [rect, 12×2, bg:#61747B, r:1]
      "Discovered" [text, Inter 10, #92A4AA, lh:15]
  Map tools [row, 150×48, bg:#121A20, gap:5, px:7, r:12, border:1 #2B3940, align:center, justify:center]
    [icon: Map control / focus, 40×34, icons/map-control-focus.svg, #182229]
    [icon: Map control / plus, 40×34, icons/map-control-plus.svg, #182229]
    [icon: Map control / minus, 40×34, icons/map-control-minus.svg, #182229]
  "TOPOLOGY VIEW · POSITIONS ARE ILLUSTRATIVE" [text, Inter 10/700, #61747B, lh:15, ls:1.15]
  "Internet unavailable" [text, Inter 11, #92A4AA, lh:16]
  "Mesh active over Bluetooth" [text, Inter 10, #69DFE8, lh:15]
Live network event [438×52, bg:#182229, r:14, border:1 #A9DDB740]
  [icon: refresh icon, 18×18, icons/refresh-icon-6.svg]
  "Network adapted" [text, Inter 12/700, #F3F5F0, lh:17]
  "A connection was lost · another route is available" [text, Inter 10, #92A4AA, lh:15]
  "NOW" [text, Inter 9/700, #A9DDB7, lh:13]