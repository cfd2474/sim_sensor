import { useEffect, useState } from "react";
import { round1 } from "../units/units";

/**
 * A number field over a stored value, optionally shown in other units. Keeps what the user
 * types (e.g. "1.") and only reports values that parse. Empty means null when [optional].
 */
export function NumberInput({
  value,
  onChange,
  toDisplay = (v) => v,
  fromDisplay = (v) => v,
  optional = false,
  min = 0,
  placeholder,
  size = 6,
}: {
  value: number | null | undefined;
  onChange: (value: number | null) => void;
  toDisplay?: (stored: number) => number;
  fromDisplay?: (shown: number) => number;
  optional?: boolean;
  min?: number;
  placeholder?: string;
  size?: number;
}) {
  const format = (v: number | null | undefined) => (v == null ? "" : String(round1(toDisplay(v))));
  const [text, setText] = useState(format(value));

  // Follow outside changes (e.g. units switched, leak dragged), not our own edits.
  useEffect(() => {
    const typed = text.trim() === "" ? null : fromDisplay(Number(text));
    const same = value == null ? typed == null : typed != null && Math.abs(typed - value) < 1e-6 * Math.max(1, Math.abs(value));
    if (!same) setText(format(value));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [value, toDisplay]);

  return (
    <input
      type="number"
      step="any"
      min={min}
      size={size}
      value={text}
      placeholder={placeholder}
      onChange={(e) => {
        setText(e.target.value);
        if (e.target.value.trim() === "") {
          if (optional) onChange(null);
          return;
        }
        const n = Number(e.target.value);
        if (Number.isFinite(n)) onChange(fromDisplay(n));
      }}
    />
  );
}
