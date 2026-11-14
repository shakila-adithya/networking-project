# figma2ai · WxH: number=px, fill=stretch to parent, hug=fit content · p:T,R,B,L · r:TL,TR,BR,BL
# text: "content" [text, Family size/weight, color, lh=line-height px, ls=letter-spacing px]
# xN = N repeated siblings — Template is the first instance, rows below list only how each other instance differs from it
# leaves: [icon: name, WxH, file] · [img, WxH, fit] · [divider axis, span, thickness color] · [decorative] = no content
# {Key=Value} = Figma component variant · @Name = named text style, replacing family size/weight
# names like navy/800 or grey/50 in place of a literal value = design token — both resolved in @tokens/@styles

@page template [1440×960, bg:#0B1014, overflow:hidden]

[icon: Relay mark, 36×36, icons/relay-mark.svg, #FFC765]
"relay" [text, Inter 25/700, #F3F5F0, lh:36]
"LOCAL NETWORK" [text, Inter 10/700, #92A4AA, lh:15, ls:1.25]
Main navigation (nav) [row, 476×54, bg:#121A20, gap:4, px:6, r:16, border:1 #2B3940, align:center, justify:center]
  Navigate / Network map (nav) [row, 162×42, bg:#FFC765, gap:8, px:8, r:11, align:center, justify:center]
    [icon: map icon, 17×17, icons/map-icon.svg]
    "Network map" [text, Inter 12/700, #0B1014, lh:17]
  Navigate / Messages (nav) [row, 144×42, gap:8, px:8, r:11, align:center, justify:center]
    [icon: chat icon, 17×17, icons/chat-icon.svg]
    "Messages" [text, Inter 12/500, #92A4AA, lh:17]
  Navigate / Share files (nav) [row, 150×42, gap:8, px:8, r:11, align:center, justify:center]
    [icon: file icon, 17×17, icons/file-icon.svg]
    "Share files" [text, Inter 12/500, #92A4AA, lh:17]
Network connected [ellipse, 6×6, bg:#A9DDB7]
"Local network" [text, Inter 12/500, #F3F5F0, lh:17]
"5 devices online" [text, Inter 10, #92A4AA, lh:15]
Avatar / C (avatar) [38×38, bg:#182229, r:19]
  "C" [text, Inter 11/700, #FFC765, lh:16]
[divider h, 1384, 1 #2B3940B2]
Full network canvas [1440×865, overflow:hidden]
  Ambient glow ×715 [ellipse, 420×420, bg:#FFC7650E]
    Variants (3 unique of 715):
      (as Template)
      bg:#69DFE80A, w:300, h:300
      ×713 · bg:#5E737C52, w:1, h:1