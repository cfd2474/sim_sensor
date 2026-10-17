import { useState } from "react";
import type { Channel } from "../api/endpoints";
import { MAX_O2_ENRICHMENT, oxygenAtSource, oxygenProblem } from "../meter/oxygen";
import { NumberInput } from "./NumberInput";

/**
 * A leak's O₂: below the baseline (depletion) or above it (enrichment), by an amount, with what the
 * meter then reads at the source. Stored as one signed number (negative = depletion).
 */
export function OxygenAmount({ channel, amount, onChange }: { channel: Channel; amount: number; onChange: (amount: number) => void }) {
  // Its own state, so clearing the number while typing doesn't flip the direction.
  const [enriching, setEnriching] = useState(amount > 0);
  const size = Math.abs(amount);
  const problem = oxygenProblem(channel, amount);
  const { reading, overRange } = oxygenAtSource(channel, amount);
  return (
    <>
      <select
        aria-label={`${channel.label} direction`}
        value={enriching ? "above" : "below"}
        onChange={(e) => {
          const up = e.target.value === "above";
          setEnriching(up);
          onChange(up ? Math.min(size || 1, MAX_O2_ENRICHMENT) : -(size || 1));
        }}
      >
        <option value="below">below baseline (depletion)</option>
        <option value="above">above baseline (enrichment)</option>
      </select>
      <NumberInput value={size} onChange={(v) => onChange((enriching ? 1 : -1) * Math.abs(v ?? 0))} />
      <span className={`gas-hint ${problem ? "warn-text small" : "muted small"}`}>
        {problem ?? `%vol: the meter reads ${overRange ? `OL, over the limit (above ${channel.rangeMax})` : reading} at the source`}
      </span>
    </>
  );
}
