import type { ImageSource, Map as MapLibre } from "maplibre-gl";
import { useEffect, useRef, useState } from "react";
import { Api, type Channel, type PreviewGrid } from "../api/endpoints";
import { errorMessage } from "../api/client";
import { removeFromMap } from "./layers";

const SOURCE = "preview";
const LAYER = "preview";
const CELLS = 96;
/** While the exercise runs the picture changes with time (ramps, growth, decay). */
const LIVE_REFRESH_MS = 3000;

type Rgba = [number, number, number, number];

/** How bad a concentration is for the channel: 0 nothing, up to 1 below A1, 2 at A1, 3 at A2. */
export function severity(channel: Channel, contribution: number): number {
  if (channel.lowAlarmFalling) {
    // O₂: leaks lower it (depletion, down to LOW) or raise it (enrichment, up to HIGH).
    const reading = channel.baseline + contribution;
    const low = channel.lowAlarm ?? channel.baseline - 1.4;
    const high = channel.highAlarm ?? channel.baseline + 2.6;
    if (reading <= low || reading >= high) return 3;
    const off = Math.abs(contribution);
    const toAlarm = contribution < 0 ? channel.baseline - low : high - channel.baseline;
    return off > 0 ? Math.min(1, off / toAlarm) : 0;
  }
  const low = channel.lowAlarm ?? channel.highAlarm ?? channel.rangeMax;
  if (channel.highAlarm != null && contribution >= channel.highAlarm) return 3;
  if (contribution >= low) return 2;
  return contribution > 0 ? Math.min(1, contribution / low) : 0;
}

export function color(level: number): Rgba {
  if (level >= 3) return [198, 40, 40, 170];
  if (level >= 2) return [245, 160, 0, 150];
  if (level < 0.03) return [0, 0, 0, 0];
  return [30, 111, 217, Math.round(30 + 90 * level)];
}

function render(grid: PreviewGrid, channel: Channel): string {
  const canvas = document.createElement("canvas");
  canvas.width = grid.cols;
  canvas.height = grid.rows;
  const ctx = canvas.getContext("2d")!;
  const image = ctx.createImageData(grid.cols, grid.rows);
  grid.values.forEach((v, i) => image.data.set(color(severity(channel, v)), i * 4));
  ctx.putImageData(image, 0, 0);
  return canvas.toDataURL();
}

/**
 * Expected concentration of one gas over the visible map, from the server (the same model as
 * the phones), drawn under the leak outlines. Refetched when the map moves, the leaks change,
 * and every few seconds while the exercise runs.
 */
export function PreviewLayer({
  map,
  exerciseId,
  channel,
  revision,
  live,
  onGrid,
}: {
  map: MapLibre;
  exerciseId: string;
  channel: Channel;
  /** Changes whenever the leaks or the exercise changed. */
  revision: unknown;
  live: boolean;
  onGrid: (grid: PreviewGrid | null, error?: string) => void;
}) {
  const [moved, setMoved] = useState(0);
  const call = useRef(0);

  useEffect(() => {
    const bump = () => setMoved((n) => n + 1);
    map.on("moveend", bump);
    return () => {
      map.off("moveend", bump);
      removeFromMap(map, [LAYER], [SOURCE]);
      onGrid(null);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map]);

  useEffect(() => {
    let timer: number | undefined;
    const load = async () => {
      const id = ++call.current;
      const b = map.getBounds();
      try {
        const grid = await Api.preview(
          exerciseId,
          channel.id,
          { south: b.getSouth(), west: b.getWest(), north: b.getNorth(), east: b.getEast() },
          CELLS,
        );
        if (id !== call.current || !map.style) return;
        const url = render(grid, channel);
        const coordinates: [[number, number], [number, number], [number, number], [number, number]] = [
          [grid.west, grid.north],
          [grid.east, grid.north],
          [grid.east, grid.south],
          [grid.west, grid.south],
        ];
        const existing = map.getSource(SOURCE) as ImageSource | undefined;
        if (existing) existing.updateImage({ url, coordinates });
        else {
          map.addSource(SOURCE, { type: "image", url, coordinates });
          const before = map.getLayer("leaks-fill") ? "leaks-fill" : map.getLayer("trails") ? "trails" : undefined;
          map.addLayer({ id: LAYER, type: "raster", source: SOURCE, paint: { "raster-resampling": "linear", "raster-fade-duration": 0 } }, before);
        }
        onGrid(grid);
      } catch (e) {
        if (id === call.current) onGrid(null, errorMessage(e));
      }
      if (live && id === call.current) timer = window.setTimeout(load, LIVE_REFRESH_MS);
    };
    void load();
    return () => {
      call.current++; // a request still in flight is ignored when it lands
      window.clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, exerciseId, channel, revision, live, moved]);

  return null;
}
