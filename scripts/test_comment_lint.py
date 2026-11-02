import tempfile
import unittest
from pathlib import Path

import comment_lint as cl


def lint(src, suffix=".kt"):
    with tempfile.TemporaryDirectory(dir=Path(__file__).parent.parent / "tmp") as d:
        f = Path(d) / ("x" + suffix)
        f.write_text(src)
        return cl.check(f)


class CommentLintTest(unittest.TestCase):
    def test_two_line_comment_passes(self):
        self.assertEqual(lint("// one\n// two\nval x = 1\n"), [])

    def test_three_line_comment_flagged(self):
        self.assertIn("3 lines", lint("// one\n// two\n// three\nval x = 1\n")[0])

    def test_kdoc_tags_do_not_count(self):
        self.assertEqual(lint("/**\n * Opens [file].\n * @param file the file\n * @return count\n * @throws X when\n */\n"), [])

    def test_long_kdoc_prose_flagged(self):
        self.assertEqual(len(lint("/**\n * a\n * b\n * c\n */\n")), 1)

    def test_narration_flagged(self):
        for s in ["// previously this used X", "// fixes #12", "// as discussed, keep it", "// now also handles Y"]:
            self.assertTrue(lint(s + "\n"), s)

    def test_url_fragment_and_plain_words_pass(self):
        self.assertEqual(lint("// see https://x.org/a#12\n// used to compute the offset\n"), [])

    def test_behaviour_wording_passes(self):
        for s in ["// A new tour replaces the old one.", "// the pidfd no longer signals"]:
            self.assertEqual(lint(s + "\n"), [], s)

    def test_allow_pragma(self):
        self.assertEqual(lint("// comment-lint: allow license header\n// b\n// c\n"), [])

    def test_go_package_doc_and_directives(self):
        self.assertEqual(lint("// Package p does a.\n// b\n// c\npackage p\n//go:embed x\n", ".go"), [])


if __name__ == "__main__":
    unittest.main()
