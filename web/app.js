(() => {
  "use strict";

  // Keep this legacy key so existing PWA profiles and settings survive the rebrand.
  const STORAGE_KEY = "bru.schedule.ios-pwa.v2";
  const DATA_ROOT = new URL("./schedule-data/", window.location.href);
  const INDEX_TTL_MS = 12 * 60 * 60 * 1000;
  const DAILY_MESSAGES_REFRESH_TTL_MS = 24 * 60 * 60 * 1000;
  const MAX_RESULTS = 100;
  const REMOVED_DAILY_MESSAGE = "Если день начался не так — обнови расписание.";
  const appRoot = document.querySelector("#app");
  const toastBox = document.querySelector("#toast");
  const Engine = window.ScheduleEngine;
  const UNIVERSITY_LIST = [
    { id: "bru", name: "Белорусско-Российский университет", short: "БРУ", hint: "Группы и преподаватели" },
    { id: "bsu", name: "Белорусский государственный университет", short: "БГУ", hint: "Расписания факультетов" },
  ];
  const TRACKS = {
    bru: [
      { id: "BELARUSIAN", label: "Белорусское отделение", end: "2027-01-10", hint: "БРУ · очное расписание" },
      { id: "RUSSIAN", label: "Российское отделение", end: "2027-01-25", hint: "БРУ · отдельная дата сессии" },
      { id: "IFZO", label: "ИФЗО", end: "2027-01-30", hint: "БРУ · индивидуальные даты" },
    ],
    bsu: [
      { id: "BSU_DAYTIME_1_3_WINTER", label: "Очная · 1–3 курсы · зимняя", end: "2027-01-24", hint: "Сессия ориентировочно 04–24 января 2027" },
      { id: "BSU_DAYTIME_1_3_SUMMER", label: "Очная · 1–3 курсы · летняя", end: "2027-06-30", hint: "Ориентир на 2026/27: июнь" },
      { id: "BSU_DAYTIME_4_WINTER", label: "Очная · выпускной курс · зимняя", end: "2027-01-10", hint: "Дата сессии может отличаться" },
      { id: "BSU_DAYTIME_4_FINAL", label: "Очная · выпускной курс · практика и ГИА", end: "2027-06-30", hint: "При необходимости задайте дату вручную" },
      { id: "BSU_CORRESPONDENCE_YEAR_1", label: "Заочная · 1 курс", end: "2027-02-28", hint: "Зимняя волна обычно в январе/феврале" },
      { id: "BSU_CORRESPONDENCE_CUSTOM", label: "Заочная · 2–5 курсы", end: "2027-04-30", hint: "Волна зависит от факультета и курса" },
    ],
  };
  const DEFAULT_ACCENTS = { lecture: "#A7B9FF", laboratory: "#FF8998", practice: "#FFC979", unknown: "#A6B0C2" };
  const LESSON_STICKERS = ["😀", "😎", "🔥", "❤️", "👍", "👎", "😴", "🐵", "💀", "😭", "🤓", "💪", "🎯", "⭐", "❌", "🙂", "😍", "🥰", "😐", "🤗", "😔", "🤔", "😌", "😢", "😮", "😵‍💫", "🤒", "🤫", "😏", "😶"];
  const THEME_OPTIONS = [
    { id: "BLUE", label: "Синяя" },
    { id: "BLACK", label: "Чёрная" },
    { id: "WHITE", label: "Белая" },
  ];
  const ACCENT_OPTIONS = ["#A7B9FF", "#FF8998", "#FFC979", "#8FD9F4", "#8FDFC0", "#B89BFF", "#F4B7D4", "#A6B0C2"];
  const TYPE_LABELS = { GROUP: "Группа", TEACHER: "Преподаватель" };
  const DEADLINE_OFFSETS = [
    [15, "15 минут"], [60, "1 час"], [1440, "1 день"], [4320, "3 дня"], [10080, "1 неделя"],
  ];
  const DAILY_MESSAGES_DEFAULT = [
    "Идеальный хаос начинается с расписания.",
    "План на день: знать, куда идти.",
    "Даже дедлайн уважает календарь.",
    "Сегодня — хороший день не перепутать аудитории.",
    "Пары идут, а ты держишь ритм.",
    "Отметь главное. Остальное — по расписанию.",
    "Пусть совпадут только время и аудитория.",
    "Свобода начинается после последней пары.",
    "Не теряйся: у каждого дня есть план.",
    "Маленькая пауза — тоже часть расписания.",
    "Если всё по плану — проверь аудиторию.",
    "Твой график уже знает, куда ты идёшь.",
    "Сегодня можно всё. Но сначала — пары.",
    "Спокойствие: расписание уже проверено.",
    "Следующая пара ближе, чем кажется.",
    "Время быть там, где стоит пара.",
    "Расписание — карта твоего учебного хаоса.",
    "Не опаздывай к своей лучшей версии.",
    "Иногда лучший план — посмотреть план.",
    "Ты, кофе и правильная аудитория.",
    "Идеи вне графика, пары — строго по нему.",
    "Пусть сегодня всё сложится без суеты.",
    "Учёба — марафон. Не забывай пить воду.",
    "Глоток кофе — и обратно в реальность.",
    "Не всё срочно. Проверь дедлайны.",
    "Запомни: аудитория тоже важна.",
    "Даже вторник достоин хорошего плана.",
    "Пусть сойдутся группа, время и аудитория.",
    "Ты справишься. А приложение напомнит.",
  ];

  function todayISO() { return Engine.toISO(new Date()); }
  function academicYearLabel(date = todayISO()) {
    const [year, month] = date.split("-").map(Number);
    const startYear = month >= 9 ? year : year - 1;
    return `${startYear}/${String(startYear + 1).slice(-2)}`;
  }
  function uuid(prefix = "id") {
    const value = window.crypto?.randomUUID?.() || `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    return `${prefix}-${value}`;
  }
  function initialState() {
    let selectedUniversityId = "bru";
    try {
      const legacy = localStorage.getItem("schedule.selected-university");
      if (UNIVERSITY_LIST.some((university) => university.id === legacy)) selectedUniversityId = legacy;
    } catch {}
    const today = todayISO();
    return {
      selectedUniversityId,
      selectedProfileId: null,
      selectedDate: today,
      calendarMonth: `${today.slice(0, 7)}-01`,
      profiles: [],
      schedules: {},
      tasks: [],
      appearance: { theme: "WHITE", lessonAccents: { ...DEFAULT_ACCENTS } },
      typography: { displayScale: 1.1, headingScale: 1.1, bodyScale: 1.1, captionScale: 1.1 },
      semester: { tracksByUniversityId: {}, endDateOverrides: {}, profileEndDates: {} },
      reminders: { enabled: true, offsetsMinutes: [1440], lessonNotifications: false },
      dailyMessages: [],
      dailyMessagesUpdatedAt: null,
    };
  }
  function loadState() {
    const base = initialState();
    try {
      const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
      if (!saved || typeof saved !== "object") return base;
      const next = { ...base, ...saved };
      next.profiles = Array.isArray(saved.profiles) ? saved.profiles : [];
      next.tasks = Array.isArray(saved.tasks) ? saved.tasks : [];
      next.schedules = saved.schedules && typeof saved.schedules === "object" ? saved.schedules : {};
      next.selectedUniversityId = UNIVERSITY_LIST.some((u) => u.id === saved.selectedUniversityId) ? saved.selectedUniversityId : base.selectedUniversityId;
      next.appearance = { ...base.appearance, ...(saved.appearance || {}), lessonAccents: { ...DEFAULT_ACCENTS, ...(saved.appearance?.lessonAccents || {}) } };
      if (!["BLUE", "BLACK", "WHITE"].includes(next.appearance.theme)) next.appearance.theme = "WHITE";
      for (const type of Object.keys(DEFAULT_ACCENTS)) next.appearance.lessonAccents[type] = safeHexColor(next.appearance.lessonAccents[type], DEFAULT_ACCENTS[type]);
      next.typography = { ...base.typography, ...(saved.typography || {}) };
      for (const key of Object.keys(base.typography)) {
        const scale = Number(next.typography[key]);
        next.typography[key] = Number.isFinite(scale) ? Math.min(1.5, Math.max(1, scale)) : 1.1;
      }
      next.semester = { ...base.semester, ...(saved.semester || {}), tracksByUniversityId: { ...(saved.semester?.tracksByUniversityId || {}) }, endDateOverrides: { ...(saved.semester?.endDateOverrides || {}) }, profileEndDates: { ...(saved.semester?.profileEndDates || {}) } };
      next.reminders = { ...base.reminders, ...(saved.reminders || {}) };
      next.dailyMessages = normalizeDailyMessages(Array.isArray(saved.dailyMessages) ? saved.dailyMessages : []);
      if (!Engine.fromISO(next.selectedDate)) next.selectedDate = base.selectedDate;
      if (!Engine.fromISO(next.calendarMonth)) next.calendarMonth = base.calendarMonth;
      if (!next.selectedProfileId || !next.profiles.some((profile) => profile.id === next.selectedProfileId)) {
        next.selectedProfileId = next.profiles[0]?.id || null;
      }
      if (!Array.isArray(next.reminders.offsetsMinutes)) next.reminders.offsetsMinutes = [1440];
      return next;
    } catch {
      return base;
    }
  }
  let state = loadState();
  let storageWarningShown = false;
  const ui = {
    route: "schedule",
    calendarMode: "week",
    monthCursor: state.calendarMonth || `${todayISO().slice(0, 7)}-01`,
    deadlineFilter: "active",
    modal: null,
    suspendedLessonDraft: null,
    busyProfileId: null,
    search: { universityId: state.selectedUniversityId, type: "GROUP", query: "", results: [], searched: false, loading: false, error: "" },
    toastTimer: null,
  };
  const indexCache = new Map();
  let serviceWorkerPromise = null;

  function saveState() {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); }
    catch (error) {
      console.warn("[SAVE001] Локальное сохранение недоступно", error);
      if (!storageWarningShown) {
        storageWarningShown = true;
        showToast("Не удалось сохранить локальные данные. Код SAVE001. Пришлите скриншот и опишите действие.", "error", 6500);
      }
    }
  }
  function safeHexColor(value, fallback = "#A6B0C2") {
    return /^#[\da-f]{6}$/i.test(String(value || "")) ? String(value) : fallback;
  }
  function esc(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
  }
  function showToast(message, kind = "", timeout = 3600) {
    if (!toastBox) return;
    window.clearTimeout(ui.toastTimer);
    toastBox.textContent = message;
    toastBox.className = `toast is-visible${kind ? ` is-${kind}` : ""}`;
    ui.toastTimer = window.setTimeout(() => { toastBox.className = "toast"; }, timeout);
  }
  function issue(message, code, error) {
    console.error(`[${code}] ${message}`, error || "");
    showToast(`${message} Код ${code}. Пришлите скриншот и опишите действие.`, "error", 6000);
  }
  function formatDate(value, options = { day: "numeric", month: "long" }) {
    const date = typeof value === "string" ? Engine.fromISO(value) : value;
    if (!date) return "—";
    return new Intl.DateTimeFormat("ru-RU", options).format(date);
  }
  function formatDateTime(epoch) {
    const date = new Date(Number(epoch));
    if (Number.isNaN(date.getTime())) return "Срок не указан";
    return new Intl.DateTimeFormat("ru-RU", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }).format(date);
  }
  function monthLabel(value) {
    const date = Engine.fromISO(value) || new Date();
    return new Intl.DateTimeFormat("ru-RU", { month: "long", year: "numeric" }).format(date);
  }
  function plural(value, one, few, many) {
    const n = Math.abs(Number(value)) % 100;
    const last = n % 10;
    if (n > 10 && n < 20) return many;
    if (last > 1 && last < 5) return few;
    if (last === 1) return one;
    return many;
  }
  function universityById(id) { return UNIVERSITY_LIST.find((university) => university.id === id) || UNIVERSITY_LIST[0]; }
  function currentProfiles(universityId = state.selectedUniversityId) { return state.profiles.filter((profile) => profile.universityId === universityId); }
  function getProfile(profileId = state.selectedProfileId) { return state.profiles.find((profile) => profile.id === profileId) || null; }
  function selectedProfile() {
    const profile = getProfile(state.selectedProfileId);
    return profile?.universityId === state.selectedUniversityId ? profile : currentProfiles()[0] || null;
  }
  function scheduleFor(profileId) {
    const value = state.schedules[profileId];
    if (!value || typeof value !== "object") return { lessons: [], previousLessons: [], lastAttemptAt: null, lastSuccessAt: null, lastError: "" };
    return { lessons: Array.isArray(value.lessons) ? value.lessons : [], previousLessons: Array.isArray(value.previousLessons) ? value.previousLessons : [], ...value };
  }
  function getTrack(universityId = state.selectedUniversityId) {
    const tracks = TRACKS[universityId] || [];
    return tracks.find((track) => track.id === state.semester.tracksByUniversityId?.[universityId]) || tracks[0] || { id: "CUSTOM", label: "Другой вуз · вручную", end: "2027-01-10", hint: "Укажите окончание периода вручную" };
  }
  function sessionEndDate(universityId = state.selectedUniversityId, profileId = null) {
    const profileOverride = profileId ? state.semester.profileEndDates?.[profileId] : null;
    if (profileOverride && Engine.fromISO(profileOverride)) return profileOverride;
    const track = getTrack(universityId);
    const key = `${universityId}|${track.id}`;
    const universityOverride = state.semester.endDateOverrides?.[key];
    return universityOverride && Engine.fromISO(universityOverride) ? universityOverride : track.end;
  }
  function coverageEndDate(universityId = state.selectedUniversityId, profileId = null) {
    const end = sessionEndDate(universityId, profileId);
    const plusReserve = Engine.addDays(end, 7);
    return plusReserve < todayISO() ? todayISO() : plusReserve;
  }
  function normalizeSelectedDate() {
    const today = todayISO();
    const profile = selectedProfile();
    const end = coverageEndDate(state.selectedUniversityId, profile?.id || null);
    if (!Engine.fromISO(state.selectedDate) || state.selectedDate < today) state.selectedDate = today;
    if (state.selectedDate > end) state.selectedDate = end;
    ui.monthCursor = `${state.selectedDate.slice(0, 7)}-01`;
    state.calendarMonth = ui.monthCursor;
  }
  function normalizeDailyMessages(messages) {
    return messages
      .filter((message) => typeof message === "string")
      .map((message) => message.trim())
      .filter((message) => message && !message.startsWith("#") && message !== REMOVED_DAILY_MESSAGE)
      .slice(0, 100);
  }
  function getDailyMessages() { return state.dailyMessages.length ? state.dailyMessages : DAILY_MESSAGES_DEFAULT; }
  function dailyQuote() {
    const messages = getDailyMessages();
    const today = Engine.fromISO(todayISO());
    const index = today ? Math.floor((today - new Date(today.getFullYear(), 0, 0, 12)) / 86400000) % messages.length : 0;
    return messages[index] || messages[0] || "Собери день по расписанию.";
  }
  function applyTheme() {
    const theme = String(state.appearance.theme || "WHITE").toLowerCase();
    const activeTheme = ["blue", "black", "white"].includes(theme) ? theme : "white";
    appRoot.className = `theme-${activeTheme}`;
    const themeColor = { blue: "#0A0D12", black: "#000000", white: "#F4F6FA" }[activeTheme];
    document.querySelector('meta[name="theme-color"]')?.setAttribute("content", themeColor);
    const typography = state.typography;
    appRoot.style.setProperty("--scale-display", String(typography.displayScale ?? 1.1));
    appRoot.style.setProperty("--scale-heading", String(typography.headingScale ?? 1.1));
    appRoot.style.setProperty("--scale-body", String(typography.bodyScale ?? 1.1));
    appRoot.style.setProperty("--scale-caption", String(typography.captionScale ?? 1.1));
    appRoot.style.setProperty("--lesson-lecture", safeHexColor(state.appearance.lessonAccents.lecture, DEFAULT_ACCENTS.lecture));
    appRoot.style.setProperty("--lesson-lab", safeHexColor(state.appearance.lessonAccents.laboratory, DEFAULT_ACCENTS.laboratory));
    appRoot.style.setProperty("--lesson-practice", safeHexColor(state.appearance.lessonAccents.practice, DEFAULT_ACCENTS.practice));
    appRoot.style.setProperty("--lesson-other", safeHexColor(state.appearance.lessonAccents.unknown, DEFAULT_ACCENTS.unknown));
  }
  function registerServiceWorkerAfterUserAction() {
    if (!("serviceWorker" in navigator) || !/^https?:$/.test(location.protocol)) return Promise.resolve(false);
    if (!serviceWorkerPromise) {
      serviceWorkerPromise = navigator.serviceWorker.register("./sw.js")
        .then(() => Promise.race([navigator.serviceWorker.ready.then(() => true), new Promise((resolve) => setTimeout(() => resolve(false), 3000))]))
        .catch((error) => { console.warn("[SW001] офлайн-кэш не зарегистрирован", error); return false; });
    }
    return serviceWorkerPromise;
  }
  function glyph(name, className = "") {
    const paths = {
      calendar: '<rect x="3.6" y="5" width="16.8" height="16" rx="3"/><path d="M3.8 10.2h16.4M8 3v4.4M16 3v4.4"/><g fill="currentColor" stroke="none"><rect x="6" y="13.2" width="2.4" height="2.4" rx=".6"/><rect x="11" y="13.2" width="2.4" height="2.4" rx=".6"/><rect x="16" y="13.2" width="2.4" height="2.4" rx=".6"/><rect x="6" y="17.4" width="2.4" height="2.4" rx=".6"/><rect x="11" y="17.4" width="2.4" height="2.4" rx=".6"/><rect x="16" y="17.4" width="2.4" height="2.4" rx=".6"/></g>',
      deadlines: '<rect x="4.3" y="4" width="15.4" height="17.3" rx="2.8"/><rect x="9" y="2.6" width="6" height="4.2" rx="1.4" fill="currentColor"/><path d="m7 9.3 1.2 1.2 2-2.6M12 9.3h5.5m-10.5 3.6 1.2 1.2 2-2.6M12 13h5.5m-10.5 3.6 1.2 1.2 2-2.6M12 16.7h5.5"/>',
      settings: '<path d="M12 5v-2m0 18v-2m7-7h2m-18 0h2M7.4 7.4 6 6m12 12-1.4-1.4m0-9.2L18 6M6 18l1.4-1.4M12 5a7 7 0 1 0 0 14 7 7 0 0 0 0-14Z"/><circle cx="12" cy="12" r="2.6"/>',
      pin: '<path d="M19 10.2c0 4.3-7 10.2-7 10.2S5 14.5 5 10.2a7 7 0 1 1 14 0Z"/><circle cx="12" cy="10" r="2.1"/>',
      cap: '<path d="m2.8 9.3 9.2-5 9.2 5-9.2 5-9.2-5Z"/><path d="M6.4 11.5v4.1c3.3 2.4 7.9 2.4 11.2 0v-4.1M20.9 9.5v5.4"/>',
      clock: '<circle cx="12" cy="12" r="8.7"/><path d="M12 7v5.4l3.7 2.2"/>',
      chevron: '<path d="m9 5 7 7-7 7"/>',
    };
    return `<svg class="${esc(className)}" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.calendar}</svg>`;
  }
  function brandRow(route = "schedule") {
    const title = route === "schedule" ? "Schedule" : route === "deadlines" ? "ДЕДЛАЙНЫ" : "НАСТРОЙКИ";
    const subtitle = route === "schedule" ? dailyQuote() : route === "deadlines" ? "Задачи и даты, которые важно не пропустить." : "Настрой приложение под себя.";
    return `<header class="brand-row">
      <svg class="brand-glyph" viewBox="0 0 44 44" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M14 3v9M30 3v9"/><rect x="5" y="7" width="34" height="30" rx="5"/><path d="M5 17h34"/>
        <g fill="currentColor" stroke="none"><rect x="12.45" y="21" width="3.2" height="3.2" rx="1"/><rect x="17.75" y="21" width="3.2" height="3.2" rx="1"/><rect x="23.05" y="21" width="3.2" height="3.2" rx="1" fill="var(--cyan)"/><rect x="28.35" y="21" width="3.2" height="3.2" rx="1"/><rect x="12.45" y="28" width="3.2" height="3.2" rx="1"/><rect x="17.75" y="28" width="3.2" height="3.2" rx="1"/><rect x="23.05" y="28" width="3.2" height="3.2" rx="1"/><rect x="28.35" y="28" width="3.2" height="3.2" rx="1"/></g>
      </svg>
      <div class="brand-copy"><p class="brand-title">${title}</p><p class="brand-quote">${esc(subtitle)}</p></div>
    </header>`;
  }
  function renderNav() {
    const items = [["schedule", "Расписание", "calendar"], ["deadlines", "Дедлайны", "deadlines"], ["settings", "Настройки", "settings"]];
    return `<nav class="bottom-nav" aria-label="Основная навигация">${items.map(([route, label, icon]) => `
      <button class="nav-item${ui.route === route ? " is-active" : ""}" type="button" data-action="navigate" data-route="${route}" aria-current="${ui.route === route ? "page" : "false"}">
        <span class="nav-icon-box">${glyph(icon, "nav-icon")}</span><span class="nav-label">${label}</span>
      </button>`).join("")}</nav>`;
  }
  function renderUniversityCard() {
    const university = universityById(state.selectedUniversityId);
    return `<button class="university-card" type="button" data-action="open-university-picker" aria-label="Выбрать вуз">
      <span class="university-badge">ВУЗ</span>
      <span class="university-copy"><span class="university-name">${esc(university.name)}</span><span class="university-label">${esc(university.short)}</span></span>
      <span class="university-change">СМЕНИТЬ&nbsp;<span class="chevron-down" aria-hidden="true"></span></span>
    </button>`;
  }
  function renderProfileRail() {
    const profiles = currentProfiles();
    return `<section class="profiles-block" aria-label="Мои расписания">
      <div class="profiles-heading"><span class="eyebrow">МОИ РАСПИСАНИЯ</span><span class="profiles-count">${profiles.length} проф.</span></div>
      ${profiles.length ? `<div class="profile-rail">${profiles.map((profile) => `<button class="profile-chip${profile.id === selectedProfile()?.id ? " is-active" : ""}" type="button" data-action="select-profile" data-id="${esc(profile.id)}">
        <span class="profile-chip-label">${esc(profile.name)}</span><span class="profile-chip-type">${profile.type === "TEACHER" ? "ПРЕПОДАВАТЕЛЬ" : "ГРУППА"}</span>
      </button>`).join("")}<button class="profile-chip profile-add-chip" type="button" data-action="open-add-profile" aria-label="Добавить расписание">+</button></div>` : `<div class="profiles-empty"><span>Профили появятся после добавления расписания.</span><button class="text-button" type="button" data-action="open-add-profile">+ Добавить</button></div>`}
    </section>`;
  }
  function getProfileLessons(profileId) {
    return scheduleFor(profileId).lessons.slice().sort((a, b) => a.lessonDate.localeCompare(b.lessonDate) || a.startTime.localeCompare(b.startTime) || Number(a.slotNumber) - Number(b.slotNumber));
  }
  function lessonsOnDate(profileId, date) { return getProfileLessons(profileId).filter((lesson) => lesson.lessonDate === date); }
  function pendingTaskCountForDay(profileId, date) {
    const lessonIds = new Set(lessonsOnDate(profileId, date).map((lesson) => lesson.id));
    return state.tasks.filter((task) => !task.isCompleted && task.profileId === profileId && lessonIds.has(task.lessonId)).length;
  }
  function renderWeekStrip(profile) {
    const monday = Engine.weekStart(state.selectedDate);
    const today = todayISO();
    const lessons = profile ? getProfileLessons(profile.id) : [];
    const days = Array.from({ length: 7 }, (_, index) => Engine.addDays(monday, index));
    return `<div class="week-strip">${days.map((date) => {
      const dateObject = Engine.fromISO(date);
      const weekday = new Intl.DateTimeFormat("ru-RU", { weekday: "short" }).format(dateObject).replace(".", "").toUpperCase();
      const hasLessons = lessons.some((lesson) => lesson.lessonDate === date);
      const disabled = date < today || date > coverageEndDate(state.selectedUniversityId, profile?.id || null);
      return `<button class="day-pill${date === state.selectedDate ? " is-selected" : ""}${date === today ? " is-today" : ""}${hasLessons ? " has-lessons" : ""}" type="button" data-action="select-date" data-date="${date}" ${disabled ? "disabled" : ""}>
        <span class="weekday">${esc(weekday)}</span><span class="day-number">${String(dateObject.getDate()).padStart(2, "0")}</span><span class="day-mark"></span>
      </button>`;
    }).join("")}</div>`;
  }
  function shortRuDate(value) {
    return formatDate(value, { day: "2-digit", month: "short" }).replace(/\.$/, "");
  }
  function renderSemesterCalendar(profile) {
    const start = Engine.fromISO(todayISO());
    const end = Engine.fromISO(coverageEndDate(state.selectedUniversityId, profile?.id || null));
    const lessons = profile ? getProfileLessons(profile.id) : [];
    const weekdays = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"].map((day) => `<span class="month-weekday">${day}</span>`).join("");
    const months = [];
    let cursor = new Date(start.getFullYear(), start.getMonth(), 1, 12);
    const lastMonth = new Date(end.getFullYear(), end.getMonth(), 1, 12);
    while (cursor <= lastMonth) {
      const first = new Date(cursor.getFullYear(), cursor.getMonth(), 1, 12);
      const daysInMonth = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 0, 12).getDate();
      const leading = (first.getDay() + 6) % 7;
      const total = Math.ceil((leading + daysInMonth) / 7) * 7;
      const cells = [];
      for (let index = 0; index < total; index += 1) {
        const day = index - leading + 1;
        if (day < 1 || day > daysInMonth) { cells.push('<span class="month-day" aria-hidden="true"></span>'); continue; }
        const date = Engine.toISO(new Date(cursor.getFullYear(), cursor.getMonth(), day, 12));
        const hasLessons = lessons.some((lesson) => lesson.lessonDate === date);
        const disabled = date < todayISO() || date > Engine.toISO(end);
        cells.push(`<button class="month-day${date === todayISO() ? " is-today" : ""}${date === state.selectedDate ? " is-selected" : ""}${hasLessons ? " has-lessons" : ""}" type="button" data-action="select-date" data-date="${date}" ${disabled ? "disabled" : ""}>${day}<span class="month-day-mark"></span></button>`);
      }
      months.push(`<section class="semester-month"><h3 class="month-nav-title">${esc(monthLabel(Engine.toISO(first)))}</h3><div class="month-weekdays">${weekdays}</div><div class="month-grid">${cells.join("")}</div></section>`);
      cursor = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1, 12);
    }
    return `<div class="semester-months">${months.join("")}</div>`;
  }
  function renderCalendar(profile) {
    const monday = Engine.weekStart(state.selectedDate);
    const sunday = Engine.addDays(monday, 6);
    const todayWeek = Engine.weekStart(todayISO());
    const end = coverageEndDate(state.selectedUniversityId, profile?.id || null);
    const record = profile ? scheduleFor(profile.id) : null;
    const hasRemoteSchedule = Boolean(record?.lastSuccessAt) || Boolean(record?.lessons?.some((lesson) => !lesson.isCustom));
    const canGoPrevious = Engine.addDays(monday, -7) >= todayWeek;
    const canGoNext = Engine.addDays(monday, 7) <= Engine.weekStart(end);
    const heading = ui.calendarMode === "week" ? `НЕДЕЛЯ · ${monday.slice(8, 10)}.${monday.slice(5, 7)}` : "СЕМЕСТР · ОБЗОР";
    const range = ui.calendarMode === "week" ? `${shortRuDate(monday)} — ${shortRuDate(sunday)}` : `${shortRuDate(todayISO())} — ${shortRuDate(end)}`;
    const endCaption = hasRemoteSchedule ? `ДО ${shortRuDate(end).toUpperCase()}` : "";
    return `<section class="calendar-panel" aria-label="Календарь расписания">
      <div class="calendar-toolbar"><div class="calendar-period"><div class="calendar-eyebrow-row"><p class="calendar-eyebrow">${esc(heading)}</p>${endCaption ? `<span class="calendar-end-caption">${esc(endCaption)}</span>` : ""}</div><h2 class="calendar-period-title">${esc(range)}</h2></div>
        <div class="calendar-actions"><button class="round-button" type="button" data-action="change-week" data-delta="-1" aria-label="Предыдущая неделя" ${!canGoPrevious || ui.calendarMode !== "week" ? "disabled" : ""}>‹</button><button class="round-button" type="button" data-action="change-week" data-delta="1" aria-label="Следующая неделя" ${!canGoNext || ui.calendarMode !== "week" ? "disabled" : ""}>›</button></div>
      </div>
      <div class="calendar-mode-row"><button class="calendar-mode-pill${ui.calendarMode === "week" ? " is-active" : ""}" type="button" data-action="calendar-mode" data-mode="week">Неделя</button><button class="calendar-mode-pill${ui.calendarMode === "semester" ? " is-active" : ""}" type="button" data-action="calendar-mode" data-mode="semester">Весь семестр</button></div>
      ${ui.calendarMode === "week" ? renderWeekStrip(profile) : renderSemesterCalendar(profile)}
    </section>`;
  }
  function getLessonAccent(lesson) {
    return safeHexColor(state.appearance.lessonAccents[lesson.type], DEFAULT_ACCENTS[lesson.type] || DEFAULT_ACCENTS.unknown);
  }
  function getTimeMinutes(value) {
    const match = String(value || "").match(/^(\d{1,2}):(\d{2})$/);
    return match ? Number(match[1]) * 60 + Number(match[2]) : 0;
  }
  function renderLessonCard(lesson) {
    const typeLabel = Engine.lessonTypeLabel(lesson.type);
    const today = todayISO();
    const now = new Date();
    const currentMinutes = now.getHours() * 60 + now.getMinutes();
    const current = lesson.lessonDate === today && currentMinutes >= getTimeMinutes(lesson.startTime) && currentMinutes < getTimeMinutes(lesson.endTime);
    const pending = state.tasks.filter((task) => !task.isCompleted && task.lessonId === lesson.id).length;
    const rating = Number.isInteger(lesson.rating) && lesson.rating >= 1 && lesson.rating <= 5 ? lesson.rating : 0;
    const stickers = Array.isArray(lesson.stickers) ? lesson.stickers.filter((sticker) => LESSON_STICKERS.includes(sticker)) : [];
    return `<article class="lesson-card${current ? " is-current" : ""}" style="--lesson-color:${esc(getLessonAccent(lesson))}" data-action="edit-lesson" data-id="${esc(lesson.id)}" tabindex="0" role="group" aria-label="Пара: ${esc(lesson.subjectName)}">
      <span class="lesson-accent" aria-hidden="true"></span>
      <div class="lesson-content${pending ? " has-task-count" : ""}">
        <div class="lesson-time"><span class="lesson-start">${esc(lesson.startTime)}</span><span class="lesson-end">${esc(lesson.endTime)}</span><span class="lesson-slot">${lesson.slotNumber || "—"} ПАРА</span></div>
        <div class="lesson-main">
          <div class="lesson-topline"><div class="lesson-badges"><span class="lesson-type">${esc(typeLabel.toUpperCase())}</span>${current ? '<span class="lesson-current">СЕЙЧАС</span>' : ""}${lesson.isCustom ? '<span class="lesson-custom">МОЁ</span>' : ""}</div></div>
          <h3 class="lesson-name">${esc(lesson.subjectName || "Новая пара")}</h3>
          <div class="lesson-meta">${lesson.teacherName ? `<span class="lesson-meta-row">${esc(lesson.teacherName)}</span>` : ""}${lesson.classroom ? `<span class="lesson-meta-row"><span class="lesson-meta-icon">${glyph("pin", "lesson-meta-pin")}</span>${esc(lesson.classroom)}</span>` : ""}</div>
          ${lesson.notes ? `<div class="lesson-notes">Заметка · ${esc(lesson.notes)}</div>` : ""}
          <div class="lesson-feedback"><div class="lesson-stickers"><button class="lesson-sticker-add" type="button" data-action="open-sticker-picker" data-id="${esc(lesson.id)}" aria-label="Добавить стикер">+</button>${stickers.map((sticker) => `<button class="lesson-sticker" type="button" data-action="toggle-lesson-sticker" data-id="${esc(lesson.id)}" data-sticker="${esc(sticker)}" aria-label="Убрать стикер ${esc(sticker)}">${esc(sticker)}</button>`).join("")}</div><div class="lesson-rating" role="group" aria-label="Оценка пары: ${rating} из 5">${[1, 2, 3, 4, 5].map((value) => `<button class="lesson-star${value <= rating ? " is-selected" : ""}" type="button" data-action="rate-lesson" data-id="${esc(lesson.id)}" data-rating="${value}" aria-label="${value} из 5">${value <= rating ? "★" : "☆"}</button>`).join("")}</div></div>
        </div>
        ${pending ? `<span class="lesson-task-count">${pending}</span>` : ""}
      </div>
    </article>`;
  }
  function findLessonConflicts(profileId, date, startTime, endTime, excludeId = null) {
    const start = getTimeMinutes(startTime);
    const end = getTimeMinutes(endTime);
    return getProfileLessons(profileId).filter((lesson) => !lesson.isCustom && lesson.id !== excludeId && lesson.lessonDate === date && start < getTimeMinutes(lesson.endTime) && end > getTimeMinutes(lesson.startTime));
  }
  function renderScheduleStatus(profile) {
    const record = scheduleFor(profile.id);
    const loaded = Boolean(record.lastSuccessAt) || record.lessons.some((lesson) => !lesson.isCustom);
    const errorCode = profile.scheduleAvailable === false ? "SYNC404" : "SYNC001";
    if (!loaded && ui.busyProfileId === profile.id) return `<div class="schedule-status-card is-loading"><span class="status-spinner"></span><span class="schedule-status-copy"><strong>Загружаем расписание</strong><span>Получаем расписание и сохраняем его на устройстве. Это может занять несколько секунд.</span></span></div>`;
    if (loaded && ui.busyProfileId === profile.id) return `<div class="schedule-status-card is-refreshing"><span class="status-spinner"></span><span>Обновляем семестровый цикл · сохранённое расписание доступно</span></div>`;
    if (record.lastError && loaded) return `<div class="schedule-status-card is-warning"><strong>Показана сохранённая локальная копия расписания.</strong><span>${esc(record.lastError)} Код ${errorCode}. Пришлите скриншот и опишите действие.</span><button class="text-button" type="button" data-action="open-settings">Открыть настройки&nbsp;<span class="inline-arrow" aria-hidden="true"></span></button></div>`;
    if (!loaded && record.lastError) return `<div class="schedule-status-card is-error"><strong>Не удалось загрузить расписание.</strong><span>${esc(record.lastError)} Код ${errorCode}. Пришлите скриншот и опишите действие.</span><button class="text-button" type="button" data-action="open-settings">Открыть настройки&nbsp;<span class="inline-arrow" aria-hidden="true"></span></button></div>`;
    if (!loaded) return `<div class="schedule-status-card"><span class="settings-label">РАСПИСАНИЕ ЕЩЁ НЕ ЗАГРУЖЕНО</span><span>Чтобы загрузить расписание и сохранить его офлайн, откройте настройки и нажмите «Обновить расписание».</span><button class="text-button" type="button" data-action="open-settings">Открыть настройки&nbsp;<span class="inline-arrow" aria-hidden="true"></span></button></div>`;
    return "";
  }
  function renderFirstProfileCard() {
    return `<section class="empty-card first-profile-card" style="margin-top:18px"><h2>Добавьте расписание</h2><p>Найдите свою группу или преподавателя.</p><div class="button-row"><button class="primary-button" type="button" data-action="open-add-profile">Найти расписание&nbsp;<span class="inline-arrow" aria-hidden="true"></span></button></div></section>`;
  }
  function renderSchedulePage() {
    normalizeSelectedDate();
    const profile = selectedProfile();
    const heading = `<div class="app-shell"><main class="page-content schedule-page">${brandRow("schedule")}${renderUniversityCard()}`;
    if (!profile) return `${heading}${renderFirstProfileCard()}</main>${renderNav()}${renderModal()}</div>`;
    const lessons = lessonsOnDate(profile.id, state.selectedDate);
    const pendingCount = pendingTaskCountForDay(profile.id, state.selectedDate);
    const record = scheduleFor(profile.id);
    const hasRemoteSchedule = Boolean(record.lastSuccessAt) || record.lessons.some((lesson) => !lesson.isCustom);
    const dateEyebrow = `${formatDate(state.selectedDate, { weekday: "long" }).toUpperCase()} · ${formatDate(state.selectedDate, { day: "numeric", month: "short" }).replace(/\.$/, "")}`;
    const websiteLessons = lessons.filter((lesson) => !lesson.isCustom);
    const conflicts = lessons.filter((lesson) => lesson.isCustom).map((personal) => ({
      personal,
      website: websiteLessons.filter((remote) => getTimeMinutes(personal.startTime) < getTimeMinutes(remote.endTime) && getTimeMinutes(personal.endTime) > getTimeMinutes(remote.startTime)),
    })).filter((conflict) => conflict.website.length);
    const conflictNotice = conflicts.length ? `<div class="schedule-conflicts"><p class="conflict-heading">НАКЛАДКИ ПАР</p>${conflicts.map(({ personal, website }) => {
      const summaries = website.slice(0, 2).map((lesson) => `${lesson.subjectName} · ${lesson.lessonDate} ${lesson.startTime}–${lesson.endTime}`).join(" · ");
      const more = website.length > 2 ? ` · ещё ${website.length - 2}` : "";
      const linkedCount = state.tasks.filter((task) => task.lessonId === personal.id).length;
      return `<article class="schedule-conflict-card"><strong>Личная пара пересекается с расписанием сайта</strong><p>Ваша: ${esc(personal.subjectName)} · ${esc(personal.lessonDate)} ${esc(personal.startTime)}–${esc(personal.endTime)}</p><p>С сайта: ${esc(summaries)}${more}</p>${linkedCount ? `<p class="conflict-deadline-note">Связанных дедлайнов: ${linkedCount}. При переносе пары можно перенести и их даты.</p>` : ""}<div class="conflict-actions"><button type="button" class="text-button" data-action="edit-lesson" data-id="${esc(personal.id)}">Изменить день / время</button><button type="button" class="text-button is-danger" data-action="delete-lesson" data-id="${esc(personal.id)}">Удалить мою пару</button></div></article>`;
    }).join("")}</div>` : "";
    const scheduleContent = `<div class="summary-row">
        <div class="summary-card"><span class="summary-eyebrow">${esc(dateEyebrow)}</span><div class="summary-value-row"><span class="summary-value">${String(lessons.length).padStart(2, "0")}</span><span class="summary-label">пар в расписании</span></div></div>
        <div class="summary-card"><span class="summary-eyebrow">АКАДЕМИЧЕСКИЕ ЗАДАЧИ</span><div class="summary-value-row"><span class="summary-value is-task-count${pendingCount ? " has-pending" : ""}">${String(pendingCount).padStart(2, "0")}</span><span class="summary-label">открытых задач</span></div></div>
      </div>
      <div class="schedule-status-slot">${renderScheduleStatus(profile)}</div>
      ${conflictNotice}
      ${lessons.length ? `<div class="lesson-list">${lessons.map(renderLessonCard).join("")}<button class="add-lesson-button" type="button" data-action="new-lesson">+ Добавить свою пару</button></div>` : hasRemoteSchedule ? `<div class="empty-card day-empty-card"><span class="day-empty-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="2.2"/></svg></span><h3>Окно для своих дел</h3><p>На ${esc(formatDate(state.selectedDate, { weekday: "long" }).toLowerCase())} занятий нет. Можно добавить личную пару или оставить день свободным.</p><button class="primary-button" type="button" data-action="new-lesson">+ Своя пара</button></div>` : ""}`;
    return `${heading}${renderProfileRail()}${renderCalendar(profile)}${scheduleContent}</main>${renderNav()}${renderModal()}</div>`;
  }
  function getAllLessons() {
    return Object.values(state.schedules).flatMap((record) => Array.isArray(record?.lessons) ? record.lessons : []);
  }
  function lessonById(id) { return getAllLessons().find((lesson) => lesson.id === id) || null; }
  function profileName(profileId) { return getProfile(profileId)?.name || "Профиль удалён"; }
  function taskLinkInfo(task) {
    if (task.linkType === "LESSON") {
      const lesson = task.lessonId ? lessonById(task.lessonId) : null;
      if (!lesson) return { label: "ПАРА", subject: task.subjectNameSnapshot || "Пара не найдена", profile: profileName(task.profileId), detail: "Связь с занятием потеряна", missing: true, lessonAccent: DEFAULT_ACCENTS.unknown };
      const location = lesson.classroom ? ` · ауд. ${lesson.classroom}` : "";
      return { label: "ПАРА", subject: lesson.subjectName, profile: profileName(task.profileId), detail: `${formatDate(lesson.lessonDate, { day: "numeric", month: "short" }).replace(/\.$/, "")} · ${lesson.startTime}${location}`, missing: false, lessonAccent: getLessonAccent(lesson) };
    }
    if (task.linkType === "SUBJECT") return { label: "ПРЕДМЕТ", subject: task.subjectNameSnapshot || "Предмет", profile: profileName(task.profileId), detail: "", missing: false, lessonAccent: "" };
    return { label: "ОБЩИЙ ДЕДЛАЙН", subject: "Без привязки к расписанию", profile: "", detail: "", missing: false, lessonAccent: "" };
  }
  function visibleTasks() {
    return state.tasks.slice().sort((a, b) => Number(a.dueDate) - Number(b.dueDate));
  }
  function deadlineRemainingDays(value) {
    const iso = typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : Engine.toISO(new Date(Number(value)));
    return Engine.differenceInDays(iso, todayISO());
  }
  function renderDeadlineCard(task) {
    const remaining = deadlineRemainingDays(task.dueDate);
    const overdue = !task.isCompleted && remaining < 0;
    const urgent = !task.isCompleted && remaining <= 1;
    const link = taskLinkInfo(task);
    const stateLabel = task.isCompleted ? "ГОТОВО" : overdue ? "ПРОСРОЧЕНО" : remaining === 0 ? "СЕГОДНЯ" : remaining === 1 ? "ЗАВТРА" : `ЧЕРЕЗ ${remaining} ДН.`;
    const dueLabel = formatDate(task.dueDate, { day: "2-digit", month: "2-digit" });
    const deadlineAccent = task.isCompleted ? "var(--mint)" : overdue ? "var(--danger)" : remaining <= 1 ? "var(--amber)" : "var(--cyan)";
    return `<article class="deadline-card${urgent ? " is-urgent" : ""}${overdue ? " is-overdue" : ""}${task.isCompleted ? " is-complete" : ""}" style="--deadline-accent:${deadlineAccent}">
      <div class="deadline-completion"><button class="deadline-check${task.isCompleted ? " is-checked" : ""}" type="button" data-action="toggle-task" data-id="${esc(task.id)}" aria-label="${task.isCompleted ? "Вернуть в активные" : "Отметить выполненным"}">${task.isCompleted ? "✓" : ""}</button><span>${task.isCompleted ? "ЗАКРЫТ" : "ЗАКРЫТЬ"}</span></div>
      <div class="deadline-main"><div class="deadline-link"><span class="deadline-link-label${link.missing ? " is-missing" : ""}">${esc(link.label)}</span>${link.lessonAccent ? `<i class="deadline-lesson-dot" style="--lesson-dot:${esc(link.lessonAccent)}"></i>` : ""}<span class="deadline-subject">${esc(link.subject)}</span>${link.profile ? `<span class="deadline-profile">· ${esc(link.profile)}</span>` : ""}</div>
        ${link.detail ? `<p class="deadline-link-detail${link.missing ? " is-missing" : ""}">${esc(link.detail)}</p>` : ""}
        <h3 class="deadline-title">${esc(task.taskTitle)}</h3>${task.description ? `<p class="deadline-description">${esc(task.description)}</p>` : ""}
      </div>
      <div class="deadline-side"><span class="deadline-date">${esc(dueLabel)}</span><span class="deadline-state${overdue ? " is-overdue" : task.isCompleted ? " is-complete" : ""}">${esc(stateLabel)}</span><button class="deadline-edit" type="button" data-action="edit-task" data-id="${esc(task.id)}">Изменить</button></div>
    </article>`;
  }
  function renderDeadlinesPage() {
    const tasks = visibleTasks();
    const active = tasks.filter((task) => !task.isCompleted);
    const completed = tasks.filter((task) => task.isCompleted);
    const filterTasks = tasks.filter((task) => {
      if (ui.deadlineFilter === "active") return !task.isCompleted;
      if (ui.deadlineFilter === "completed") return task.isCompleted;
      return true;
    });
    const filters = [["active", "Активные"], ["completed", "Закрытые"], ["all", "Все"]];
    return `<div class="app-shell"><main class="page-content deadlines-page">
      <div class="page-heading"><h1>Дедлайны</h1><p>Задачи и важные даты.</p></div>
      <div class="deadline-summary"><div class="summary-card"><span class="summary-eyebrow">АКТИВНЫЕ</span><span class="summary-value is-active-count">${String(active.length).padStart(2, "0")}</span></div><div class="summary-card"><span class="summary-eyebrow">ВСЕГО</span><span class="summary-value is-total-count">${String(tasks.length).padStart(2, "0")}</span></div></div>
      <button class="primary-button deadline-add-button" type="button" data-action="new-task">+&nbsp; Добавить дедлайн</button>
      <div class="deadline-filters">${filters.map(([id, label]) => `<button class="deadline-filter${ui.deadlineFilter === id ? " is-active" : ""}" type="button" data-action="deadline-filter" data-filter="${id}">${esc(label)}</button>`).join("")}</div>
      ${filterTasks.length ? `<div class="deadline-list">${filterTasks.map(renderDeadlineCard).join("")}</div>` : `<div class="empty-card deadline-empty"><span class="deadline-empty-icon">${tasks.length ? "✓" : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m12 2.7 9.3 9.3-9.3 9.3L2.7 12 12 2.7Z"/><path d="m8.5 12 2.2 2.2 4.8-5"/></svg>'}</span><h3>${tasks.length === 0 ? "Список чист" : ui.deadlineFilter === "active" ? "Все задачи закрыты" : "Пока пусто"}</h3><p>${tasks.length === 0 ? "Добавьте дедлайн здесь или из карточки пары. Его можно связать с предметом, своей парой или оставить без привязки." : "Здесь появятся задания из выбранного раздела."}</p></div>`}
    </main>${renderNav()}${renderModal()}</div>`;
  }

  function swatchCheckColor(value) {
    const hex = safeHexColor(value, "#A6B0C2").slice(1);
    const channels = [0, 2, 4].map((offset) => Number.parseInt(hex.slice(offset, offset + 2), 16) / 255);
    const luminance = channels.map((channel) => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4);
    return 0.2126 * luminance[0] + 0.7152 * luminance[1] + 0.0722 * luminance[2] < 0.48 ? "#FFFFFF" : "#000000";
  }
  function renderThemeSettings() {
    const preview = {
      BLUE: { background: "#0A0D12", accent: "#8FD9F4", foreground: "#F2F5FA", outline: "#263241" },
      BLACK: { background: "#000000", accent: "#83A9FF", foreground: "#F5F7FA", outline: "#262B35" },
      WHITE: { background: "#F4F6FA", accent: "#3C57D8", foreground: "#152033", outline: "#D8DFEA" },
    };
    const descriptions = { BLUE: "Насыщенный синий", BLACK: "AMOLED", WHITE: "Светлая" };
    const labels = { lecture: "Лекция", laboratory: "Лабораторная", practice: "Практика", unknown: "Другое" };
    return `<section class="settings-section appearance-settings"><div class="settings-card">
      <div class="settings-card-heading"><div><p class="eyebrow">ВНЕШНИЙ ВИД</p><h2 class="section-title">Своя палитра</h2><p class="settings-copy">Тема и акценты сохраняются на устройстве и применяются сразу.</p></div></div>
      <div class="theme-choice-row">${THEME_OPTIONS.map((theme) => {
        const colors = preview[theme.id];
        return `<button class="theme-choice${state.appearance.theme === theme.id ? " is-active" : ""}" type="button" data-action="set-theme" data-value="${theme.id}">
          <span class="theme-mini-preview" style="--preview-bg:${colors.background};--preview-accent:${colors.accent};--preview-foreground:${colors.foreground};--preview-outline:${colors.outline}"><i></i><b>Aa</b></span>
          <span class="theme-choice-label">${esc(theme.label)}</span><span class="theme-choice-description">${descriptions[theme.id]}</span>
        </button>`;
      }).join("")}</div>
      <div class="settings-divider"></div>
      <div class="settings-subhead"><div><h3>ЦВЕТА ЗАНЯТИЙ</h3><p>Выберите акцент для каждого типа пары</p></div><button class="text-button" type="button" data-action="reset-accents">Сбросить</button></div>
      ${Object.entries(labels).map(([type, label]) => `<div class="lesson-accent-editor"><div class="accent-heading"><span>${label}</span><code>${esc(state.appearance.lessonAccents[type].toUpperCase())}</code></div><div class="swatch-row">${ACCENT_OPTIONS.map((color) => `<button class="swatch${state.appearance.lessonAccents[type].toLowerCase() === color.toLowerCase() ? " is-active" : ""}" type="button" style="--swatch:${color};--swatch-check:${swatchCheckColor(color)}" data-action="set-accent" data-type="${type}" data-color="${color}" aria-label="${label}: ${color}">${state.appearance.lessonAccents[type].toLowerCase() === color.toLowerCase() ? "✓" : ""}</button>`).join("")}</div></div>`).join("")}
    </div></section>`;
  }
  function renderTypographySettings() {
    const rows = [
      ["displayScale", "Крупные заголовки · от 22 sp", "display"],
      ["headingScale", "Заголовки · 15–21 sp", "heading"],
      ["bodyScale", "Основной текст · 11–14 sp", "body"],
      ["captionScale", "Подписи · до 10 sp", "caption"],
    ];
    return `<section class="settings-section typography-settings"><div class="settings-card">
      <div class="settings-card-heading"><div><p class="eyebrow">ТИПОГРАФИКА</p><h2 class="section-title">Размеры текста</h2><p class="settings-copy">Отдельные ползунки 100–150%; предпросмотр обновляется сразу.</p></div></div>
      <div class="typography-preview"><span class="preview-display">РАСПИСАНИЕ</span><span class="preview-heading">Заголовок карточки</span><span class="preview-body">Обычный текст расписания на день</span><span class="preview-caption">Мелкая подпись · время 09:40</span></div>
      <div class="typography-controls">${rows.map(([key, label, role]) => {
        const value = Math.round(Number(state.typography[key] ?? 1) * 100);
        const rangeFill = `${Math.min(100, Math.max(0, (value - 100) * 2))}%`;
        return `<div class="typography-control"><div class="typography-control-heading"><label for="scale-${key}">${label}</label><span data-scale-value="${key}">${value}%</span></div><input id="scale-${key}" type="range" min="100" max="150" step="5" value="${value}" style="--range-fill:${rangeFill}" data-scale="${key}" aria-label="${label}, масштаб текста"><div class="range-limits"><span>100%</span><span>150%</span></div></div>`;
      }).join("")}</div>
      <button class="text-button typography-reset" type="button" data-action="reset-typography">Сбросить все размеры к 100%</button>
    </div></section>`;
  }
  function formatRussianDateInput(iso) {
    const match = String(iso || "").match(/^(\d{4})-(\d{2})-(\d{2})$/);
    return match ? `${match[3]}.${match[2]}.${match[1]}` : "";
  }
  function parseRussianDateInput(value) {
    const match = String(value || "").match(/^(\d{2})\.(\d{2})\.(\d{4})$/);
    if (!match) return null;
    const day = Number(match[1]); const month = Number(match[2]); const year = Number(match[3]);
    const date = new Date(year, month - 1, day, 12);
    if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day) return null;
    return Engine.toISO(date);
  }
  function renderSemesterSettings(profile) {
    const universityId = state.selectedUniversityId;
    const university = universityById(universityId);
    const tracks = TRACKS[universityId] || [];
    const track = getTrack(universityId);
    const universityDate = sessionEndDate(universityId);
    const profileDate = sessionEndDate(universityId, profile?.id || null);
    const profileOverride = profile ? state.semester.profileEndDates?.[profile.id] : null;
    const universityOverrideKey = `${universityId}|${track.id}`;
    const hasUniversityOverride = Boolean(state.semester.endDateOverrides?.[universityOverrideKey]);
    const coverage = coverageEndDate(universityId, profile?.id || null);
    const dateField = (id, label, value, profileId = "") => `<div class="form-field session-date-field"><label for="${id}">${label}</label><input class="form-control" id="${id}" type="text" inputmode="numeric" maxlength="10" placeholder="ДД.ММ.ГГГГ" autocomplete="off" value="${esc(formatRussianDateInput(value))}" data-session-date data-profile-id="${esc(profileId)}" data-university-id="${esc(universityId)}"></div>`;
    return `<section class="settings-section semester-settings"><div class="settings-card">
      <div class="settings-card-heading"><div><p class="eyebrow">СЕССИЯ ${academicYearLabel()} · ${esc(university.name.toUpperCase())}</p><h2 class="section-title">Период календаря</h2><p class="settings-copy">Расписание доступно с сегодняшнего дня до недели после окончания сессии. Даты по умолчанию ориентировочные — проверьте их для своего факультета и курса.</p></div></div>
      <div class="track-choice-list">${tracks.map((item) => `<button class="track-choice${item.id === track.id ? " is-active" : ""}" type="button" data-action="set-track" data-track="${item.id}"><span class="track-choice-copy"><strong>${esc(item.label)}</strong><span>${esc(item.hint)} · дата по умолчанию до ${formatRussianDateInput(item.end)}, календарь до ${formatRussianDateInput(Engine.addDays(item.end, 7))}</span></span><span class="track-choice-check">${item.id === track.id ? "✓" : "○"}</span></button>`).join("")}</div>
      ${dateField("university-session-end", "Последний день сессии для вуза", universityDate)}
      ${hasUniversityOverride ? `<button class="text-button session-reset" type="button" data-action="reset-university-session-date">Использовать дату по умолчанию для вуза</button>` : ""}
      <p class="form-help session-help">Покрытие автоматически продлевается ещё на 7 дней после указанной даты.</p>
      ${profile ? `<div class="profile-session-settings"><span class="settings-label">ОТДЕЛЬНО ДЛЯ ЭТОГО РАСПИСАНИЯ</span><p class="settings-copy">Факультеты могут заканчивать сессию в разные даты. Для «${esc(profile.name)}» можно задать свою; календарь захватит ещё 7 дней.</p>${dateField("profile-session-end", "Последний день сессии для расписания", profileDate, profile.id)}${profileOverride ? `<button class="text-button session-reset" type="button" data-action="reset-profile-session-date" data-id="${esc(profile.id)}">Использовать дату вуза</button>` : ""}</div>` : ""}
      <p class="semester-coverage-note">Последний день календаря · <strong>${esc(formatDate(coverage, { day: "numeric", month: "long", year: "numeric" }))}</strong></p>
    </div></section>`;
  }
  function renderReminderSettings() {
    const enabled = Boolean(state.reminders.enabled);
    const permission = typeof Notification === "undefined" ? "unsupported" : Notification.permission;
    const permissionLabel = permission === "granted" ? "Разрешение выдано" : permission === "denied" ? "Уведомления запрещены в настройках браузера" : permission === "unsupported" ? "Уведомления не поддерживаются этим браузером" : "Разрешение не запрошено";
    return `<section class="settings-section reminder-settings"><div class="settings-card">
      <div class="settings-card-heading reminder-heading"><div><p class="eyebrow">ДЕДЛАЙНЫ</p><h2 class="section-title">Напоминания</h2><p class="settings-copy">Общие значения для задач с режимом «По умолчанию».</p></div><button class="toggle${enabled ? " is-on" : ""}" type="button" data-action="toggle-reminders" role="switch" aria-checked="${enabled}" aria-label="Напоминания о дедлайнах"></button></div>
      ${enabled ? `<div class="settings-divider"></div><span class="settings-label">НАПОМИНАТЬ ЗАРАНЕЕ</span><div class="reminder-offsets">${DEADLINE_OFFSETS.map(([offset, label]) => `<button class="reminder-offset${state.reminders.offsetsMinutes.includes(offset) ? " is-active" : ""}" type="button" data-action="toggle-reminder-offset" data-offset="${offset}" aria-pressed="${state.reminders.offsetsMinutes.includes(offset)}">${label}</button>`).join("")}</div><p class="form-help">Можно выбрать несколько интервалов. Напоминания доставляются устройством и работают без сети.</p>` : `<p class="form-help">Все напоминания о дедлайнах приостановлены.</p>`}
      <div class="notification-pref"><div class="toggle-copy"><p class="toggle-title">Проверять уведомления, пока приложение открыто</p><p class="toggle-description">${esc(permissionLabel)}</p></div><button class="toggle${state.reminders.lessonNotifications ? " is-on" : ""}" type="button" data-action="toggle-local-notifications" role="switch" aria-checked="${Boolean(state.reminders.lessonNotifications)}" aria-label="Локальные уведомления"></button></div>
      ${state.reminders.lessonNotifications && permission !== "granted" ? `<button class="secondary-button" style="width:100%;margin-top:9px" type="button" data-action="request-notification">Разрешить уведомления</button>` : ""}
      <p class="form-help">iOS может ограничивать фоновые уведомления веб-приложений. Проверка выполняется, пока приложение открыто.</p>
    </div></section>`;
  }
  function renderProfileManagement() {
    const profiles = currentProfiles();
    const profile = selectedProfile();
    const record = profile ? scheduleFor(profile.id) : null;
    const status = !profile ? "Не загружено" : ui.busyProfileId === profile.id ? "Обновляем" : record.lastError ? "Ошибка загрузки" : record.lastSuccessAt ? "Синхронизировано" : "Не загружено";
    const statusClass = !profile ? "" : ui.busyProfileId === profile.id ? "is-loading" : record.lastError ? "is-error" : record.lastSuccessAt ? "is-success" : "";
    const rows = record ? `<div class="management-journal"><div><span>Последнее успешное обновление</span><strong>${record.lastSuccessAt ? esc(formatDateTime(record.lastSuccessAt)) : "Пока нет"}</strong></div><div><span>Последняя попытка</span><strong>${record.lastAttemptAt ? esc(formatDateTime(record.lastAttemptAt)) : "Ещё не запускали"}</strong></div><div><span>Локальный сейв</span><strong>${record.previousSnapshotAt ? esc(formatDateTime(record.previousSnapshotAt)) : record.previousLessons.length ? "Предыдущая версия сохранена" : "Пока не создан"}</strong></div></div>` : "";
    const error = record?.lastError ? `<div class="schedule-status-card is-error"><strong>${profile.scheduleAvailable === false ? "Расписание для этого профиля недоступно." : "Последняя попытка обновления завершилась ошибкой."}</strong><span>${esc(record.lastError)} Код ${profile.scheduleAvailable === false ? "SYNC404" : "SYNC001"}. Пришлите скриншот и опишите действие.</span></div>` : "";
    return `<section class="settings-section management-settings"><div class="settings-card">
      <div class="settings-card-heading"><div><p class="eyebrow">УПРАВЛЕНИЕ РАСПИСАНИЕМ</p></div></div>
      ${profiles.length ? `<div class="profile-select-wrap"><div class="profile-selector"><span class="profile-selector-copy"><span class="profile-selector-label">Выбранное расписание</span><strong>${esc(profile?.name || "Добавьте расписание")}</strong></span><span class="profile-selector-status ${statusClass}">${esc(status)}</span><span class="profile-selector-chevron"><span class="chevron-down" aria-hidden="true"></span></span><select id="managed-profile" class="profile-selector-native" data-setting-profile aria-label="Выбранное расписание">${profiles.map((item) => `<option value="${esc(item.id)}" ${item.id === profile?.id ? "selected" : ""}>${esc(item.name)} · ${TYPE_LABELS[item.type] || "Профиль"}</option>`).join("")}<option value="" disabled>Добавить расписание…</option></select></div></div>
        <div class="settings-divider"></div>${rows}<p class="form-help">Профиль не загружается автоматически. Для обновления нажмите кнопку ниже.</p>${error}
        <div class="management-actions"><button class="primary-button" type="button" data-action="refresh-profile" data-id="${esc(profile.id)}" ${ui.busyProfileId === profile.id ? "disabled" : ""}>${ui.busyProfileId === profile.id ? "Обновляем расписание…" : "Обновить расписание"}</button><button class="secondary-button" type="button" data-action="rollback-profile" data-id="${esc(profile.id)}" ${record.previousLessons.length ? "" : "disabled"}>Восстановить локальный сейв</button><button class="danger-outline-button" type="button" data-action="delete-profile" data-id="${esc(profile.id)}" ${ui.busyProfileId === profile.id ? "disabled" : ""}>Удалить это расписание</button></div>` : `<p class="settings-copy">Сначала добавьте расписание на главном экране.</p><button class="primary-button" style="width:100%;margin-top:9px" type="button" data-action="open-add-profile">Добавить расписание</button>`}
    </div></section>`;
  }
  function renderInfoSettings() {
    return `<section class="settings-section info-settings"><div class="settings-card"><p class="eyebrow">ОБРАТНАЯ СВЯЗЬ</p><a class="text-button info-link" href="https://t.me/schedule_suggestion_bot" target="_blank" rel="noopener noreferrer">Telegram-бот</a></div></section>
      <section class="settings-section install-guide-settings"><div class="settings-card"><a class="secondary-button install-guide-link" href="./ios-install.html">Установка на iPhone / iPad</a></div></section>`;
  }
  function renderSettingsPage() {
    const profile = selectedProfile();
    return `<div class="app-shell"><main class="page-content settings-page">
      <div class="settings-page-heading"><h1>Настройки</h1><p>Оформление и напоминания.</p></div>
      ${renderThemeSettings()}${renderTypographySettings()}${renderSemesterSettings(profile)}${renderReminderSettings()}${renderProfileManagement()}${renderInfoSettings()}
    </main>${renderNav()}${renderModal()}</div>`;
  }

  function openScheduleManagementSettings() {
    ui.route = "settings";
    ui.modal = null;
    ui.suspendedLessonDraft = null;
    render();
    window.requestAnimationFrame(() => {
      appRoot.querySelector(".management-settings")?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
  }

  function render() {
    applyTheme();
    if (ui.route === "deadlines") appRoot.innerHTML = renderDeadlinesPage();
    else if (ui.route === "settings") appRoot.innerHTML = renderSettingsPage();
    else appRoot.innerHTML = renderSchedulePage();
  }

  function modalShell(kicker, title, body, footer = "", dialog = false, variant = "") {
    const variantClass = variant ? ` ${variant}` : "";
    return `<div class="modal-backdrop${dialog ? " is-dialog-backdrop" : ""}" role="presentation"><section class="modal-sheet${dialog ? " is-dialog" : ""}${variantClass}" role="dialog" aria-modal="true" aria-label="${esc(title)}">
      <div class="sheet-handle"></div><header class="modal-header"><div class="modal-header-copy"><p class="modal-kicker">${esc(kicker)}</p><h2 class="modal-title">${esc(title)}</h2></div><button class="modal-close" type="button" data-action="close-modal" aria-label="Закрыть">×</button></header>
      <div class="modal-body">${body}</div>${footer ? `<footer class="modal-footer">${footer}</footer>` : ""}
    </section></div>`;
  }
  function renderUniversityModal() {
    const choices = UNIVERSITY_LIST.map((university) => `<button class="search-result" type="button" data-action="choose-university" data-id="${university.id}">
      <span class="university-badge">${glyph("cap", "")}</span><span class="search-result-copy"><span class="search-result-name">${esc(university.name)}</span><span class="search-result-subtitle">${esc(university.hint)}</span></span><span class="search-result-status">${state.selectedUniversityId === university.id ? "Выбрано" : "Выбрать"}</span>
    </button>`).join("");
    return modalShell("ВЫБОР ВУЗА", "Выберите вуз", `<div class="search-results">${choices}</div>`, `<button class="secondary-button" type="button" data-action="close-modal">Закрыть</button>`);
  }
  function renderAddProfileModal() {
    const university = universityById(ui.search.universityId);
    const inputLabel = ui.search.type === "GROUP" ? "Код или название группы" : "Имя преподавателя";
    const placeholder = ui.search.type === "GROUP" ? "Например, МПР-261 или 41" : "Например, Агеева Т.Н.";
    const resultMarkup = ui.search.loading
      ? ""
      : ui.search.error
        ? `<p class="form-error">${esc(ui.search.error)}</p>`
        : ui.search.results.length
          ? `<div class="search-results">${ui.search.results.map((candidate, index) => {
            const duplicate = state.profiles.some((profile) => profile.universityId === candidate.universityId && profile.type === candidate.type && profile.sourceKey === candidate.sourceKey);
            return `<button class="search-result" type="button" data-action="add-profile-result" data-index="${index}" ${duplicate || !candidate.scheduleAvailable ? "disabled" : ""}>
              <span class="search-result-copy"><span class="search-result-name">${esc(candidate.name)}</span><span class="search-result-subtitle">${esc(candidate.subtitle || `${university.short} · ${ui.search.type === "GROUP" ? "Группа" : "Преподаватель"}`)}</span></span>
              <span class="search-result-status${candidate.scheduleAvailable ? "" : " is-offline"}">${duplicate ? "Добавлено" : candidate.scheduleAvailable ? "Добавить" : "Нет расписания"}</span>
            </button>`;
          }).join("")}</div>`
          : ui.search.searched
            ? `<p class="add-profile-empty-results">Ничего не найдено. Проверьте запрос или попробуйте позже.</p>`
            : "";
    const body = `<div class="add-profile-content">
      <p class="add-profile-intro">${esc(university.name)}. Введите код группы или имя преподавателя.</p>
      <div class="choice-row add-profile-types"><button class="choice-pill${ui.search.type === "GROUP" ? " is-active" : ""}" type="button" data-action="set-search-type" data-type="GROUP">Группа</button><button class="choice-pill${ui.search.type === "TEACHER" ? " is-active" : ""}" type="button" data-action="set-search-type" data-type="TEACHER">Преподаватель</button></div>
      <form id="profile-search-form" class="add-profile-search-form" data-form="profile-search" novalidate>
        <div class="form-field add-profile-query-field"><label for="profile-query">${inputLabel}</label><input id="profile-query" class="form-control" name="query" type="search" value="${esc(ui.search.query)}" placeholder="${esc(placeholder)}" autocomplete="off" data-search-query></div>
        <button class="primary-button add-profile-search-button" type="submit" ${ui.search.loading || !ui.search.query.trim() ? "disabled" : ""}>${ui.search.loading ? '<span class="loading-spinner" aria-hidden="true"></span>Ищем расписание…' : "Искать расписание"}</button>
      </form>
      ${resultMarkup}
      <p class="add-profile-note">Односимвольные коды групп поддерживаются.</p>
      <button class="text-button add-profile-close" type="button" data-action="close-modal">Закрыть</button>
    </div>`;
    return modalShell("НОВОЕ РАСПИСАНИЕ", "Найти профиль", body, "", false, "add-profile-sheet");
  }
  function formInput(label, name, value, options = {}) {
    const { type = "text", placeholder = "", full = false, required = false, maxLength = null, help = "", min = null, max = null, step = null } = options;
    const attrs = [min != null ? `min="${esc(min)}"` : "", max != null ? `max="${esc(max)}"` : "", step != null ? `step="${esc(step)}"` : ""].filter(Boolean).join(" ");
    return `<div class="form-field${full ? " is-full" : ""}"><label for="field-${esc(name)}">${esc(label)}</label><input class="form-control" id="field-${esc(name)}" type="${esc(type)}" name="${esc(name)}" value="${esc(value)}" placeholder="${esc(placeholder)}" ${required ? "required" : ""} ${maxLength != null ? `maxlength="${maxLength}"` : ""} ${attrs} data-field="${esc(name)}">${help ? `<p class="form-help">${esc(help)}</p>` : ""}</div>`;
  }
  function formTextarea(label, name, value, options = {}) {
    const { full = false, placeholder = "", maxLength = 500, help = true } = options;
    return `<div class="form-field${full ? " is-full" : ""}"><label for="field-${esc(name)}">${esc(label)}</label><textarea class="form-control" id="field-${esc(name)}" name="${esc(name)}" placeholder="${esc(placeholder)}" maxlength="${maxLength}" data-field="${esc(name)}">${esc(value)}</textarea>${help ? `<p class="form-help">До ${maxLength} символов.</p>` : ""}</div>`;
  }
  function renderLessonModal() {
    const modal = ui.modal;
    const fields = modal.fields;
    const existing = modal.lessonId ? lessonById(modal.lessonId) : null;
    const linkedCount = existing ? state.tasks.filter((task) => task.lessonId === existing.id && !task.isCompleted).length : 0;
    const typeOptions = [["lecture", "Лекция"], ["laboratory", "Лабораторная"], ["practice", "Практика"], ["unknown", "Другое / неизвестно"]];
    const recurrenceOptions = [["NONE", "Без повтора"], ["WEEKLY", "Раз в неделю"], ["BIWEEKLY", "Раз в две недели"], ["MONTHLY", "Раз в месяц"]];
    const conflictSection = modal.overlaps?.length ? `<div class="schedule-status-card is-warning modal-conflict-panel"><strong>Личная пара пересекается с расписанием сайта.</strong><span>Измените дату или время выше. Если пара всё равно нужна, её можно сохранить вместе с занятиями сайта.</span><div class="modal-conflict-list">${modal.overlaps.map((lesson) => `<div><strong>${esc(lesson.subjectName)}</strong><span>${esc(lesson.startTime)}–${esc(lesson.endTime)}</span></div>`).join("")}</div><button class="secondary-button" type="button" data-action="save-lesson-anyway">Оставить обе пары</button></div>` : "";
    const typeField = `<div class="lesson-dropdown-field"><label class="lesson-dropdown-label" for="field-type">ТИП ЗАНЯТИЯ</label><div class="lesson-dropdown-control"><select id="field-type" name="type" data-field="type">${typeOptions.map(([value, label]) => `<option value="${value}" ${fields.type === value ? "selected" : ""}>${label}</option>`).join("")}</select><span class="select-chevron" aria-hidden="true"></span></div></div>`;
    const recurrenceField = `<div class="lesson-dropdown-field"><label class="lesson-dropdown-label" for="field-recurrence">ПОВТОРЕНИЕ</label><div class="lesson-dropdown-control"><select id="field-recurrence" data-field="recurrence">${recurrenceOptions.map(([value, label]) => `<option value="${value}" ${fields.recurrence === value ? "selected" : ""}>${label}</option>`).join("")}</select><span class="select-chevron" aria-hidden="true"></span></div></div>`;
    const linkedDeadlines = linkedCount ? `<label class="checkbox-inline lesson-linked-deadlines"><input type="checkbox" data-field="moveLinkedDeadlines" ${fields.moveLinkedDeadlines ? "checked" : ""}><span>Перенести ${linkedCount} связанных дедлайнов на дату этой пары</span></label>` : "";
    const sourceNote = existing && !existing.isCustom ? `<p class="lesson-source-note">Занятие из расписания. Изменения сохранятся в приложении.</p>` : "";
    const existingActions = existing ? `<div class="lesson-editor-actions"><button class="secondary-button" type="button" data-action="add-deadline-from-lesson" data-id="${esc(existing.id)}">+ Добавить дедлайн к этой паре</button>${existing.isCustom ? `<button class="danger-button" type="button" data-action="delete-lesson" data-id="${esc(existing.id)}">Удалить эту пару</button>` : ""}</div>` : "";
    const body = `<form id="lesson-form" class="lesson-editor-form" data-form="lesson" novalidate><div class="lesson-editor-stack">
      ${formInput("Предмет", "subjectName", fields.subjectName, { required: true })}
      ${formInput("Преподаватель", "teacherName", fields.teacherName)}
      ${formInput("Корпус / аудитория", "classroom", fields.classroom)}
      ${formTextarea("Заметки · необязательно", "notes", fields.notes, { placeholder: "Например: ручку и линейку", help: false })}
      <div class="lesson-field-row lesson-date-slot-row">${formInput("Дата · ГГГГ-ММ-ДД", "lessonDate", fields.lessonDate, { type: "date", required: true })}${formInput("Пара №", "slotNumber", fields.slotNumber, { type: "number", min: 1, max: 12 })}</div>
      <div class="lesson-field-row lesson-time-row">${formInput("Начало", "startTime", fields.startTime, { type: "time", required: true })}${formInput("Конец", "endTime", fields.endTime, { type: "time", required: true })}</div>
      ${typeField}
      ${!existing ? `${recurrenceField}<p class="lesson-recurrence-note">Повторы создаются до конца периода расписания. Каждый экземпляр можно изменить отдельно.</p>` : ""}
      ${modal.validationError ? `<p class="form-error lesson-validation-error">${esc(modal.validationError)}</p>` : ""}
      ${sourceNote}<p class="lesson-sync-note">Ручные пары не перезаписываются при синхронизации с сайтом.</p>
      ${linkedDeadlines}${conflictSection}
      <div class="lesson-editor-save-row"><button class="text-button" type="button" data-action="close-modal">Закрыть</button><button class="primary-button" type="submit" form="lesson-form">Сохранить правку</button></div>
      ${existingActions}
    </div></form>`;
    const kicker = `${existing ? "РУЧНАЯ ПРАВКА" : "НОВАЯ"} · ПАРА`;
    return modalShell(kicker, existing ? "Изменить расписание" : "Добавить пару", body, "", false, "lesson-editor-sheet");
  }
  function renderDeadlineModal() {
    const modal = ui.modal;
    const fields = modal.fields;
    const linkType = fields.linkType || "NONE";
    const profileId = fields.profileId || "";
    const profile = getProfile(profileId);
    const lessons = profile ? getProfileLessons(profile.id) : [];
    const subjects = [...new Set(lessons.map((lesson) => lesson.subjectName.trim()).filter(Boolean))].sort((a, b) => a.localeCompare(b, "ru"));
    const linkedLesson = lessons.find((lesson) => lesson.id === fields.lessonId);
    const existingTask = modal.taskId ? state.tasks.find((task) => task.id === modal.taskId) : null;
    const linkControls = `<div class="modal-section"><span class="settings-label">ПРИВЯЗКА</span><div class="choice-row"><button class="choice-pill${linkType === "SUBJECT" ? " is-active" : ""}" type="button" data-action="deadline-link-type" data-type="SUBJECT">Предмет</button><button class="choice-pill${linkType === "LESSON" ? " is-active" : ""}" type="button" data-action="deadline-link-type" data-type="LESSON">Пара</button><button class="choice-pill${linkType === "NONE" ? " is-active" : ""}" type="button" data-action="deadline-link-type" data-type="NONE">Без привязки</button></div></div>`;
    let associationFields = `<div class="notice-card" style="margin-top:9px"><span class="notice-icon" aria-hidden="true"><span class="icon-ring"></span></span><div class="notice-copy"><strong>Общий дедлайн</strong><p>Задача не будет связана с профилем, предметом или одной конкретной парой.</p></div></div>`;
    if (linkType !== "NONE") {
      associationFields = `<div class="modal-section form-field"><label for="field-profileId">ПРОФИЛЬ</label><select id="field-profileId" class="form-control" data-field="profileId"><option value="">Выберите профиль</option>${state.profiles.map((item) => `<option value="${esc(item.id)}" ${item.id === profileId ? "selected" : ""}>${esc(item.name)} · ${esc(universityById(item.universityId).short)}</option>`).join("")}</select></div>`;
      if (linkType === "SUBJECT") {
        associationFields += `<div class="modal-section form-field"><label for="field-subjectNameSnapshot">ПРЕДМЕТ</label><input class="form-control" id="field-subjectNameSnapshot" list="deadline-subject-list" type="text" data-field="subjectNameSnapshot" value="${esc(fields.subjectNameSnapshot)}" placeholder="Выберите или введите предмет"><datalist id="deadline-subject-list">${subjects.map((subject) => `<option value="${esc(subject)}"></option>`).join("")}</datalist><p class="form-help">Дедлайн связан с предметом, но не с одной конкретной датой занятия.</p></div>`;
      } else {
        associationFields += `<div class="modal-section form-field"><label for="field-lessonId">КОНКРЕТНАЯ ПАРА</label><select class="form-control" id="field-lessonId" data-field="lessonId"><option value="">Выберите пару</option>${lessons.map((lesson) => `<option value="${esc(lesson.id)}" ${lesson.id === fields.lessonId ? "selected" : ""}>${esc(formatDate(lesson.lessonDate, { day: "numeric", month: "short" }))} · ${esc(lesson.startTime)} · ${esc(lesson.subjectName)}</option>`).join("")}</select>${existingTask?.linkType === "LESSON" && !linkedLesson ? `<p class="form-error">Исходная пара удалена или перенесена. Выберите новую пару или измените привязку.</p>` : ""}${!lessons.length ? `<p class="form-help">В этом профиле пока нет загруженных занятий. Сначала обновите расписание или выберите другую привязку.</p>` : ""}</div>`;
      }
    }
    const reminderMode = fields.reminderMode || "INHERIT";
    const offsets = Array.isArray(fields.reminderOffsetsMinutes) ? fields.reminderOffsetsMinutes : [];
    const reminderMarkup = `<div class="modal-section"><span class="settings-label">НАПОМИНАНИЯ ДЛЯ ЭТОЙ ЗАДАЧИ</span><div class="choice-row"><button class="choice-pill${reminderMode === "INHERIT" ? " is-active" : ""}" type="button" data-action="task-reminder-mode" data-mode="INHERIT">Общие</button><button class="choice-pill${reminderMode === "CUSTOM" ? " is-active" : ""}" type="button" data-action="task-reminder-mode" data-mode="CUSTOM">Индивидуальные</button><button class="choice-pill${reminderMode === "NONE" ? " is-active" : ""}" type="button" data-action="task-reminder-mode" data-mode="NONE">Не напоминать</button></div>${reminderMode === "CUSTOM" ? `<div class="preference-list">${DEADLINE_OFFSETS.map(([offset, label]) => `<label class="preference-option"><input type="checkbox" data-task-reminder-offset="${offset}" ${offsets.includes(offset) ? "checked" : ""}><span>${label} до срока</span></label>`).join("")}</div>` : ""}</div>`;
    const body = `<form id="deadline-form" data-form="deadline" novalidate><div class="form-grid">
      ${formInput("Название задания", "taskTitle", fields.taskTitle, { full: true, required: true, placeholder: "Например, лабораторная работа"})}
      ${formTextarea("Комментарий · необязательно", "description", fields.description, { full: true, placeholder: "Что нужно подготовить или сдать…", maxLength: 1000 })}
      ${formInput("Срок · дата", "dueDate", fields.dueDate, { type: "date", full: true, required: true })}
    </div>${linkControls}${associationFields}${reminderMarkup}${modal.validationError ? `<p class="form-error">${esc(modal.validationError)}</p>` : ""}</form>`;
    const footer = `<button class="secondary-button" type="button" data-action="close-modal">Отмена</button><button class="primary-button" type="submit" form="deadline-form">${existingTask ? "Сохранить" : "Добавить"}</button>`;
    return modalShell("ПЛАНИРОВАНИЕ · ДЕДЛАЙН", existingTask ? "Изменить дедлайн" : "Новая задача", body, footer);
  }
  function renderConfirmModal() {
    const modal = ui.modal;
    let title = "Подтвердите действие";
    let message = "Изменение нельзя будет отменить.";
    if (modal.entity === "profile") {
      title = "Удалить профиль?";
      const profile = getProfile(modal.id);
      message = `Профиль «${profile?.name || "Профиль"}», сохранённые пары, ручные правки и связанные задания будут удалены с этого устройства.`;
    } else if (modal.entity === "task") {
      title = "Удалить дедлайн?";
      const task = state.tasks.find((item) => item.id === modal.id);
      message = `Задача «${task?.taskTitle || ""}» будет удалена.`;
    } else if (modal.entity === "lesson") {
      title = "Удалить пару?";
      const lesson = lessonById(modal.id);
      message = `Пара «${lesson?.subjectName || ""}» будет удалена. Связанные дедлайны сохранятся и, если предмет известен, останутся привязаны к нему.`;
    }
    return modalShell("ПОДТВЕРЖДЕНИЕ", title, `<p class="settings-copy">${esc(message)}</p>`, `<button class="secondary-button" type="button" data-action="close-modal">Отмена</button><button class="danger-button" type="button" data-action="confirm-delete-entity">Удалить</button>`, true);
  }
  function renderStickerPicker() {
    const lesson = lessonById(ui.modal?.lessonId);
    if (!lesson) return modalShell("СТИКЕР", "Пара не найдена", "<p class=\"settings-copy\">Обновите расписание и попробуйте снова.</p>");
    const selected = new Set(Array.isArray(lesson.stickers) ? lesson.stickers : []);
    const choices = LESSON_STICKERS.map((sticker) => `<button class="sticker-picker-item${selected.has(sticker) ? " is-selected" : ""}" type="button" data-action="select-lesson-sticker" data-id="${esc(lesson.id)}" data-sticker="${esc(sticker)}" aria-label="Стикер ${esc(sticker)}">${esc(sticker)}</button>`).join("");
    return modalShell(lesson.subjectName, "Выберите стикер", `<div class="sticker-picker-grid">${choices}</div>`, `<button class="secondary-button" type="button" data-action="close-modal">Закрыть</button>`);
  }
  function renderModal() {
    if (!ui.modal) return "";
    if (ui.modal.kind === "sticker-picker") return renderStickerPicker();
    if (ui.modal.kind === "add-profile") return renderAddProfileModal();
    if (ui.modal.kind === "university-picker") return renderUniversityModal();
    if (ui.modal.kind === "lesson-editor") return renderLessonModal();
    if (ui.modal.kind === "deadline-editor") return renderDeadlineModal();
    if (ui.modal.kind === "confirm-delete") return renderConfirmModal();
    return "";
  }

  function openAddProfileModal() {
    ui.search = { universityId: state.selectedUniversityId, type: "GROUP", query: "", results: [], searched: false, loading: false, error: "" };
    ui.modal = { kind: "add-profile" };
    render();
  }
  function openLessonEditor(lessonId = null) {
    const profile = selectedProfile();
    if (!profile) {
      showToast("Сначала добавьте профиль расписания.");
      return;
    }
    const existing = lessonId ? lessonById(lessonId) : null;
    const fields = existing ? {
      subjectName: existing.subjectName || "", teacherName: existing.teacherName || "", classroom: existing.classroom || "",
      lessonDate: existing.lessonDate, startTime: existing.startTime, endTime: existing.endTime,
      slotNumber: existing.slotNumber || "", type: existing.type || "unknown", notes: existing.notes || "",
      recurrence: "NONE", moveLinkedDeadlines: false,
    } : {
      subjectName: "", teacherName: "", classroom: "", lessonDate: state.selectedDate || todayISO(),
      startTime: "09:00", endTime: "10:25", slotNumber: "", type: "unknown", notes: "",
      recurrence: "NONE", moveLinkedDeadlines: false,
    };
    ui.modal = { kind: "lesson-editor", lessonId: existing?.id || null, profileId: profile.id, fields, overlaps: [], validationError: "" };
    render();
  }
  function openDeadlineEditor(taskId = null, contextualLesson = null) {
    if (ui.modal?.kind === "lesson-editor") ui.suspendedLessonDraft = { ...ui.modal, fields: { ...ui.modal.fields }, overlaps: [...(ui.modal.overlaps || [])] };
    const task = taskId ? state.tasks.find((item) => item.id === taskId) : null;
    const profileId = task?.profileId || contextualLesson?.profileId || selectedProfile()?.id || "";
    const date = task ? Engine.toISO(new Date(task.dueDate)) : Engine.addDays(todayISO(), 3);
    const fields = task ? {
      taskTitle: task.taskTitle || "", description: task.description || "", dueDate: date,
      linkType: task.linkType || "NONE", profileId: task.profileId || "", subjectNameSnapshot: task.subjectNameSnapshot || "",
      lessonId: task.lessonId || "", reminderMode: task.reminderMode || "INHERIT",
      reminderOffsetsMinutes: task.reminderOffsetsMinutes?.length ? [...task.reminderOffsetsMinutes] : [...state.reminders.offsetsMinutes],
    } : {
      taskTitle: contextualLesson ? `Задание · ${contextualLesson.subjectName}` : "", description: "", dueDate: date,
      linkType: contextualLesson ? "LESSON" : "NONE", profileId,
      subjectNameSnapshot: contextualLesson?.subjectName || "", lessonId: contextualLesson?.id || "",
      reminderMode: "INHERIT", reminderOffsetsMinutes: [...state.reminders.offsetsMinutes],
    };
    ui.modal = { kind: "deadline-editor", taskId: task?.id || null, fields, validationError: "" };
    render();
  }
  function safeCatalogPath(profile) {
    const path = String(profile?.dataPath || "").replaceAll("\\", "/");
    const prefix = `universities/${profile?.universityId}/schedules/`;
    const parts = path.split("/");
    return path.startsWith(prefix) && parts.every((part) => part && part !== "." && part !== "..") ? path : null;
  }
  async function fetchCatalogText(path, accept = "text/plain,text/html,*/*;q=0.8") {
    const url = new URL(path, DATA_ROOT);
    if (url.origin !== DATA_ROOT.origin || !url.pathname.includes("/schedule-data/")) throw new Error("Некорректный путь к расписанию.");
    const response = await fetch(url, { headers: { Accept: accept }, cache: "default" });
    if (!response.ok) {
      const error = new Error(`Не удалось загрузить данные (HTTP ${response.status}).`);
      error.status = response.status;
      throw error;
    }
    return response.text();
  }
  async function loadIndex(universityId, type, force = false) {
    const key = `${universityId}|${type}`;
    const cached = indexCache.get(key);
    if (!force && cached && Date.now() - cached.loadedAt < INDEX_TTL_MS) return cached.rows;
    const folder = type === "GROUP" ? "groups" : "teachers";
    const path = `universities/${universityId}/${folder}/index.csv`;
    const content = await fetchCatalogText(path, "text/csv,text/plain,*/*;q=0.8");
    const rows = Engine.parseCSV(content);
    if (!rows.length) throw new Error("Индекс расписания пуст или повреждён.");
    indexCache.set(key, { rows, loadedAt: Date.now() });
    return rows;
  }
  async function searchProfiles() {
    const query = Engine.normalize(ui.search.query);
    if (!query) {
      ui.search.error = "Введите код группы или имя преподавателя.";
      render();
      return;
    }
    ui.search.loading = true;
    ui.search.searched = true;
    ui.search.error = "";
    ui.search.results = [];
    render();
    await registerServiceWorkerAfterUserAction();
    try {
      const rows = await loadIndex(ui.search.universityId, ui.search.type);
      const university = universityById(ui.search.universityId);
      const matches = rows.filter((row) => [row.name, row.subtitle, row.id, row.source_key].some((value) => Engine.normalize(value).includes(query)))
        .slice(0, MAX_RESULTS)
        .map((row) => {
          const schedulePath = String(row.schedule_path || "").replaceAll("\\", "/");
          const safePath = schedulePath.startsWith("schedules/") && schedulePath.split("/").every((part) => part && part !== "." && part !== "..") ? schedulePath : "";
          return {
            id: row.id,
            name: row.name || row.id,
            subtitle: row.subtitle || `${university.short} · ${TYPE_LABELS[ui.search.type]}`,
            type: ui.search.type,
            universityId: ui.search.universityId,
            sourceKey: row.source_key || row.id,
            dataPath: safePath ? `universities/${ui.search.universityId}/${safePath}` : "",
            scheduleAvailable: row.status === "ready" && Boolean(safePath),
            updatedAt: row.updated_at || "",
          };
        });
      ui.search.results = matches;
      ui.search.loading = false;
      ui.search.error = "";
      render();
      if (!matches.length) showToast("Ничего не найдено. Проверьте запрос.");
    } catch (error) {
      ui.search.loading = false;
      ui.search.error = "Не удалось выполнить поиск. Проверьте интернет и попробуйте ещё раз.";
      render();
      issue("Не удалось загрузить индекс расписаний.", "CAT001", error);
    }
  }
  async function addProfileResult(index) {
    const candidate = ui.search.results[Number(index)];
    if (!candidate) return;
    if (!candidate.scheduleAvailable) {
      showToast("Файл расписания не опубликован. Профиль не добавлен.");
      return;
    }
    const duplicate = state.profiles.find((profile) => profile.universityId === candidate.universityId && profile.type === candidate.type && profile.sourceKey === candidate.sourceKey);
    if (duplicate) {
      state.selectedUniversityId = duplicate.universityId;
      state.selectedProfileId = duplicate.id;
      ui.modal = null;
      saveState(); render();
      showToast("Профиль уже добавлен.");
      return;
    }
    const profile = {
      id: uuid("profile"), universityId: candidate.universityId,
      type: candidate.type, name: candidate.name, subtitle: candidate.subtitle,
      sourceKey: candidate.sourceKey, dataPath: candidate.dataPath,
      scheduleAvailable: candidate.scheduleAvailable, sourceUpdatedAt: candidate.updatedAt,
      addedAt: Date.now(),
    };
    state.profiles.push(profile);
    state.selectedUniversityId = profile.universityId;
    state.selectedProfileId = profile.id;
    state.selectedDate = state.selectedDate < todayISO() ? todayISO() : state.selectedDate;
    ui.route = "schedule";
    ui.modal = null;
    saveState(); render();
    if (profile.scheduleAvailable) {
      showToast("Профиль добавлен. Загружаем опубликованное расписание…");
      await refreshProfileSchedule(profile.id);
    } else {
      showToast("Профиль добавлен, но расписание пока недоступно.");
    }
  }
  function conciseSyncError(error) {
    if (error?.status === 404) return "Расписание для этого профиля недоступно. Сохранённая копия не удалена.";
    if (!navigator.onLine) return "Нет подключения к интернету. Если расписание уже загружалось, сохранённая копия остаётся доступна.";
    return "Не удалось обновить расписание. Сохранённая копия не изменена.";
  }
  async function refreshProfileSchedule(profileId) {
    const profile = getProfile(profileId);
    if (!profile) return;
    ui.busyProfileId = profile.id;
    render();
    await registerServiceWorkerAfterUserAction();
    try {
      let path = safeCatalogPath(profile);
      if (!profile.scheduleAvailable || !path) {
        const rows = await loadIndex(profile.universityId, profile.type, true);
        const row = rows.find((item) => (item.source_key || item.id) === profile.sourceKey || item.id === profile.sourceKey);
        const relative = String(row?.schedule_path || "").replaceAll("\\", "/");
        const safe = relative.startsWith("schedules/") && relative.split("/").every((part) => part && part !== "." && part !== "..");
        if (!row || row.status !== "ready" || !safe) {
          const missing = new Error("Расписание для профиля пока недоступно.");
          missing.status = 404;
          throw missing;
        }
        profile.dataPath = `universities/${profile.universityId}/${relative}`;
        profile.scheduleAvailable = true;
        profile.subtitle = row.subtitle || profile.subtitle;
        profile.sourceUpdatedAt = row.updated_at || profile.sourceUpdatedAt || "";
        path = safeCatalogPath(profile);
      }
      if (!path) {
        const unsafe = new Error("Путь расписания не прошёл проверку.");
        unsafe.status = 404;
        throw unsafe;
      }
      const content = await fetchCatalogText(path);
      const parsed = Engine.parseSchedule(content, profile, coverageEndDate(profile.universityId, profile.id), todayISO());
      if (!parsed.length) throw new Error("В расписании нет пар в текущем учебном периоде.");
      const current = scheduleFor(profile.id);
      const previousLessons = current.lessons.filter((lesson) => !lesson.isCustom);
      const merged = Engine.mergeRemoteAndCustom(parsed, current.lessons);
      state.schedules[profile.id] = {
        ...current,
        lessons: merged,
        previousLessons: previousLessons.length ? previousLessons : current.previousLessons,
        previousSnapshotAt: previousLessons.length ? Date.now() : current.previousSnapshotAt || null,
        lastAttemptAt: Date.now(), lastSuccessAt: Date.now(), lastError: "",
      };
      profile.scheduleAvailable = true;
      saveState();
      showToast(`Расписание обновлено: ${parsed.length} ${plural(parsed.length, "занятие", "занятия", "занятий")}.`);
    } catch (error) {
      const record = scheduleFor(profile.id);
      record.lastAttemptAt = Date.now();
      record.lastError = conciseSyncError(error);
      if (error?.status === 404) profile.scheduleAvailable = false;
      state.schedules[profile.id] = record;
      saveState();
      issue(error?.status === 404 ? "Расписание недоступно; локальная копия сохранена." : "Не удалось обновить расписание; локальная копия сохранена.", error?.status === 404 ? "SYNC404" : "SYNC001", error);
    } finally {
      ui.busyProfileId = null;
      render();
    }
  }
  function rollbackSchedule(profileId) {
    const record = scheduleFor(profileId);
    if (!record.previousLessons.length) {
      showToast("Нет предыдущей версии для отката.");
      return;
    }
    const currentRemote = record.lessons.filter((lesson) => !lesson.isCustom);
    const custom = record.lessons.filter((lesson) => lesson.isCustom);
    const currentById = new Map(currentRemote.map((lesson) => [lesson.id, lesson]));
    const currentBySourceKey = new Map(currentRemote.filter((lesson) => lesson.sourceKey).map((lesson) => [lesson.sourceKey, lesson]));
    const restored = record.previousLessons.map((snapshotLesson) => {
      const latest = currentById.get(snapshotLesson.id) || (snapshotLesson.sourceKey && currentBySourceKey.get(snapshotLesson.sourceKey));
      return latest ? {
        ...snapshotLesson,
        notes: latest.notes || "",
        rating: latest.rating ?? null,
        stickers: Array.isArray(latest.stickers) ? [...latest.stickers] : [],
      } : snapshotLesson;
    });
    record.lessons = [...restored, ...custom].sort((a, b) => a.lessonDate.localeCompare(b.lessonDate) || a.startTime.localeCompare(b.startTime));
    record.previousLessons = currentRemote;
    record.previousSnapshotAt = Date.now();
    record.lastError = "";
    state.schedules[profileId] = record;
    saveState(); render();
    showToast("Предыдущая версия расписания восстановлена.");
  }
  function updateLessonFeedback(lessonId, update) {
    const lesson = lessonById(lessonId);
    if (!lesson) return false;
    const record = scheduleFor(lesson.profileId);
    const matches = (item) => item.id === lesson.id || (item.profileId === lesson.profileId && item.lessonDate === lesson.lessonDate && Number(item.slotNumber) === Number(lesson.slotNumber) && (item.subjectName === lesson.subjectName || Boolean(lesson.sourceKey && item.sourceKey === lesson.sourceKey)));
    const apply = (items) => items.map((item) => matches(item) ? { ...item, ...update } : item);
    record.lessons = apply(record.lessons);
    record.previousLessons = apply(record.previousLessons);
    state.schedules[lesson.profileId] = record;
    saveState();
    render();
    return true;
  }
  function saveLessonRating(lessonId, rating) {
    if (!Number.isInteger(rating) || rating < 1 || rating > 5) return;
    updateLessonFeedback(lessonId, { rating });
  }
  function toggleLessonSticker(lessonId, sticker) {
    if (!LESSON_STICKERS.includes(sticker)) return;
    const lesson = lessonById(lessonId);
    if (!lesson) return;
    const current = Array.isArray(lesson.stickers) ? lesson.stickers.filter((item) => LESSON_STICKERS.includes(item)) : [];
    const stickers = current.includes(sticker) ? current.filter((item) => item !== sticker) : [...current, sticker];
    updateLessonFeedback(lessonId, { stickers });
  }
  async function refreshDailyMessagesOnStartup() {
    const now = Date.now();
    const lastUpdate = Number(state.dailyMessagesUpdatedAt);
    const age = now - lastUpdate;
    if (navigator.onLine === false || (Number.isFinite(lastUpdate) && lastUpdate > 0 && age >= 0 && age < DAILY_MESSAGES_REFRESH_TTL_MS)) return;
    try {
      const url = new URL("./content/daily-messages.txt", window.location.href);
      const response = await fetch(url, { headers: { Accept: "text/plain" }, cache: "no-cache" });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const messages = normalizeDailyMessages((await response.text()).split(/\r?\n/));
      if (!messages.length) throw new Error("Файл сообщений пуст.");
      state.dailyMessages = messages;
      state.dailyMessagesUpdatedAt = Date.now();
      saveState();
      if (ui.route === "schedule") {
        const quote = appRoot.querySelector(".brand-quote");
        if (quote) quote.textContent = dailyQuote();
      }
    } catch (error) {
      console.warn("[MSG001] Не удалось обновить фразы дня", error);
    }
  }

  function recurrenceDates(startDate, recurrence, throughDate) {
    const dates = [startDate];
    if (recurrence === "NONE") return dates;
    let date = startDate;
    const monthlyAnchorDay = Engine.fromISO(startDate)?.getDate() || 1;
    while (date < throughDate && dates.length < 80) {
      if (recurrence === "WEEKLY") date = Engine.addDays(date, 7);
      else if (recurrence === "BIWEEKLY") date = Engine.addDays(date, 14);
      else if (recurrence === "MONTHLY") {
        const old = Engine.fromISO(date);
        const next = new Date(old.getFullYear(), old.getMonth() + 1, 1, 12);
        const lastDay = new Date(next.getFullYear(), next.getMonth() + 1, 0, 12).getDate();
        next.setDate(Math.min(monthlyAnchorDay, lastDay));
        date = Engine.toISO(next);
      } else break;
      if (date <= throughDate) dates.push(date);
    }
    return dates;
  }
  function lessonFromFields(profile, fields, existing, date, seriesId = null) {
    const type = fields.type || "unknown";
    const id = existing?.id || (seriesId ? `custom-${Engine.hash(`${seriesId}|${date}`)}` : uuid("lesson"));
    const sourceKey = existing?.sourceKey || (seriesId || `custom-${id}`);
    return {
      id, profileId: profile.id,
      subjectName: String(fields.subjectName || "").trim(),
      teacherName: String(fields.teacherName || "").trim(),
      classroom: String(fields.classroom || "").trim(),
      startTime: fields.startTime, endTime: fields.endTime,
      type, colorHex: existing?.colorHex || state.appearance.lessonAccents[type] || DEFAULT_ACCENTS.unknown,
      isCustom: true, version: Number(existing?.version || 0) + 1,
      lessonDate: date, dayOfWeek: Engine.dayNumber(date),
      slotNumber: Number(fields.slotNumber || 0) || 0,
      sourceKey, notes: String(fields.notes || "").trim().slice(0, 500),
      rating: existing?.rating ?? null,
      stickers: Array.isArray(existing?.stickers) ? [...existing.stickers] : [],
      groupName: existing?.groupName || "", seriesId: existing?.seriesId || seriesId || null,
    };
  }
  function saveLessonFromModal(force = false) {
    const modal = ui.modal;
    if (!modal || modal.kind !== "lesson-editor") return false;
    const fields = modal.fields;
    const profile = getProfile(modal.profileId);
    const existing = modal.lessonId ? lessonById(modal.lessonId) : null;
    if (!profile) {
      modal.validationError = "Профиль расписания больше не найден.";
      render(); return false;
    }
    const subject = String(fields.subjectName || "").trim();
    const date = String(fields.lessonDate || "");
    const start = String(fields.startTime || "");
    const end = String(fields.endTime || "");
    const startMinutes = getTimeMinutes(start);
    const endMinutes = getTimeMinutes(end);
    const maximumDate = coverageEndDate(profile.universityId, profile.id);
    if (!subject) modal.validationError = "Название предмета не может быть пустым.";
    else if (!Engine.fromISO(date)) modal.validationError = "Проверьте дату пары.";
    else if (!/^\d{2}:\d{2}$/.test(start) || !/^\d{2}:\d{2}$/.test(end) || endMinutes <= startMinutes) modal.validationError = "Укажите корректное время начала и окончания.";
    else if (date < todayISO()) modal.validationError = "Дата пары не может быть раньше сегодняшнего дня.";
    else if (date > maximumDate) modal.validationError = `Дата пары выходит за конец периода: ${formatDate(maximumDate, { day: "numeric", month: "long" })}.`;
    else modal.validationError = "";
    if (modal.validationError) { modal.overlaps = []; render(); return false; }

    const dates = existing ? [date] : recurrenceDates(date, fields.recurrence || "NONE", maximumDate);
    const proposed = dates.map((lessonDate) => lessonFromFields(profile, fields, existing, lessonDate, existing ? null : uuid("series")));
    if (!existing && dates.length > 1) {
      const seriesId = uuid("series");
      proposed.forEach((lesson, index) => {
        lesson.seriesId = seriesId;
        lesson.sourceKey = seriesId;
        lesson.id = `custom-${Engine.hash(`${seriesId}|${dates[index]}`)}`;
      });
    }
    const overlaps = [];
    for (const lesson of proposed) {
      overlaps.push(...findLessonConflicts(profile.id, lesson.lessonDate, lesson.startTime, lesson.endTime, existing?.id || null));
    }
    const uniqueOverlaps = [...new Map(overlaps.map((lesson) => [lesson.id, lesson])).values()];
    if (uniqueOverlaps.length && !force) {
      modal.overlaps = uniqueOverlaps;
      modal.validationError = "Проверьте пары, пересекающиеся по времени.";
      render();
      return false;
    }
    const record = scheduleFor(profile.id);
    const next = record.lessons.filter((lesson) => lesson.id !== existing?.id);
    if (force && modal.deleteOverlapId) {
      const conflict = lessonById(modal.deleteOverlapId);
      if (conflict?.isCustom && conflict.profileId === profile.id) {
        detachDeadlinesFromLesson(conflict);
        const index = next.findIndex((lesson) => lesson.id === conflict.id);
        if (index >= 0) next.splice(index, 1);
      }
    }
    next.push(...proposed);
    if (existing && fields.moveLinkedDeadlines && existing.lessonDate !== date) {
      const dayDelta = Engine.differenceInDays(date, existing.lessonDate);
      state.tasks = state.tasks.map((task) => task.lessonId === existing.id ? { ...task, dueDate: Number(task.dueDate) + dayDelta * 86400000 } : task);
    }
    record.lessons = next.sort((a, b) => a.lessonDate.localeCompare(b.lessonDate) || a.startTime.localeCompare(b.startTime) || Number(a.slotNumber) - Number(b.slotNumber));
    state.schedules[profile.id] = record;
    const resumeDraft = existing ? ui.suspendedLessonDraft : null;
    ui.suspendedLessonDraft = null;
    ui.modal = resumeDraft || null;
    if (resumeDraft) {
      resumeDraft.overlaps = [];
      resumeDraft.validationError = "Пересекающаяся пара обновлена. Проверьте и сохраните новую пару.";
    }
    saveState(); render();
    showToast(resumeDraft ? "Пересекающаяся пара изменена. Продолжите сохранение новой пары." : existing ? "Изменения пары сохранены на устройстве." : `Добавлено ${proposed.length} ${plural(proposed.length, "пара", "пары", "пар")}.`);
    return true;
  }
  function saveDeadlineFromModal() {
    const modal = ui.modal;
    if (!modal || modal.kind !== "deadline-editor") return;
    const fields = modal.fields;
    const title = String(fields.taskTitle || "").trim();
    const dueDate = String(fields.dueDate || "");
    const linkType = fields.linkType || "NONE";
    const profile = linkType === "NONE" ? null : getProfile(fields.profileId);
    const linkedLesson = linkType === "LESSON" ? lessonById(fields.lessonId) : null;
    if (!title) modal.validationError = "Название задания не может быть пустым.";
    else if (!Engine.fromISO(dueDate)) modal.validationError = "Проверьте дату дедлайна.";
    else if (linkType !== "NONE" && !profile) modal.validationError = "Выберите профиль для привязки.";
    else if (linkType === "SUBJECT" && !String(fields.subjectNameSnapshot || "").trim()) modal.validationError = "Выберите или введите предмет.";
    else if (linkType === "LESSON" && (!linkedLesson || linkedLesson.profileId !== profile?.id)) modal.validationError = "Выберите доступную пару или смените тип привязки.";
    else modal.validationError = "";
    if (modal.validationError) { render(); return; }
    const due = Engine.fromISO(dueDate);
    due.setHours(9, 0, 0, 0);
    const existing = modal.taskId ? state.tasks.find((task) => task.id === modal.taskId) : null;
    const task = {
      id: existing?.id || uuid("task"),
      lessonId: linkType === "LESSON" ? linkedLesson.id : null,
      taskTitle: title,
      description: String(fields.description || "").trim(),
      dueDate: due.getTime(),
      isCompleted: Boolean(existing?.isCompleted),
      profileId: profile?.id || null,
      subjectNameSnapshot: linkType === "SUBJECT" ? String(fields.subjectNameSnapshot || "").trim() : linkedLesson?.subjectName || "",
      linkType,
      reminderMode: fields.reminderMode || "INHERIT",
      reminderOffsetsMinutes: fields.reminderMode === "CUSTOM" ? [...new Set((fields.reminderOffsetsMinutes || []).map(Number))].sort((a, b) => a - b) : [],
    };
    const index = state.tasks.findIndex((item) => item.id === task.id);
    if (index >= 0) state.tasks[index] = task;
    else state.tasks.push(task);
    const resumeDraft = ui.suspendedLessonDraft;
    ui.suspendedLessonDraft = null;
    ui.modal = resumeDraft || null;
    saveState(); render();
    showToast(existing ? "Дедлайн обновлён." : "Дедлайн добавлен.");
  }
  function setSessionEnd(profileId, universityId, value) {
    if (!Engine.fromISO(value)) return;
    if (profileId) {
      if (value === sessionEndDate(universityId)) delete state.semester.profileEndDates[profileId];
      else state.semester.profileEndDates[profileId] = value;
    } else {
      const track = getTrack(universityId);
      const key = `${universityId}|${track.id}`;
      if (value === track.end) delete state.semester.endDateOverrides[key];
      else state.semester.endDateOverrides[key] = value;
    }
    normalizeSelectedDate();
    saveState(); render();
    showToast("Период расписания сохранён.");
  }
  function resetUniversitySessionEnd() {
    const track = getTrack(state.selectedUniversityId);
    delete state.semester.endDateOverrides[`${state.selectedUniversityId}|${track.id}`];
    normalizeSelectedDate(); saveState(); render();
  }
  function resetProfileSessionEnd(profileId) {
    delete state.semester.profileEndDates[profileId];
    normalizeSelectedDate(); saveState(); render();
  }
  function toggleReminderOffset(offset) {
    const values = new Set(state.reminders.offsetsMinutes || []);
    if (values.has(offset)) {
      if (values.size <= 1) { showToast("Оставьте хотя бы один интервал напоминания."); return; }
      values.delete(offset);
    } else values.add(offset);
    state.reminders.offsetsMinutes = [...values].sort((a, b) => a - b);
    saveState(); render();
  }
  function setTrack(trackId) {
    const universityId = state.selectedUniversityId;
    if (!(TRACKS[universityId] || []).some((track) => track.id === trackId)) return;
    state.semester.tracksByUniversityId[universityId] = trackId;
    normalizeSelectedDate();
    saveState(); render();
  }
  async function requestNotifications() {
    if (typeof Notification === "undefined") {
      showToast("Этот браузер не поддерживает уведомления.");
      return;
    }
    try {
      const permission = await Notification.requestPermission();
      if (permission === "granted") showToast("Разрешение на уведомления выдано.");
      else showToast("Разрешение не выдано. Его можно изменить в настройках iOS.");
    } catch (error) {
      issue("Не удалось запросить разрешение на уведомления.", "NOT001", error);
    }
    render();
  }
  function deleteProfile(profileId) {
    const profile = getProfile(profileId);
    if (!profile) return;
    const removedLessonIds = new Set(scheduleFor(profileId).lessons.map((lesson) => lesson.id));
    state.profiles = state.profiles.filter((item) => item.id !== profileId);
    state.tasks = state.tasks.filter((task) => task.profileId !== profileId && !removedLessonIds.has(task.lessonId));
    delete state.schedules[profileId];
    if (state.semester.profileEndDates) delete state.semester.profileEndDates[profileId];
    if (state.selectedProfileId === profileId) state.selectedProfileId = currentProfiles(state.selectedUniversityId)[0]?.id || state.profiles[0]?.id || null;
    ui.modal = null;
    normalizeSelectedDate();
    saveState(); render();
    showToast("Профиль и расписание удалены с этого устройства.");
  }
  function detachDeadlinesFromLesson(lesson) {
    state.tasks = state.tasks.map((task) => {
      if (task.lessonId !== lesson.id) return task;
      const subject = String(task.subjectNameSnapshot || lesson.subjectName || "").trim();
      return subject
        ? { ...task, lessonId: null, linkType: "SUBJECT", profileId: lesson.profileId, subjectNameSnapshot: subject }
        : { ...task, lessonId: null, linkType: "NONE", profileId: null, subjectNameSnapshot: "" };
    });
  }
  function confirmDeleteEntity() {
    const modal = ui.modal;
    if (!modal || modal.kind !== "confirm-delete") return;
    if (modal.entity === "profile") deleteProfile(modal.id);
    else if (modal.entity === "task") {
      state.tasks = state.tasks.filter((task) => task.id !== modal.id);
      ui.modal = null; saveState(); render(); showToast("Дедлайн удалён.");
    } else if (modal.entity === "lesson") {
      const lesson = lessonById(modal.id);
      if (lesson?.isCustom) {
        detachDeadlinesFromLesson(lesson);
        const record = scheduleFor(lesson.profileId);
        record.lessons = record.lessons.filter((item) => item.id !== lesson.id);
        state.schedules[lesson.profileId] = record;
        saveState();
      }
      ui.modal = ui.suspendedLessonDraft || null;
      ui.suspendedLessonDraft = null;
      render(); showToast(ui.modal ? "Пара удалена. Проверьте конфликт и сохраните новую запись." : "Пользовательская пара удалена.");
    }
  }

  function updateModalField(input) {
    if (!ui.modal?.fields || !input.dataset.field) return;
    const name = input.dataset.field;
    ui.modal.fields[name] = input.type === "checkbox" ? input.checked : input.value;
    if (ui.modal.validationError) ui.modal.validationError = "";
  }
  function onInput(event) {
    const target = event.target;
    if (target.matches("[data-search-query]")) {
      ui.search.query = target.value;
      const submit = target.form?.querySelector(".add-profile-search-button");
      if (submit) submit.disabled = ui.search.loading || !target.value.trim();
    }
    if (target.matches("[data-field]")) updateModalField(target);
    if (target.matches("[data-session-date]")) {
      const digits = target.value.replace(/\D/g, "").slice(0, 8);
      target.value = digits.length <= 2 ? digits : digits.length <= 4 ? `${digits.slice(0, 2)}.${digits.slice(2)}` : `${digits.slice(0, 2)}.${digits.slice(2, 4)}.${digits.slice(4)}`;
      const isoDate = parseRussianDateInput(target.value);
      if (isoDate && target.dataset.lastCommittedIso !== isoDate) {
        target.dataset.lastCommittedIso = isoDate;
        const profileId = target.dataset.profileId || null;
        const universityId = target.dataset.universityId;
        window.setTimeout(() => {
          if (target.value === formatRussianDateInput(isoDate)) setSessionEnd(profileId, universityId, isoDate);
        }, 0);
      } else if (!isoDate) {
        delete target.dataset.lastCommittedIso;
      }
    }
    if (target.matches("[data-scale]")) {
      const key = target.dataset.scale;
      state.typography[key] = Number(target.value) / 100;
      target.style.setProperty("--range-fill", `${Math.min(100, Math.max(0, (Number(target.value) - 100) * 2))}%`);
      const value = appRoot.querySelector(`[data-scale-value="${key}"]`);
      if (value) value.textContent = `${target.value}%`;
      saveState(); applyTheme();
    }
  }
  function onChange(event) {
    const target = event.target;
    if (target.matches("[data-search-university]")) {
      ui.search.universityId = target.value;
      ui.search.results = []; ui.search.searched = false; ui.search.error = "";
      render(); return;
    }
    if (target.matches("[data-setting-profile]")) {
      const profile = getProfile(target.value);
      if (profile) {
        state.selectedProfileId = profile.id; state.selectedUniversityId = profile.universityId;
        normalizeSelectedDate(); saveState(); render();
      }
      return;
    }
    if (target.matches("[data-field]")) {
      updateModalField(target);
      const name = target.dataset.field;
      if (ui.modal?.kind === "deadline-editor" && name === "profileId") {
        const profile = getProfile(target.value);
        const lessons = profile ? getProfileLessons(profile.id) : [];
        if (ui.modal.fields.linkType === "SUBJECT") ui.modal.fields.subjectNameSnapshot = lessons[0]?.subjectName || "";
        if (ui.modal.fields.linkType === "LESSON") ui.modal.fields.lessonId = lessons[0]?.id || "";
        render(); return;
      }
    }
    if (target.matches("[data-reminder-offset]")) {
      const offset = Number(target.dataset.reminderOffset);
      const values = new Set(state.reminders.offsetsMinutes || []);
      if (target.checked) values.add(offset); else values.delete(offset);
      state.reminders.offsetsMinutes = [...values].sort((a, b) => a - b);
      saveState(); render(); return;
    }
    if (target.matches("[data-task-reminder-offset]")) {
      const offset = Number(target.dataset.taskReminderOffset);
      const values = new Set(ui.modal?.fields?.reminderOffsetsMinutes || []);
      if (target.checked) values.add(offset); else values.delete(offset);
      if (ui.modal?.fields) ui.modal.fields.reminderOffsetsMinutes = [...values].sort((a, b) => a - b);
      return;
    }
    if (target.matches("[data-session-date]")) {
      if (target.value.length === 10 && !parseRussianDateInput(target.value)) issue("Проверьте дату в формате ДД.ММ.ГГГГ.", "TERM001");
      return;
    }
    if (target.matches("[data-scale]")) saveState();
  }
  async function onSubmit(event) {
    const form = event.target.closest("form[data-form]");
    if (!form) return;
    event.preventDefault();
    if (form.dataset.form === "profile-search") await searchProfiles();
    else if (form.dataset.form === "lesson") saveLessonFromModal(false);
    else if (form.dataset.form === "deadline") saveDeadlineFromModal();
  }
  function openDeleteConfirmation(entity, id) {
    ui.modal = { kind: "confirm-delete", entity, id };
    render();
  }
  async function onClick(event) {
    if (event.target.classList?.contains("modal-backdrop")) {
      ui.modal = null; render(); return;
    }
    const button = event.target.closest("[data-action]");
    if (!button) return;
    const action = button.dataset.action;
    const id = button.dataset.id;
    if (action === "navigate") {
      ui.route = button.dataset.route;
      ui.modal = null; ui.suspendedLessonDraft = null;
      render();
    } else if (action === "open-settings") {
      openScheduleManagementSettings();
    } else if (action === "close-modal") {
      if (ui.suspendedLessonDraft && ["lesson-editor", "deadline-editor"].includes(ui.modal?.kind)) {
        ui.modal = ui.suspendedLessonDraft;
        ui.suspendedLessonDraft = null;
      } else {
        ui.modal = null;
        ui.suspendedLessonDraft = null;
      }
      render();
    } else if (action === "open-university-picker") {
      ui.modal = { kind: "university-picker" }; render();
    } else if (action === "choose-university") {
      state.selectedUniversityId = id;
      state.selectedProfileId = currentProfiles(id)[0]?.id || null;
      ui.modal = null; normalizeSelectedDate(); saveState(); render();
    } else if (action === "open-add-profile") {
      openAddProfileModal();
    } else if (action === "set-search-type") {
      ui.search.type = button.dataset.type; ui.search.results = []; ui.search.searched = false; ui.search.error = ""; render();
    } else if (action === "add-profile-result") {
      await addProfileResult(button.dataset.index);
    } else if (action === "select-profile") {
      const profile = getProfile(id);
      if (profile) {
        state.selectedProfileId = profile.id; state.selectedUniversityId = profile.universityId;
        normalizeSelectedDate(); saveState(); render();
      }
    } else if (action === "calendar-mode") {
      ui.calendarMode = button.dataset.mode;
      ui.monthCursor = `${state.selectedDate.slice(0, 7)}-01`;
      state.calendarMonth = ui.monthCursor; saveState(); render();
    } else if (action === "change-week") {
      const monday = Engine.weekStart(state.selectedDate);
      let next = Engine.addDays(monday, Number(button.dataset.delta) * 7);
      if (next < todayISO()) next = todayISO();
      const end = coverageEndDate(state.selectedUniversityId, selectedProfile()?.id || null);
      if (next > end) next = end;
      state.selectedDate = next; state.calendarMonth = `${next.slice(0, 7)}-01`; ui.monthCursor = state.calendarMonth; saveState(); render();
    } else if (action === "change-month") {
      const current = Engine.fromISO(ui.monthCursor) || Engine.fromISO(todayISO());
      const next = new Date(current.getFullYear(), current.getMonth() + Number(button.dataset.delta), 1, 12);
      const today = Engine.fromISO(todayISO());
      const max = Engine.fromISO(coverageEndDate(state.selectedUniversityId, selectedProfile()?.id || null));
      const monthStart = new Date(next.getFullYear(), next.getMonth(), 1, 12);
      if (monthStart.getFullYear() < today.getFullYear() || (monthStart.getFullYear() === today.getFullYear() && monthStart.getMonth() < today.getMonth())) return;
      if (max && monthStart > max) return;
      ui.monthCursor = Engine.toISO(next); state.calendarMonth = ui.monthCursor; saveState(); render();
    } else if (action === "select-date") {
      state.selectedDate = button.dataset.date; state.calendarMonth = `${state.selectedDate.slice(0, 7)}-01`; ui.monthCursor = state.calendarMonth; ui.calendarMode = "week"; saveState(); render();
    } else if (action === "refresh-profile") {
      await refreshProfileSchedule(id);
    } else if (action === "rollback-profile") {
      rollbackSchedule(id);
    } else if (action === "delete-profile") {
      openDeleteConfirmation("profile", id);
    } else if (action === "confirm-delete-entity") {
      confirmDeleteEntity();
    } else if (action === "new-lesson") {
      openLessonEditor();
    } else if (action === "edit-lesson") {
      openLessonEditor(id);
    } else if (action === "open-sticker-picker") {
      if (lessonById(id)) { ui.modal = { kind: "sticker-picker", lessonId: id }; render(); }
    } else if (action === "select-lesson-sticker") {
      ui.modal = null;
      toggleLessonSticker(id, button.dataset.sticker);
    } else if (action === "toggle-lesson-sticker") {
      toggleLessonSticker(id, button.dataset.sticker);
    } else if (action === "rate-lesson") {
      saveLessonRating(id, Number(button.dataset.rating));
    } else if (action === "add-deadline-from-lesson") {
      openDeadlineEditor(null, lessonById(id));
    } else if (action === "delete-lesson") {
      openDeleteConfirmation("lesson", id);
    } else if (action === "edit-overlap") {
      if (ui.modal?.kind === "lesson-editor") {
        ui.suspendedLessonDraft = { ...ui.modal, fields: { ...ui.modal.fields }, overlaps: [...(ui.modal.overlaps || [])] };
        openLessonEditor(id);
      }
    } else if (action === "save-lesson-anyway") {
      saveLessonFromModal(true);
    } else if (action === "delete-overlap-and-save") {
      if (ui.modal) ui.modal.deleteOverlapId = id;
      saveLessonFromModal(true);
    } else if (action === "new-task") {
      openDeadlineEditor();
    } else if (action === "edit-task") {
      openDeadlineEditor(id);
    } else if (action === "delete-task") {
      openDeleteConfirmation("task", id);
    } else if (action === "toggle-task") {
      state.tasks = state.tasks.map((task) => task.id === id ? { ...task, isCompleted: !task.isCompleted } : task);
      saveState(); render();
    } else if (action === "deadline-filter") {
      ui.deadlineFilter = button.dataset.filter; render();
    } else if (action === "deadline-link-type") {
      if (ui.modal?.fields) {
        ui.modal.fields.linkType = button.dataset.type;
        if (button.dataset.type !== "NONE" && !ui.modal.fields.profileId) ui.modal.fields.profileId = selectedProfile()?.id || state.profiles[0]?.id || "";
        const profile = getProfile(ui.modal.fields.profileId);
        const lessons = profile ? getProfileLessons(profile.id) : [];
        if (button.dataset.type === "SUBJECT" && !ui.modal.fields.subjectNameSnapshot) ui.modal.fields.subjectNameSnapshot = lessons[0]?.subjectName || "";
        if (button.dataset.type === "LESSON" && !ui.modal.fields.lessonId) ui.modal.fields.lessonId = lessons[0]?.id || "";
        ui.modal.validationError = ""; render();
      }
    } else if (action === "task-reminder-mode") {
      if (ui.modal?.fields) { ui.modal.fields.reminderMode = button.dataset.mode; render(); }
    } else if (action === "set-theme") {
      state.appearance.theme = button.dataset.value; saveState(); render();
    } else if (action === "set-accent") {
      state.appearance.lessonAccents[button.dataset.type] = button.dataset.color; saveState(); render();
    } else if (action === "reset-accents") {
      state.appearance.lessonAccents = { ...DEFAULT_ACCENTS }; saveState(); render();
    } else if (action === "reset-typography") {
      state.typography = { displayScale: 1, headingScale: 1, bodyScale: 1, captionScale: 1 };
      saveState(); render();
    } else if (action === "set-track") {
      setTrack(button.dataset.track);
    } else if (action === "reset-university-session-date") {
      resetUniversitySessionEnd();
    } else if (action === "reset-profile-session-date") {
      resetProfileSessionEnd(id);
    } else if (action === "toggle-reminder-offset") {
      toggleReminderOffset(Number(button.dataset.offset));
    } else if (action === "toggle-reminders") {
      state.reminders.enabled = !state.reminders.enabled; saveState(); render();
    } else if (action === "toggle-local-notifications") {
      state.reminders.lessonNotifications = !state.reminders.lessonNotifications; saveState();
      if (state.reminders.lessonNotifications && typeof Notification !== "undefined" && Notification.permission === "default") await requestNotifications();
      else render();
    } else if (action === "request-notification") {
      await requestNotifications();
    }
  }

  const sentReminderKeys = new Set();
  function checkOpenAppReminders() {
    if (!state.reminders.lessonNotifications || typeof Notification === "undefined" || Notification.permission !== "granted") return;
    const now = Date.now();
    for (const task of state.tasks) {
      if (task.isCompleted) continue;
      let offsets = [];
      if (task.reminderMode === "NONE") continue;
      if (task.reminderMode === "CUSTOM") offsets = task.reminderOffsetsMinutes || [];
      else if (state.reminders.enabled) offsets = state.reminders.offsetsMinutes || [];
      for (const offset of offsets) {
        const key = `${task.id}:${offset}`;
        const notifyAt = Number(task.dueDate) - Number(offset) * 60000;
        if (sentReminderKeys.has(key) || now < notifyAt || now - notifyAt > 60000 || Number(task.dueDate) <= now) continue;
        sentReminderKeys.add(key);
        const body = `${task.taskTitle} · срок ${formatDateTime(task.dueDate)}`;
        try {
          if (navigator.serviceWorker?.controller) {
            navigator.serviceWorker.ready.then((registration) => registration.showNotification("Скоро дедлайн", { body, tag: key, icon: "./icons/icon-192.png" })).catch(() => new Notification("Скоро дедлайн", { body }));
          } else new Notification("Скоро дедлайн", { body });
        } catch (error) { console.warn("[NOT002] не удалось показать уведомление", error); }
      }
    }
  }

  appRoot.addEventListener("click", (event) => { onClick(event).catch((error) => issue("Не удалось выполнить действие.", "APP001", error)); });
  appRoot.addEventListener("keydown", (event) => {
    const lessonCard = event.target.closest?.(".lesson-card[data-action='edit-lesson']");
    if (lessonCard && event.target === lessonCard && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault(); lessonCard.click();
    }
  });
  appRoot.addEventListener("input", onInput);
  appRoot.addEventListener("change", onChange);
  appRoot.addEventListener("submit", (event) => { onSubmit(event).catch((error) => issue("Не удалось выполнить действие формы.", "FORM001", error)); });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && ui.modal) {
      if (ui.suspendedLessonDraft && ui.modal.kind === "lesson-editor") { ui.modal = ui.suspendedLessonDraft; ui.suspendedLessonDraft = null; }
      else { ui.modal = null; ui.suspendedLessonDraft = null; }
      render();
    }
  });
  document.addEventListener("visibilitychange", checkOpenAppReminders);
  window.setInterval(checkOpenAppReminders, 60000);
  normalizeSelectedDate();
  render();
  void refreshDailyMessagesOnStartup();
})();
