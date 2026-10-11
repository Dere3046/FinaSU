"""Host checks for complete ABI pairing and license preservation."""
import struct
import tempfile
import unittest
from pathlib import Path

from package import PAIRS, package


class PackageTests(unittest.TestCase):
    def stage(self, root):
        notices = ("LICENSE", "NOTICE", "UPSTREAM.md", "licenses/CSOLoader/LICENSE",
                   "licenses/PLTI/LICENSE")
        for abi, elf_class, machine, bits, libdir in PAIRS["aarch64"]:
            header = bytearray(20)
            header[:6] = b"\x7fELF" + bytes((elf_class, 1))
            struct.pack_into("<HH", header, 16, 3, machine)
            for name in (f"bin/zygisk-ptrace{bits}", f"bin/zygiskd{bits}",
                         f"{libdir}/libzygisk.so"):
                path = root / abi / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(header)
            for name in notices:
                path = root / abi / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(name)

    def test_pair_and_license_texts_are_complete(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.stage(root / "stage")
            output = package(root / "stage", root / "assets", "aarch64")
            self.assertEqual(len([p for p in output.rglob("*") if p.is_file()]), 12)
            self.assertEqual((output / "AGPL-3.0.txt").read_bytes(),
                             (output / "licenses/CSOLoader/LICENSE").read_bytes())
            self.assertEqual((output / "bin/zygiskd64").stat().st_mode & 0o777, 0o755)
            self.assertEqual((output / "AGPL-3.0.txt").stat().st_mode & 0o777, 0o644)
            with self.assertRaises(FileExistsError):
                package(root / "stage", root / "assets", "aarch64")

    def test_wrong_companion_does_not_publish(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.stage(root / "stage")
            (root / "stage/armeabi-v7a/bin/zygiskd32").write_bytes(b"invalid")
            with self.assertRaises(ValueError):
                package(root / "stage", root / "assets", "aarch64")
            self.assertFalse((root / "assets/folkzygisk").exists())


if __name__ == "__main__":
    unittest.main()
