package com.example.bruschedule

import com.example.bruschedule.data.scraper.BsuBiologyScheduleHtmlParser
import com.example.bruschedule.data.scraper.BsuScheduleHtmlParser
import com.example.bruschedule.domain.model.LessonType
import com.example.bruschedule.domain.model.ScheduleProfile
import com.example.bruschedule.domain.model.ScheduleProfileType
import com.example.bruschedule.domain.model.UniversityIds
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BsuBiologySourceSnapshotTest {
    @Test
    fun attachedOfficialHtmlMapsAllGroupsAndParsesARealLesson() {
        val parser = BsuBiologyScheduleHtmlParser()
        val groups = parser.discoverGroups(ATTACHED_SOURCE_FRAGMENT)

        assertEquals(
            listOf("47", "48", "49", "50", "51", "52", "53", "54", "55", "56", "79", "57", "58", "59"),
            groups.map { it.id },
        )
        assertEquals(
            listOf("Группа 41", "Группа 42", "Группа 42A", "Группа 43", "Группа 44", "Группа 45", "Группа 46", "Группа 47", "Группа 48", "Группа 49", "Группа 50", "Группа 61", "Группа 62", "Группа 63"),
            groups.map { it.name },
        )

        val profile = ScheduleProfile(
            id = "bsu-bio-42",
            type = ScheduleProfileType.GROUP,
            name = "Группа 42",
            sourceUrl = "https://bio.bsu.by/schedule/?study_mode=%D0%94%D0%BD%D0%B5%D0%B2%D0%BD%D0%B0%D1%8F&course=3&week_date=2026-10-19",
            sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_SOURCE_KEY_PREFIX}48",
            universityId = UniversityIds.BSU,
        )
        val lesson = parser.parse(profile, ATTACHED_SOURCE_FRAGMENT, LocalDate(2026, 10, 19)).single()

        assertEquals("Физическая культура (ПЗ)", lesson.subjectName)
        assertEquals("Микитченко Надежда Владимировна", lesson.teacherName)
        assertEquals("2026-10-19", lesson.lessonDate)
        assertEquals("10:35", lesson.startTime)
        assertEquals("12:00", lesson.endTime)
        assertEquals(1, lesson.dayOfWeek)
        assertEquals(2, lesson.slotNumber)
    }

    @Test
    fun attachedFirstCourseGroup41PageParsesWeekAndCombinedRoomLocation() {
        val profile = ScheduleProfile(
            id = "bsu-bio-course-1-group-41",
            type = ScheduleProfileType.GROUP,
            name = "Группа 41",
            sourceUrl = "https://konstantin-chvei.github.io/Schedule/schedule-data/universities/bsu/schedules/groups/biology-group-41.html",
            sourceKey = "${BsuBiologyScheduleHtmlParser.BIO_GROUP_NUMBER_SOURCE_KEY_PREFIX}41",
            universityId = UniversityIds.BSU,
        )
        val biologyParser = BsuBiologyScheduleHtmlParser()

        assertTrue(biologyParser.isBiologyProfile(profile))
        assertTrue(
            biologyParser.isBiologyProfile(
                profile.copy(
                    type = ScheduleProfileType.TEACHER,
                    name = "Иванова И.И.",
                    sourceUrl = "https://konstantin-chvei.github.io/Schedule/schedule-data/universities/bsu/schedules/teachers/ivanova-ii.html",
                    sourceKey = BsuBiologyScheduleHtmlParser.BIOLOGY_FACULTY_SOURCE_KEY,
                ),
            ),
        )
        assertTrue(biologyParser.hasScheduleGrid(LEGACY_GROUP_41_HTML))
        val lessons = BsuScheduleHtmlParser().parse(profile, LEGACY_GROUP_41_HTML, LocalDate(2026, 10, 19))

        assertEquals(6, lessons.size)
        assertEquals(
            listOf("2026-10-19", "2026-10-19", "2026-10-19", "2026-10-19", "2026-10-19", "2026-10-22"),
            lessons.map { it.lessonDate },
        )
        assertEquals(listOf("09:00", "10:35", "14:00", "14:00", "14:00", "12:10"), lessons.map { it.startTime })
        assertEquals(
            listOf(
                "Физическая культура",
                "Общая и неорганическая химия",
                "Англ. язык (общ. влад.) · подгруппа 1",
                "Англ. язык (общ. влад.) · подгруппа 2",
                "Немецкий язык · подгруппа 3",
                "Общая и неорганическая химия · подгруппа 1",
            ),
            lessons.map { it.subjectName },
        )
        assertEquals("Микитченко Надежда Владимировна", lessons.first().teacherName)
        assertEquals("Курчатова 10", lessons.first().classroom)
        assertEquals("3 · Курчатова 10", lessons[1].classroom)
        assertEquals("114 · Курчатова 10", lessons[2].classroom)
        assertEquals("Левандовская А.И.", lessons[2].teacherName)
        assertEquals("Рудакевич Кристина Сергеевна", lessons[3].teacherName)
        assertEquals(LessonType.PRACTICE, lessons.first().type)
        assertEquals(LessonType.LECTURE, lessons[1].type)
        assertEquals(LessonType.PRACTICE, lessons[2].type)
        assertEquals(LessonType.LABORATORY, lessons.last().type)
        assertEquals("Вершель Евгений Евгеньевич", lessons.last().teacherName)
        assertEquals("413 · Ленинградская 14", lessons.last().classroom)
        assertEquals(4, lessons.last().dayOfWeek)
        assertTrue(lessons.last().notes.contains("Время занятий 11.50-14.50"))
    }

    private companion object {
        val LEGACY_GROUP_41_HTML = """
            <div class="schedule-heading"><h2>Группа №41 — Экология 6-05-0521-01</h2><div class="week">19–25 октября 2026</div></div>
            <section class="day"><h2 class="day-title">Понедельник</h2>
              <div class="lesson">
                <div class="lesson-time"><div class="lesson-number">1 пара</div><div class="lesson-hours">09:00-10:25</div></div>
                <div class="lesson-main"><div class="lesson-entry">
                  <div class="discipline-line lesson-type-practice"><span class="discipline">Физическая культура</span><span class="type-sep">/</span><span class="lesson-type">Практика</span></div>
                  <div class="lesson-info"><div>Преподаватель: Микитченко Надежда Владимировна</div><div>Ауд. — (Курчатова 10)</div></div>
                </div></div>
              </div>
              <div class="lesson">
                <div class="lesson-time"><div class="lesson-number">2 пара</div><div class="lesson-hours">10:35-12:00</div></div>
                <div class="lesson-main"><div class="lesson-entry">
                  <div class="discipline-line lesson-type-lecture"><span class="discipline">Общая и неорганическая химия</span><span class="type-sep">/</span><span class="lesson-type">Лекция</span></div>
                  <div class="lesson-info"><div>Преподаватель: Богатиков Андрей Николаевич</div><div>Ауд. <strong>3</strong> (Курчатова 10)</div></div>
                </div></div>
              </div>
              <div class="lesson">
                <div class="lesson-time"><div class="lesson-number">4 пара</div><div class="lesson-hours">14:00-15:25</div></div>
                <div class="lesson-main">
                  <div class="lesson-entry"><div class="subgroup-label">Подгруппа 1</div>
                    <div class="discipline-line lesson-type-practice"><span class="discipline">Англ. язык (общ. влад.)</span><span class="type-sep">/</span><span class="lesson-type">Практика</span></div>
                    <div class="lesson-info"><div>Преподаватель: Левандовская А.И.</div><div>Ауд. <strong>114</strong> (Курчатова 10)</div></div>
                  </div>
                  <div class="lesson-entry"><div class="subgroup-label">Подгруппа 2</div>
                    <div class="discipline-line lesson-type-practice"><span class="discipline">Англ. язык (общ. влад.)</span><span class="type-sep">/</span><span class="lesson-type">Практика</span></div>
                    <div class="lesson-info"><div>Преподаватель: Рудакевич Кристина Сергеевна</div><div>Ауд. <strong>208</strong> (Курчатова 10)</div></div>
                  </div>
                  <div class="lesson-entry"><div class="subgroup-label">Подгруппа 3</div>
                    <div class="discipline-line lesson-type-practice"><span class="discipline">Немецкий язык</span><span class="type-sep">/</span><span class="lesson-type">Практика</span></div>
                    <div class="lesson-info"><div>Преподаватель: Ходасевич Светлана Александровна</div><div>Ауд. <strong>434</strong> (Курчатова 10)</div></div>
                  </div>
                </div>
              </div>
            </section>
            <section class="day"><h2 class="day-title">Четверг</h2>
              <div class="lesson">
                <div class="lesson-time"><div class="lesson-number">3 пара</div><div class="lesson-hours">12:10-13:35</div></div>
                <div class="lesson-main"><div class="lesson-entry"><div class="subgroup-label">Подгруппа 1</div>
                  <div class="discipline-line lesson-type-lab"><span class="discipline">Общая и неорганическая химия</span><span class="type-sep">/</span><span class="lesson-type">Лабораторная</span></div>
                  <div class="lesson-info"><div>Преподаватель: Вершель Евгений Евгеньевич</div><div>Ауд. <strong>413</strong> (Ленинградская 14)</div><div class="lesson-comment">⚠️ Время занятий 11.50-14.50</div></div>
                </div></div>
              </div>
            </section>
        """.trimIndent()

        val ATTACHED_SOURCE_FRAGMENT = """
        <html><body>
        <div id="week-info" style="color: rgb(16, 185, 129);">19.10.2026 — 25.10.2026</div>
        <table class="grid-table">
        <thead id="schedule-header">
                <tr>
                    <th class="day-header">День</th>
                    <th class="time-header">Время</th>
                    <th class="group-header"><span class="group-header-number">Группа 41</span><span class="group-header-name">Экология 6-05-0521-01</span></th><th class="group-header"><span class="group-header-number">Группа 42</span><span class="group-header-name">Биология 6-05-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 42A</span><span class="group-header-name">Биология 6-05-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 43</span><span class="group-header-name">Биология 6-05-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 44</span><span class="group-header-name">Биология 6-05-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 45</span><span class="group-header-name">Биохимия 6-05-0511-02</span></th><th class="group-header"><span class="group-header-number">Группа 46</span><span class="group-header-name">Микробиология 6-05-0511-03</span></th><th class="group-header"><span class="group-header-number">Группа 47</span><span class="group-header-name">Микробиология 6-05-0511-03</span></th><th class="group-header"><span class="group-header-number">Группа 48</span><span class="group-header-name">Биоинженерия и биоинформатика 6-05-0511-05</span></th><th class="group-header"><span class="group-header-number">Группа 49</span><span class="group-header-name">Биоинженерия и биоинформатика 6-05-0511-05</span></th><th class="group-header"><span class="group-header-number">Группа 50</span><span class="group-header-name">6-05-0511-06 Биотехнология</span></th><th class="group-header"><span class="group-header-number">Группа 61</span><span class="group-header-name">ФПБт 7-07-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 62</span><span class="group-header-name">ФПБт 7-07-0511-01</span></th><th class="group-header"><span class="group-header-number">Группа 63</span><span class="group-header-name">ФПБт 7-07-0511-01</span></th>
                </tr>
            </thead>
        <tbody id="schedule-body">
        <tr><td class="day-header" rowspan="8">
                <span class="day-name-full">Понедельник</span>
                <span class="day-name-short" aria-label="Понедельник">Пн</span>
            </td><td class="time-header" data-day="0" data-slot-id="1">
                <strong>1 пара</strong><br>
                <span class="time-range-full">09:00-10:25</span>
                <span class="time-range-mobile">09:00-<br>10:25</span>
            </td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="47" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="48" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="49" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="50" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="51" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="52" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="53" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="54" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="55" style="cursor: default;"></td><td class="cell" data-day="0" data-slot="1" data-group-id="56" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="79" style="cursor: default;"></td><td class="cell" data-day="0" data-slot="1" data-group-id="57" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="58" style="cursor: default;"></td><td class="cell empty-cell" data-day="0" data-slot="1" data-group-id="59" style="cursor: default;"></td></tr>
        <tr><td class="time-header" data-day="0" data-slot-id="2">
                <strong>2 пара</strong><br>
                <span class="time-range-full">10:35-12:00</span>
                <span class="time-range-mobile">10:35-<br>12:00</span>
            </td><td class="cell" data-day="0" data-slot="2" data-group-id="48" style="cursor: default;">
                                    <div class="lesson-card practice is-readonly-preview" data-lesson-id="12121" style="cursor: default;">

                                        <div class="lesson-content">
                                            <div class="lesson-title">Физическая культура (ПЗ)</div>
                                            <div class="lesson-teacher"><a href="https://bio.bsu.by/employees/261/?from=schedule&amp;study_mode=%D0%94%D0%BD%D0%B5%D0%B2%D0%BD%D0%B0%D1%8F&amp;course=3" class="teacher-link" onclick="event.stopPropagation()">Микитченко Надежда Владимировна</a></div>

                                        </div>
                                    </div>
                                </td></tr>
        </tbody></table>
        </body></html>        """.trimIndent()
    }
}
