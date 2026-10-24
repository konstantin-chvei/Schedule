const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const context = { window: {} };
vm.runInNewContext(
  fs.readFileSync(path.join(__dirname, "..", "schedule-engine.js"), "utf8"),
  context,
  { filename: "schedule-engine.js" },
);
const { mergeRemoteAndCustom } = context.window.ScheduleEngine;

function lesson(id, lessonDate, overrides = {}) {
  return {
    id,
    profileId: "profile-1",
    subjectName: "Алгебра",
    teacherName: "Преподаватель",
    classroom: "ауд. 10",
    startTime: "09:00",
    endTime: "10:30",
    lessonDate,
    dayOfWeek: 1,
    slotNumber: 1,
    sourceKey: "src-weekly-algebra",
    isCustom: false,
    notes: "",
    rating: null,
    stickers: [],
    ...overrides,
  };
}

test("refresh applies a local edit only to its dated occurrence when source keys repeat", () => {
  const remote = [
    lesson("remote-2026-10-19", "2026-10-19"),
    lesson("remote-2026-10-26", "2026-10-26"),
    lesson("remote-2026-11-02", "2026-11-02"),
  ];
  const edited = {
    ...remote[1],
    subjectName: "Алгебра · моя правка",
    isCustom: true,
    version: 2,
  };
  const merged = mergeRemoteAndCustom(remote, [remote[0], edited, remote[2]]);

  assert.equal(merged.length, 3);
  assert.equal(new Set(merged.map((item) => item.id)).size, 3);
  assert.deepEqual(
    Array.from(merged, (item) => item.lessonDate).sort(),
    ["2026-10-19", "2026-10-26", "2026-11-02"],
  );
  assert.equal(merged.find((item) => item.lessonDate === "2026-10-26").subjectName, "Алгебра · моя правка");
  assert.equal(merged.find((item) => item.lessonDate === "2026-10-26").isCustom, true);
  assert.equal(merged.find((item) => item.lessonDate === "2026-10-19").subjectName, "Алгебра");
  assert.equal(merged.find((item) => item.lessonDate === "2026-10-19").isCustom, false);
});

test("source-key fallback preserves an edit on the same date without affecting other dates", () => {
  const remote = [
    lesson("new-remote-2026-10-19", "2026-10-19"),
    lesson("new-remote-2026-10-26", "2026-10-26"),
    lesson("new-remote-2026-11-02", "2026-11-02"),
  ];
  const custom = lesson("old-remote-2026-10-26", "2026-10-26", {
    subjectName: "Алгебра · моя правка",
    isCustom: true,
  });

  const merged = mergeRemoteAndCustom(remote, [custom]);

  assert.equal(merged.length, 3);
  assert.equal(merged.find((item) => item.lessonDate === "2026-10-26").id, custom.id);
  assert.equal(merged.find((item) => item.lessonDate === "2026-10-26").subjectName, "Алгебра · моя правка");
  assert.equal(merged.find((item) => item.lessonDate === "2026-10-19").isCustom, false);
  assert.equal(merged.find((item) => item.lessonDate === "2026-11-02").isCustom, false);
});

test("refresh collapses duplicate custom rows left by the previous fan-out behavior", () => {
  const remote = Array.from({ length: 12 }, (_, index) => {
    const date = new Date(Date.UTC(2026, 9, 19 + index * 7)).toISOString().slice(0, 10);
    return lesson(`remote-${index}`, date);
  });
  const edited = {
    ...remote[4],
    id: "legacy-edited-remote-id",
    subjectName: "Алгебра · моя правка",
    isCustom: true,
    version: 2,
  };
  const legacyDuplicateRows = Array.from({ length: 12 }, () => ({ ...edited }));
  const refreshedRemote = remote.map((item) => ({ ...item, id: `refreshed-${item.id}` }));

  const merged = mergeRemoteAndCustom(refreshedRemote, legacyDuplicateRows);

  assert.equal(merged.length, 12);
  assert.equal(new Set(merged.map((item) => item.id)).size, 12);
  assert.equal(merged.filter((item) => item.isCustom).length, 1);
  assert.equal(merged.find((item) => item.id === edited.id).subjectName, "Алгебра · моя правка");
});

test("source-key fallback still preserves a custom edit for a unique remote lesson", () => {
  const custom = lesson("old-remote-id", "2026-10-19", {
    subjectName: "Алгебра · моя правка",
    isCustom: true,
  });
  const refreshed = lesson("new-remote-id", "2026-10-19");

  const merged = mergeRemoteAndCustom([refreshed], [custom]);

  assert.equal(merged.length, 1);
  assert.equal(merged[0].id, custom.id);
  assert.equal(merged[0].subjectName, "Алгебра · моя правка");
  assert.equal(merged[0].isCustom, true);
});
