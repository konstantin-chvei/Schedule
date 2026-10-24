from __future__ import annotations

import argparse
import csv
import tempfile
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

from tools.schedules.build_schedule_tree import (
    BuildError,
    PoliteFetcher,
    build,
    normalize_snapshot_data,
    safe_relative_path,
)


class PoliteFetcherTest(unittest.TestCase):
    def test_permanent_http_errors_are_not_retried(self) -> None:
        url = "https://example.test/schedule.html"
        failure = HTTPError(url, 404, "Not Found", Message(), None)
        fetcher = PoliteFetcher(delay_seconds=0, retries=3)

        with patch("tools.schedules.build_schedule_tree.urllib.request.urlopen", side_effect=failure) as open_url:
            with self.assertRaisesRegex(BuildError, "HTTP 404"):
                fetcher.fetch(url)

        self.assertEqual(open_url.call_count, 1)

    def test_temporary_server_error_retries_with_backoff(self) -> None:
        url = "https://example.test/schedule.html"
        headers = Message()
        headers["Retry-After"] = "0"
        failure = HTTPError(url, 503, "Unavailable", headers, None)
        response = _Response(b"<table></table>")
        fetcher = PoliteFetcher(delay_seconds=0, retries=1)

        with (
            patch(
                "tools.schedules.build_schedule_tree.urllib.request.urlopen",
                side_effect=[failure, response],
            ) as open_url,
            patch("tools.schedules.build_schedule_tree.time.sleep") as sleep,
        ):
            self.assertEqual(fetcher.fetch(url), b"<table></table>")

        self.assertEqual(open_url.call_count, 2)
        sleep.assert_called_once_with(0.0)

    def test_non_https_sources_are_rejected_without_network(self) -> None:
        fetcher = PoliteFetcher(delay_seconds=0, retries=1)
        with patch("tools.schedules.build_schedule_tree.urllib.request.urlopen") as open_url:
            with self.assertRaisesRegex(BuildError, "только публичные HTTPS"):
                fetcher.fetch("http://example.test/schedule.html")
        open_url.assert_not_called()


class SchedulePathValidationTest(unittest.TestCase):
    def test_html_snapshot_normalization_only_removes_trailing_ascii_whitespace(self) -> None:
        html = b"<div>text</div>  \r\n  \t\n"
        self.assertEqual(normalize_snapshot_data(html, ".HTML"), b"<div>text</div>\r\n\n")
        self.assertEqual(normalize_snapshot_data(b"binary\x00  ", ".pdf"), b"binary\x00  ")

    def test_profile_file_must_stay_in_its_schedule_directory(self) -> None:
        self.assertEqual(
            safe_relative_path("schedules/groups/group-41.html", "groups").as_posix(),
            "schedules/groups/group-41.html",
        )
        for unsafe in (
            "../../private.html",
            "schedules/groups/../../private.html",
            "schedules/teachers/teacher.html",
        ):
            with self.subTest(path=unsafe), self.assertRaises(BuildError):
                safe_relative_path(unsafe, "groups")


class ScheduleBuildMetadataTest(unittest.TestCase):
    def test_static_index_keeps_parser_key_but_does_not_publish_source_url(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            registry = root / "registry"
            (registry / "bsu").mkdir(parents=True)
            local_snapshot = root / "imports/bsu/group-41.html"
            local_snapshot.parent.mkdir(parents=True)
            local_snapshot.write_text("<html>schedule</html>", encoding="utf-8")
            (registry / "universities.csv").write_text(
                """id,name,website_url
bsu,Белорусский государственный университет,https://bsu.by
""",
                encoding="utf-8",
            )
            (registry / "bsu/groups.csv").write_text(
                """id,name,subtitle,source_url,source_key,schedule_path,local_file
biology-group-41,41,Биофак,https://bsu.bio/schedule.php?group=41,bio-group-number:41,schedules/groups/biology-group-41.html,imports/bsu/group-41.html
""",
                encoding="utf-8",
            )

            build(argparse.Namespace(
                registry=registry,
                output=root / "schedule-data",
                repository_root=root,
                university=None,
                profile_id=None,
                fetch=False,
                delay_seconds=0.0,
                retries=0,
            ))

            index_path = root / "schedule-data/universities/bsu/groups/index.csv"
            with index_path.open("r", encoding="utf-8", newline="") as handle:
                reader = csv.DictReader(handle)
                row = next(reader)
                self.assertIn("source_key", reader.fieldnames or [])
                self.assertNotIn("source_url", reader.fieldnames or [])
            self.assertEqual("bio-group-number:41", row["source_key"])
            self.assertEqual("ready", row["status"])


class _Response:
    def __init__(self, body: bytes) -> None:
        self.headers = {}
        self._body = body

    def __enter__(self) -> _Response:
        return self

    def __exit__(self, *_: object) -> None:
        return None

    def read(self, _limit: int) -> bytes:
        return self._body


if __name__ == "__main__":
    unittest.main()
