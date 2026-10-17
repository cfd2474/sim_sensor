import { useState } from "react";
import { Api, type GeocodeResult, type ExerciseLocation } from "../api/endpoints";
import { ErrorBox } from "./ui";

/**
 * Address search for an exercise location. One lookup per Find press (the address service's
 * usage policy forbids search-as-you-type); the instructor picks one of the places found.
 */
export function LocationPicker({ value, onChange }: { value: ExerciseLocation | null; onChange: (l: ExerciseLocation | null) => void }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<GeocodeResult[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState<unknown>();

  const find = async () => {
    if (query.trim().length < 3) return;
    setSearching(true);
    setError(undefined);
    try {
      setResults(await Api.geocode(query.trim()));
    } catch (e) {
      setError(e);
      setResults(null);
    } finally {
      setSearching(false);
    }
  };

  return (
    <div className="stack location-picker">
      {value && (
        <div className="row wrap">
          <span className="location-chosen">📍 {value.label}</span>
          <button type="button" className="btn secondary small" onClick={() => onChange(null)}>
            Change
          </button>
        </div>
      )}
      {!value && (
        <>
          <div className="row wrap">
            <input
              className="grow"
              placeholder="Address or place, e.g. 1 Main St, Springfield"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  void find();
                }
              }}
              aria-label="Address"
            />
            <button type="button" className="btn secondary" onClick={() => void find()} disabled={searching || query.trim().length < 3}>
              {searching ? "Finding…" : "Find"}
            </button>
          </div>
          <ErrorBox error={error} />
          {results?.length === 0 && <p className="muted small">Nothing found. Try adding the city or a postcode.</p>}
          {results && results.length > 0 && (
            <ul className="location-results">
              {results.map((r) => (
                <li key={`${r.lat},${r.lon}`}>
                  <button type="button" className="link-button" onClick={() => onChange({ label: r.label, lat: r.lat, lon: r.lon })}>
                    {r.label}
                  </button>
                </li>
              ))}
            </ul>
          )}
          <p className="muted small">Address search © OpenStreetMap contributors.</p>
        </>
      )}
    </div>
  );
}
