package com.taksolutions.simsensor.profile

/**
 * Built-in templates. Starting values only; server users edit them (docs/DESIGN.md §7.1).
 */
object DefaultProfiles {

    val FIVE_GAS = SensorProfile(
        name = "5-Gas",
        channels = listOf(
            ChannelConfig(
                id = "O2", label = "O₂", unit = "%VOL",
                rangeMin = 0.0, rangeMax = 30.0, resolution = 0.1, baseline = 20.9,
                lowAlarm = 19.5, highAlarm = 23.5, lowAlarmFalling = true,
                t90Seconds = 15.0, noise = 0.05,
            ),
            ChannelConfig(
                id = "LEL", label = "LEL", unit = "%LEL",
                rangeMin = 0.0, rangeMax = 100.0, resolution = 1.0, baseline = 0.0,
                lowAlarm = 10.0, highAlarm = 20.0,
                t90Seconds = 15.0,
            ),
            ChannelConfig(
                id = "CO", label = "CO", unit = "PPM",
                rangeMin = 0.0, rangeMax = 1000.0, resolution = 1.0, baseline = 0.0,
                lowAlarm = 35.0, highAlarm = 200.0, stelAlarm = 400.0, twaAlarm = 35.0,
                t90Seconds = 30.0,
            ),
            ChannelConfig(
                id = "H2S", label = "H₂S", unit = "PPM",
                rangeMin = 0.0, rangeMax = 200.0, resolution = 0.1, baseline = 0.0,
                lowAlarm = 10.0, highAlarm = 15.0, stelAlarm = 15.0, twaAlarm = 10.0,
                t90Seconds = 30.0,
            ),
            ChannelConfig(
                id = "HCN", label = "HCN", unit = "PPM",
                rangeMin = 0.0, rangeMax = 30.0, resolution = 0.1, baseline = 0.0,
                lowAlarm = 4.7, highAlarm = 10.0, stelAlarm = 4.7, twaAlarm = 4.7,
                t90Seconds = 60.0,
            ),
        ),
    )

    val ALL: List<SensorProfile> = listOf(FIVE_GAS)
}
