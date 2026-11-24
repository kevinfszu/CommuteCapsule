package com.busnotice.commute.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.busnotice.commute.Candidate
import com.busnotice.commute.Leg
import com.busnotice.commute.Trip
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Records : Screen
    data object Learn : Screen
    data object LineImport : Screen
    data class LineDetail(val name: String) : Screen
    data object AmapImport : Screen
    data object Edit : Screen
    data class PickBus(val legIndex: Int) : Screen
    data class PickMetro(val legIndex: Int) : Screen
    data class EditShuttle(val legIndex: Int, val candidateIndex: Int) : Screen
}

@Composable
fun App(resumeTick: Int) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    // 行程记录可从设置或算法学习进入，返回时回到来路
    var recordsFrom by remember { mutableStateOf<Screen>(Screen.Settings) }
    var draft by remember { mutableStateOf(Trip(chinaWorkday = true)) }
    var dirty by remember { mutableStateOf(false) }
    val editState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun addCandidate(legIndex: Int, c: Candidate, replaceAt: Int = -1, rideMin: Int = 0) {
        val legs = draft.legs.toMutableList()
        while (legs.size <= legIndex) legs += Leg(emptyList())
        val leg = legs[legIndex]
        val cands = leg.candidates.toMutableList()
        if (replaceAt in cands.indices) cands[replaceAt] = c else cands += c
        legs[legIndex] = leg.copy(
            candidates = cands,
            rideMin = if (rideMin > 0) rideMin else leg.rideMin,
            rideMinManual = if (rideMin > 0) true else leg.rideMinManual,
        )
        draft = draft.copy(legs = legs)
        dirty = true
        screen = Screen.Edit
    }

    // 系统返回键：回退一级（设置下的二级页回设置，详情与高德生成页回线路导入）
    fun goBack() {
        screen = when (screen) {
            Screen.Settings -> Screen.Home
            Screen.Records -> recordsFrom
            Screen.Learn, Screen.LineImport -> Screen.Settings
            Screen.AmapImport -> Screen.LineImport
            is Screen.LineDetail -> Screen.LineImport
            else -> Screen.Home
        }
    }

    BackHandler(enabled = screen != Screen.Home) { goBack() }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            resumeTick = resumeTick,
            onNew = {
                draft = Trip(chinaWorkday = true, legs = listOf(Leg(emptyList()))); dirty = false
                scope.launch { editState.scrollToItem(0) }
                screen = Screen.Edit
            },
            onEdit = { draft = it; dirty = false; screen = Screen.Edit },
            onOpenSettings = { screen = Screen.Settings },
            onOpenRecords = { recordsFrom = Screen.Home; screen = Screen.Records },
        )
        Screen.Settings -> SettingsScreen(
            onBack = { screen = Screen.Home },
            onOpenRecords = { recordsFrom = Screen.Settings; screen = Screen.Records },
            onOpenLearn = { screen = Screen.Learn },
            onOpenSource = { screen = Screen.LineImport },
        )
        Screen.Records -> RecordsScreen(onBack = { screen = recordsFrom })
        Screen.Learn -> LearnScreen(
            onBack = { screen = Screen.Settings },
            onOpenRecords = { recordsFrom = Screen.Learn; screen = Screen.Records },
        )
        Screen.LineImport -> LineImportScreen(
            onBack = { screen = Screen.Settings },
            onOpenAmap = { screen = Screen.AmapImport },
            onOpenLine = { screen = Screen.LineDetail(it) },
        )
        is Screen.LineDetail -> LineEditorScreen(
            lineName = s.name,
            onBack = { screen = Screen.LineImport },
            onDeleted = { screen = Screen.LineImport },
        )
        Screen.AmapImport -> AmapImportScreen(
            onBack = { screen = Screen.LineImport },
            onDone = { screen = Screen.LineImport },
        )
        Screen.Edit -> TripEditor(
            trip = draft,
            dirty = dirty,
            state = editState,
            onChange = { draft = it; dirty = true },
            onBack = { dirty = false; screen = Screen.Home },
            onSaved = { dirty = false; screen = Screen.Home },
            onAddBus = { screen = Screen.PickBus(it) },
            onAddMetro = { screen = Screen.PickMetro(it) },
            onEditShuttle = { leg, idx -> screen = Screen.EditShuttle(leg, idx) },
        )
        is Screen.PickBus -> BusPicker(
            onBack = { screen = Screen.Edit },
            onPicked = { c, ride -> addCandidate(s.legIndex, c, rideMin = ride) },
        )
        is Screen.PickMetro -> MetroPicker(
            onBack = { screen = Screen.Edit },
            onPicked = { c, ride -> addCandidate(s.legIndex, c, rideMin = ride) },
        )
        is Screen.EditShuttle -> ShuttleEditor(
            initial = draft.legs.getOrNull(s.legIndex)?.candidates?.getOrNull(s.candidateIndex),
            onBack = { screen = Screen.Edit },
            onDone = { addCandidate(s.legIndex, it, replaceAt = s.candidateIndex) },
        )
    }
}
