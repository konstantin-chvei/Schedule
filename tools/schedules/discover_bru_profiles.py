#!/usr/bin/env python3
"""Merge group/teacher links from saved rasp.bru.by pages into the updater registry.

This command is fully offline. It does not fetch the university website. It is useful when
new local HTML snapshots of BRU catalogs have been placed in ./bru.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import re
import sys
import unicodedata
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urljoin

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "tools/schedules/registry/bru"
FIELDNAMES = ("id", "name", "subtitle", "source_url", "schedule_path", "local_file")
CYRILLIC = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "yo",
    "ж": "zh", "з": "z", "и": "i", "й": "y", "к": "k", "л": "l", "м": "m",
    "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t", "у": "u",
    "ф": "f", "х": "h", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "sch",
    "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu", "я": "ya",
}

def normalize(value: str) -> str:
    value = value.casefold().replace("ё", "е")
    return re.sub(r"[^a-zа-я0-9]+", "", value)

def slugify(value: str) -> str:
    expanded = "".join(CYRILLIC.get(char, char) for char in value.casefold())
    expanded = unicodedata.normalize("NFKD", expanded).encode("ascii", "ignore").decode("ascii")
    slug = re.sub(r"[^a-z0-9]+", "-", expanded).strip("-")[:60].rstrip("-")
    if not slug:
        slug = "profile"
    return slug

class LinkCollector(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.anchor: dict[str, object] | None = None
        self.records: list[dict[str, str]] = []
        self.heading: list[str] = []
        self.current_heading: str | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attributes = dict(attrs)
        if tag in {"h1", "h2"} and self.current_heading is None:
            self.current_heading = tag
            self.heading = []
        if tag == "a":
            self.anchor = {
                "href": attributes.get("href") or "",
                "classes": set((attributes.get("class") or "").split()),
                "text": [],
                "strong": [],
                "teacher_name": [],
                "in_strong": False,
                "in_teacher_name": False,
            }
        elif self.anchor is not None and tag == "strong":
            self.anchor["in_strong"] = True
        elif self.anchor is not None and "teacher-name" in set((attributes.get("class") or "").split()):
            self.anchor["in_teacher_name"] = True

    def handle_endtag(self, tag: str) -> None:
        if self.anchor is not None and tag == "strong":
            self.anchor["in_strong"] = False
        if self.anchor is not None and tag in {"div", "span"} and self.anchor["in_teacher_name"]:
            self.anchor["in_teacher_name"] = False
        if self.anchor is not None and tag == "a":
            href = str(self.anchor["href"]).strip()
            text = " ".join(str(part).strip() for part in self.anchor["text"] if str(part).strip())
            strong = " ".join(str(part).strip() for part in self.anchor["strong"] if str(part).strip())
            teacher_name = " ".join(str(part).strip() for part in self.anchor["teacher_name"] if str(part).strip())
            classes = self.anchor["classes"]
            if "group-link" in classes and href:
                self.records.append({"type": "groups", "name": strong or text, "href": href})
            elif "/teachers/" in href.lower() and href.lower().endswith((".html", ".htm")) and text:
                self.records.append({"type": "teachers", "name": teacher_name or text.split(" ", 1)[0], "href": href})
            self.anchor = None
        if self.current_heading == tag:
            self.heading = [" ".join(self.heading).strip()]
            self.current_heading = None

    def handle_data(self, data: str) -> None:
        if self.anchor is not None:
            self.anchor["text"].append(data)
            if self.anchor["in_strong"]:
                self.anchor["strong"].append(data)
            if self.anchor["in_teacher_name"]:
                self.anchor["teacher_name"].append(data)
        if self.current_heading is not None:
            self.heading.append(data)

def read_local_page(path: Path) -> tuple[str, str, list[dict[str, str]], str]:
    content = path.read_text(encoding="utf-8", errors="replace")
    saved = re.search(r"saved from url=\(\d+\)(https?://[^\s<>]+)", content, re.I)
    base_url = saved.group(1).rstrip("-") if saved else "https://rasp.bru.by/"
    title_match = re.search(r"<title[^>]*>(.*?)</title>", content, re.I | re.S)
    title = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", title_match.group(1))).strip() if title_match else ""
    collector = LinkCollector()
    collector.feed(content)
    heading = " ".join(collector.heading).strip() or title
    links = []
    for record in collector.records:
        record["href"] = urljoin(base_url, record["href"])
        record["subtitle"] = heading
        record["local_file"] = ""
        links.append(record)
    return title, heading, links, base_url

def current_records(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        return []
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle))

def unique_id(name: str, source_url: str, used: set[str]) -> str:
    base = slugify(name)
    candidate = base
    if candidate in used:
        suffix = hashlib.sha1(source_url.encode("utf-8")).hexdigest()[:8]
        candidate = f"{base[:50]}-{suffix}"
    counter = 2
    while candidate in used:
        candidate = f"{base[:46]}-{counter}"
        counter += 1
    used.add(candidate)
    return candidate

def write_registry(path: Path, records: list[dict[str, str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDNAMES, lineterminator="\n")
        writer.writeheader()
        writer.writerows(records)

def merge_profile_type(
    *,
    profile_type: str,
    discovered: list[dict[str, str]],
    snapshot_pages: dict[str, tuple[Path, str]],
    registry_file: Path,
) -> int:
    existing = current_records(registry_file)
    by_name = {normalize(row.get("name", "")): row for row in existing if row.get("name")}
    records_by_name = dict(by_name)
    used_ids = {row.get("id", "") for row in existing if row.get("id")}

    for name, (local_path, source_url) in snapshot_pages.items():
        if name and name not in records_by_name:
            records_by_name[name] = {
                "id": unique_id(name, source_url, used_ids),
                "name": name,
                "subtitle": "Сохранённая страница расписания",
                "source_url": source_url,
                "schedule_path": f"schedules/{profile_type}/{slugify(name)}.html",
                "local_file": local_path.relative_to(ROOT).as_posix(),
            }

    for item in discovered:
        if item["type"] != profile_type:
            continue
        name = re.sub(r"\s+", " ", item["name"]).strip()
        key = normalize(name)
        if not key:
            continue
        existing_row = records_by_name.get(key)
        if existing_row is not None:

            if item["href"]:
                existing_row["source_url"] = item["href"]
            if item.get("subtitle") and not existing_row.get("subtitle"):
                existing_row["subtitle"] = item["subtitle"]
            continue
        profile_id = unique_id(name, item["href"], used_ids)
        snapshot = snapshot_pages.get(key)
        file_path, source_url = snapshot if snapshot else (None, "")
        records_by_name[key] = {
            "id": profile_id,
            "name": name,
            "subtitle": item.get("subtitle", ""),
            "source_url": item["href"],
            "schedule_path": f"schedules/{profile_type}/{profile_id}.html",
            "local_file": file_path.relative_to(ROOT).as_posix() if file_path else "",
        }

    ordered = sorted(records_by_name.values(), key=lambda row: normalize(row.get("name", "")))
    write_registry(registry_file, ordered)
    return len(ordered)

def discover(source_dir: Path, registry_dir: Path) -> tuple[int, int]:
    pages: list[tuple[Path, str, str, list[dict[str, str]], str]] = []
    for path in sorted(source_dir.glob("*.html")):
        title, heading, links, source_url = read_local_page(path)
        pages.append((path, title, heading, links, source_url))

    discovered: list[dict[str, str]] = []
    for _, title, _, links, _ in pages:
        for link in links:
            if link["type"] == "groups" and "факультет" not in title.casefold():
                continue
            discovered.append(link)
    group_snapshots: dict[str, tuple[Path, str]] = {}
    teacher_snapshots: dict[str, tuple[Path, str]] = {}
    for path, title, _, _, source_url in pages:
        if "—" not in title:
            continue
        label, suffix = (part.strip() for part in title.split("—", 1))
        normalized_suffix = suffix.casefold()
        if "занятость" in normalized_suffix:
            teacher_snapshots[normalize(label)] = (path, source_url)
        elif "расписание" in normalized_suffix and any(character.isdigit() for character in label):
            group_snapshots[normalize(label)] = (path, source_url)

    group_count = merge_profile_type(
        profile_type="groups",
        discovered=discovered,
        snapshot_pages=group_snapshots,
        registry_file=registry_dir / "groups.csv",
    )
    teacher_count = merge_profile_type(
        profile_type="teachers",
        discovered=discovered,
        snapshot_pages=teacher_snapshots,
        registry_file=registry_dir / "teachers.csv",
    )
    return group_count, teacher_count

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, default=ROOT / "bru")
    parser.add_argument("--registry-dir", type=Path, default=REGISTRY)
    args = parser.parse_args()
    try:
        groups, teachers = discover(args.source_dir.resolve(), args.registry_dir.resolve())
    except (OSError, csv.Error, ValueError) as exc:
        print(f"ОШИБКА: {exc}", file=sys.stderr)
        return 2
    print(f"BRU registry обновлён из локальных HTML-копий: групп {groups}, преподавателей {teachers}")
    print("Источники в интернет не запрашивались. Запустите build_schedule_tree.py для пересборки индексов.")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
