/* FolkSU adaptation of ReZygisk: shared built-in engine/runtime paths.
 * Upstream copyright and license notices remain in LICENSE/NOTICE.
 */
#ifndef FOLKZYGISK_PATHS_H
#define FOLKZYGISK_PATHS_H

#define FOLKZYGISK_ENGINE_DIR "/data/adb/ksu/bin/folkzygisk"
#define FOLKZYGISK_RUNTIME_DIR "/data/adb/ksu/.zygisk"
#define FOLKZYGISK_STATE_FILE FOLKZYGISK_RUNTIME_DIR "/state.json"
#define FOLKZYGISK_MONITOR_LOCK FOLKZYGISK_RUNTIME_DIR "/monitor.lock"
#define FOLKZYGISK_DAEMON_BASE FOLKZYGISK_ENGINE_DIR "/bin/zygiskd"
#define FOLKZYGISK_TRACER_BASE FOLKZYGISK_ENGINE_DIR "/bin/zygisk-ptrace"

#endif
