#!/usr/bin/env python3
"""Build the static GitHub schedule tree from a small, reviewable university registry.

The app only reads the generated GitHub files. It never reads source_url values from this
registry and never contacts university sites. Network retrieval is explicit via --fetch.

Run from the repository root:
  python3 tools/schedules/build_schedule_tree.py
  python3 tools/schedules/build_schedule_tree.py --university bru --fetch --delay-seconds 2.5
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import shutil
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Iterable
from urllib.parse import urlparse

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PROFILE_TYPES = ("groups", "teachers")
INDEX_FIELDS = ("id", "name", "subtitle", "schedule_path", "status", "updated_at")
INDEX_FIELDS_WITH_SOURCE_KEY = ("id", "name", "subtitle", "source_key", "schedule_path", "status", "updated_at")
ALLOWED_SUFFIXES = {".html", ".htm", ".csv", ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".zip"}
MAX_DOWNLOAD_BYTES = 30 * 1024 * 1024
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,79}$")


class BuildError(RuntimeError):
    pass


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames is None:
            return []
        return [
            {key.strip(): (value or "").strip() for key, value in row.items() if key is not None}
            for row in reader
        ]


def write_csv(path: Path, rows: Iterable[dict[str, str]], fields: tuple[str, ...]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="ignore", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def safe_relative_path(raw: str, profile_type: str) -> PurePosixPath:
    path = PurePosixPath(raw.strip().replace("\\", "/"))
    expected = f"schedules/{profile_type}/"
    if path.is_absolute() or any(part in {"", ".", ".."} for part in path.parts):
        raise BuildError(f"Опасный или пустой schedule_path: {raw!r}")
    if not path.as_posix().startswith(expected):
        raise BuildError(f"Путь расписания должен начинаться с {expected}: {raw!r}")
    if path.suffix.lower() not in ALLOWED_SUFFIXES:
        raise BuildError(f"Формат файла не поддерживается: {path.suffix or '(без расширения)'}")
    return path


def normalize_snapshot_data(data: bytes, suffix: str) -> bytes:
    """Remove insignificant line-end spaces from HTML snapshots without changing source files."""
    if suffix.lower() not in {".html", ".htm"}:
        return data
    return re.sub(rb"[ \t]+(?=\r?$)", b"", data, flags=re.MULTILINE)


def file_timestamp(path: Path) -> str:
    return datetime.fromtimestamp(path.stat().st_mtime, timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


class PoliteFetcher:
    def __init__(self, delay_seconds: float, retries: int) -> None:
        self.delay_seconds = max(0.0, delay_seconds)
        self.retries = max(0, retries)
        self.last_request_by_host: dict[str, float] = {}

    def fetch(self, url: str) -> bytes:
        parsed = urlparse(url)
        if parsed.scheme.lower() != "https" or not parsed.hostname:
            raise BuildError(f"Разрешены только публичные HTTPS-источники: {url}")
        host = parsed.hostname.lower()
        for attempt in range(self.retries + 1):
            elapsed = time.monotonic() - self.last_request_by_host.get(host, 0.0)
            if elapsed < self.delay_seconds:
                time.sleep(self.delay_seconds - elapsed)
            self.last_request_by_host[host] = time.monotonic()
            request = urllib.request.Request(
                url,
                headers={
                    "User-Agent": "ScheduleDataBuilder/1.0 (manual snapshot update)",
                    "Accept": "text/html,application/xhtml+xml,application/pdf,application/octet-stream,*/*;q=0.8",
                },
            )
            try:
                with urllib.request.urlopen(request, timeout=40) as response:
                    length = response.headers.get("Content-Length")
                    if length and int(length) > MAX_DOWNLOAD_BYTES:
                        raise BuildError(f"Источник вернул файл больше 30 МБ: {url}")
                    body = response.read(MAX_DOWNLOAD_BYTES + 1)
                    if len(body) > MAX_DOWNLOAD_BYTES:
                        raise BuildError(f"Источник вернул файл больше 30 МБ: {url}")
                    if not body:
                        raise BuildError(f"Источник вернул пустой файл: {url}")
                    return body
            except BuildError:
                raise
            except urllib.error.HTTPError as exc:
                retryable = exc.code in {408, 429} or 500 <= exc.code <= 599
                if not retryable or attempt >= self.retries:
                    raise BuildError(f"Источник ответил HTTP {exc.code}: {url}") from exc
                retry_after = exc.headers.get("Retry-After") if exc.headers else None
                try:
                    backoff = max(0.0, min(float(retry_after), 60.0)) if retry_after else min(2 ** attempt, 8)
                except ValueError:
                    backoff = min(2 ** attempt, 8)
                exc.close()
                time.sleep(backoff)
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                if attempt >= self.retries:
                    raise BuildError(f"Не удалось скачать {url}: {exc}") from exc
                time.sleep(min(2 ** attempt, 8))
        raise BuildError(f"Не удалось скачать {url}")


def load_universities(registry: Path) -> list[dict[str, str]]:
    rows = read_csv(registry / "universities.csv")
    if not rows:
        raise BuildError(f"Нет списка вузов: {registry / 'universities.csv'}")
    seen: set[str] = set()
    result: list[dict[str, str]] = []
    for row in rows:
        university_id = row.get("id", "")
        name = row.get("name", "")
        if not SAFE_ID.fullmatch(university_id):
            raise BuildError(f"Некорректный university id: {university_id!r}")
        if not name:
            raise BuildError(f"Для вуза {university_id} не указано название")
        if university_id in seen:
            raise BuildError(f"Повторный university id: {university_id}")
        seen.add(university_id)
        result.append(row)
    return result


def load_profiles(registry: Path, university_id: str, profile_type: str) -> list[dict[str, str]]:
    rows = read_csv(registry / university_id / f"{profile_type}.csv")
    seen: set[str] = set()
    result: list[dict[str, str]] = []
    for row in rows:
        profile_id = row.get("id", "")
        name = row.get("name", "")
        if not SAFE_ID.fullmatch(profile_id):
            raise BuildError(f"Некорректный id профиля в {university_id}/{profile_type}: {profile_id!r}")
        if not name:
            raise BuildError(f"Для профиля {profile_id} не указано имя")
        if profile_id in seen:
            raise BuildError(f"Повторный id профиля {profile_id} в {university_id}/{profile_type}")
        seen.add(profile_id)
        row["schedule_path"] = safe_relative_path(
            row.get("schedule_path", "") or f"schedules/{profile_type}/{profile_id}.html",
            profile_type,
        ).as_posix()
        result.append(row)
    return result


def process_profile(
    *,
    row: dict[str, str],
    profile_type: str,
    university_id: str,
    output_university_dir: Path,
    fetcher: PoliteFetcher | None,
    repository_root: Path,
) -> dict[str, str]:
    relative_path = safe_relative_path(row["schedule_path"], profile_type)
    target = output_university_dir.joinpath(*relative_path.parts)
    target.parent.mkdir(parents=True, exist_ok=True)
    did_write = False

    source_url = row.get("source_url", "")
    if fetcher is not None and source_url:
        try:
            target.write_bytes(normalize_snapshot_data(fetcher.fetch(source_url), target.suffix))
            did_write = True
            print(f"Обновлено: {university_id}/{relative_path}")
        except BuildError as exc:
            print(f"ПРЕДУПРЕЖДЕНИЕ: {exc}; оставляю предыдущую копию, если она есть", file=sys.stderr)

    local_file = row.get("local_file", "")
    if not did_write and local_file:
        source = (repository_root / local_file).resolve()
        root = repository_root.resolve()
        if root not in source.parents or not source.is_file():
            print(f"ПРЕДУПРЕЖДЕНИЕ: локальный файл не найден или вне репозитория: {local_file}", file=sys.stderr)
        else:
            if target.suffix.lower() in {".html", ".htm"}:
                target.write_bytes(normalize_snapshot_data(source.read_bytes(), target.suffix))
                shutil.copystat(source, target)
            else:
                shutil.copy2(source, target)
            did_write = True

    exists = target.is_file() and target.stat().st_size > 0
    status = "ready" if exists else "missing"
    return {
        "id": row["id"],
        "name": row["name"],
        "subtitle": row.get("subtitle", ""),
        "source_key": row.get("source_key", ""),
        "schedule_path": relative_path.as_posix(),
        "status": status,
        "updated_at": file_timestamp(target) if exists else "",
    }


def build(args: argparse.Namespace) -> int:
    registry = args.registry.resolve()
    output = args.output.resolve()
    repository_root = args.repository_root.resolve()
    universities = load_universities(registry)
    if args.university and args.university not in {row["id"] for row in universities}:
        raise BuildError(f"В registry нет вуза {args.university!r}")

    fetcher = PoliteFetcher(args.delay_seconds, args.retries) if args.fetch else None
    root_index: list[dict[str, str]] = []
    missing_total = 0
    ready_total = 0

    for university in universities:
        university_id = university["id"]
        name = university["name"]
        university_dir = output / "universities" / university_id
        university_dir.mkdir(parents=True, exist_ok=True)
        for profile_type in PROFILE_TYPES:
            (university_dir / "schedules" / profile_type).mkdir(parents=True, exist_ok=True)
            records = load_profiles(registry, university_id, profile_type)
            index_rows: list[dict[str, str]] = []
            for row in records:
                should_fetch = args.fetch and (args.university is None or args.university == university_id)
                if args.profile_id and row["id"] != args.profile_id:
                    should_fetch = False
                profile_fetcher = fetcher if should_fetch else None
                generated = process_profile(
                    row=row,
                    profile_type=profile_type,
                    university_id=university_id,
                    output_university_dir=university_dir,
                    fetcher=profile_fetcher,
                    repository_root=repository_root,
                )
                index_rows.append(generated)
                if generated["status"] == "ready":
                    ready_total += 1
                else:
                    missing_total += 1
            index_fields = INDEX_FIELDS_WITH_SOURCE_KEY if any(row["source_key"] for row in index_rows) else INDEX_FIELDS
            write_csv(
                university_dir / profile_type / "index.csv",
                index_rows,
                index_fields,
            )

        metadata = {
            "schema_version": 1,
            "id": university_id,
            "name": name,
            "website_url": university.get("website_url", ""),
            "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            "profiles": {
                "groups": len(load_profiles(registry, university_id, "groups")),
                "teachers": len(load_profiles(registry, university_id, "teachers")),
            },
        }
        (university_dir / "university.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        root_index.append({
            "id": university_id,
            "name": name,
            "website_url": university.get("website_url", ""),
            "enabled": "true",
        })

    write_csv(output / "universities.csv", root_index, ("id", "name", "website_url", "enabled"))
    print(f"Каталог собран: {output}")
    print(f"Готовых профилей: {ready_total}; без файла расписания: {missing_total}")
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path, default=REPOSITORY_ROOT / "tools/schedules/registry")
    parser.add_argument("--output", type=Path, default=REPOSITORY_ROOT / "schedule-data")
    parser.add_argument("--repository-root", type=Path, default=REPOSITORY_ROOT)
    parser.add_argument("--university", help="обновить только один вуз, например bru")
    parser.add_argument("--profile-id", help="при --fetch обновить только одну группу или одного преподавателя")
    parser.add_argument("--fetch", action="store_true", help="скачать источники из registry; без этого сеть не используется")
    parser.add_argument("--delay-seconds", type=float, default=2.5, help="минимальная пауза между запросами к одному сайту")
    parser.add_argument("--retries", type=int, default=2, help="число повторов при временной сетевой ошибке")
    return parser.parse_args()


if __name__ == "__main__":
    try:
        raise SystemExit(build(parse_args()))
    except BuildError as error:
        print(f"ОШИБКА: {error}", file=sys.stderr)
        raise SystemExit(2)
