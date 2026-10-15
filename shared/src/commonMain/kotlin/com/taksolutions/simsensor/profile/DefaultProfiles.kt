package com.taksolutions.simsensor.profile

/**
 * Built-in templates. Starting values only; server users edit them (docs/DESIGN.md §7.1).
 */
object DefaultProfiles {

    /**
     * Response time (T90) of every built-in sensor. Real meters quote under 10–15 s; trainees
     * walking through a leak expect the reading to follow quickly (operator field walk,
     * 2026-10-14). Editable per sensor in the console.
     */
    const val RESPONSE_SECONDS = 5.0

    /** The generic 5-gas meter, built from the sensor catalog's defaults. */
    val FIVE_GAS = SensorProfile(
        name = "5-Gas",
        channels = listOf("O2", "LEL", "CO", "H2S", "HCN").map { SensorCatalog.sensor(it)!! },
    )

    val ALL: List<SensorProfile> = listOf(FIVE_GAS)
}
