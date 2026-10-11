package me.weishu.kernelsu.ui.util

import com.topjohnwu.superuser.CallbackList
import java.util.concurrent.Executor

/** Store command output immediately; UI callbacks may be delivered after exec returns. */
internal class FlashOutputCollector(
    executor: Executor,
    onLine: (String) -> Unit,
) : CallbackList<String>(executor, ArrayList()) {
    private val callback = onLine

    override fun onAddElement(line: String) {
        callback(line)
    }
}
