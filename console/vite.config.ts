import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development, forward API and WebSocket calls to a server on :8080.
export default defineConfig({
  plugins: [react()],
  build: {
    // MapLibre is large and changes rarely: keep it in its own long-cached file.
    rollupOptions: { output: { manualChunks: { maplibre: ["maplibre-gl"] } } },
    chunkSizeWarningLimit: 1100,
  },
  server: {
    proxy: {
      "/health": "http://localhost:8080",
      "/api": "http://localhost:8080",
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
});
