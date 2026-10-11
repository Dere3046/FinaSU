package me.weishu.kernelsu.ui.screen.settings

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.R
import me.weishu.kernelsu.ui.component.material.ExpressiveScaffold
import me.weishu.kernelsu.ui.component.material.SegmentedColumn
import me.weishu.kernelsu.ui.component.material.SegmentedRadioItem
import me.weishu.kernelsu.ui.component.material.TopBarBackButton
import me.weishu.kernelsu.ui.component.material.expressiveTopAppBarColors
import me.weishu.kernelsu.ui.component.settings.SettingsContentMaxWidth
import me.weishu.kernelsu.ui.navigation3.LocalNavigator
import me.weishu.kernelsu.wallpaper.ThemeLanguage

/**
 * In-app language picker, mirroring FolkPatch's list. Selection flows through [ThemeLanguage],
 * the same state the theme protocol writes, so the two never disagree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePickerScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val languages = stringArrayResource(R.array.languages)
    val languageValues = stringArrayResource(R.array.languages_values)
    val currentTags = ThemeLanguage.currentTags(context)

    // Instant feedback: setApplicationLocales()/recreate() apply asynchronously.
    var lastSelected by remember { mutableStateOf(-1) }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_app_language)) },
                navigationIcon = { TopBarBackButton(onClick = { navigator.pop() }) },
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(innerPadding)
                .wrapContentWidth(Alignment.CenterHorizontally)
                .widthIn(max = SettingsContentMaxWidth)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SegmentedColumn(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 13.dp)) {
                languages.forEachIndexed { index, label ->
                    item(key = languageValues[index]) {
                        val selected = if (lastSelected != -1) {
                            lastSelected == index
                        } else if (index == 0) {
                            currentTags.isEmpty()
                        } else {
                            currentTags == languageValues[index]
                        }
                        SegmentedRadioItem(
                            title = label,
                            selected = selected,
                            onClick = {
                                lastSelected = index
                                ThemeLanguage.setTags(
                                    context,
                                    if (index == 0) "" else languageValues[index],
                                )
                                if (Build.VERSION.SDK_INT < 33) activity?.recreate()
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
