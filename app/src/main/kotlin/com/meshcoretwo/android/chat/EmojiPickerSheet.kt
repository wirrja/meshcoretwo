// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcoretwo.android.R
import com.meshcoretwo.android.chat.emoji.EmojiCatalog

/**
 * Full emoji picker — search field + every [EmojiCatalog] section. Ported from
 * `EmojiPickerSheet.swift`/`EmojiPickerViewModel.swift`, minus its `NavigationStack` chrome (a
 * `ModalBottomSheet`'s own drag handle serves the same purpose) and per-category `LazyVGrid`s: a
 * single `LazyColumn` of (header, [FlowRow]) pairs avoids nesting a scrollable grid inside a
 * sheet's own scroll container, which Compose doesn't support cleanly. Opened by
 * [MessageActionsSheet]'s quick-react row's "+" button, or standalone; either way, picking an
 * emoji here calls [onSelect] and the caller (`ChatConversationScreen`) dismisses this sheet before
 * actually sending the reaction — mirroring `ActionsEmojiSection.swift`'s comment about not
 * stranding the parent sheet mid-dismissal.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EmojiPickerSheet(recentEmojis: List<String>, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val sections = remember(query, recentEmojis) { EmojiCatalog.sections(query, recentEmojis) }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text(stringResource(R.string.emoji_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
            )
            if (sections.isEmpty()) {
                Text(
                    stringResource(R.string.emoji_no_results),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false), contentPadding = PaddingValues(bottom = 16.dp)) {
                    for (section in sections) {
                        item(key = "${section.titleRes}-header") {
                            Text(
                                stringResource(section.titleRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        item(key = "${section.titleRes}-grid") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (emoji in section.emoji) {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .clickable { onSelect(emoji.unicode) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(emoji.unicode, fontSize = 24.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
