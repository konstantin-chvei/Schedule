package com.example.bruschedule.domain.usecase

import com.example.bruschedule.domain.model.Lesson

object ScheduleMergeStrategy {
    fun merge(existing: List<Lesson>, incoming: List<Lesson>): List<Lesson> {
        val customById = existing.asSequence()
            .filter(Lesson::isCustom)
            .associateBy(Lesson::id)
        val customBySourceKey = existing.asSequence()
            .filter(Lesson::isCustom)
            .filter { it.sourceKey.isNotBlank() }
            .associateBy(Lesson::sourceKey)
        val existingWebsiteLessons = existing.filterNot(Lesson::isCustom)
        val previousWebById = existingWebsiteLessons.associateBy(Lesson::id)
        val previousWebBySourceKey = existingWebsiteLessons
            .filter { it.sourceKey.isNotBlank() }
            .associateBy(Lesson::sourceKey)
        val previousWebByLessonIdentity = existingWebsiteLessons.groupBy(Lesson::noteIdentity)

        val distinctIncoming = incoming.distinctBy { lesson ->
            lesson.sourceKey.takeIf(String::isNotBlank) ?: lesson.id
        }
        val mergedWebsiteRows = distinctIncoming.map { webLesson ->
            val customOverride = customById[webLesson.id]
                ?: webLesson.sourceKey.takeIf(String::isNotBlank)?.let(customBySourceKey::get)
            if (customOverride != null) {
                customOverride
            } else {
                val previousWebsiteLesson = previousWebById[webLesson.id]
                    ?: webLesson.sourceKey.takeIf(String::isNotBlank)?.let(previousWebBySourceKey::get)
                    ?: previousWebByLessonIdentity[webLesson.noteIdentity()]?.singleOrNull()
                webLesson.copy(
                    isCustom = false,
                    version = (previousWebById[webLesson.id]?.version ?: 0) + 1,
                    notes = previousWebsiteLesson?.notes.orEmpty(),
                    rating = previousWebsiteLesson?.rating,
                    stickers = previousWebsiteLesson?.stickers.orEmpty(),
                )
            }
        }

        val representedCustomIds = mergedWebsiteRows.asSequence()
            .filter(Lesson::isCustom)
            .map(Lesson::id)
            .toSet()
        val customOnlyRows = existing.filter { it.isCustom && it.id !in representedCustomIds }
        return (mergedWebsiteRows + customOnlyRows)
            .distinctBy(Lesson::id)
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }

    fun restoreSnapshot(snapshot: List<Lesson>, current: List<Lesson>): List<Lesson> {
        val currentCustom = current.filter(Lesson::isCustom)
        val customById = currentCustom.associateBy(Lesson::id)
        val customBySourceKey = currentCustom
            .filter { it.sourceKey.isNotBlank() }
            .associateBy(Lesson::sourceKey)
        val currentWebsite = current.filterNot(Lesson::isCustom)
        val currentWebsiteById = currentWebsite.associateBy(Lesson::id)
        val currentWebsiteBySourceKey = currentWebsite
            .filter { it.sourceKey.isNotBlank() }
            .associateBy(Lesson::sourceKey)
        val currentWebsiteByIdentity = currentWebsite.groupBy(Lesson::noteIdentity)
        val restoredSnapshotRows = snapshot.map { snapshotLesson ->
            val customOverride = customById[snapshotLesson.id]
                ?: snapshotLesson.sourceKey.takeIf(String::isNotBlank)?.let(customBySourceKey::get)
            if (customOverride != null) {
                customOverride
            } else {
                val latest = currentWebsiteById[snapshotLesson.id]
                    ?: snapshotLesson.sourceKey.takeIf(String::isNotBlank)?.let(currentWebsiteBySourceKey::get)
                    ?: currentWebsiteByIdentity[snapshotLesson.noteIdentity()]?.singleOrNull()
                latest?.let {
                    snapshotLesson.copy(
                        notes = it.notes,
                        rating = it.rating,
                        stickers = it.stickers,
                    )
                } ?: snapshotLesson
            }
        }
        val representedCustomIds = restoredSnapshotRows.asSequence()
            .filter(Lesson::isCustom)
            .map(Lesson::id)
            .toSet()
        val customOnlyRows = currentCustom.filter { it.id !in representedCustomIds }

        return (restoredSnapshotRows + customOnlyRows)
            .distinctBy(Lesson::id)
            .sortedWith(compareBy<Lesson>({ it.lessonDate }, { it.startTime }, { it.slotNumber }, { it.id }))
    }
}

private fun Lesson.noteIdentity(): String =
    listOf(profileId, lessonDate, slotNumber.toString(), subjectName.trim().lowercase()).joinToString("|")
