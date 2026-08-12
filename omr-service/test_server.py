import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from server import JobStore, extract_mxl, find_music_xml, safe_display_name, tokens_equal


MUSIC_XML = b'<?xml version="1.0"?><score-partwise version="4.0"><part-list/></score-partwise>'


class OmrServiceTest(unittest.TestCase):
    def test_job_store_survives_reload(self):
        with tempfile.TemporaryDirectory() as directory:
            store = JobStore(Path(directory))
            created = store.create("image/png", "../private/score.png", b"png")
            updated = store.transition(created["jobId"], status="running", stage="audiveris")
            self.assertEqual("score.png", updated["originalName"])
            self.assertEqual("running", JobStore(Path(directory)).load(created["jobId"])["status"])

    def test_extracts_declared_musicxml_from_mxl(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "score.mxl"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(
                    "META-INF/container.xml",
                    '<?xml version="1.0"?><container><rootfiles><rootfile full-path="score.xml"/></rootfiles></container>',
                )
                archive.writestr("score.xml", MUSIC_XML)
            self.assertEqual(MUSIC_XML, extract_mxl(archive_path))
            self.assertEqual(MUSIC_XML, find_music_xml(Path(directory)))

    def test_rejects_mxl_path_traversal(self):
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "score.mxl"
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(
                    "META-INF/container.xml",
                    '<container><rootfiles><rootfile full-path="../score.xml"/></rootfiles></container>',
                )
                archive.writestr("../score.xml", MUSIC_XML)
            with self.assertRaises(ValueError):
                extract_mxl(archive_path)

    def test_filename_is_not_a_path_or_header(self):
        self.assertEqual("score.png", safe_display_name("../../score.png\r\nSecret: yes"))

    def test_non_ascii_authorization_is_rejected_without_error(self):
        self.assertFalse(tokens_equal("Bearer 令牌", "Bearer expected"))
        self.assertTrue(tokens_equal("Bearer expected", "Bearer expected"))


if __name__ == "__main__":
    unittest.main()
