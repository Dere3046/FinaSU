"""Test the actual ownership query and specialization decisions without Android."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class UmountTest(unittest.TestCase):
    def test_kernel_ownership_and_explicit_module_override(self):
        kernelsu = (ROOT / "zygiskd/src/root_impl/kernelsu.c").read_text()
        query = kernelsu[kernelsu.index("bool ksu_kernel_owns_umount(void)"):
                         kernelsu.index("bool ksu_uid_should_umount(uid_t uid)")]
        hook = (ROOT / "loader/src/injector/hook.c").read_text()
        first = hook[hook.index("  bool kernel_owns_umount ="):
                     hook.index("  if ((ctx->info_flags & PROCESS_IS_MANAGER)",
                                hook.index("  bool kernel_owns_umount ="))]
        deny = hook[hook.index("  bool in_denylist = !kernel_owns_umount"):
                    hook.index("\n}\n\nstatic void rz_app_specialize_post")]
        with tempfile.TemporaryDirectory(prefix="folkzygisk-umount-", dir="/tmp/opencode") as temporary:
            directory = Path(temporary)
            harness = directory / "umount.c"
            harness.write_text('''
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <errno.h>
#include <string.h>
#define LOGE(...) ((void)0)
#define KSU_IOCTL_GET_FEATURE 13
struct ksu_get_feature_cmd { uint32_t feature_id; uint64_t value; uint8_t supported; };
static bool ksu_uses_new_ksuctl = true;
static int ksu_fd = 1, query_result;
static uint64_t value;
static uint8_t supported;
static int ioctl(int fd, int cmd, struct ksu_get_feature_cmd *arg) {
  assert(fd == 1 && cmd == 13 && arg->feature_id == 1);
  arg->value = value; arg->supported = supported;
  return query_result;
}
''' + query + '''
#define PROCESS_ON_DENYLIST (1u << 1)
#define PROCESS_KERNEL_OWNS_UMOUNT (1u << 26)
#define PROCESS_IS_MANAGER (1u << 27)
#define PROCESS_IS_FIRST_STARTED (1u << 31)
#define DO_REVERT_UNMOUNT 1
#define FLAG_SET(ctx, flag) ((ctx)->flags |= (flag))
#define FLAG_GET(ctx, flag) (((ctx)->flags & (flag)) != 0)
#define Clean 0
struct context { uint32_t info_flags; int flags; bool request; };
static int automatic_clean, explicit_clean;
static void update_mnt_ns(int state, bool dry) {
  assert(state == Clean);
  if (dry) automatic_clean++; else explicit_clean++;
}
static void rz_run_modules_pre(struct context *ctx) {
  if (ctx->request) FLAG_SET(ctx, DO_REVERT_UNMOUNT);
}
static void specialize(struct context *ctx) {
''' + first + deny + '''
}
int main(void) {
  supported = 1; value = 1; assert(ksu_kernel_owns_umount());
  value = 0; assert(!ksu_kernel_owns_umount());
  supported = 0; value = 1; assert(!ksu_kernel_owns_umount());
  query_result = -1; assert(ksu_kernel_owns_umount());
  ksu_uses_new_ksuctl = false; assert(ksu_kernel_owns_umount());
  struct context ctx = { .info_flags = PROCESS_ON_DENYLIST | PROCESS_KERNEL_OWNS_UMOUNT };
  specialize(&ctx); assert(explicit_clean == 0 && ctx.flags == 0);
  ctx.request = true; specialize(&ctx); assert(explicit_clean == 1);
  explicit_clean = 0;
  ctx = (struct context){ .info_flags = PROCESS_ON_DENYLIST };
  specialize(&ctx); assert(explicit_clean == 1);
  ctx = (struct context){ .info_flags = PROCESS_IS_FIRST_STARTED | PROCESS_KERNEL_OWNS_UMOUNT };
  specialize(&ctx); assert(automatic_clean == 0);
  ctx.info_flags = PROCESS_IS_FIRST_STARTED;
  specialize(&ctx); assert(automatic_clean == 1);
  return 0;
}
''')
            binary = directory / "umount"
            subprocess.run(["cc", "-std=c18", "-Wall", "-Wextra", "-Werror",
                            str(harness), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    unittest.main()
