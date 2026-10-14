package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.auth.Argon2PasswordHasher
import com.taksolutions.simsensor.server.auth.AuthService
import com.taksolutions.simsensor.server.auth.InMemoryLoginRateLimiter
import com.taksolutions.simsensor.server.auth.JdbcSessionRepository
import com.taksolutions.simsensor.server.auth.LoginRateLimiter
import com.taksolutions.simsensor.server.auth.PasswordHasher
import com.taksolutions.simsensor.server.bootstrap.Seeder
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.device.DeviceMessageHandler
import com.taksolutions.simsensor.server.exercises.ExerciseService
import com.taksolutions.simsensor.server.exercises.JdbcExerciseRepository
import com.taksolutions.simsensor.server.instructors.InstructorService
import com.taksolutions.simsensor.server.instructors.JdbcInstructorRepository
import com.taksolutions.simsensor.server.profiles.JdbcProfileRepository
import com.taksolutions.simsensor.server.profiles.ProfileService
import java.time.Clock
import javax.sql.DataSource

/** Composition root: builds every service once. Tests build it with their own collaborators. */
class AppServices(
    val authService: AuthService,
    val instructorService: InstructorService,
    val profileService: ProfileService,
    val exerciseService: ExerciseService,
    val deviceMessageHandler: DeviceMessageHandler,
    val seeder: Seeder,
) {
    companion object {
        fun create(
            dataSource: DataSource,
            config: ServerConfig,
            hasher: PasswordHasher = Argon2PasswordHasher(),
            rateLimiter: LoginRateLimiter = InMemoryLoginRateLimiter(),
            clock: Clock = Clock.systemUTC(),
        ): AppServices {
            val db = Database(dataSource)
            val instructors = JdbcInstructorRepository(db)
            val sessions = JdbcSessionRepository(db)
            val profiles = JdbcProfileRepository(db)
            val exercises = JdbcExerciseRepository(db)
            return AppServices(
                authService = AuthService(instructors, sessions, hasher, rateLimiter, config.sessionTtl, clock),
                instructorService = InstructorService(instructors, sessions, hasher),
                profileService = ProfileService(profiles, exercises),
                exerciseService = ExerciseService(exercises, profiles, instructors),
                deviceMessageHandler = DeviceMessageHandler(serverVersion = config.version, clock = clock::millis),
                seeder = Seeder(profiles, instructors, hasher),
            )
        }
    }
}
