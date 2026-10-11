# FolkSU engine-only build; upstream component Makefiles retain attribution.
include common.mk

BUILD_DIR := $(abspath $(BUILD_DIR))
STAGE_DIR := $(BUILD_DIR)/stage/$(BUILD_TYPE)

.PHONY: all
all:
	@set -eu; for arch in $(ARCHS); do \
		case $$arch in \
			arm64-v8a|x86_64) bits=64; libdir=lib64 ;; \
			armeabi-v7a|x86) bits=32; libdir=lib ;; \
			*) echo "Unsupported architecture: $$arch" >&2; exit 1 ;; \
		esac; \
		$(MAKE) -C loader BUILD_DIR=$(BUILD_DIR) BUILD_TYPE=$(BUILD_TYPE) \
			ARCH=$$arch NDK_PATH=$(NDK_PATH) API_LEVEL=$(API_LEVEL) \
			ZKSU_VERSION=FolkZygisk-$(VER_NAME) arch; \
		$(MAKE) -C zygiskd BUILD_DIR=$(BUILD_DIR) BUILD_TYPE=$(BUILD_TYPE) \
			ARCH=$$arch NDK_PATH=$(NDK_PATH) API_LEVEL=$(API_LEVEL) \
			ZKSU_VERSION=FolkZygisk-$(VER_NAME) arch; \
		out=$(STAGE_DIR)/$$arch; \
		mkdir -p $$out/bin $$out/$$libdir $$out/licenses/CSOLoader $$out/licenses/PLTI; \
		cp LICENSE NOTICE UPSTREAM.md $$out/; \
		cp loader/src/external/csoloader/LICENSE $$out/licenses/CSOLoader/; \
		cp loader/src/external/plti/LICENSE $$out/licenses/PLTI/; \
		install -m 0755 $(BUILD_DIR)/obj/$(BUILD_TYPE)/loader/$$arch/stripped/libzygisk_ptrace.so $$out/bin/zygisk-ptrace$$bits; \
		install -m 0755 $(BUILD_DIR)/obj/$(BUILD_TYPE)/zygiskd/$$arch/zygiskd $$out/bin/zygiskd$$bits; \
		install -m 0644 $(BUILD_DIR)/obj/$(BUILD_TYPE)/loader/$$arch/stripped/libzygisk.so $$out/$$libdir/libzygisk.so; \
	done
