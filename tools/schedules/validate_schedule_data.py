#!/usr/bin/env python3
"""Validate the generated schedule-data tree before committing it to GitHub."""
from __future__ import annotations

import csv
import re
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[2] / "schedule-data"
PROFILE_TYPES = ("groups", "teachers")
REQUIRED_INDEX_FIELDS = {"id", "name", "schedule_path", "status"}
REQUIRED_SCHEDULE_FIELDS = {"date", "subject"}


def read_csv(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        return list(reader.fieldnames or []), [
            {key: (value or "").strip() for key, value in row.items() if key}
            for row in reader
        ]


def validate(root: Path) -> list[str]:
    errors: list[str] = []
    universities_path = root / "universities.csv"
    if not universities_path.is_file():
        return [f"Отсутствует {universities_path}"]
    try:
        fields, universities = read_csv(universities_path)
    except (OSError, csv.Error) as exc:
        return [f"Не читается universities.csv: {exc}"]
    if not {"id", "name"}.issubset(fields):
        errors.append("universities.csv должен содержать столбцы id,name")
    seen_universities: set[str] = set()
    for university in universities:
        university_id = university.get("id", "")
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", university_id):
            errors.append(f"Некорректный university id: {university_id!r}")
            continue
        if university_id in seen_universities:
            errors.append(f"Повторный university id: {university_id}")
        seen_universities.add(university_id)
        university_dir = root / "universities" / university_id
        if not (university_dir / "university.json").is_file():
            errors.append(f"Нет university.json для {university_id}")
        for profile_type in PROFILE_TYPES:
            index_path = university_dir / profile_type / "index.csv"
            if not index_path.is_file():
                errors.append(f"Нет индекса {index_path}")
                continue
            try:
                index_fields, profiles = read_csv(index_path)
            except (OSError, csv.Error) as exc:
                errors.append(f"Не читается {index_path}: {exc}")
                continue
            if not REQUIRED_INDEX_FIELDS.issubset(index_fields):
                errors.append(f"В {index_path} нужны столбцы {','.join(sorted(REQUIRED_INDEX_FIELDS))}")
                continue
            seen_profiles: set[str] = set()
            for row in profiles:
                profile_id = row.get("id", "")
                if not profile_id or profile_id in seen_profiles:
                    errors.append(f"Пустой или повторный id профиля в {index_path}: {profile_id!r}")
                seen_profiles.add(profile_id)
                path = PurePosixPath(row.get("schedule_path", ""))
                expected_prefix = f"schedules/{profile_type}/"
                if path.is_absolute() or ".." in path.parts or not path.as_posix().startswith(expected_prefix):
                    errors.append(f"Небезопасный schedule_path {path} в {index_path}")
                    continue
                schedule_file = university_dir.joinpath(*path.parts)
                if row.get("status") == "ready" and not schedule_file.is_file():
                    errors.append(f"{profile_id} помечен ready, но файла нет: {schedule_file}")
                    continue
                if row.get("status") != "ready":
                    continue
                if schedule_file.stat().st_size == 0:
                    errors.append(f"Пустой файл расписания: {schedule_file}")
                if schedule_file.suffix.lower() == ".csv":
                    try:
                        schedule_fields, schedule_rows = read_csv(schedule_file)
                    except (OSError, csv.Error) as exc:
                        errors.append(f"Не читается расписание {schedule_file}: {exc}")
                        continue
                    normalized = {field.strip().lower().replace(" ", "_") for field in schedule_fields}
                    if not REQUIRED_SCHEDULE_FIELDS.issubset(normalized):
                        errors.append(f"CSV расписания должен содержать date,subject: {schedule_file}")
                    if not schedule_rows:
                        errors.append(f"CSV расписания без пар: {schedule_file}")
                if schedule_file.stat().st_size > 30 * 1024 * 1024:
                    errors.append(f"Файл больше лимита приложения 30 МБ: {schedule_file}")
    return errors


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT
    errors = validate(root)
    if errors:
        for error in errors:
            print(f"ОШИБКА: {error}", file=sys.stderr)
        return 1
    print(f"OK: структура расписаний корректна ({root})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
