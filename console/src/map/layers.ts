import type { Map as MapLibre } from "maplibre-gl";

/**
 * Removes layers, then sources, from [map]. Does nothing once the map itself is gone: when the
 * Live tab closes, the map may be destroyed before the layers' own clean-up runs.
 */
export function removeFromMap(map: MapLibre, layerIds: string[], sourceIds: string[]) {
  if (!map.style) return;
  layerIds.forEach((id) => map.getLayer(id) && map.removeLayer(id));
  sourceIds.forEach((id) => map.getSource(id) && map.removeSource(id));
}
