"use client";
import { useEffect, useImperativeHandle, useRef, useState } from "react";
import type { KeyboardEvent, PointerEvent, ReactNode, Ref } from "react";

export type MapCamera = { centerOn: (x: number, y: number) => void };
type View = { x: number; y: number; scale: number };
type Point = { x: number; y: number; startX: number; startY: number };
const initial: View = { x: 0, y: 0, scale: 1 };
const minScale = 0.5, maxScale = 2.5;
const limitScale = (scale: number) => Math.max(minScale, Math.min(maxScale, scale));

/** A local topology camera: no map tiles, location services or network requests. */
export function MapViewport({ children, cameraRef, self, onClear }: {
  children: ReactNode;
  cameraRef: Ref<MapCamera>;
  self: { x: number; y: number };
  onClear: () => void;
}) {
  const canvas = useRef<HTMLDivElement>(null);
  const current = useRef(initial);
  const pointers = useRef(new Map<number, Point>());
  const moved = useRef(false);
  const [view, setView] = useState(initial);
  const [dragging, setDragging] = useState(false);

  function update(next: View) {
    const bounds = canvas.current?.getBoundingClientRect();
    if (!bounds) return;
    // Keep travel bounded. Fit network always brings every device back into view.
    next = { ...next, x: Math.max(bounds.width * (0.1 - next.scale), Math.min(bounds.width * 0.9, next.x)),
      y: Math.max(bounds.height * (0.1 - next.scale), Math.min(bounds.height * 0.9, next.y)) };
    current.current = next;
    setView(next);
  }
  function zoom(factor: number, x?: number, y?: number) {
    const bounds = canvas.current?.getBoundingClientRect();
    if (!bounds) return;
    const old = current.current, scale = limitScale(old.scale * factor);
    const anchorX = x ?? bounds.width / 2, anchorY = y ?? bounds.height / 2;
    update({ scale, x: anchorX - (anchorX - old.x) * scale / old.scale,
      y: anchorY - (anchorY - old.y) * scale / old.scale });
  }
  function centerOn(x: number, y: number) {
    const bounds = canvas.current?.getBoundingClientRect();
    if (!bounds) return;
    const scale = Math.max(1, current.current.scale);
    update({ scale, x: bounds.width / 2 - x / 1050 * bounds.width * scale,
      y: bounds.height / 2 - y / 760 * bounds.height * scale });
  }
  useImperativeHandle(cameraRef, () => ({ centerOn }));

  useEffect(() => {
    const element = canvas.current;
    if (!element) return;
    // A non-passive listener prevents the page from scrolling while zooming this map.
    const wheel = (event: WheelEvent) => {
      event.preventDefault();
      const bounds = element.getBoundingClientRect();
      const unit = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? bounds.height : 1;
      zoom(Math.exp(-Math.max(-100, Math.min(100, event.deltaY * unit)) * 0.003), event.clientX - bounds.left, event.clientY - bounds.top);
    };
    element.addEventListener("wheel", wheel, { passive: false });
    let width = element.clientWidth, height = element.clientHeight;
    const observer = new ResizeObserver(() => {
      if (width !== element.clientWidth || height !== element.clientHeight) {
        width = element.clientWidth; height = element.clientHeight;
        update(initial);
      }
    });
    observer.observe(element);
    return () => { element.removeEventListener("wheel", wheel); observer.disconnect(); };
  }, []);

  function point(event: PointerEvent<HTMLDivElement>): Point {
    const bounds = event.currentTarget.getBoundingClientRect();
    return { x: event.clientX - bounds.left, y: event.clientY - bounds.top,
      startX: event.clientX - bounds.left, startY: event.clientY - bounds.top };
  }
  function down(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0) return;
    if (pointers.current.size === 0) moved.current = false;
    pointers.current.set(event.pointerId, point(event));
    // Capture on the original button so a tap still activates that device.
    const target = (event.target as HTMLElement).closest<HTMLElement>("button") || event.currentTarget;
    target.setPointerCapture(event.pointerId);
    if (target === event.currentTarget) target.focus({ preventScroll: true });
    if (pointers.current.size > 1) moved.current = true;
  }
  function move(event: PointerEvent<HTMLDivElement>) {
    const previous = pointers.current.get(event.pointerId);
    if (!previous) return;
    const next = { ...point(event), startX: previous.startX, startY: previous.startY };
    const before = [...pointers.current.values()];
    pointers.current.set(event.pointerId, next);
    const after = [...pointers.current.values()];
    if (after.length === 1) {
      if (!moved.current && Math.hypot(next.x - next.startX, next.y - next.startY) < 5) return;
      moved.current = true;
      update({ ...current.current, x: current.current.x + next.x - previous.x, y: current.current.y + next.y - previous.y });
    } else if (after.length === 2) {
      const distance = (points: Point[]) => Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y);
      const oldDistance = distance(before);
      if (oldDistance < 1) return;
      moved.current = true;
      const old = current.current, scale = limitScale(old.scale * distance(after) / oldDistance);
      update({ scale,
        x: (after[0].x + after[1].x) / 2 - ((before[0].x + before[1].x) / 2 - old.x) * scale / old.scale,
        y: (after[0].y + after[1].y) / 2 - ((before[0].y + before[1].y) / 2 - old.y) * scale / old.scale });
    }
    setDragging(true);
  }
  function end(event: PointerEvent<HTMLDivElement>) {
    pointers.current.delete(event.pointerId);
    if (pointers.current.size === 0) setDragging(false);
  }
  function keyboard(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === "Escape") { onClear(); return; }
    if (event.target !== event.currentTarget) return;
    const old = current.current;
    switch (event.key) {
      case "+": case "=": zoom(1.25); break;
      case "-": zoom(0.8); break;
      case "0": case "Home": update(initial); break;
      case "ArrowLeft": update({ ...old, x: old.x + 60 }); break;
      case "ArrowRight": update({ ...old, x: old.x - 60 }); break;
      case "ArrowUp": update({ ...old, y: old.y + 60 }); break;
      case "ArrowDown": update({ ...old, y: old.y - 60 }); break;
      default: return;
    }
    event.preventDefault();
  }
  return <div className="topology">
    <div ref={canvas} className={`map-canvas${dragging ? " dragging" : ""}`} role="region" aria-label="Interactive network map" aria-describedby="map-instructions" tabIndex={0}
      onPointerDown={down} onPointerMove={move} onPointerUp={end} onPointerCancel={end} onLostPointerCapture={end}
      onKeyDown={keyboard}
      onFocus={event => {
        const node = (event.target as HTMLElement).closest<HTMLElement>(".map-node");
        if (!node?.matches(":focus-visible")) return;
        const bounds = event.currentTarget.getBoundingClientRect(), tile = node.getBoundingClientRect();
        if (tile.left < bounds.left || tile.right > bounds.right || tile.top < bounds.top || tile.bottom > bounds.bottom)
          centerOn(node.offsetLeft / event.currentTarget.clientWidth * 1050, node.offsetTop / event.currentTarget.clientHeight * 760);
      }}
      onClickCapture={event => { if (event.detail > 0 && moved.current) { event.preventDefault(); event.stopPropagation(); } }}
      onClick={event => { if (!(event.target as HTMLElement).closest(".map-node")) onClear(); }}
      onDoubleClick={event => { if (!(event.target as HTMLElement).closest(".map-node")) { const bounds = event.currentTarget.getBoundingClientRect(); zoom(1.5, event.clientX - bounds.left, event.clientY - bounds.top); } }}>
      <div className="topology-inner" style={{ transform: `translate(${view.x}px, ${view.y}px) scale(${view.scale})` }}>{children}</div>
    </div>
    <p className="map-instructions" id="map-instructions">Drag to move · Scroll or pinch to zoom · Click a device<br /><span>Keyboard: arrows to move, + / − to zoom, 0 to fit</span></p>
    <div className="map-controls" role="group" aria-label="Map controls">
      <div className="map-navigation"><button type="button" onClick={() => centerOn(self.x, self.y)} title="Center on your device">Find me</button><button type="button" onClick={() => update(initial)} title="Show the whole network">Fit network</button></div>
      <div className="map-zoom"><button type="button" aria-label="Zoom out" disabled={view.scale <= minScale} onClick={() => zoom(0.8)}>−</button><output aria-label="Map zoom">{Math.round(view.scale * 100)}%</output><button type="button" aria-label="Zoom in" disabled={view.scale >= maxScale} onClick={() => zoom(1.25)}>+</button></div>
    </div>
  </div>;
}
