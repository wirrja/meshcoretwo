// SPDX-License-Identifier: GPL-3.0-only

package com.meshcoretwo.android.tools

import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** One batch-size option (3× / 5×) in [PathActionsSection]. Ported from `BatchSizeChip.swift`. */
@Composable
fun BatchSizeChip(size: Int, selectedSize: Int, onSelect: (Int) -> Unit) {
    FilterChip(selected = selectedSize == size, onClick = { onSelect(size) }, label = { Text("${size}×") })
}
