#!/usr/bin/env python3
# @trace screen-text-check-7c6d09
"""
Tests for the T6c check of tools/check.py: Russian text standing where Compose draws it, outside tr().

Run:  python -m unittest discover -s tools -p "test_check_*.py"

ASCII only, like check.py: the Russian samples are written as \\u escapes.

A check that cannot fail is worse than none, so these tests are the proof that T6c can: every "must be
flagged" case is a Russian literal in a position the check promises to read, and every "must pass" case is the
shape the app really uses (tr pairs, comments, previews). To see a test earn its keep, break the scanner (for
instance make _value_strings skip everything) and the "flagged" cases go red.
"""

import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("check", str(HERE / "check.py"))
check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check)

# Built from code points, so that this file stays ASCII (the console on the owner's machine is cp1251).
RU = "".join(chr(c) for c in (0x41F, 0x440, 0x438, 0x432, 0x435, 0x442))   # "Privet"
RU2 = "".join(chr(c) for c in (0x41F, 0x43E, 0x43A, 0x430))                # "Poka"


def flagged(source):
    return [text for _, text in check.screen_cyrillic_literals(source)]


class ScannerTest(unittest.TestCase):
    # ---- positions that must be flagged

    def test_named_text_argument(self):
        self.assertEqual([RU], flagged('Text(text = "%s")' % RU))

    def test_first_positional_argument_of_text(self):
        self.assertEqual([RU], flagged('Text("%s", style = x)' % RU))

    def test_first_positional_argument_of_buttons(self):
        for name in ("Button", "TextButton", "OutlinedButton"):
            self.assertEqual([RU], flagged('%s("%s")' % (name, RU)), name)

    def test_every_named_position(self):
        for name in ("text", "contentDescription", "label", "placeholder", "title", "supportingText",
                     "headline"):
            self.assertEqual([RU], flagged('Foo(%s = "%s")' % (name, RU)), name)

    def test_text_inside_a_trailing_lambda(self):
        self.assertEqual([RU], flagged('Button(onClick = {}) { Text("%s") }' % RU))

    def test_both_branches_of_an_if(self):
        self.assertEqual([RU, RU2], flagged('Foo(label = if (on) "%s" else "%s")' % (RU, RU2)))

    def test_elvis_and_concatenation(self):
        self.assertEqual([RU], flagged('Foo(text = name ?: "%s")' % RU))
        self.assertEqual([RU], flagged('Foo(text = "a" + "%s")' % RU))

    def test_string_template(self):
        self.assertEqual(["%s, " % RU], flagged('Foo(text = "%s, ${name}")' % RU))

    def test_literal_nested_in_a_template_expression(self):
        self.assertEqual([RU], flagged('Foo(title = "${if (x) "%s" else "ok"}")' % RU))

    def test_raw_string(self):
        self.assertEqual(1, len(flagged('Foo(text = """\n%s\nx\n""")' % RU)))

    def test_default_value_of_a_text_parameter(self):
        self.assertEqual([RU], flagged('fun Bar(title: String = "%s") {}' % RU))
        self.assertEqual([RU], flagged('class Baz(val label: String? = "%s")' % RU))

    def test_a_char_literal_does_not_break_the_lexer(self):
        self.assertEqual([RU], flagged('val q = \'"\'\nText(text = "%s")' % RU))

    # ---- shapes that must pass

    def test_a_tr_pair_is_the_normal_russian_side(self):
        self.assertEqual([], flagged('Text(tr("%s", "Hello"))' % RU))
        self.assertEqual([], flagged('Foo(text = tr("%s", "Hello"))' % RU))
        self.assertEqual([], flagged('Foo(contentDescription = if (x) tr("%s", "A") else tr("%s", "B"))'
                                     % (RU, RU2)))

    def test_tr_inside_a_template(self):
        self.assertEqual([], flagged('Foo(text = "${tr("%s", "Hello")} !")' % RU))

    def test_a_multiline_tr_pair(self):
        self.assertEqual([], flagged('Foo(\n  text = tr(\n    "%s",\n    "Hello",\n  ),\n)' % RU))

    def test_english_text(self):
        self.assertEqual([], flagged('Text("Hello", style = x)\nFoo(text = "Hi ${name}")'))

    def test_comments_and_kdoc(self):
        src = ('// Text(text = "%s")\n/* Foo(label = "%s") */\n/**\n * Text("%s")\n */\nText("ok")\n'
               % (RU, RU, RU))
        self.assertEqual([], flagged(src))

    def test_a_nested_block_comment(self):
        self.assertEqual([], flagged('/* a /* b */ Text("%s") */\nText("ok")' % RU))

    def test_conditions_are_compared_against_not_shown(self):
        self.assertEqual([], flagged('Foo(text = if (lang == "%s") "yes" else "no")' % RU))

    def test_a_value_that_is_not_in_a_screen_position(self):
        # Stated limits of the check (see the T6c note): a declaration, and a call that builds the text.
        self.assertEqual([], flagged('val LABEL = "%s"' % RU))
        self.assertEqual([], flagged('Foo(text = format("%s", n))' % RU))
        self.assertEqual([], flagged('Foo(name = "%s")' % RU))

    def test_preview_functions_are_fixtures(self):
        src = '@Preview(showBackground = true)\n@Composable\nprivate fun P() {\n  Text("%s")\n}\n' % RU
        self.assertEqual([], flagged(src))
        # ...and the same code in a function that is not a preview is flagged.
        self.assertEqual([RU], flagged('@Composable\nprivate fun P() {\n  Text("%s")\n}\n' % RU))

    def test_a_preview_does_not_hide_the_screen_next_to_it(self):
        src = ('@Composable\nfun Screen() { Text("%s") }\n\n'
               '@Preview\n@Composable\nprivate fun ScreenPreview() { Text("%s") }\n') % (RU, RU2)
        self.assertEqual([RU], flagged(src))

    def test_unbalanced_source_does_not_crash(self):
        flagged('Text("%s"' % RU)
        flagged(')))) Text("%s")' % RU)
        flagged('Foo(text = "%s' % RU)


class CheckTest(unittest.TestCase):
    """check_screen_russian over a throw-away source tree, with the module's paths pointed at it."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.java = self.root / "app" / "src" / "main" / "java" / "com" / "x"
        self.lessons = self.root / "app" / "src" / "main" / "assets" / "lessons"
        self.java.mkdir(parents=True)
        self.lessons.mkdir(parents=True)
        self._saved = {k: getattr(check, k) for k in
                       ("ROOT", "JAVA", "LESSONS", "SCREEN_RU_EXEMPT", "SCREEN_RU_LESSON_ONLY")}
        check.ROOT = self.root
        check.JAVA = self.root / "app" / "src" / "main" / "java"
        check.LESSONS = self.lessons
        check.SCREEN_RU_EXEMPT = {}
        check.SCREEN_RU_LESSON_ONLY = {}

    def tearDown(self):
        for k, v in self._saved.items():
            setattr(check, k, v)
        self._tmp.cleanup()

    def write(self, name, text):
        path = self.java / name
        io.open(str(path), "w", encoding="utf-8", newline="").write(text)
        return check.rel(path)

    def run_check(self):
        report = check.Report()
        check.check_screen_russian(report)
        self.assertEqual(1, len(report.items))
        item = report.items[0]
        self.assertEqual("T6c", item["id"])
        return item

    def lesson(self, lesson_id, scene, with_english=False):
        data = json.dumps({"id": lesson_id, "steps": [{"type": "animation", "scene": scene}]})
        io.open(str(self.lessons / (lesson_id + ".json")), "w", encoding="utf-8").write(data)
        if with_english:
            (self.lessons / "en").mkdir(exist_ok=True)
            io.open(str(self.lessons / "en" / (lesson_id + ".json")), "w", encoding="utf-8").write(data)
        dispatcher = check.JAVA / "com" / "aiopenpocket" / "ui" / "chat" / "components"
        dispatcher.mkdir(parents=True, exist_ok=True)
        io.open(str(dispatcher / "LessonAnimationScenes.kt"), "w", encoding="utf-8").write(
            'fun Dispatch(s: String) { when (s) { "%s" -> FooScene() } }\n' % scene)

    def test_loose_russian_fails(self):
        self.write("Screen.kt", 'fun S() { Text(text = "%s") }\n' % RU)
        item = self.run_check()
        self.assertEqual("FAIL", item["status"])
        self.assertIn("Screen.kt:1", item["details"][0])

    def test_a_tr_pair_passes(self):
        self.write("Screen.kt", 'fun S() { Text(text = tr("%s", "Hello")) }\n' % RU)
        self.assertEqual("PASS", self.run_check()["status"])

    def test_previews_files_are_skipped_by_name(self):
        self.write("Screen.kt", 'fun S() { Text("Hello") }\n')
        self.write("ScreenPreviews.kt", 'fun S() { Text(text = "%s") }\n' % RU)
        self.assertEqual("PASS", self.run_check()["status"])

    def test_a_file_exemption_by_path_covers_that_file_only(self):
        a = self.write("A.kt", 'fun S() { Text(text = "%s") }\n' % RU)
        b = self.write("B.kt", 'fun S() { Text(text = "%s") }\n' % RU)
        check.SCREEN_RU_EXEMPT = {a: "a reason"}
        item = self.run_check()
        self.assertEqual("FAIL", item["status"])
        self.assertTrue(all("B.kt" in d for d in item["details"]), item["details"])
        self.assertNotIn(b, check.SCREEN_RU_EXEMPT)

    def test_a_scene_of_a_russian_only_lesson_is_exempt(self):
        path = self.write("SceneFoo.kt", 'fun FooScene() { Text("%s") }\n' % RU)
        self.lesson("foo_lesson", "foo")
        check.SCREEN_RU_LESSON_ONLY = {path: "foo_lesson"}
        item = self.run_check()
        self.assertEqual("PASS", item["status"], item)

    def test_the_exemption_ends_when_the_english_lesson_appears(self):
        path = self.write("SceneFoo.kt", 'fun FooScene() { Text("%s") }\n' % RU)
        self.lesson("foo_lesson", "foo", with_english=True)
        check.SCREEN_RU_LESSON_ONLY = {path: "foo_lesson"}
        item = self.run_check()
        self.assertEqual("FAIL", item["status"])
        self.assertIn("English copy", "\n".join(item["details"]))

    def test_the_exemption_needs_the_file_to_draw_that_lesson(self):
        path = self.write("SceneBar.kt", 'fun BarScene() { Text("%s") }\n' % RU)
        self.lesson("foo_lesson", "foo")  # the dispatcher sends "foo" to FooScene, which is not in SceneBar.kt
        check.SCREEN_RU_LESSON_ONLY = {path: "foo_lesson"}
        self.assertEqual("FAIL", self.run_check()["status"])

    def test_the_exemption_needs_the_lesson_to_exist(self):
        path = self.write("SceneFoo.kt", 'fun FooScene() { Text("%s") }\n' % RU)
        check.SCREEN_RU_LESSON_ONLY = {path: "no_such_lesson"}
        self.assertEqual("FAIL", self.run_check()["status"])

    def test_an_exemption_that_covers_nothing_warns(self):
        path = self.write("Clean.kt", 'fun S() { Text("Hello") }\n')
        check.SCREEN_RU_EXEMPT = {path: "was Russian once"}
        self.assertEqual("WARN", self.run_check()["status"])


class RealTreeTest(unittest.TestCase):
    """The repository itself, with the real exemption tables."""

    def test_t6c_is_green_on_the_repository(self):
        report = check.Report()
        check.check_screen_russian(report)
        item = report.items[0]
        self.assertEqual("PASS", item["status"], item)

    def test_no_exempt_scene_has_an_english_lesson(self):
        # The coordinator's rule, as a fact: only scenes of lessons that exist in Russian alone are exempt.
        for path, lesson_id in sorted(check.SCREEN_RU_LESSON_ONLY.items()):
            english = check.LESSONS / "en" / (lesson_id + ".json")
            self.assertFalse(english.exists(), "%s: %s has an English copy" % (path, lesson_id))
            self.assertIsNone(check.lesson_only_exemption(path, lesson_id)[1], path)

    def test_the_demo_path_scenes_are_not_exempt(self):
        for name in ("SceneVideoFrames", "SceneMetaLineup", "SceneBlueprint", "ScenePersonaSwitch",
                     "SceneChatVsAgent", "ScenePromptLayers", "ScenePromptSharpen", "LessonAnimationScenes"):
            for path in list(check.SCREEN_RU_LESSON_ONLY) + list(check.SCREEN_RU_EXEMPT):
                self.assertFalse(path.endswith("/" + name + ".kt"), path)


if __name__ == "__main__":
    unittest.main()
