package com.taksolutions.simsensor.profile

/**
 * The sensor types a monitor can have. A monitor's channels are chosen from this list; its id,
 * label, unit and alarm direction always come from here, the rest (range, alarm levels,
 * latching, response time) starts from these defaults and is editable per monitor.
 *
 * Defaults: the original five as in DESIGN §7.1. NH₃, SO₂, Cl₂, CO₂ alarm levels from the
 * Industrial Scientific default instrument alarm settings (low = OSHA PEL / NIOSH, high = 2 ×
 * PEL); HF by the same rule on its OSHA PEL of 3 ppm. Ranges: Cl₂ 0–20 and SO₂ 0–100 ppm as
 * Dräger XXS sensors; NH₃ 0–200, HF 0–10 ppm, CO₂ 0–5 %vol as typical portable sensors.
 *
 * VOC is a photoionization detector (PID), read as isobutylene equivalent, as the MSA ALTAIR io 6
 * and Blackline G8 carry: range 0–2000 ppm at 0.1 ppm from the io 6 product page. Its alarm levels
 * (A1 50, A2 100, STEL 25, TWA 10 ppm) are common isobutylene presets, an assumption: neither
 * maker publishes defaults, and agencies set their own (operator 2026-10-17).
 */
object SensorCatalog {
    private const val RESPONSE_SECONDS = DefaultProfiles.RESPONSE_SECONDS

    private fun toxic(
        id: String, label: String, rangeMax: Double, resolution: Double,
        low: Double, high: Double, twa: Double, stel: Double, unit: String = "PPM",
    ) = ChannelConfig(
        id = id, label = label, unit = unit, rangeMin = 0.0, rangeMax = rangeMax, resolution = resolution,
        baseline = 0.0, lowAlarm = low, highAlarm = high, stelAlarm = stel, twaAlarm = twa,
        t90Seconds = RESPONSE_SECONDS,
    )

    /** In display order: the usual four, HCN, then the others. */
    val SENSORS: List<ChannelConfig> = listOf(
        ChannelConfig(
            id = "O2", label = "O₂", unit = "%VOL", rangeMin = 0.0, rangeMax = O2_RANGE_MAX, resolution = 0.1, baseline = 20.9,
            lowAlarm = 19.5, highAlarm = 23.5, lowAlarmFalling = true, t90Seconds = RESPONSE_SECONDS, noise = 0.05,
        ),
        ChannelConfig(
            id = "LEL", label = "LEL", unit = "%LEL", rangeMin = 0.0, rangeMax = 100.0, resolution = 1.0, baseline = 0.0,
            lowAlarm = 10.0, highAlarm = 20.0, t90Seconds = RESPONSE_SECONDS,
        ),
        toxic("CO", "CO", rangeMax = 1000.0, resolution = 1.0, low = 35.0, high = 200.0, twa = 35.0, stel = 400.0),
        toxic("H2S", "H₂S", rangeMax = 200.0, resolution = 0.1, low = 10.0, high = 15.0, twa = 10.0, stel = 15.0),
        toxic("HCN", "HCN", rangeMax = 30.0, resolution = 0.1, low = 4.7, high = 10.0, twa = 4.7, stel = 4.7),
        toxic("NH3", "NH₃", rangeMax = 200.0, resolution = 1.0, low = 25.0, high = 50.0, twa = 25.0, stel = 35.0),
        toxic("SO2", "SO₂", rangeMax = 100.0, resolution = 0.1, low = 2.0, high = 4.0, twa = 2.0, stel = 5.0),
        toxic("CL2", "Cl₂", rangeMax = 20.0, resolution = 0.1, low = 0.5, high = 1.0, twa = 0.5, stel = 1.0),
        toxic("HF", "HF", rangeMax = 10.0, resolution = 0.1, low = 3.0, high = 6.0, twa = 3.0, stel = 6.0),
        toxic("CO2", "CO₂", rangeMax = 5.0, resolution = 0.01, low = 0.5, high = 1.0, twa = 0.5, stel = 3.0, unit = "%VOL"),
        toxic("VOC", "VOC", rangeMax = 2000.0, resolution = 0.1, low = 50.0, high = 100.0, twa = 10.0, stel = 25.0),
    )

    private val byId = SENSORS.associateBy { it.id }

    /**
     * O₂ reads 0–25 %vol on every monitor; above is over range (operator, 2026-10-17). Fixed, not
     * editable per monitor: it is part of how the sensor behaves, like its unit.
     */
    const val O2_RANGE_MAX = 25.0

    fun sensor(id: String): ChannelConfig? = byId[id]

    /**
     * [profile] with each channel's identity taken from the catalog (label, unit, alarm
     * direction, and O₂'s fixed 0–25 range), so a monitor can't drift from the sensor it claims
     * to have. Unknown ids are left alone for the validator to report.
     */
    fun normalize(profile: SensorProfile): SensorProfile = profile.copy(
        channels = profile.channels.map { c ->
            byId[c.id]?.let { s ->
                val identity = c.copy(label = s.label, unit = s.unit, lowAlarmFalling = s.lowAlarmFalling)
                if (s.lowAlarmFalling) identity.copy(rangeMin = s.rangeMin, rangeMax = s.rangeMax) else identity
            } ?: c
        },
    )
}
