"""Packaging rejects incomplete or wrong-architecture ABI pairs."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("folkzygisk_package", ROOT / "package.py")
PACKAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACKAGE)


class PackageTest(unittest.TestCase):
    def test_valid_pair_and_overwrite_refusal(self):
        with tempfile.TemporaryDirectory(dir="/tmp/opencode") as temporary:
            root = Path(temporary)
            stage, assets = root / "stage", root / "assets"
            for abi, elf_class, machine, bits, libdir in PACKAGE.PAIRS["aarch64"]:
                header = bytearray(64)
                header[:6] = b"\x7fELF" + bytes((elf_class, 1))
                struct.pack_into("<HH", header, 16, 3, machine)
                for relative in (f"bin/zygisk-ptrace{bits}", f"bin/zygiskd{bits}",
                                 f"{libdir}/libzygisk.so"):
                    path = stage / abi / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(header)
                for relative in ("LICENSE", "NOTICE", "UPSTREAM.md", "licenses/CSOLoader/LICENSE",
                                 "licenses/PLTI/LICENSE"):
                    path = stage / abi / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(relative)
            wrong = stage / "armeabi-v7a/bin/zygiskd32"
            good = wrong.read_bytes()
            wrong.write_bytes(b"not an ELF")
            with self.assertRaises(ValueError):
                PACKAGE.package(stage, assets, "aarch64")
            self.assertFalse((assets / "folkzygisk").exists())
            wrong.write_bytes(good)
            result = PACKAGE.package(stage, assets, "aarch64")
            self.assertEqual(len(list(result.rglob("*"))) - len([p for p in result.rglob("*") if p.is_dir()]), 12)
            self.assertEqual((result / "bin/zygiskd32").stat().st_mode & 0o777, 0o755)
            self.assertEqual((result / "lib64/libzygisk.so").stat().st_mode & 0o777, 0o644)
            with self.assertRaises(FileExistsError):
                PACKAGE.package(stage, assets, "aarch64")


if __name__ == "__main__":
    unittest.main()
