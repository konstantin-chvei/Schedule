package com.example.bruschedule.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.example.bruschedule.data.local.db.ScheduleDatabase
import com.example.bruschedule.domain.model.AcademicTask
import com.example.bruschedule.domain.model.DeadlineLinkType
import com.example.bruschedule.domain.model.Lesson
import com.example.bruschedule.domain.model.LessonNotes
import com.example.bruschedule.domain.model.LessonStickerCodec
import com.example.bruschedule.domain.model.LessonStickerPalette
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.LessonTypePalette
import com.example.bruschedule.domain.model.ProfileSyncState
import com.example.bruschedule.domain.model.RefreshCommit
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.TaskReminderMode
import com.example.bruschedule.domain.repository.AcademicTaskRepository
import com.example.bruschedule.domain.repository.ScheduleRepository
import com.example.bruschedule.domain.repository.ScheduleSyncRepository
import com.example.bruschedule.domain.usecase.ScheduleMergeStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SqlDelightScheduleRepository(
    private val database: ScheduleDatabase,
) : ScheduleRepository, ScheduleSyncRepository, AcademicTaskRepository {
    private val queries get() = database.scheduleQueries

    override fun observeProfiles(): Flow<List<ScheduleProfile>> =
        queries.selectProfiles().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row ->
                ScheduleProfile(
                    id = row.id,
                    type = profileType(row.profileType),
                    name = row.name,
                    sourceUrl = row.sourceUrl,
                    sourceKey = row.sourceKey,
                    universityId = row.universityId,
                )
            }
        }

    override fun observeAllLessons(): Flow<List<Lesson>> =
        queries.selectAllLessons().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row -> lessonFromDb(
                row.id, row.profileId, row.lessonDate, row.dayOfWeek, row.slotNumber,
                row.subjectName, row.teacherName, row.classroom, row.startTime, row.endTime,
                row.lessonType, row.colorHex, row.isCustom, row.version, row.sourceKey, row.sourceColorHex, row.notes, row.rating, row.stickers,
            ) }
        }

    override fun observeLessons(profileId: String): Flow<List<Lesson>> =
        queries.selectLessonsForProfile(profileId).asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row -> lessonFromDb(
                row.id, row.profileId, row.lessonDate, row.dayOfWeek, row.slotNumber,
                row.subjectName, row.teacherName, row.classroom, row.startTime, row.endTime,
                row.lessonType, row.colorHex, row.isCustom, row.version, row.sourceKey, row.sourceColorHex, row.notes, row.rating, row.stickers,
            ) }
        }

    override fun observeSyncState(profileId: String): Flow<ProfileSyncState?> =
        queries.getSyncState(profileId).asFlow().mapToOneOrNull(Dispatchers.Default).map { row ->
            row?.let {
                ProfileSyncState(
                    profileId = it.profileId,
                    lastAttemptAtEpochMillis = it.lastAttemptAtEpochMillis,
                    lastSuccessfulAtEpochMillis = it.lastSuccessfulAtEpochMillis,
                    lastError = it.lastError,
                    previousSnapshotAtEpochMillis = it.previousSnapshotAtEpochMillis,
                    canRollback = it.previousSnapshotId != null,
                )
            }
        }

    override fun observeTasks(): Flow<List<AcademicTask>> =
        queries.selectTasks().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row -> taskFromDb(
                row.id, row.lessonId, row.profileId, row.subjectNameSnapshot, row.taskTitle,
                row.description, row.dueDate, row.isCompleted, row.linkType, row.reminderMode,
                row.reminderOffsetsMinutes,
            ) }
        }

    override fun observeTasksForLesson(lessonId: String): Flow<List<AcademicTask>> =
        queries.selectTasksForLesson(lessonId).asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row -> taskFromDb(
                row.id, row.lessonId, row.profileId, row.subjectNameSnapshot, row.taskTitle,
                row.description, row.dueDate, row.isCompleted, row.linkType, row.reminderMode,
                row.reminderOffsetsMinutes,
            ) }
        }

    override suspend fun getProfile(profileId: String): ScheduleProfile? =
        queries.getProfile(profileId).executeAsOneOrNull()?.let { row ->
            ScheduleProfile(
                id = row.id,
                type = profileType(row.profileType),
                name = row.name,
                sourceUrl = row.sourceUrl,
                sourceKey = row.sourceKey,
                universityId = row.universityId,
            )
        }

    override suspend fun getLesson(lessonId: String): Lesson? =
        queries.getLesson(lessonId).executeAsOneOrNull()?.let { row ->
            lessonFromDb(
                row.id, row.profileId, row.lessonDate, row.dayOfWeek, row.slotNumber,
                row.subjectName, row.teacherName, row.classroom, row.startTime, row.endTime,
                row.lessonType, row.colorHex, row.isCustom, row.version, row.sourceKey, row.sourceColorHex, row.notes, row.rating, row.stickers,
            )
        }

    override suspend fun getLessons(profileId: String): List<Lesson> =
        queries.selectLessonsForProfile(profileId).executeAsList().map { row ->
            lessonFromDb(
                row.id, row.profileId, row.lessonDate, row.dayOfWeek, row.slotNumber,
                row.subjectName, row.teacherName, row.classroom, row.startTime, row.endTime,
                row.lessonType, row.colorHex, row.isCustom, row.version, row.sourceKey, row.sourceColorHex, row.notes, row.rating, row.stickers,
            )
        }

    override suspend fun saveProfile(profile: ScheduleProfile) {
        database.transaction {
            queries.insertProfile(
                profile.id,
                profile.type.name,
                profile.name.trim(),
                profile.sourceUrl,
                profile.sourceKey,
                profile.universityId,
            )
            queries.updateProfile(
                profile.type.name,
                profile.name.trim(),
                profile.sourceUrl,
                profile.sourceKey,
                profile.universityId,
                profile.id,
            )
        }
    }

    override suspend fun deleteProfile(profileId: String) {
        database.transaction {
            queries.deleteTasksForProfile(profileId)
            queries.deleteLessonsForProfile(profileId)
            queries.deleteSnapshotLessonsForProfile(profileId)
            queries.deleteSnapshotsForProfile(profileId)
            queries.deleteSyncStateForProfile(profileId)
            queries.deleteProfile(profileId)
        }
    }

    override suspend fun saveCustomLesson(lesson: Lesson) {
        saveCustomLessons(listOf(lesson))
    }

    override suspend fun saveCustomLessons(lessons: List<Lesson>) {
        if (lessons.isEmpty()) return
        database.transaction { lessons.forEach(::upsertCustomLesson) }
    }

    override suspend fun saveLessonNotes(lessonId: String, notes: String): Boolean {
        var saved = false
        database.transaction {
            if (queries.getLesson(lessonId).executeAsOneOrNull() == null) return@transaction
            val safeNotes = notes.take(LessonNotes.MAX_LENGTH)
            queries.updateLessonNotes(safeNotes, lessonId)

            queries.updateLessonSnapshotNotes(safeNotes, lessonId)
            saved = true
        }
        return saved
    }

    override suspend fun saveLessonRating(lessonId: String, rating: Int): Boolean {
        if (rating !in 1..5) return false
        var saved = false
        database.transaction {
            if (queries.getLesson(lessonId).executeAsOneOrNull() == null) return@transaction
            queries.updateLessonRating(rating.toLong(), lessonId)
            queries.updateLessonSnapshotRating(rating.toLong(), lessonId)
            saved = true
        }
        return saved
    }

    override suspend fun toggleLessonSticker(lessonId: String, sticker: String): Boolean {
        if (sticker !in LessonStickerPalette.choices) return false
        var saved = false
        database.transaction {
            val current = queries.getLesson(lessonId).executeAsOneOrNull() ?: return@transaction
            val stickers = LessonStickerCodec.decode(current.stickers).toMutableList()
            if (sticker in stickers) stickers.remove(sticker) else stickers += sticker
            val encoded = LessonStickerCodec.encode(stickers)
            queries.updateLessonStickers(encoded, lessonId)
            queries.updateLessonSnapshotStickers(encoded, lessonId)
            saved = true
        }
        return saved
    }

    private fun upsertCustomLesson(lesson: Lesson) {
        val previous = queries.getLesson(lesson.id).executeAsOneOrNull()
        val customLesson = lesson.copy(
            isCustom = true,
            version = (previous?.version?.toInt() ?: 0) + 1,
            colorHex = LessonTypePalette.accent(lesson.type),
            sourceKey = lesson.sourceKey.ifBlank { "custom:${lesson.id}" },
            notes = lesson.notes.take(LessonNotes.MAX_LENGTH),
        )
        upsertLesson(customLesson)
    }

    override suspend fun deleteCustomLessonAndPreserveTasks(lessonId: String): Boolean {
        var deleted = false
        database.transaction {
            val lesson = queries.getLesson(lessonId).executeAsOneOrNull() ?: return@transaction
            if (lesson.isCustom == 0L) return@transaction

            queries.selectTasksForLesson(lessonId).executeAsList().forEach { task ->
                val canKeepSubjectLink = !task.profileId.isNullOrBlank() && task.subjectNameSnapshot.isNotBlank()
                val fallbackLinkType = if (canKeepSubjectLink) DeadlineLinkType.SUBJECT.name else DeadlineLinkType.NONE.name
                queries.updateTask(
                    null,
                    task.profileId.takeIf { canKeepSubjectLink },
                    task.subjectNameSnapshot,
                    task.taskTitle,
                    task.description,
                    task.dueDate,
                    task.isCompleted,
                    fallbackLinkType,
                    task.reminderMode,
                    task.reminderOffsetsMinutes,
                    task.id,
                )
            }
            queries.deleteLesson(lessonId)
            deleted = true
        }
        return deleted
    }

    override suspend fun applyWebsiteSchedule(
        profileId: String,
        websiteLessons: List<Lesson>,
        successfulAtEpochMillis: Long,
    ): RefreshCommit {
        require(websiteLessons.isNotEmpty()) { "Пустое расписание нельзя применять" }
        require(websiteLessons.all { it.profileId == profileId }) {
            "В ответе парсера обнаружены пары другого профиля"
        }

        var commit = RefreshCommit(0, 0, null)
        database.transaction {
            val existing = queries.selectLessonsForProfile(profileId).executeAsList().map { row ->
                lessonFromDb(
                    row.id, row.profileId, row.lessonDate, row.dayOfWeek, row.slotNumber,
                    row.subjectName, row.teacherName, row.classroom, row.startTime, row.endTime,
                    row.lessonType, row.colorHex, row.isCustom, row.version, row.sourceKey, row.sourceColorHex, row.notes, row.rating, row.stickers,
                )
            }

            val previousSnapshotId = if (existing.isNotEmpty()) {
                val id = "snapshot:$profileId:$successfulAtEpochMillis"
                queries.deleteSnapshotLessonsForProfile(profileId)
                queries.deleteSnapshotsForProfile(profileId)
                queries.insertSnapshot(id, profileId, successfulAtEpochMillis)
                existing.forEach { lesson -> insertSnapshotLesson(id, lesson) }
                id
            } else {
                null
            }

            val merged = ScheduleMergeStrategy.merge(existing, websiteLessons)
            val retainedIds = merged.mapTo(mutableSetOf()) { it.id }
            existing.asSequence()
                .filterNot(Lesson::isCustom)
                .filter { it.id !in retainedIds }
                .forEach { obsolete -> queries.deleteLesson(obsolete.id) }
            merged.forEach(::upsertLesson)

            queries.replaceSyncState(
                profileId,
                successfulAtEpochMillis,
                successfulAtEpochMillis,
                null,
                previousSnapshotId,
                successfulAtEpochMillis.takeIf { previousSnapshotId != null },
            )
            commit = RefreshCommit(
                appliedLessonCount = merged.size,
                preservedCustomLessonCount = merged.count(Lesson::isCustom),
                previousSnapshotAtEpochMillis = successfulAtEpochMillis.takeIf { previousSnapshotId != null },
            )
        }
        return commit
    }

    override suspend fun recordRefreshFailure(
        profileId: String,
        attemptedAtEpochMillis: Long,
        message: String,
    ) {
        database.transaction {
            val current = queries.getSyncState(profileId).executeAsOneOrNull()
            queries.replaceSyncState(
                profileId,
                attemptedAtEpochMillis,
                current?.lastSuccessfulAtEpochMillis,
                message,
                current?.previousSnapshotId,
                current?.previousSnapshotAtEpochMillis,
            )
        }
    }

    override suspend fun rollbackToPrevious(profileId: String): Boolean {
        var rolledBack = false
        database.transaction {
            val state = queries.getSyncState(profileId).executeAsOneOrNull() ?: return@transaction
            val snapshotId = state.previousSnapshotId ?: return@transaction
            val snapshotRows = queries.getSnapshotLessons(snapshotId).executeAsList().map { row ->
                lessonFromDb(
                    row.id, row.profileId, row.lessonDate, row.dayOfWeek, row.slotNumber,
                    row.subjectName, row.teacherName, row.classroom, row.startTime, row.endTime,
                    row.lessonType, row.colorHex, row.isCustom, row.version, row.sourceKey, row.sourceColorHex, row.notes, row.rating, row.stickers,
                )
            }
            val current = queries.selectLessonsForProfile(profileId).executeAsList().map { row ->
                lessonFromDb(
                    row.id, row.profileId, row.lessonDate, row.dayOfWeek, row.slotNumber,
                    row.subjectName, row.teacherName, row.classroom, row.startTime, row.endTime,
                    row.lessonType, row.colorHex, row.isCustom, row.version, row.sourceKey, row.sourceColorHex, row.notes, row.rating, row.stickers,
                )
            }
            val restored = ScheduleMergeStrategy.restoreSnapshot(
                snapshot = snapshotRows,
                current = current,
            )

            queries.deleteLessonsForProfile(profileId)
            restored.forEach(::upsertLesson)
            rolledBack = true
        }
        return rolledBack
    }

    override suspend fun saveTask(task: AcademicTask) {
        database.transaction { upsertTask(task) }
    }

    override suspend fun saveTasks(tasks: List<AcademicTask>) {
        if (tasks.isEmpty()) return
        database.transaction { tasks.forEach(::upsertTask) }
    }

    private fun upsertTask(task: AcademicTask) {
        queries.insertTask(
            task.id, task.lessonId, task.profileId, task.subjectNameSnapshot,
            task.taskTitle.trim(), task.description.trim(), task.dueDate, task.isCompleted.sqlInt(),
            task.linkType.name, task.reminderMode.name, task.reminderOffsetsMinutes.joinToString(","),
        )
        queries.updateTask(
            task.lessonId, task.profileId, task.subjectNameSnapshot, task.taskTitle.trim(),
            task.description.trim(), task.dueDate, task.isCompleted.sqlInt(), task.linkType.name,
            task.reminderMode.name, task.reminderOffsetsMinutes.joinToString(","), task.id,
        )
    }

    override suspend fun setTaskCompleted(taskId: String, completed: Boolean) {
        queries.setTaskCompleted(completed.sqlInt(), taskId)
    }

    override suspend fun deleteTask(taskId: String) {
        queries.deleteTask(taskId)
    }

    private fun upsertLesson(lesson: Lesson) {
        queries.insertLesson(
            lesson.id,
            lesson.profileId,
            lesson.lessonDate,
            lesson.dayOfWeek.toLong(),
            lesson.slotNumber.toLong(),
            lesson.subjectName,
            lesson.teacherName,
            lesson.classroom,
            lesson.startTime,
            lesson.endTime,
            lesson.type.name,
            lesson.colorHex,
            lesson.isCustom.sqlInt(),
            lesson.version.toLong(),
            lesson.sourceKey,
            lesson.sourceColorHex,
            lesson.notes.take(LessonNotes.MAX_LENGTH),
            lesson.rating?.toLong(),
            LessonStickerCodec.encode(lesson.stickers),
        )
        queries.updateLesson(
            lesson.profileId,
            lesson.lessonDate,
            lesson.dayOfWeek.toLong(),
            lesson.slotNumber.toLong(),
            lesson.subjectName,
            lesson.teacherName,
            lesson.classroom,
            lesson.startTime,
            lesson.endTime,
            lesson.type.name,
            lesson.colorHex,
            lesson.isCustom.sqlInt(),
            lesson.version.toLong(),
            lesson.sourceKey,
            lesson.sourceColorHex,
            lesson.notes.take(LessonNotes.MAX_LENGTH),
            lesson.rating?.toLong(),
            LessonStickerCodec.encode(lesson.stickers),
            lesson.id,
        )
    }

    private fun insertSnapshotLesson(snapshotId: String, lesson: Lesson) {
        queries.insertSnapshotLesson(
            snapshotId,
            lesson.id,
            lesson.profileId,
            lesson.lessonDate,
            lesson.dayOfWeek.toLong(),
            lesson.slotNumber.toLong(),
            lesson.subjectName,
            lesson.teacherName,
            lesson.classroom,
            lesson.startTime,
            lesson.endTime,
            lesson.type.name,
            lesson.colorHex,
            lesson.isCustom.sqlInt(),
            lesson.version.toLong(),
            lesson.sourceKey,
            lesson.sourceColorHex,
            lesson.notes.take(LessonNotes.MAX_LENGTH),
            lesson.rating?.toLong(),
            LessonStickerCodec.encode(lesson.stickers),
        )
    }
}

private fun profileType(value: String): ScheduleProfileType =
    runCatching { ScheduleProfileType.valueOf(value) }.getOrDefault(ScheduleProfileType.GROUP)

private fun Boolean.sqlInt(): Long = if (this) 1L else 0L

private fun lessonFromDb(
    id: String,
    profileId: String,
    lessonDate: String,
    dayOfWeek: Long,
    slotNumber: Long,
    subjectName: String,
    teacherName: String,
    classroom: String,
    startTime: String,
    endTime: String,
    lessonType: String,
    colorHex: String,
    isCustom: Long,
    version: Long,
    sourceKey: String,
    sourceColorHex: String?,
    notes: String,
    rating: Long?,
    stickers: String,
) = Lesson(
    id = id,
    profileId = profileId,
    subjectName = subjectName,
    teacherName = teacherName,
    classroom = classroom,
    startTime = startTime,
    endTime = endTime,
    type = runCatching { LessonType.valueOf(lessonType) }.getOrDefault(LessonType.UNKNOWN),
    colorHex = colorHex,
    isCustom = isCustom != 0L,
    version = version.toInt(),
    lessonDate = lessonDate,
    dayOfWeek = dayOfWeek.toInt(),
    slotNumber = slotNumber.toInt(),
    sourceKey = sourceKey,
    sourceColorHex = sourceColorHex,
    notes = notes,
    rating = rating?.takeIf { it in 1L..5L }?.toInt(),
    stickers = LessonStickerCodec.decode(stickers),
)

private fun taskFromDb(
    id: String,
    lessonId: String?,
    profileId: String?,
    subjectNameSnapshot: String,
    taskTitle: String,
    description: String,
    dueDate: Long,
    isCompleted: Long,
    linkType: String,
    reminderMode: String,
    reminderOffsetsMinutes: String,
) = AcademicTask(
    id = id,
    lessonId = lessonId,
    taskTitle = taskTitle,
    description = description,
    dueDate = dueDate,
    isCompleted = isCompleted != 0L,
    profileId = profileId,
    subjectNameSnapshot = subjectNameSnapshot,
    linkType = runCatching { DeadlineLinkType.valueOf(linkType) }
        .getOrDefault(if (lessonId == null) DeadlineLinkType.NONE else DeadlineLinkType.SUBJECT),
    reminderMode = runCatching { TaskReminderMode.valueOf(reminderMode) }
        .getOrDefault(TaskReminderMode.INHERIT),
    reminderOffsetsMinutes = reminderOffsetsMinutes.split(",").mapNotNull { it.toIntOrNull() },
)
