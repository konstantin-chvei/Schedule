package com.example.bruschedule.data.remote

import com.example.bruschedule.domain.model.ScheduleProfileType

object GitHubScheduleDataPaths {
    const val OWNER = "konstantin-chvei"
    const val REPOSITORY = "Schedule"
    const val DATA_DIRECTORY = "schedule-data"

    // Transitional read fallback for profiles and Pages data created before the repository rename.
    const val LEGACY_REPOSITORY = "BRU-schedule"

    const val PAGES_URL = "https://$OWNER.github.io/$REPOSITORY/"
    const val UNIVERSITIES_ROOT = "${PAGES_URL}${DATA_DIRECTORY}/universities"
    const val LEGACY_PAGES_URL = "https://$OWNER.github.io/$LEGACY_REPOSITORY/"
    const val LEGACY_UNIVERSITIES_ROOT = "${LEGACY_PAGES_URL}${DATA_DIRECTORY}/universities"

    fun indexUrl(universityId: String, type: ScheduleProfileType): String =
        indexUrl(universityId, type, PAGES_URL)

    fun legacyIndexUrl(universityId: String, type: ScheduleProfileType): String =
        indexUrl(universityId, type, LEGACY_PAGES_URL)

    fun indexUrls(universityId: String, type: ScheduleProfileType): List<String> =
        listOf(indexUrl(universityId, type), legacyIndexUrl(universityId, type))

    fun scheduleUrl(
        universityId: String,
        type: ScheduleProfileType,
        relativePath: String,
        pagesUrl: String = PAGES_URL,
    ): String {
        require(isSafeSlug(universityId)) { "Идентификатор вуза в GitHub-каталоге некорректен" }
        require(pagesUrl == PAGES_URL || pagesUrl == LEGACY_PAGES_URL) {
            "Разрешены только известные адреса GitHub Pages"
        }
        val path = relativePath.trim().removePrefix("./")
        val requiredPrefix = if (type == ScheduleProfileType.GROUP) "schedules/groups/" else "schedules/teachers/"
        require(path.startsWith(requiredPrefix) && isSafeRelativePath(path)) {
            "Путь расписания должен находиться в папке $requiredPrefix"
        }
        return "${pagesUrl}${DATA_DIRECTORY}/universities/$universityId/$path"
    }

    /** Maps saved URLs from the previous Pages repository name to the new canonical URL. */
    fun canonicalScheduleUrl(url: String): String? {
        val root = when {
            url.startsWith("$UNIVERSITIES_ROOT/", ignoreCase = true) -> UNIVERSITIES_ROOT
            url.startsWith("$LEGACY_UNIVERSITIES_ROOT/", ignoreCase = true) -> LEGACY_UNIVERSITIES_ROOT
            else -> return null
        }
        val relativePath = url.substring(root.length + 1)
        val segments = relativePath.split('/')
        val isSchedulePath = segments.size >= 4 &&
            isSafeSlug(segments.first()) &&
            segments[1] == "schedules" &&
            segments[2] in setOf("groups", "teachers") &&
            isSafeRelativePath(relativePath)
        return if (isSchedulePath) "$UNIVERSITIES_ROOT/$relativePath" else null
    }

    /** Tries the new Pages URL first, then the previous URL during repository migration. */
    fun scheduleUrls(url: String): List<String> {
        val canonicalUrl = canonicalScheduleUrl(url) ?: return emptyList()
        val relativePath = canonicalUrl.removePrefix("$UNIVERSITIES_ROOT/")
        return listOf(canonicalUrl, "$LEGACY_UNIVERSITIES_ROOT/$relativePath")
    }

    fun isPublishedDataUrl(url: String): Boolean {
        val root = when {
            url.startsWith("$UNIVERSITIES_ROOT/", ignoreCase = true) -> UNIVERSITIES_ROOT
            url.startsWith("$LEGACY_UNIVERSITIES_ROOT/", ignoreCase = true) -> LEGACY_UNIVERSITIES_ROOT
            else -> return false
        }
        val relativePath = url.substring(root.length + 1)
        val segments = relativePath.split('/')
        val isProfileIndex = segments.size == 3 &&
            isSafeSlug(segments[0]) &&
            segments[1] in setOf("groups", "teachers") &&
            segments[2] == "index.csv"
        return isProfileIndex || isScheduleFileUrl(url)
    }

    fun isScheduleFileUrl(url: String): Boolean = canonicalScheduleUrl(url) != null

    fun isCsvScheduleUrl(url: String?): Boolean =
        url != null && isScheduleFileUrl(url) && url.endsWith(".csv", ignoreCase = true)

    private fun indexUrl(universityId: String, type: ScheduleProfileType, pagesUrl: String): String {
        require(isSafeSlug(universityId)) { "Идентификатор вуза в GitHub-каталоге некорректен" }
        val profileFolder = if (type == ScheduleProfileType.GROUP) "groups" else "teachers"
        return "${pagesUrl}${DATA_DIRECTORY}/universities/$universityId/$profileFolder/index.csv"
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotBlank() &&
            !path.startsWith('/') &&
            path.split('/').all { segment ->
                segment.isNotBlank() && segment != "." && segment != ".." &&
                    segment.all { it.isLetterOrDigit() || it in "-_." }
            }

    private fun isSafeSlug(value: String): Boolean =
        value.isNotBlank() && value.length <= 80 &&
            value.all { it.isLetterOrDigit() || it in "-_" }
}
