from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "check_local_links.py"
spec = importlib.util.spec_from_file_location("check_local_links", SCRIPT)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class LocalLinkCheckTest(unittest.TestCase):
    def test_valid_anchors_and_fenced_examples(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "guide.md").write_text("# Usage\n\n<a id=\"custom\"></a>\n", encoding="utf-8")
            (root / "README.md").write_text("[Guide](guide.md#usage) [Custom](guide.md#custom)\n\n```text\n[Example](missing.md)\n```\n", encoding="utf-8")
            with patch.object(checker, "ROOT", root), redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(checker.main(), 0)

    def test_missing_files_and_anchors_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "guide.md").write_text("# Existing\n", encoding="utf-8")
            (root / "README.md").write_text("[Missing](missing.md) [Bad anchor](guide.md#missing)\n", encoding="utf-8")
            errors = io.StringIO()
            with patch.object(checker, "ROOT", root), redirect_stdout(io.StringIO()), redirect_stderr(errors):
                self.assertEqual(checker.main(), 1)
            self.assertIn("missing.md", errors.getvalue())
            self.assertIn("guide.md#missing", errors.getvalue())


if __name__ == "__main__":
    unittest.main()
