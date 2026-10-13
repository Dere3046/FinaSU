package me.weishu.kernelsu.ui.component.module

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Lays [items] out in two independent columns for wide screens.
 *
 * Items are split by index parity, so expanding or folding one card never shifts the other
 * column. The list scrolls as a whole; [beforeItems] spans the full width above both columns.
 */
@Composable
fun <T> TwoColumnGrid(
    items: List<T>,
    key: (item: T) -> Any,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 16.dp,
    horizontalSpacing: Dp = 16.dp,
    contentPadding: PaddingValues = PaddingValues(),
    beforeItems: (@Composable () -> Unit)? = null,
    itemContent: @Composable (item: T) -> Unit,
) {
    val leftItems = items.filterIndexed { index, _ -> index % 2 == 0 }
    val rightItems = items.filterIndexed { index, _ -> index % 2 == 1 }
    val layoutDirection = LocalLayoutDirection.current
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(
            start = contentPadding.calculateLeftPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateRightPadding(layoutDirection),
        ),
    ) {
        beforeItems?.invoke()
        if (items.isEmpty()) return@Column
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            ) {
                leftItems.forEach { item -> key(key(item)) { itemContent(item) } }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(verticalSpacing),
            ) {
                rightItems.forEach { item -> key(key(item)) { itemContent(item) } }
            }
        }
        Spacer(modifier = Modifier.height(contentPadding.calculateBottomPadding()))
    }
}
