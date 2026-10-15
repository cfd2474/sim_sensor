import maplibregl, { type GeoJSONSource, type Map as MapLibre } from "maplibre-gl";
import { useEffect, useRef } from "react";
import type { GasSource, SourceShape } from "../api/endpoints";
import { anchor, moveTo, outline } from "../leaks/geometry";
import { removeFromMap } from "./layers";

const SOURCE = "leaks";
const FILL = "leaks-fill";
const LINE = "leaks-line";

/**
 * Leak outlines on the map, plus one labelled handle per leak. Clicking selects a leak;
 * dragging the handle moves it (when [editable]) and reports the new shape on release.
 */
export function LeakLayer({
  map,
  sources,
  windFromDeg,
  selectedId,
  editable,
  onSelect,
  onMove,
}: {
  map: MapLibre;
  sources: GasSource[];
  windFromDeg: number | null | undefined;
  selectedId: string | null;
  editable: boolean;
  onSelect: (id: string) => void;
  onMove: (source: GasSource, shape: SourceShape) => void;
}) {
  const handles = useRef(new Map<string, maplibregl.Marker>());
  /** Shapes being dragged, drawn instead of the stored ones until the save comes back. */
  const dragging = useRef(new Map<string, SourceShape>());
  const latest = useRef({ sources, windFromDeg, selectedId, onSelect, onMove });
  latest.current = { sources, windFromDeg, selectedId, onSelect, onMove };

  const draw = () => {
    const { sources: list, windFromDeg: wind, selectedId: sel } = latest.current;
    (map.getSource(SOURCE) as GeoJSONSource | undefined)?.setData({
      type: "FeatureCollection",
      features: list.map((s) => ({
        type: "Feature",
        properties: { id: s.id, enabled: s.enabled, selected: s.id === sel },
        geometry: { type: "Polygon", coordinates: [outline(dragging.current.get(s.id) ?? s.shape, wind)] },
      })),
    });
  };

  // Layers: added once, removed with the component.
  useEffect(() => {
    map.addSource(SOURCE, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
    const before = map.getLayer("trails") ? "trails" : undefined;
    map.addLayer(
      {
        id: FILL,
        type: "fill",
        source: SOURCE,
        paint: {
          "fill-color": ["case", ["get", "enabled"], "#e65100", "#8a8a8a"],
          "fill-opacity": ["case", ["get", "selected"], 0.28, 0.14],
        },
      },
      before,
    );
    map.addLayer(
      {
        id: LINE,
        type: "line",
        source: SOURCE,
        paint: {
          "line-color": ["case", ["get", "enabled"], "#e65100", "#8a8a8a"],
          "line-width": ["case", ["get", "selected"], 3, 1.5],
          "line-dasharray": ["case", ["get", "enabled"], ["literal", [1, 0]], ["literal", [2, 2]]],
        },
      },
      before,
    );
    const click = (e: maplibregl.MapLayerMouseEvent) => {
      const id = e.features?.[0]?.properties?.id as string | undefined;
      if (id) latest.current.onSelect(id);
    };
    map.on("click", FILL, click);
    draw();
    const owned = handles.current;
    return () => {
      map.off("click", FILL, click);
      owned.forEach((h) => h.remove());
      owned.clear();
      removeFromMap(map, [LINE, FILL], [SOURCE]);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map]);

  // Outlines and handles follow the data.
  useEffect(() => {
    dragging.current.clear();
    draw();
    const seen = new Set<string>();
    for (const s of sources) {
      seen.add(s.id);
      let handle = handles.current.get(s.id);
      if (!handle) {
        const el = document.createElement("div");
        el.className = "leak-handle";
        el.addEventListener("click", (e) => {
          e.stopPropagation();
          latest.current.onSelect(el.dataset.id!);
        });
        handle = new maplibregl.Marker({ element: el, draggable: editable }).setLngLat([0, 0]).addTo(map);
        handle.on("drag", () => {
          const source = latest.current.sources.find((x) => x.id === el.dataset.id);
          if (!source) return;
          const p = handle!.getLngLat();
          dragging.current.set(source.id, moveTo(source.shape, { lat: p.lat, lon: p.lng }));
          draw();
        });
        handle.on("dragend", () => {
          const source = latest.current.sources.find((x) => x.id === el.dataset.id);
          const shape = source && dragging.current.get(source.id);
          if (source && shape) latest.current.onMove(source, shape);
        });
        handles.current.set(s.id, handle);
      }
      const a = anchor(s.shape);
      handle.setLngLat([a.lon, a.lat]);
      handle.setDraggable(editable);
      const el = handle.getElement();
      el.dataset.id = s.id;
      el.textContent = s.name;
      el.title = editable ? `${s.name}: drag to move` : s.name;
      el.classList.toggle("off", !s.enabled);
      el.classList.toggle("selected", s.id === selectedId);
    }
    for (const [id, h] of handles.current) {
      if (!seen.has(id)) {
        h.remove();
        handles.current.delete(id);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sources, windFromDeg, selectedId, editable]);

  return null;
}
