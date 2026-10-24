(() => {
  "use strict";

  const MONTHS_RU = {
    января: 0, январь: 0,
    февраля: 1, февраль: 1,
    марта: 2, март: 2,
    апреля: 3, апрель: 3,
    мая: 4, май: 4,
    июня: 5, июнь: 5,
    июля: 6, июль: 6,
    августа: 7, август: 7,
    сентября: 8, сентябрь: 8,
    октября: 9, октябрь: 9,
    ноября: 10, ноябрь: 10,
    декабря: 11, декабрь: 11,
  };
  const DAY_NAMES_RU = {
    понедельник: 0, вторник: 1, среда: 2, четверг: 3,
    пятница: 4, суббота: 5, воскресенье: 6,
  };
  const LESSON_COLORS = {
    lecture: "#A7B9FF",
    laboratory: "#FF8998",
    practice: "#FFC979",
    unknown: "#A6B0C2",
  };

  function pad2(value) { return String(value).padStart(2, "0"); }
  function toISO(date) {
    return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`;
  }
  function fromISO(value) {
    const match = String(value || "").match(/^(\d{4})-(\d{2})-(\d{2})$/);
    if (!match) return null;
    const date = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 12, 0, 0, 0);
    return Number.isNaN(date.getTime()) ? null : date;
  }
  function addDays(value, count) {
    const date = typeof value === "string" ? fromISO(value) : new Date(value);
    if (!date) return "";
    date.setDate(date.getDate() + Number(count || 0));
    return toISO(date);
  }
  function weekStart(value) {
    const date = typeof value === "string" ? fromISO(value) : new Date(value);
    if (!date) return "";
    const isoWeekday = date.getDay() === 0 ? 7 : date.getDay();
    date.setDate(date.getDate() - isoWeekday + 1);
    return toISO(date);
  }
  function dayNumber(value) {
    const date = typeof value === "string" ? fromISO(value) : new Date(value);
    return date ? (date.getDay() === 0 ? 7 : date.getDay()) : 1;
  }
  function differenceInDays(left, right) {
    const a = fromISO(left);
    const b = fromISO(right);
    if (!a || !b) return 0;
    return Math.round((a.getTime() - b.getTime()) / 86400000);
  }
  function normalize(value) {
    return String(value || "").normalize("NFC").toLocaleLowerCase("ru-RU")
      .replaceAll("ё", "е").replace(/\s+/g, " ").trim();
  }
  function hash(value) {
    let result = 2166136261;
    for (const char of String(value)) {
      result ^= char.charCodeAt(0);
      result = Math.imul(result, 16777619);
    }
    return (result >>> 0).toString(36);
  }
  function plainText(node) {
    if (!node) return "";
    return String(node.textContent || "").replace(/\u00a0/g, " ").replace(/\s+/g, " ").trim();
  }
  function parseTimeRange(value) {
    const match = String(value || "").match(/(\d{1,2}:\d{2})\s*[–—-]\s*(\d{1,2}:\d{2})/);
    if (!match) return null;
    const normalizeTime = (time) => time.split(":").map((part) => part.padStart(2, "0")).join(":");
    return { startTime: normalizeTime(match[1]), endTime: normalizeTime(match[2]) };
  }
  function parseDate(value, fallbackYear = new Date().getFullYear()) {
    const text = String(value || "").trim();
    const isoMatch = text.match(/(\d{4})-(\d{1,2})-(\d{1,2})/);
    if (isoMatch) return `${isoMatch[1]}-${pad2(isoMatch[2])}-${pad2(isoMatch[3])}`;
    const numericMatch = text.match(/(\d{1,2})[./-](\d{1,2})(?:[./-](\d{2,4}))?/);
    if (numericMatch) {
      let year = Number(numericMatch[3] || fallbackYear);
      if (year < 100) year += year < 70 ? 2000 : 1900;
      const date = new Date(year, Number(numericMatch[2]) - 1, Number(numericMatch[1]), 12);
      return Number.isNaN(date.getTime()) ? "" : toISO(date);
    }
    const normalizedText = normalize(text);
    const namedRangeMatch = normalizedText.match(/(\d{1,2})\s*[–—-]\s*\d{1,2}\s+([а-яё]+)\s+(\d{4})/i);
    const namedMatch = namedRangeMatch || normalizedText.match(/(\d{1,2})\s+([а-яё]+)(?:\s+(\d{4}))?/i);
    if (namedMatch) {
      const month = MONTHS_RU[namedMatch[2]];
      if (month !== undefined) {
        const date = new Date(Number(namedMatch[3] || fallbackYear), month, Number(namedMatch[1]), 12);
        return Number.isNaN(date.getTime()) ? "" : toISO(date);
      }
    }
    return "";
  }
  function parseBruWeekInfo(table, fallbackYear) {
    let header = table.closest(".table-wrapper")?.previousElementSibling || table.parentElement?.previousElementSibling;
    for (let steps = 0; header && steps < 3 && !header.classList?.contains("week-header"); steps += 1) header = header.previousElementSibling;
    const text = plainText(header);
    const range = text.match(/(\d{1,2})[./](\d{1,2})\s*[–—-]\s*(\d{1,2})[./](\d{1,2})[./](\d{4})/);
    if (range) {
      const startMonth = Number(range[2]);
      const endMonth = Number(range[4]);
      const endYear = Number(range[5]);
      const crossesYear = startMonth > endMonth;
      return { year: endYear, startMonth, startYear: crossesYear ? endYear - 1 : endYear, endYear, crossesYear };
    }
    const year = Number(text.match(/\b(20\d{2})\b/)?.[1] || fallbackYear);
    return { year, startMonth: null, startYear: year, endYear: year, crossesYear: false };
  }
  function parseCSV(text) {
    const rows = [];
    let row = [];
    let field = "";
    let quoted = false;
    const input = String(text || "").replace(/^\uFEFF/, "");
    for (let index = 0; index < input.length; index += 1) {
      const char = input[index];
      if (char === '"' && quoted && input[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (char === '"') {
        quoted = !quoted;
      } else if (char === "," && !quoted) {
        row.push(field.trim());
        field = "";
      } else if ((char === "\n" || char === "\r") && !quoted) {
        row.push(field.trim());
        if (row.some((cell) => cell !== "")) rows.push(row);
        row = [];
        field = "";
        if (char === "\r" && input[index + 1] === "\n") index += 1;
      } else {
        field += char;
      }
    }
    if (quoted) throw new Error("CSV: не закрыта кавычка.");
    if (field !== "" || row.length) {
      row.push(field.trim());
      if (row.some((cell) => cell !== "")) rows.push(row);
    }
    if (rows.length < 2) return [];
    const headers = rows.shift().map((header) => normalize(header).replace(/[\s-]+/g, "_"));
    return rows.map((cells) => Object.fromEntries(headers.map((header, index) => [header, cells[index] || ""])));
  }
  function mapType(value, element) {
    const text = normalize(value);
    const classNames = `${element?.className || ""} ${element?.parentElement?.className || ""}`.toLowerCase();
    if (/лаборатор|lab/.test(text) || /lesson-lab|occ-lab|type-lab/.test(classNames)) return "laboratory";
    if (/лекц|lecture/.test(text) || /lesson-lecture|occ-lecture|type-lecture/.test(classNames)) return "lecture";
    if (/практи|семинар|practice|seminar/.test(text) || /lesson-practice|lesson-pe|occ-practice/.test(classNames)) return "practice";
    return "unknown";
  }
  function lessonTypeLabel(type) {
    return ({ lecture: "Лекция", laboratory: "Лабораторная", practice: "Практика", unknown: "Другое" })[type] || "Другое";
  }
  function makeLesson(profile, fields) {
    const date = fields.date || "";
    const times = fields.startTime && fields.endTime ? { startTime: fields.startTime, endTime: fields.endTime } : parseTimeRange(fields.time || "");
    const subject = String(fields.subject || "").trim();
    if (!date || !times || !subject) return null;
    const type = fields.type || "unknown";
    const dayOfWeek = dayNumber(date);
    const slotNumber = Number(fields.slotNumber || 0) || 0;
    const sourceKey = `src-${hash([profile.id, dayOfWeek, slotNumber, times.startTime, subject, fields.teacher || "", fields.classroom || ""].join("|"))}`;
    const id = `remote-${hash([profile.id, date, times.startTime, sourceKey].join("|"))}`;
    return {
      id, profileId: profile.id, subjectName: subject,
      teacherName: String(fields.teacher || "").trim(),
      classroom: String(fields.classroom || "").trim(),
      startTime: times.startTime, endTime: times.endTime, type,
      colorHex: LESSON_COLORS[type] || LESSON_COLORS.unknown,
      isCustom: false, version: 1, lessonDate: date, dayOfWeek, slotNumber,
      sourceKey, notes: "", groupName: String(fields.groupName || "").trim(),
    };
  }
  function dedupeLessons(lessons) {
    const output = new Map();
    for (const lesson of lessons) {
      if (!lesson) continue;
      const key = [lesson.lessonDate, lesson.startTime, lesson.endTime, normalize(lesson.subjectName), normalize(lesson.teacherName), normalize(lesson.classroom), normalize(lesson.groupName || "")].join("|");
      if (!output.has(key)) output.set(key, lesson);
    }
    return [...output.values()].sort((a, b) => a.lessonDate.localeCompare(b.lessonDate) || a.startTime.localeCompare(b.startTime) || a.slotNumber - b.slotNumber);
  }

  function mergeRemoteAndCustom(remoteLessons, existingLessons) {
    const custom = existingLessons.filter((lesson) => lesson.isCustom);
    const groupBySourceKey = (lessons) => {
      const grouped = new Map();
      for (const lesson of lessons) {
        if (!lesson.sourceKey) continue;
        const matches = grouped.get(lesson.sourceKey) || [];
        if (!matches.some((candidate) => candidate.id === lesson.id)) matches.push(lesson);
        grouped.set(lesson.sourceKey, matches);
      }
      return grouped;
    };
    const customById = new Map(custom.map((lesson) => [lesson.id, lesson]));
    const customBySourceKey = groupBySourceKey(custom);
    const previousWebsiteLessons = existingLessons.filter((lesson) => !lesson.isCustom);
    const previousById = new Map(previousWebsiteLessons.map((lesson) => [lesson.id, lesson]));
    const previousBySourceKey = groupBySourceKey(previousWebsiteLessons);
    const remoteSourceKeyCounts = new Map();
    const remoteOccurrenceCounts = new Map();
    const occurrenceKey = (lesson) => JSON.stringify([lesson.sourceKey, lesson.lessonDate]);
    for (const lesson of remoteLessons) {
      if (!lesson.sourceKey) continue;
      remoteSourceKeyCounts.set(lesson.sourceKey, (remoteSourceKeyCounts.get(lesson.sourceKey) || 0) + 1);
      const key = occurrenceKey(lesson);
      remoteOccurrenceCounts.set(key, (remoteOccurrenceCounts.get(key) || 0) + 1);
    }

    // A source key can identify a repeating weekly template, not one dated occurrence.
    // Never fan one local edit out to every date that shares that template key.
    const findSourceKeyMatch = (lesson, bySourceKey) => {
      if (!lesson.sourceKey) return null;
      const candidates = bySourceKey.get(lesson.sourceKey) || [];
      if (!candidates.length) return null;
      if (candidates.length === 1 && remoteSourceKeyCounts.get(lesson.sourceKey) === 1) return candidates[0];
      const sameDate = candidates.filter((candidate) => candidate.lessonDate === lesson.lessonDate);
      return sameDate.length === 1 && remoteOccurrenceCounts.get(occurrenceKey(lesson)) === 1
        ? sameDate[0]
        : null;
    };

    const remote = remoteLessons.map((lesson) => {
      const override = customById.get(lesson.id) || findSourceKeyMatch(lesson, customBySourceKey);
      if (override) return { ...lesson, ...override, isCustom: true };
      const previous = previousById.get(lesson.id) || findSourceKeyMatch(lesson, previousBySourceKey);
      return {
        ...lesson,
        notes: previous ? previous.notes || "" : lesson.notes || "",
        rating: previous ? previous.rating ?? null : null,
        stickers: previous && Array.isArray(previous.stickers) ? [...previous.stickers] : [],
      };
    });
    const representedCustomIds = new Set(remote.filter((lesson) => lesson.isCustom).map((lesson) => lesson.id));
    const mergedById = new Map();
    for (const lesson of [...remote, ...custom.filter((item) => !representedCustomIds.has(item.id))]) {
      const key = lesson.id || JSON.stringify([lesson.profileId, lesson.lessonDate, lesson.startTime, lesson.subjectName]);
      const current = mergedById.get(key);
      if (!current || (!current.isCustom && lesson.isCustom)) mergedById.set(key, lesson);
    }
    return [...mergedById.values()]
      .sort((a, b) => a.lessonDate.localeCompare(b.lessonDate) || a.startTime.localeCompare(b.startTime) || Number(a.slotNumber) - Number(b.slotNumber));
  }

  function parseCSVSchedule(text, profile) {
    const rows = parseCSV(text);
    const lessons = rows.map((row) => {
      const get = (...names) => {
        for (const name of names) {
          if (row[name] !== undefined && row[name] !== "") return row[name];
        }
        return "";
      };
      const date = parseDate(get("lesson_date", "date", "дата"));
      const typeValue = get("type", "lesson_type", "тип", "тип_занятия");
      return makeLesson(profile, {
        date,
        startTime: get("start_time", "start", "начало", "время_начала"),
        endTime: get("end_time", "end", "окончание", "время_окончания"),
        time: get("time", "время"),
        subject: get("subject_name", "subject", "discipline", "предмет", "дисциплина"),
        teacher: get("teacher_name", "teacher", "преподаватель"),
        classroom: get("classroom", "room", "аудитория", "кабинет"),
        type: mapType(typeValue),
        slotNumber: get("slot_number", "slot", "номер_пары"),
        groupName: get("group", "group_name", "группа"),
      });
    });
    return dedupeLessons(lessons);
  }

  function parseBruTeacher(doc, profile) {
    const lessons = [];
    for (const card of doc.querySelectorAll(".occ-card")) {
      const line1 = plainText(card.querySelector(".occ-line1"));
      const subject = plainText(card.querySelector(".occ-line2"));
      const groupName = plainText(card.querySelector(".occ-line3"));
      const classroom = plainText(card.querySelector(".occ-line4")).replace(/^🏛️\s*/, "");
      const timeText = plainText(card.querySelector(".occ-line5"));
      const date = parseDate(timeText);
      const times = parseTimeRange(timeText);
      if (!date || !times || !subject) continue;
      let slotNumber = 0;
      const timeCell = card.closest("tr")?.querySelector(".time-col");
      const slotMatch = plainText(timeCell).match(/(\d+)\s*пара/i);
      if (slotMatch) slotNumber = Number(slotMatch[1]);
      const lesson = makeLesson(profile, {
        date, startTime: times.startTime, endTime: times.endTime,
        subject, teacher: profile.name, classroom, groupName,
        type: mapType(line1, card), slotNumber,
      });
      if (lesson) lessons.push(lesson);
    }
    return dedupeLessons(lessons);
  }

  function parseBruGroup(doc, profile) {
    const lessons = [];
    const tables = [...doc.querySelectorAll("table.schedule-table")];
    for (const table of tables) {
      const headers = [...table.querySelectorAll("thead th")];
      const weekInfo = parseBruWeekInfo(table, new Date().getFullYear());
      const dates = headers.map((header, index) => {
        if (index === 0 || header.classList.contains("time-col")) return "";
        const small = header.querySelector("small");
        const dateText = plainText(small);
        const dateParts = dateText.match(/(\d{1,2})[./](\d{1,2})/);
        let year = weekInfo.year;
        if (weekInfo.crossesYear && dateParts) year = Number(dateParts[2]) >= weekInfo.startMonth ? weekInfo.startYear : weekInfo.endYear;
        return parseDate(dateText, year);
      });
      let lastTime = "";
      let lastSlot = 0;
      for (const row of table.querySelectorAll("tbody tr, tr")) {
        const cells = [...row.children].filter((cell) => cell.tagName === "TD" || cell.tagName === "TH");
        if (!cells.length) continue;
        const timeCell = row.querySelector("td.time-col");
        const timeText = plainText(timeCell || cells[0]);
        const times = parseTimeRange(timeText);
        const slotMatch = timeText.match(/(\d+)\s*пара/i);
        if (times) lastTime = `${times.startTime}-${times.endTime}`;
        if (slotMatch) lastSlot = Number(slotMatch[1]);
        const activeTimes = times || parseTimeRange(lastTime);
        if (!activeTimes) continue;
        const firstDayCell = timeCell ? cells.indexOf(timeCell) + 1 : 0;
        for (let cellIndex = firstDayCell; cellIndex < cells.length; cellIndex += 1) {
          const headerIndex = cellIndex - firstDayCell + 1;
          const date = dates[headerIndex] || "";
          if (!date) continue;
          const cell = cells[cellIndex];
          const cards = [...cell.querySelectorAll(".lesson-card")];
          for (const card of cards) {
            const subject = plainText(card.querySelector(".title")) || plainText(card.querySelector(".discipline"));
            if (!subject) continue;
            const info = card.querySelector(".info");
            const infoText = (info?.innerText || info?.textContent || "").replace(/\u00a0/g, " ");
            const teacherMatch = infoText.match(/(?:преподаватель|преп\.?):\s*([^\n\r]+)/i);
            const roomMatch = infoText.match(/(?:ауд\.?|аудитория|каб\.?):\s*([^\n\r]+)/i);
            const lesson = makeLesson(profile, {
              date, ...activeTimes, subject,
              teacher: teacherMatch?.[1]?.trim() || "",
              classroom: roomMatch?.[1]?.trim() || "",
              type: mapType(card.className, card), slotNumber: lastSlot,
            });
            if (lesson) lessons.push(lesson);
          }
        }
      }
    }
    return dedupeLessons(lessons);
  }

  function parseBiologyGroup(doc, profile) {
    const heading = plainText(doc.querySelector(".schedule-heading .week"));
    const startDate = parseDate(heading);
    const monday = startDate ? weekStart(startDate) : "";
    if (!monday) return [];
    const lessons = [];
    for (const section of doc.querySelectorAll("section.day")) {
      const dayTitle = normalize(plainText(section.querySelector(".day-title")));
      const offset = DAY_NAMES_RU[dayTitle];
      if (offset === undefined) continue;
      const date = addDays(monday, offset);
      for (const entry of section.querySelectorAll(".lesson-entry")) {
        const subject = plainText(entry.querySelector(".discipline"));
        const typeText = plainText(entry.querySelector(".lesson-type"));
        const lessonContainer = entry.closest(".lesson");
        const timeText = plainText(lessonContainer?.querySelector(".lesson-hours"));
        const times = parseTimeRange(timeText);
        const numberText = plainText(lessonContainer?.querySelector(".lesson-number"));
        const slotMatch = numberText.match(/(\d+)/);
        const info = entry.querySelector(".lesson-info");
        const infoLines = [...(info?.querySelectorAll(":scope > div") || [])].map(plainText).filter(Boolean);
        const infoText = plainText(info);
        const teacherLine = infoLines.find((line) => /преподаватель/i.test(line)) || infoText.match(/преподаватель:\s*([^;]+)/i)?.[1] || "";
        const teacher = String(teacherLine).replace(/^.*?преподаватель:\s*/i, "").trim();
        const roomLine = infoLines.find((line) => /ауд\.?/i.test(line)) || "";
        const classroom = roomLine.replace(/^.*?ауд\.?\s*/i, "").trim();
        if (!subject || !times) continue;
        const lesson = makeLesson(profile, {
          date, ...times, subject, teacher, classroom,
          type: mapType(typeText, entry), slotNumber: slotMatch?.[1] || 0,
        });
        if (lesson) lessons.push(lesson);
      }
    }
    return dedupeLessons(lessons);
  }

  function parseGenericTable(doc, profile) {
    const lessons = [];
    for (const row of doc.querySelectorAll("table tr")) {
      const cells = [...row.children].filter((cell) => cell.tagName === "TD" || cell.tagName === "TH").map(plainText);
      if (cells.length < 3) continue;
      const text = cells.join(" | ");
      const date = parseDate(text);
      const time = parseTimeRange(text);
      if (!date || !time) continue;
      const subject = cells.find((cell) => cell.length > 2 && !parseTimeRange(cell) && !parseDate(cell)) || "";
      if (!subject) continue;
      const lesson = makeLesson(profile, { date, ...time, subject, teacher: cells[3] || "", classroom: cells[4] || "", type: mapType(text) });
      if (lesson) lessons.push(lesson);
    }
    return dedupeLessons(lessons);
  }

  function expandHtmlCycle(lessons, fromDate, throughDate) {
    if (!lessons.length || !fromDate || !throughDate || fromDate > throughDate) return lessons;
    const byWeek = new Map();
    for (const lesson of lessons) {
      const monday = weekStart(lesson.lessonDate);
      if (!byWeek.has(monday)) byWeek.set(monday, []);
      byWeek.get(monday).push(lesson);
    }
    const weeks = [...byWeek.keys()].sort();
    if (!weeks.length) return lessons;
    const signature = (week) => (byWeek.get(week) || []).map((lesson) => [
      lesson.dayOfWeek, lesson.startTime, lesson.endTime, lesson.slotNumber,
      normalize(lesson.subjectName), normalize(lesson.teacherName), normalize(lesson.classroom),
    ].join("|")).sort().join(";;");
    let cycleLength = 1;
    if (weeks.length >= 2 && signature(weeks[0]) !== signature(weeks[1])) cycleLength = 2;
    if (weeks.length >= 4 && signature(weeks[0]) === signature(weeks[2]) && signature(weeks[1]) === signature(weeks[3])) cycleLength = 2;
    if (weeks.length >= 2 && signature(weeks[0]) === signature(weeks[1])) cycleLength = 1;
    const templates = weeks.slice(0, cycleLength).map((week) => byWeek.get(week) || []);
    const baseMonday = weeks[0];
    const expanded = [];
    for (let date = fromDate; date <= throughDate; date = addDays(date, 1)) {
      const weekIndex = Math.floor(differenceInDays(weekStart(date), baseMonday) / 7);
      const templateIndex = ((weekIndex % cycleLength) + cycleLength) % cycleLength;
      const day = dayNumber(date);
      for (const sample of templates[templateIndex]) {
        if (sample.dayOfWeek !== day) continue;
        const sourceKey = sample.sourceKey;
        const id = `remote-${hash([sample.profileId, date, sample.startTime, sourceKey].join("|"))}`;
        expanded.push({ ...sample, id, lessonDate: date, dayOfWeek: day });
      }
    }
    return dedupeLessons(expanded);
  }

  function parseSchedule(content, profile, throughDate, today = toISO(new Date())) {
    const trimmed = String(content || "").trim();
    if (!trimmed) throw new Error("Файл расписания пуст.");
    const isCsv = /\.csv(?:$|\?)/i.test(profile.dataPath || "") || (!trimmed.startsWith("<") && /(?:lesson_date|start_time|subject_name|lessonDate)/i.test(trimmed.slice(0, 500)));
    if (isCsv) {
      const lessons = parseCSVSchedule(trimmed, profile).filter((lesson) => lesson.lessonDate >= today && lesson.lessonDate <= throughDate);
      if (!lessons.length) throw new Error("В CSV не найдены пары в текущем периоде.");
      return lessons;
    }
    if (typeof DOMParser === "undefined") throw new Error("Браузер не поддерживает разбор HTML расписания.");
    const doc = new DOMParser().parseFromString(trimmed, "text/html");
    let lessons = [];
    if (profile.type === "TEACHER" || doc.querySelector(".occ-card")) lessons = parseBruTeacher(doc, profile);
    if (!lessons.length && doc.querySelector("section.day .lesson-entry")) lessons = parseBiologyGroup(doc, profile);
    if (!lessons.length && doc.querySelector("table.schedule-table")) lessons = parseBruGroup(doc, profile);
    if (!lessons.length) lessons = parseGenericTable(doc, profile);
    lessons = dedupeLessons(lessons);
    if (!lessons.length) throw new Error("Не удалось найти пары в опубликованном HTML.");
    return expandHtmlCycle(lessons, today, throughDate);
  }

  window.ScheduleEngine = Object.freeze({
    addDays, dayNumber, differenceInDays, fromISO, hash, lessonTypeLabel, mergeRemoteAndCustom, parseCSV,
    normalize, parseDate, parseSchedule, parseTimeRange, toISO, weekStart,
  });
})();
