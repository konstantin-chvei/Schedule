package com.example.bruschedule

import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import com.example.bruschedule.data.createIosAppGraph

fun MainViewController() = ComposeUIViewController {
    val graph = remember { createIosAppGraph() }
    App(graph = graph)
}
