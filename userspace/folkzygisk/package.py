"""Validate and package a native ABI pair for ksud's RustEmbed assets."""
import argparse
from pathlib import Path
import shutil
import struct
import tempfile

PAIRS = {
    "aarch64": (("arm64-v8a", 2, 183, "64", "lib64"),
                ("armeabi-v7a", 1, 40, "32", "lib")),
    "x86_64": (("x86_64", 2, 62, "64", "lib64"),
               ("x86", 1, 3, "32", "lib")),
}


def validate_elf(path, elf_class, machine):
    header = path.read_bytes()[:20]
    if (len(header) != 20 or header[:4] != b"\x7fELF"
            or header[4] != elf_class or header[5] != 1
            or struct.unpack_from("<H", header, 16)[0] != 3
            or struct.unpack_from("<H", header, 18)[0] != machine):
        raise ValueError(f"Invalid Android ELF architecture/type: {path}")


def package(stage, assets, target):
    pairs = PAIRS[target]
    assets.mkdir(parents=True, exist_ok=True)
    destination = assets / "folkzygisk"
    if destination.exists() or destination.is_symlink():
        raise FileExistsError(f"Refusing to overwrite existing package: {destination}")
    # Validate both ABIs and all notices before making anything visible to Cargo.
    files = []
    for abi, elf_class, machine, bits, libdir in pairs:
        for relative in (f"bin/zygisk-ptrace{bits}", f"bin/zygiskd{bits}",
                         f"{libdir}/libzygisk.so"):
            source = stage / abi / relative
            validate_elf(source, elf_class, machine)
            files.append((source, relative, 0o755 if relative.startswith("bin/") else 0o644))
    notices = ("LICENSE", "NOTICE", "UPSTREAM.md", "licenses/CSOLoader/LICENSE",
               "licenses/PLTI/LICENSE")
    for relative in notices:
        source = stage / pairs[0][0] / relative
        if not source.is_file() or not source.read_bytes():
            raise ValueError(f"Missing upstream notice: {source}")
        if source.read_bytes() != (stage / pairs[1][0] / relative).read_bytes():
            raise ValueError(f"Mismatched ABI notice: {relative}")
        files.append((source, relative, 0o644))
    with tempfile.TemporaryDirectory(prefix=".folkzygisk-", dir=assets) as temporary:
        root = Path(temporary) / "folkzygisk"
        for source, relative, mode in files:
            output = root / relative
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, output)
            output.chmod(mode)
        # Include the applicable dependency license without replacing upstream LICENSE.
        shutil.copyfile(root / "licenses/CSOLoader/LICENSE", root / "AGPL-3.0.txt")
        (root / "AGPL-3.0.txt").chmod(0o644)
        root.rename(destination)
    return destination


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--target", choices=PAIRS, required=True)
    args = parser.parse_args()
    print(package(args.stage, args.assets, args.target))
