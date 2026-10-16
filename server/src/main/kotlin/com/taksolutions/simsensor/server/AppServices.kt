package com.taksolutions.simsensor.server

import com.taksolutions.simsensor.server.auth.Argon2PasswordHasher
import com.taksolutions.simsensor.server.auth.AuthService
import com.taksolutions.simsensor.server.auth.DevicePrincipal
import com.taksolutions.simsensor.server.auth.InMemoryLoginRateLimiter
import com.taksolutions.simsensor.server.auth.JdbcSessionRepository
import com.taksolutions.simsensor.server.auth.LoginRateLimiter
import com.taksolutions.simsensor.server.auth.PasswordHasher
import com.taksolutions.simsensor.server.auth.SecureTokens
import com.taksolutions.simsensor.server.bootstrap.Seeder
import com.taksolutions.simsensor.server.console.ConsoleSessionHandler
import com.taksolutions.simsensor.server.db.Database
import com.taksolutions.simsensor.server.device.DeviceSessionHandler
import com.taksolutions.simsensor.server.devices.DeviceGroupRepository
import com.taksolutions.simsensor.server.devices.DeviceRepository
import com.taksolutions.simsensor.server.devices.DeviceService
import com.taksolutions.simsensor.server.devices.DeviceStateService
import com.taksolutions.simsensor.server.devices.EnrollmentService
import com.taksolutions.simsensor.server.devices.JdbcDeviceGroupRepository
import com.taksolutions.simsensor.server.devices.JdbcDeviceRepository
import com.taksolutions.simsensor.server.devices.JdbcEnrollmentRepository
import com.taksolutions.simsensor.server.events.JdbcEventLog
import com.taksolutions.simsensor.server.exercises.ExerciseService
import com.taksolutions.simsensor.server.exercises.JdbcExerciseRepository
import com.taksolutions.simsensor.server.instructors.InstructorService
import com.taksolutions.simsensor.server.instructors.JdbcInstructorRepository
import com.taksolutions.simsensor.server.profiles.JdbcProfileRepository
import com.taksolutions.simsensor.server.profiles.ProfileService
import com.taksolutions.simsensor.server.realtime.BusChangeNotifier
import com.taksolutions.simsensor.server.realtime.ConsoleConnections
import com.taksolutions.simsensor.server.realtime.DeviceConnections
import com.taksolutions.simsensor.server.realtime.InMemoryBus
import com.taksolutions.simsensor.server.realtime.RealtimeBus
import com.taksolutions.simsensor.server.realtime.RealtimeDispatcher
import com.taksolutions.simsensor.server.realtime.RedisBus
import com.taksolutions.simsensor.server.telemetry.BatchingTelemetryWriter
import com.taksolutions.simsensor.server.telemetry.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.time.Clock
import javax.sql.DataSource

/**
 * Composition root: builds every service once and owns the background work (telemetry
 * writer, bus dispatcher). Tests build it with their own collaborators.
 */
class AppServices(
    val authService: AuthService,
    val instructorService: InstructorService,
    val profileService: ProfileService,
    val exerciseService: ExerciseService,
    val enrollmentService: EnrollmentService,
    val deviceService: DeviceService,
    val deviceGroups: DeviceGroupRepository,
    val deviceSessions: DeviceSessionHandler,
    val consoleSessions: ConsoleSessionHandler,
    val telemetryWriter: BatchingTelemetryWriter,
    val telemetryRepository: TelemetryRepository,
    val seeder: Seeder,
    val sourceService: com.taksolutions.simsensor.server.sources.SourceService,
    val settingsService: com.taksolutions.simsensor.server.settings.SettingsService,
    val beaconService: com.taksolutions.simsensor.server.beacons.BeaconService,
    val exerciseBeacons: com.taksolutions.simsensor.server.beacons.ExerciseBeacons,
    val mailService: com.taksolutions.simsensor.server.mail.MailService,
    val passwordResetService: com.taksolutions.simsensor.server.auth.PasswordResetService,
    private val devices: DeviceRepository,
    private val bus: RealtimeBus,
    private val dispatcher: RealtimeDispatcher,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts the bus dispatcher and the telemetry writer. */
    fun start() {
        dispatcher.start(bus, scope)
        telemetryWriter.start(scope)
    }

    suspend fun resolveDevice(token: String): DevicePrincipal? =
        devices.findActiveByTokenHash(SecureTokens.sha256(token))?.let { DevicePrincipal(it.id) }

    override fun close() {
        runCatching { runBlocking { telemetryWriter.flush() } }
        scope.cancel()
        bus.close()
    }

    companion object {
        fun create(
            dataSource: DataSource,
            config: ServerConfig,
            hasher: PasswordHasher = Argon2PasswordHasher(),
            rateLimiter: LoginRateLimiter = InMemoryLoginRateLimiter(),
            clock: Clock = Clock.systemUTC(),
            bus: RealtimeBus = config.redisUrl?.let(::RedisBus) ?: InMemoryBus(),
            mailSender: com.taksolutions.simsensor.server.mail.MailSender = com.taksolutions.simsensor.server.mail.SmtpMailSender(),
        ): AppServices {
            val db = Database(dataSource)
            val instructors = JdbcInstructorRepository(db)
            val sessions = JdbcSessionRepository(db)
            val profiles = JdbcProfileRepository(db)
            val exercises = JdbcExerciseRepository(db)
            val devices = JdbcDeviceRepository(db)
            val groups = JdbcDeviceGroupRepository(db)
            val eventLog = JdbcEventLog(db)
            val notifier = BusChangeNotifier(bus)
            val sources = com.taksolutions.simsensor.server.sources.JdbcSourceRepository(db)
            val monitors = com.taksolutions.simsensor.server.profiles.ExerciseMonitors(profiles)
            val beaconRepository = com.taksolutions.simsensor.server.beacons.JdbcBeaconRepository(db)
            val beaconService = com.taksolutions.simsensor.server.beacons.BeaconService(beaconRepository, exercises, notifier)
            val state = DeviceStateService(devices, exercises, monitors, sources, clock, beaconsOf = beaconRepository::inExercise)
            val exerciseService = ExerciseService(exercises, profiles, instructors, devices, notifier, eventLog, clock, monitors)
            val exerciseBeacons = com.taksolutions.simsensor.server.beacons.ExerciseBeacons(
                beaconRepository, beaconService, exerciseService, exercises, sources, notifier, eventLog,
            )
            val settingsRepository = com.taksolutions.simsensor.server.settings.JdbcSettingsRepository(db)
            val settingsService = com.taksolutions.simsensor.server.settings.SettingsService(settingsRepository, notifier, eventLog)
            val mailService = com.taksolutions.simsensor.server.mail.MailService(settingsRepository, mailSender, config.publicUrl, eventLog)
            val passwordResetService = com.taksolutions.simsensor.server.auth.PasswordResetService(
                instructors, com.taksolutions.simsensor.server.auth.JdbcPasswordTokenRepository(db), sessions, hasher, mailService, eventLog, clock,
            )
            val deviceService = DeviceService(devices, groups, exerciseService, exercises, profiles, state, notifier, eventLog, clock)
            val deviceConnections = DeviceConnections()
            val consoleConnections = ConsoleConnections()
            val telemetryWriter = BatchingTelemetryWriter(db, devices, exercises, clock)
            val telemetryRepository = TelemetryRepository(db)
            return AppServices(
                authService = AuthService(instructors, sessions, hasher, rateLimiter, config.sessionTtl, clock),
                instructorService = InstructorService(instructors, sessions, hasher),
                profileService = ProfileService(profiles, exercises, devices, notifier),
                exerciseService = exerciseService,
                enrollmentService = EnrollmentService(JdbcEnrollmentRepository(db), eventLog, config.publicUrl, clock),
                deviceService = deviceService,
                deviceGroups = groups,
                deviceSessions = DeviceSessionHandler(
                    devices, state, deviceConnections, telemetryWriter, eventLog, bus, config.version, clock,
                    units = { settingsService.get().units },
                ),
                consoleSessions = ConsoleSessionHandler(deviceService, telemetryRepository, consoleConnections, clock),
                telemetryWriter = telemetryWriter,
                telemetryRepository = telemetryRepository,
                seeder = Seeder(profiles, instructors, hasher),
                sourceService = com.taksolutions.simsensor.server.sources.SourceService(
                    sources, exercises, exerciseService, notifier, eventLog, clock, monitors,
                    requireBeaconMember = exerciseBeacons::requireMember,
                ),
                beaconService = beaconService,
                exerciseBeacons = exerciseBeacons,
                mailService = mailService,
                passwordResetService = passwordResetService,
                settingsService = settingsService,
                devices = devices,
                bus = bus,
                dispatcher = RealtimeDispatcher(deviceConnections, consoleConnections, devices, state) { settingsService.get().units },
            )
        }
    }
}
