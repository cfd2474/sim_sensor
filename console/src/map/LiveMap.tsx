import maplibregl, { type GeoJSONSource, type Map as MapLibre } from "maplibre-gl";
import { useEffect, useRef } from "react";
import type { ConsoleConfig, Units } from "../api/endpoints";
import { alarmSeverity, isStale, type LiveDevice } from "../live/useExerciseLive";

const SEVERITY_CLASS = ["ok", "warn", "bad"] as const;

/**
 * MapLibre map with one HTML marker per device (name, worst-alarm color, readings in alarm)
 * and a breadcrumb trail. Raster tiles from the server config (OpenStreetMap by default).
 * Other layers (leaks, preview, placing) attach to the map handed out by [onMap].
 */
export function LiveMap({
  config,
  devices,
  selected,
  onSelect,
  units,
  focus,
  onMap,
}: {
  config: ConsoleConfig;
  devices: LiveDevice[];
  selected: Set<string>;
  onSelect: (deviceId: string) => void;
  units: Units;
  /** Extra points (e.g. leak outlines) to include when the map first zooms in. */
  focus: [number, number][];
  onMap: (map: MapLibre | null) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  const map = useRef<MapLibre | null>(null);
  const markers = useRef(new Map<string, maplibregl.Marker>());
  const fitted = useRef(false);
  const scale = useRef<maplibregl.ScaleControl | null>(null);

  useEffect(() => {
    if (!container.current) return;
    const m = new maplibregl.Map({
      container: container.current,
      style: {
        version: 8,
        sources: {
          tiles: { type: "raster", tiles: [config.tileUrl], tileSize: 256, attribution: config.tileAttribution, maxzoom: 19 },
          trails: { type: "geojson", data: { type: "FeatureCollection", features: [] } },
        },
        layers: [
          { id: "tiles", type: "raster", source: "tiles" },
          { id: "trails", type: "line", source: "trails", paint: { "line-color": "#1e6fd9", "line-width": 3, "line-opacity": 0.6 } },
        ],
      },
      center: [-98.5, 39.8],
      zoom: 3,
    });
    m.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
    scale.current = new maplibregl.ScaleControl({ unit: "imperial" });
    m.addControl(scale.current, "bottom-left");
    map.current = m;
    m.once("load", () => onMap(m));
    const owned = markers.current;
    return () => {
      onMap(null);
      owned.forEach((marker) => marker.remove());
      owned.clear();
      m.remove();
      map.current = null;
      scale.current = null;
      fitted.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [config.tileUrl, config.tileAttribution]);

  useEffect(() => scale.current?.setUnit(units === "METRIC" ? "metric" : "imperial"), [units]);

  useEffect(() => {
    const m = map.current;
    if (!m) return;
    const now = Date.now();
    const seen = new Set<string>();

    for (const d of devices) {
      const lat = d.last?.lat;
      const lon = d.last?.lon;
      if (lat == null || lon == null) continue;
      seen.add(d.deviceId);
      let marker = markers.current.get(d.deviceId);
      if (!marker) {
        const el = document.createElement("button");
        el.className = "marker";
        el.addEventListener("click", (e) => {
          e.stopPropagation();
          onSelect(el.dataset.id!);
        });
        marker = new maplibregl.Marker({ element: el, anchor: "left" }).setLngLat([lon, lat]).addTo(m);
        markers.current.set(d.deviceId, marker);
      }
      marker.setLngLat([lon, lat]);
      const el = marker.getElement();
      el.dataset.id = d.deviceId;
      const severity = alarmSeverity(d.last?.alarms);
      el.className = `marker ${SEVERITY_CLASS[severity]}${isStale(d, now) ? " stale" : ""}${selected.has(d.deviceId) ? " selected" : ""}`;
      el.innerHTML = "";
      const dot = document.createElement("span");
      dot.className = "marker-dot";
      const label = document.createElement("span");
      label.className = "marker-label";
      label.textContent = d.name;
      const alarms = d.last?.alarms ?? [];
      if (alarms.length) {
        const sub = document.createElement("small");
        sub.textContent = alarms.join(" ");
        label.appendChild(document.createElement("br"));
        label.appendChild(sub);
      }
      el.append(dot, label);
    }
    for (const [id, marker] of markers.current) {
      if (!seen.has(id)) {
        marker.remove();
        markers.current.delete(id);
      }
    }

    const draw = () => {
      const source = m.getSource("trails") as GeoJSONSource | undefined;
      source?.setData({
        type: "FeatureCollection",
        features: devices
          .filter((d) => d.trail.length > 1)
          .map((d) => ({ type: "Feature", properties: { id: d.deviceId }, geometry: { type: "LineString", coordinates: d.trail } })),
      });
    };
    if (m.isStyleLoaded()) draw();
    else m.once("load", draw);

    // Zoom to the devices and leaks the first time there is something to show.
    if (!fitted.current && (seen.size > 0 || focus.length > 0)) {
      const bounds = new maplibregl.LngLatBounds();
      devices.forEach((d) => d.last?.lat != null && d.last?.lon != null && bounds.extend([d.last.lon, d.last.lat]));
      focus.forEach((p) => bounds.extend(p));
      m.fitBounds(bounds, { padding: 80, maxZoom: 17, duration: 0 });
      fitted.current = true;
    }
  }, [devices, selected, onSelect, focus]);

  return <div ref={container} className="map" />;
}
