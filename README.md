<div align="center">
<img src="logo.png" width="180" alt="FinaSU logo">

[![Latest Release](https://img.shields.io/github/v/release/LyraVoid/FinaSU?label=Release&logo=github)](https://github.com/LyraVoid/FinaSU/releases/latest)
[![Telegram](https://img.shields.io/badge/Telegram-FinaSU-blue?logo=telegram)](https://t.me/FinaSU)
[![License](https://img.shields.io/github/license/LyraVoid/FinaSU?logo=gnu)](/LICENSE)

</div>

**Language:** [English](./README.md) / [中文](./README_CN.md)

FinaSU is a kernel-based root solution for Android, built on [KernelSU](https://github.com/tiann/KernelSU). It combines kernel-level `su` and root access management with a module system, a built-in Zygisk engine, SUSFS support, and a configurable modern manager.

---

## Overview

FinaSU provides:

- Kernel-based `su` with whitelist-based root access management
- A Magisk-like module system based on overlayfs and metamodules
- App Profile, to confine root power to a per-app policy
- The built-in FolkZygisk engine, with manager controls
- SUSFS support, gated by kernel capability
- Kernel-level hiding: mount hiding, SELinux hiding, AVC log spoofing, and unmount policy
- `.fpt` themes with FolkPatch-compatible exchange, plus an online theme store
- Multi-page wallpapers, custom fonts, and a launcher icon switcher
- Module repository browsing and one-tap installation
- An in-app language picker with community translations

## Requirements

- An ARM64 (arm64-v8a) Android device; `x86_64` and `riscv64` hooks are also present
- A GKI kernel (5.10+) is recommended; 4.14+ is supported but requires building the kernel manually
- Installation via LKM (a loadable `kernelsu.ko`), a patched boot image, or an AnyKernel3 package

## Manager

- Multiple home layouts, including GridUI and dashboard-style pages
- Configurable theming: colors, wallpapers, and fonts
- A category-based settings hub covering general, appearance, behavior, function, security, and multimedia options
- Optional update checks and boot-time module blocking

## Modules and Extensions

### KernelSU modules

Standard KernelSU modules are managed from the manager. A single active metamodule can provide the mount backend, and FinaSU warns when an enabled metamodule may not be compatible with the built-in engine.

### FolkZygisk

FolkZygisk is a built-in Zygisk engine. The manager exposes its installation and activation controls, and `ksud` handles provider registration without conflicting with an existing Zygisk provider.

### SUSFS

SUSFS management is capability-gated. The controls appear only on kernels that support it, and every configuration change is applied with a receipt.

### Folk-Mount

Folk-Mount is the built-in module mount engine. It can run in auto, built-in, or metamodule mode.

## Security

The manager authenticates itself by APK signature: only the trusted manager and apps the user has explicitly claimed can manage the kernel. Root access is whitelist-based, so only granted apps can reach `su`. See [SECURITY.md](./SECURITY.md) for vulnerability reporting.

## Translation

English and Chinese are the reference languages. Only submit corrections for the target language, and include the entire translated file when adding a new language.

## Download and Installation

1. Download the latest package from the [Releases page](https://github.com/LyraVoid/FinaSU/releases/latest).
2. Install the manager on the device.
3. Follow the in-app flow to install the kernel module, patch a boot image, or flash an AnyKernel3 package.

## Building from Source

The repository ships a local mirror of the CI pipeline:

```bash
./build.sh ksud     # build the Rust userspace daemon
./build.sh lkm      # build the kernel module
./build.sh apk      # build the manager APK (requires the ksud build)
./build.sh all      # full local run
```

See [AGENTS.md](./AGENTS.md) for the per-component build and check commands.

## Credits

- [KernelSU](https://github.com/tiann/KernelSU) - upstream project
- [ReZygisk](https://github.com/PerformanC/ReZygisk) - FolkZygisk is adapted from it
- [YukiSU](https://github.com/Anatdx/YukiSU) - dynamic manager reference
- [BakaSU](https://github.com/Baka-SU/BakaSU) - AnyKernel3 flashing and smaller-feature reference
- [Magisk](https://github.com/topjohnwu/Magisk) - `magiskpolicy` and module concepts
- [SUSFS](https://gitlab.com/simonpunk/susfs4ksu) - kernel-level hiding
- [FolkPatch](https://github.com/LyraVoid/FolkPatch) - theme format and design reference
- [MMRL](https://github.com/MMRLApp/MMRL) - module repository format reference

## License

- Files under the `kernel` directory are [GPL-2.0-only](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html).
- All other parts are [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html).

## Community

- Telegram: [@FinaSU](https://t.me/FinaSU)
