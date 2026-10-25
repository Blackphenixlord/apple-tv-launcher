"""The family edition's Windows scripts must never disable, hide or uninstall a system package.

install-family.ps1 keeps Google TV's launcher (launcherx) and its setup wizard enabled and leaves the default
Home alone, because Google TV profiles live inside launcherx; undo-family.ps1 only uninstalls this launcher.
These are text checks on the scripts themselves (CI has no TV), plus a PowerShell parse where pwsh exists.

Run from the repository root: python3 -m unittest discover -s tests -v
"""
from pathlib import Path
import re
import shutil
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]
INSTALL = ROOT / "launcher" / "install-family.ps1"
UNDO = ROOT / "launcher" / "undo-family.ps1"
PWSH = shutil.which("pwsh")


def commands(path):
    """The script without its comment lines, which describe what it never does."""
    return "\n".join(line for line in path.read_text(encoding="utf-8").splitlines()
                     if not line.lstrip().startswith("#"))


class FamilyInstallTest(unittest.TestCase):
    def test_install_never_disables_anything(self):
        text = commands(INSTALL)
        for forbidden in ("disable-user", "pm disable", "pm hide", "pm uninstall", "pm suspend", "pm clear"):
            self.assertNotIn(forbidden, text)

    def test_install_changes_home_only_when_asked(self):
        # Home changes only behind -DefaultHome: to this launcher, or back to Google TV's if that failed.
        text = commands(INSTALL)
        block = text[text.index("if ($DefaultHome) {"):text.index('Write-Host "Default Home: $homeNow"')]
        self.assertEqual(text.count("set-home-activity"), block.count("set-home-activity"))
        self.assertIn("set-home-activity --user $($u.Id) $Pkg/.MainActivity", block)
        self.assertIn("set-home-activity $google", block)

    def test_undo_gives_home_back_before_uninstalling(self):
        text = commands(UNDO)
        self.assertLess(text.index("set-home-activity --user $id $google"), text.index("pm uninstall $Pkg"))

    def test_install_covers_every_user(self):
        text = commands(INSTALL)
        self.assertIn("pm list users", text)
        self.assertIn("pm install-existing --user $id", text)
        self.assertIn("settings --user $id put secure enabled_accessibility_services", text)
        # Only HomeKeyService is enabled.
        self.assertIn("/.HomeKeyService", text)
        self.assertNotIn("SleepService", text)
        self.assertNotIn("InputKeyService", text)

    def test_install_backs_up_before_it_installs(self):
        text = commands(INSTALL)
        self.assertLess(text.index("restore.json"), text.index("pm install -r"))
        for item in ("pm list packages -f", "pm list packages -d", "getprop", "list $ns", "resolve-activity"):
            self.assertIn(item, text)

    def test_undo_only_uninstalls_this_launcher(self):
        text = commands(UNDO)
        for forbidden in ("disable-user", "pm disable", "pm hide", "pm suspend", "pm clear"):
            self.assertNotIn(forbidden, text)
        uninstalls = re.findall(r"pm uninstall[^\n\"]*", text)
        self.assertEqual(uninstalls, ["pm uninstall $Pkg"])
        self.assertIn("$Pkg = 'com.gathin.tvhome'", text)

    @unittest.skipUnless(PWSH, "pwsh not on PATH")
    def test_scripts_parse(self):
        for script in (INSTALL, UNDO, ROOT / "launcher" / "build-and-install.ps1", ROOT / "tools" / "frametimes.ps1"):
            if not script.exists():
                continue
            ps = ("$e = $null; [void][System.Management.Automation.Language.Parser]::ParseFile("
                  f"'{script}', [ref]$null, [ref]$e); if ($e) {{ $e | ForEach-Object {{ $_.ToString() }}; exit 1 }}")
            r = subprocess.run([PWSH, "-NoProfile", "-Command", ps], capture_output=True, text=True, timeout=120)
            self.assertEqual(r.returncode, 0, f"{script.name}: {r.stdout}{r.stderr}")


if __name__ == "__main__":
    unittest.main()
