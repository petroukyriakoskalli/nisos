"""Tests for the Tasker bridge files, which nothing else can check.

These two XML files are the one part of the project that cannot be exercised
off a phone, so what is left is to check the things that are *statically* true
and that fail in ways nobody would guess:

1. The files parse. A task that fails to import is indistinguishable, from
   nisos' side, from a task that imports and does nothing.
2. The JavaScript inside them avoids the two characters XML will not carry.
   That one is genuinely invisible -- it looks like ordinary JavaScript right
   up until the import silently refuses.
3. Every action nisos broadcasts has a branch waiting for it. Adding a Tasker
   action and forgetting its branch produces a flash saying "unknown action"
   on a phone in your pocket, and nothing at all on the terminal.
"""

import re
import xml.etree.ElementTree as ElementTree
from pathlib import Path

import pytest

TASKER = Path(__file__).resolve().parent.parent / "tasker"
ACTIONS = Path(__file__).resolve().parent.parent / "nisos" / "actions.py"


def javascript(path: Path) -> str:
    """The JavaScriptlet source out of a Tasker task file, if it has one."""
    text = path.read_text(encoding="utf-8")
    found = re.search(r'<Str sr="arg0" ve="3">(.*?)</Str>', text, re.S)
    return found.group(1) if found else ""


@pytest.mark.parametrize("path", sorted(TASKER.glob("*.xml")),
                         ids=lambda p: p.name)
class TestTheFilesAreImportable:
    def test_parses(self, path):
        ElementTree.parse(path)

    def test_the_javascript_avoids_what_xml_cannot_carry(self, path):
        """A bare "<" is not character data and "&" starts an entity.

        Escaping them is not the fix: tasker/README.md tells you to paste this
        block into Tasker by hand, where an escape arrives as literal broken
        JavaScript. The code has to avoid both characters outright -- a
        counted loop and a logical "and" are what you reach for and neither is
        available.
        """
        source = javascript(path)
        assert "<" not in source
        assert "&" not in source


class TestTheBridgeHasBothEnds:
    def test_every_broadcast_action_has_a_branch(self):
        """Otherwise the phone flashes "unknown action" and the terminal says
        nothing at all."""
        broadcast = set(re.findall(r'ctx\.tasker\(\s*"([\w.]+)"',
                                   ACTIONS.read_text(encoding="utf-8")))
        assert broadcast, "no ctx.tasker calls found -- has the API changed?"

        dispatcher = javascript(TASKER / "NisosAction.tsk.xml")
        for name in sorted(broadcast):
            assert f'"{name}"' in dispatcher, (
                f"nisos broadcasts {name!r} and NisosAction has no branch for "
                f"it -- see the 'Adding an action' section in tasker/README.md"
            )
