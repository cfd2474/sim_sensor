import type { GeoJSONSource, Map as MapLibre, MapMouseEvent } from "maplibre-gl";
import { useEffect, useRef, useState } from "react";
import type { LatLon } from "../api/endpoints";
import { removeFromMap } from "./layers";

export type PlaceKind = "point" | "plume" | "zone";

const SOURCE = "placing";

/**
 * Picks where a new leak goes: one click for a point or plume, clicks around the area for a
 * zone (double-click or Finish to close it, Escape to cancel).
 */
export function PlacementLayer({
  map,
  kind,
  onPlaced,
  onCancel,
}: {
  map: MapLibre;
  kind: PlaceKind;
  onPlaced: (points: LatLon[]) => void;
  onCancel: () => void;
}) {
  const [vertices, setVertices] = useState<LatLon[]>([]);
  const corners = useRef<LatLon[]>([]);
  const update = (next: LatLon[]) => {
    corners.current = next;
    setVertices(next);
  };

  useEffect(() => {
    const canvas = map.getCanvas();
    canvas.style.cursor = "crosshair";
    const zoom = map.doubleClickZoom.isEnabled();
    map.doubleClickZoom.disable();
    map.addSource(SOURCE, { type: "geojson", data: { type: "FeatureCollection", features: [] } });
    map.addLayer({ id: `${SOURCE}-line`, type: "line", source: SOURCE, paint: { "line-color": "#e65100", "line-width": 2, "line-dasharray": [2, 1] } });
    map.addLayer({ id: `${SOURCE}-dots`, type: "circle", source: SOURCE, paint: { "circle-radius": 4, "circle-color": "#e65100" } });
    const key = (e: KeyboardEvent) => e.key === "Escape" && onCancel();
    window.addEventListener("keydown", key);
    return () => {
      window.removeEventListener("keydown", key);
      canvas.style.cursor = "";
      if (zoom && map.style) map.doubleClickZoom.enable();
      removeFromMap(map, [`${SOURCE}-dots`, `${SOURCE}-line`], [SOURCE]);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map]);

  useEffect(() => {
    const click = (e: MapMouseEvent) => {
      const p = { lat: e.lngLat.lat, lon: e.lngLat.lng };
      if (kind !== "zone") onPlaced([p]);
      else update([...corners.current, p]);
    };
    const finish = (e: MapMouseEvent) => {
      e.preventDefault();
      // The double-click's two clicks already added the last corner (twice).
      const v = corners.current;
      const unique = v.filter((p, i) => i === 0 || p.lat !== v[i - 1].lat || p.lon !== v[i - 1].lon);
      update(unique);
      if (unique.length >= 3) onPlaced(unique);
    };
    map.on("click", click);
    if (kind === "zone") map.on("dblclick", finish);
    return () => {
      map.off("click", click);
      map.off("dblclick", finish);
    };
  }, [map, kind, onPlaced]);

  useEffect(() => {
    const coords = vertices.map((p) => [p.lon, p.lat]);
    (map.getSource(SOURCE) as GeoJSONSource | undefined)?.setData({
      type: "FeatureCollection",
      features: [
        ...(coords.length > 1 ? [{ type: "Feature" as const, properties: {}, geometry: { type: "LineString" as const, coordinates: [...coords, coords[0]] } }] : []),
        ...coords.map((c) => ({ type: "Feature" as const, properties: {}, geometry: { type: "Point" as const, coordinates: c } })),
      ],
    });
  }, [map, vertices]);

  const hint =
    kind === "zone"
      ? vertices.length < 3
        ? `Click the corners of the area (${vertices.length} so far, at least 3).`
        : "Keep clicking corners, or double-click / Finish to close the area."
      : kind === "plume"
        ? "Click where the release is. The plume drifts downwind from there."
        : "Click the centre of the leak.";

  return (
    <div className="map-hint">
      <span>{hint}</span>
      {kind === "zone" && vertices.length >= 3 && (
        <button className="btn primary small" onClick={() => onPlaced(vertices)}>
          Finish
        </button>
      )}
      <button className="btn secondary small" onClick={onCancel}>
        Cancel
      </button>
    </div>
  );
}
