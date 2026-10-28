#!/usr/bin/env python3
# @trace screen-text-check-7c6d09
"""
Tests for the T6d check of tools/check.py: Russian text that reaches a person through a user-text sink,
either straight or through one function of the same file.

Run:  python -m unittest discover -s tools -p "test_check_*.py"

ASCII only, like check.py: the Russian samples are written as code points.

T6d exists because T6, T6b and T6c all measured the place where a string is WRITTEN. So these tests are
written the other way round too: next to every "must be flagged" case there is the same code with the
Russian turned English, and it has to come back clean. A check that cannot change its answer is not
checking. The documented blind spots (two steps, another file, a variable, a qualified call) have tests of
their own under "stated limits", so that a later reader sees they are chosen, not forgotten.
"""

import importlib.util
import io
import re
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
    return [text for _, text, _ in check.user_text_sink_literals(source)]


def why(source):
    return [reason for _, _, reason in check.user_text_sink_literals(source)]


def english(source):
    """The same source with the two Russian samples turned English: the reverse of every case below."""
    return source.replace(RU, "Hello").replace(RU2, "Bye")


class SinkTest(unittest.TestCase):
    """A bare Russian literal standing as a sink's own argument."""

    def check_both_ways(self, source, expected):
        self.assertEqual(expected, flagged(source), source)
        self.assertEqual([], flagged(english(source)), "stayed red once the text was English")

    def test_stream_event_error_positionally(self):
        self.check_both_ways('emit(StreamEvent.Error("%s"))' % RU, [RU])

    def test_stream_event_error_by_name(self):
        self.check_both_ways('emit(StreamEvent.Error(message = "%s", code = "x"))' % RU, [RU])

    def test_notification_builder_lines(self):
        self.check_both_ways('b.setSmallIcon(i).setContentTitle("%s").setContentText("%s")' % (RU, RU2),
                             [RU, RU2])

    def test_show_agent_notification_positionally(self):
        self.check_both_ways('showAgentNotification(context, "%s", "%s", 7)' % (RU, RU2), [RU, RU2])

    def test_show_agent_notification_by_name(self):
        self.check_both_ways('showAgentNotification(context = c, title = "%s", message = "%s", '
                             'notificationId = 7)' % (RU, RU2), [RU, RU2])

    def test_external_activity_launcher(self):
        self.check_both_ways('launchExternalActivity(c, f, i, "%s", "%s")' % (RU, RU2), [RU, RU2])

    def test_turn_push(self):
        self.check_both_ways('notifier.turnFinished(id, "%s", "%s", failed = true)' % (RU, RU2), [RU, RU2])

    def test_branches_and_concatenation_at_the_sink(self):
        self.check_both_ways('emit(StreamEvent.Error(if (x) "%s" else "%s"))' % (RU, RU2), [RU, RU2])
        self.check_both_ways('b.setContentText(name ?: "%s")' % RU, [RU])
        self.check_both_ways('b.setContentText("%s" + tail)' % RU, [RU])
        self.check_both_ways('b.setContentText("%s ${n}")' % RU, ["%s " % RU])

    def test_a_tr_pair_is_how_it_should_be_written(self):
        self.assertEqual([], flagged('b.setContentTitle(tr("%s", "The agent is replying"))' % RU))
        self.assertEqual([], flagged('emit(StreamEvent.Error(tr("%s", "Try again")))' % RU))

    def test_an_argument_that_is_not_user_text(self):
        # code is a machine marker read by the chat's retry button, not something anybody reads
        self.assertEqual([], flagged('emit(StreamEvent.Error(msg, code = "%s"))' % RU))
        # the id and the intent of a notification
        self.assertEqual([], flagged('showAgentNotification(context, t, m, "%s".hashCode())' % RU))

    def test_a_bare_name_is_not_the_qualified_sink(self):
        # the table says StreamEvent.Error; a bare Error("...") could be any class in the project
        self.assertEqual([], flagged('emit(Error("%s"))' % RU))

    def test_a_declaration_is_not_a_call(self):
        # T6d reads calls, so a sink's OWN declaration is skipped -- including a Russian default value,
        # which is a stated limit, not a win: for `title` and `text` T6c already reads defaults.
        self.assertEqual([], flagged('fun showAgentNotification(context: Context, title: String = "%s") {}'
                                     % RU))
        self.assertEqual([], flagged('data class Error(val message: String = "%s", val code: String?)' % RU))

    def test_comments_are_not_code(self):
        self.assertEqual([], flagged('// b.setContentTitle("%s")\n/* StreamEvent.Error("%s") */' % (RU, RU2)))

    def test_a_preview_is_a_fixture(self):
        src = '@Preview\n@Composable\nprivate fun P() { b.setContentTitle("%s") }' % RU
        self.assertEqual([], flagged(src))
        self.assertEqual([RU], flagged(src.replace("@Preview\n", "")))

    def test_unbalanced_source_does_not_crash(self):
        flagged('emit(StreamEvent.Error("%s"' % RU)
        flagged(')))) b.setContentText("%s")' % RU)
        flagged('fun err() = "%s' % RU)


class IndirectionTest(unittest.TestCase):
    """The one thing T6d is for: the sink is handed a call to a function of the same file."""

    def test_an_expression_body_with_a_when(self):
        # the shape of the real userFacingLlmError in agent/core/llm/OpenAiCompatClient.kt
        src = ('internal fun errText(code: String?): String = when {\n'
               '  code == "blank_key" -> "%s"\n'
               '  code == "429" -> "ok"\n'
               '  else -> "%s"\n'
               '}\n'
               'fun stream() { emit(StreamEvent.Error(errText(last), code = last)) }\n') % (RU, RU2)
        self.assertEqual([RU, RU2], flagged(src))
        self.assertEqual([], flagged(english(src)), "stayed red once the helper was English")

    def test_a_block_body_with_a_return(self):
        src = ('private fun errText(c: String): String {\n'
               '  if (c == "a") return "%s"\n'
               '  return "ok"\n'
               '}\n'
               'fun f() { emit(StreamEvent.Error(errText(c))) }\n') % RU
        self.assertEqual([RU], flagged(src))
        self.assertEqual([], flagged(english(src)))

    def test_a_when_subject_is_compared_against_not_shown(self):
        src = ('fun errText(c: String): String = when (c) {\n'
               '  "%s" -> "ok"\n'
               '  else -> "fine"\n'
               '}\n'
               'fun f() { emit(StreamEvent.Error(errText(c))) }\n') % RU
        self.assertEqual([], flagged(src))

    def test_the_helper_of_a_notification(self):
        src = ('fun pushTitle(failed: Boolean) = if (failed) "%s" else "%s"\n'
               'fun f() { showAgentNotification(c, pushTitle(x), m, 1) }\n') % (RU, RU2)
        self.assertEqual([RU, RU2], flagged(src))
        self.assertEqual([], flagged(english(src)))

    def test_a_helper_inside_a_branch_at_the_sink(self):
        src = ('fun errText() = "%s"\n'
               'fun f() { emit(StreamEvent.Error(if (x) errText() else "%s")) }\n') % (RU, RU2)
        self.assertEqual([RU, RU2], flagged(src))

    def test_only_the_helper_the_sink_actually_calls_is_read(self):
        src = ('fun shown() = "%s"\n'
               'fun forTheModel() = "%s"\n'
               'fun f() { emit(StreamEvent.Error(shown())) }\n') % (RU, RU2)
        self.assertEqual([RU], flagged(src))

    def test_the_reason_names_both_ends(self):
        src = ('fun errText() = "%s"\nfun f() { emit(StreamEvent.Error(errText())) }\n' % RU)
        self.assertEqual(1, len(why(src)))
        self.assertIn("errText()", why(src)[0])
        self.assertIn("StreamEvent.Error()", why(src)[0])

    def test_a_helper_body_is_read_whole(self):
        # a long when-block must not be cut short: the last branch is as shown as the first
        branches = "\n".join('  code == "c%d" -> "ok%d"' % (i, i) for i in range(30))
        src = ('fun errText(code: String): String = when {\n%s\n  else -> "%s"\n}\n'
               'fun f() { emit(StreamEvent.Error(errText(c))) }\n') % (branches, RU)
        self.assertEqual([RU], flagged(src))

    def test_the_helper_body_stops_at_the_next_declaration(self):
        # the Russian below belongs to another function; reading past the body would blame the wrong one
        src = ('fun errText() = "ok"\n'
               'fun forTheModel() = "%s"\n'
               'fun f() { emit(StreamEvent.Error(errText())) }\n') % RU
        self.assertEqual([], flagged(src))

    # ---- stated limits, tested so that they stay chosen rather than forgotten

    def test_two_steps_of_indirection_are_not_seen(self):
        src = ('fun outer() = inner()\nfun inner() = "%s"\n'
               'fun f() { emit(StreamEvent.Error(outer())) }\n') % RU
        self.assertEqual([], flagged(src))

    def test_a_helper_in_another_file_is_not_seen(self):
        self.assertEqual([], flagged('fun f() { emit(StreamEvent.Error(errTextFromElsewhere())) }'))

    def test_a_qualified_call_is_not_resolved(self):
        src = 'fun text() = "%s"\nfun f() { emit(StreamEvent.Error(helper.text())) }\n' % RU
        self.assertEqual([], flagged(src))

    def test_a_string_arriving_through_a_variable_is_not_seen(self):
        self.assertEqual([], flagged('val T = "%s"\nfun f() { b.setContentTitle(T) }' % RU))

    def test_a_string_built_by_another_call_is_not_seen(self):
        self.assertEqual([], flagged('fun f() { b.setContentText(format("%s", n)) }' % RU))

    def test_a_sink_that_is_not_in_the_table_is_not_seen(self):
        self.assertEqual([], flagged('Toast.makeText(c, "%s", LENGTH_LONG)' % RU))


class CheckTest(unittest.TestCase):
    """check_sink_russian over a throw-away source tree, with the module's paths pointed at it."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.java = self.root / "app" / "src" / "main" / "java" / "com" / "x"
        self.java.mkdir(parents=True)
        self._saved = {k: getattr(check, k) for k in ("ROOT", "JAVA", "USER_TEXT_SINK_EXEMPT")}
        check.ROOT = self.root
        check.JAVA = self.root / "app" / "src" / "main" / "java"
        check.USER_TEXT_SINK_EXEMPT = {}

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
        check.check_sink_russian(report)
        self.assertEqual(1, len(report.items))
        item = report.items[0]
        self.assertEqual("T6d", item["id"])
        return item

    def test_an_indirect_leak_fails_and_points_at_the_helper(self):
        self.write("Client.kt", 'fun errText() = "%s"\nfun f() { emit(StreamEvent.Error(errText())) }\n' % RU)
        item = self.run_check()
        self.assertEqual("FAIL", item["status"])
        self.assertIn("Client.kt:1", item["details"][0])

    def test_a_tr_pair_passes(self):
        self.write("Client.kt", 'fun f() { emit(StreamEvent.Error(tr("%s", "Try again"))) }\n' % RU)
        self.assertEqual("PASS", self.run_check()["status"])

    def test_previews_files_are_skipped_by_name(self):
        self.write("Client.kt", 'fun f() { emit(StreamEvent.Error("Try again")) }\n')
        self.write("ClientPreviews.kt", 'fun f() { emit(StreamEvent.Error("%s")) }\n' % RU)
        self.assertEqual("PASS", self.run_check()["status"])

    def test_an_exemption_by_path_covers_that_file_only(self):
        a = self.write("A.kt", 'fun f() { b.setContentTitle("%s") }\n' % RU)
        self.write("B.kt", 'fun f() { b.setContentTitle("%s") }\n' % RU)
        check.USER_TEXT_SINK_EXEMPT = {a: "a reason"}
        item = self.run_check()
        self.assertEqual("FAIL", item["status"])
        self.assertTrue(all("B.kt" in d for d in item["details"]), item["details"])

    def test_an_exemption_that_covers_nothing_warns(self):
        path = self.write("Clean.kt", 'fun f() { b.setContentTitle("Ready") }\n')
        check.USER_TEXT_SINK_EXEMPT = {path: "was Russian once"}
        self.assertEqual("WARN", self.run_check()["status"])

    def test_no_kotlin_at_all_is_a_failure_not_a_pass(self):
        item = self.run_check()
        self.assertEqual("FAIL", item["status"])
        self.assertIn("nothing was checked", item["title"])


class RealTreeTest(unittest.TestCase):
    """Facts about the repository itself. Deliberately NOT "T6d is green": on the branch that added it
    T6d is red on a live defect, and the point of these tests is the mechanism, which has to go on
    holding after that defect is fixed."""

    CLIENT = "app/src/main/java/com/aiopenpocket/agent/core/llm/OpenAiCompatClient.kt"

    def test_the_chat_banner_is_fed_by_a_helper_of_that_same_file(self):
        """The path T6d was written for, as a fact that survives the fix: the error banner's text comes
        from a bare-name call to a function of this very file, and T6d can read that function whole."""
        src = check.read(check.ROOT / self.CLIENT)
        self.assertRegex(src, r"StreamEvent\.Error\(\s*userFacingLlmError\(")
        toks = check.kotlin_tokens(src)
        bodies = check.kotlin_local_functions(toks, check._bracket_pairs(toks))
        self.assertIn("userFacingLlmError", bodies)
        start, end = bodies["userFacingLlmError"]
        self.assertGreater(end - start, 40, "the helper's body was found but not whole")

    def test_every_sink_is_really_called_in_the_app(self):
        """A sink nobody calls guards nothing: a rename would switch it off and leave the check green."""
        sources = [check.read(p) for p in check.JAVA.rglob("*.kt")]
        for key in check.USER_TEXT_SINKS:
            last = key.split(".")[-1]
            self.assertTrue(any(re.search(r"\b%s\s*\(" % re.escape(last), s) for s in sources),
                            "%s is in USER_TEXT_SINKS but nothing calls it" % key)

    def test_every_exemption_names_a_file_that_exists(self):
        for path in check.USER_TEXT_SINK_EXEMPT:
            self.assertTrue((check.ROOT / path).exists(), path)

    def test_no_sink_argument_table_is_empty(self):
        for key, spec in check.USER_TEXT_SINKS.items():
            self.assertTrue(spec.args, "%s names no argument, so it reads nothing" % key)
            self.assertTrue(spec.why, "%s has no stated reason for being a sink" % key)


if __name__ == "__main__":
    unittest.main()
