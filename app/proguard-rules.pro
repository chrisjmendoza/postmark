# No app-specific keep rules needed today:
#  - Room entities/DAOs are KSP-generated code, no reflection.
#  - Domain models are only touched by hand-written JSON codecs
#    (ThreadParticipants.kt, BackupArchive.kt) — no Gson/Moshi reflection.
#  - Hilt, Compose, WorkManager, Media3, Coil all ship consumer rules in
#    their AARs.
# Blanket -keep rules on data.db.entity.** / domain.model.** were removed
# July 2026 — they blocked R8 from optimizing the hottest data classes.
-keepattributes *Annotation*
