"""The themes packed into the APK for Launcher settings' Theme row (launcher/app/build.gradle, themePresets).

A preset is only a config.json, so a theme that needs a picture can't be one: picking it on the TV would give the
theme's dock and colours without its wallpaper. Each name must also be a theme folder, or the build copies nothing.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "launcher" / "app" / "build.gradle"
SMALL = {"config.json", "screenshot.jpg"}


def presets():
    match = re.search(r"def themePresets = \[([^\]]*)\]", GRADLE.read_text(encoding="utf-8"))
    return re.findall(r"'([^']+)'", match.group(1)) if match else []


class ThemePresetsTest(unittest.TestCase):
    def test_there_are_presets(self):
        self.assertTrue(presets(), "build.gradle has no themePresets list")

    def test_every_preset_is_a_theme_folder(self):
        for name in presets():
            with self.subTest(preset=name):
                self.assertTrue((ROOT / "themes" / name / "config.json").is_file())

    def test_no_preset_needs_a_picture(self):
        for name in presets():
            with self.subTest(preset=name):
                extra = [p.name for p in (ROOT / "themes" / name).rglob("*") if p.is_file() and p.name not in SMALL]
                self.assertEqual([], extra, "a preset is only a config.json: push this theme with tools/theme.sh")

    def test_no_preset_sets_the_picker_itself(self):
        # theme_picker is read from config.xml or the pushed theme, so a packed theme that set it would do nothing.
        for name in presets():
            with self.subTest(preset=name):
                text = (ROOT / "themes" / name / "config.json").read_text(encoding="utf-8")
                self.assertNotIn("theme_picker", text)


if __name__ == "__main__":
    unittest.main()
