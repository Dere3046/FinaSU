<div align="center">
<img src="logo.png" width="180" alt="FinaSU logo">

[![Latest Release](https://img.shields.io/github/v/release/LyraVoid/FinaSU?label=Release&logo=github)](https://github.com/LyraVoid/FinaSU/releases/latest)
[![Telegram](https://img.shields.io/badge/Telegram-FinaSU-blue?logo=telegram)](https://t.me/FinaSU)
[![License](https://img.shields.io/github/license/LyraVoid/FinaSU?logo=gnu)](/LICENSE)

</div>

**语言：** [English](./README.md) / [中文](./README_CN.md)

FinaSU 是基于 [KernelSU](https://github.com/tiann/KernelSU) 构建的 Android 内核级 Root 方案，集成了内核 `su` 与 Root 权限管理、模块系统、内置 Zygisk 引擎、SUSFS 支持以及可配置的现代化管理器。

---

## 功能概览

FinaSU 提供：

- 内核级 `su` 与基于白名单的 Root 权限管理
- 基于 overlayfs 与 metamodule 的类 Magisk 模块系统
- App Profile，将 Root 权限限制在单个应用策略内
- 内置 FolkZygisk 引擎，并提供管理器控制
- SUSFS 支持，按内核能力开关
- 内核级隐藏：挂载隐藏、SELinux 隐藏、AVC 日志伪装与卸载策略
- `.fpt` 主题，与 FolkPatch 互通，并提供在线主题商店
- 多页壁纸、自定义字体与启动图标切换
- 模块仓库浏览与一键安装
- 应用内语言切换与社区翻译

## 前置要求

- ARM64（arm64-v8a）Android 设备；同时保留 `x86_64` 与 `riscv64` 钩子
- 推荐 GKI 内核（5.10+）；4.14+ 受支持，但需要自行编译内核
- 通过 LKM（可加载的 `kernelsu.ko`）、修补 boot 镜像或 AnyKernel3 包进行安装

## 管理器

- 多种首页布局，包括 GridUI 与仪表盘式页面
- 可配置的主题：配色、壁纸与字体
- 分类式设置中心，涵盖通用、外观、行为、功能、安全与多媒体
- 可选更新检查与开机模块屏蔽

## 模块与扩展

### KernelSU 模块

标准 KernelSU 模块由管理器管理。同一时间只允许一个 metamodule 提供挂载后端；当已启用的 metamodule 与内置引擎不兼容时，FinaSU 会给出提示。

### FolkZygisk

FolkZygisk 是内置的 Zygisk 引擎。管理器提供安装与激活控制，`ksud` 负责注册 provider，并与已有的 Zygisk provider 互不冲突。

### SUSFS

SUSFS 管理按能力开关。仅在支持的内核上显示相关控件，每次配置变更都会附带应用回执。

### Folk-Mount

Folk-Mount 是内置的模块挂载引擎，可在自动、内置或 metamodule 模式间切换。

## 安全提示

管理器通过 APK 签名验证自身身份：只有受信任的管理器以及用户显式认领的应用才能管理内核。Root 权限基于白名单，只有被授权的应用才能访问 `su`。漏洞上报请参阅 [SECURITY.md](./SECURITY.md)。

## 翻译

英语和中文是参考语言。翻译修正请只提交对应语言的文件；新增语言时，请在 Pull Request 中提供完整翻译文件。

## 下载与安装

1. 从[发布页面](https://github.com/LyraVoid/FinaSU/releases/latest)下载最新安装包。
2. 在设备上安装管理器。
3. 按应用内引导安装内核模块、修补 boot 镜像，或刷入 AnyKernel3 包。

## 从源码构建

仓库提供了与 CI 一致的本地构建入口：

```bash
./build.sh ksud     # 构建 Rust 用户态守护进程
./build.sh lkm      # 构建内核模块
./build.sh apk      # 构建管理器 APK（需先构建 ksud）
./build.sh all      # 完整本地构建
```

各组件具体的构建与检查命令请参阅 [AGENTS.md](./AGENTS.md)。

## 开源致谢

- [KernelSU](https://github.com/tiann/KernelSU)：上游项目
- [ReZygisk](https://github.com/PerformanC/ReZygisk)：FolkZygisk 由其改写而来
- [YukiSU](https://github.com/Anatdx/YukiSU)：动态管理器参考
- [BakaSU](https://github.com/Baka-SU/BakaSU)：AnyKernel3 刷写及部分小功能参考
- [Magisk](https://github.com/topjohnwu/Magisk)：`magiskpolicy` 与模块概念
- [SUSFS](https://gitlab.com/simonpunk/susfs4ksu)：内核级隐藏
- [FolkPatch](https://github.com/LyraVoid/FolkPatch)：主题格式与设计参考
- [MMRL](https://github.com/MMRLApp/MMRL)：模块仓库格式参考

## 许可证

- `kernel` 目录下的文件遵循 [GPL-2.0-only](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html)。
- 其余部分遵循 [GPL-3.0](https://www.gnu.org/licenses/gpl-3.0.html)。

## 社区

- Telegram：[**@FinaSU**](https://t.me/FinaSU)
