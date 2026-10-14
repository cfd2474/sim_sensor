package com.taksolutions.simsensor.server.bootstrap

import com.taksolutions.simsensor.profile.DefaultProfiles
import com.taksolutions.simsensor.server.BootstrapAdmin
import com.taksolutions.simsensor.server.auth.PasswordHasher
import com.taksolutions.simsensor.server.instructors.InstructorRepository
import com.taksolutions.simsensor.server.instructors.InstructorService
import com.taksolutions.simsensor.server.instructors.Role
import com.taksolutions.simsensor.server.profiles.ProfileRepository
import org.slf4j.LoggerFactory

/** First-start data: built-in profile templates and the bootstrap admin. Safe to run on every start. */
class Seeder(
    private val profiles: ProfileRepository,
    private val instructors: InstructorRepository,
    private val hasher: PasswordHasher,
) {
    private val log = LoggerFactory.getLogger(Seeder::class.java)

    suspend fun seed(admin: BootstrapAdmin?) {
        if (profiles.countBuiltin() == 0L) {
            DefaultProfiles.ALL.forEach { profiles.create(it, builtin = true) }
            log.info("Seeded built-in profiles: {}", DefaultProfiles.ALL.joinToString { it.name })
        }

        if (instructors.count() > 0L) return
        if (admin == null) {
            log.warn("No instructors exist. Set SIM_ADMIN_EMAIL and SIM_ADMIN_PASSWORD to create the first admin.")
            return
        }
        require(admin.password.length >= InstructorService.MIN_PASSWORD_LENGTH) {
            "SIM_ADMIN_PASSWORD must be at least ${InstructorService.MIN_PASSWORD_LENGTH} characters"
        }
        instructors.create(admin.email, "Administrator", hasher.hash(admin.password), Role.ADMIN)
        log.info("Created bootstrap admin {}", admin.email)
    }
}
