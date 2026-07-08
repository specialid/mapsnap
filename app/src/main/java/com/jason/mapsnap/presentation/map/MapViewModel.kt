package com.jason.mapsnap.presentation.map

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jason.mapsnap.data.model.DeviceUsage
import com.jason.mapsnap.data.tracker.ApiCallTracker
import com.jason.mapsnap.domain.model.AppSettings
import com.jason.mapsnap.domain.model.GpxExportOptions
import com.jason.mapsnap.domain.model.SavedRoute
import com.jason.mapsnap.domain.repository.SavedRouteRepository
import com.jason.mapsnap.domain.repository.SettingsRepository
import com.jason.mapsnap.domain.usecase.CheckApiLimitUseCase
import com.jason.mapsnap.domain.usecase.IncrementApiCountUseCase
import com.jason.mapsnap.domain.usecase.RechargeApiLimitUseCase
import com.jason.mapsnap.domain.usecase.ExportGpxUseCase
import com.jason.mapsnap.domain.usecase.SimplifyPathUseCase
import com.jason.mapsnap.domain.usecase.SnapToRoadUseCase
import com.jason.mapsnap.domain.util.GeoUtils.haversineMeters
import java.util.UUID
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.compose.MapType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.viewmodel.container
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    private val snapToRoad: SnapToRoadUseCase,
    private val simplifyPath: SimplifyPathUseCase,
    private val exportGpx: ExportGpxUseCase,
    private val apiCallTracker: ApiCallTracker,
    private val checkApiLimitUseCase: CheckApiLimitUseCase,
    private val incrementApiCountUseCase: IncrementApiCountUseCase,
    private val rechargeApiLimitUseCase: RechargeApiLimitUseCase,
    private val settingsRepository: SettingsRepository,
    private val savedRouteRepository: SavedRouteRepository
) : ViewModel(), ContainerHost<MapState, MapSideEffect> {

    override val container = container<MapState, MapSideEffect>(MapState())

    // 진행 중인 스냅 작업 세대 토큰: 취소/중복 호출 시 stale 결과 적용 방지
    private var snapGeneration = 0

    // reduce 반영 전 연타로 인한 중복 T-Map 호출 방지 (state.isProcessing은 suspend 구간 중 갱신되지 않아 신뢰 불가)
    @Volatile
    private var snapInFlight = false

    @Volatile
    private var applyInFlight = false

    private val activeStroke = mutableListOf<LatLng>()

    /** PROCESSING 중 취소: 진행 작업을 무효화하고 직전 모드로 복귀 */
    fun onCancelProcessing() = intent {
        snapGeneration++
        reduce {
            state.copy(
                drawingMode = if (state.snappedRoute.isNotEmpty()) DrawingMode.DONE else DrawingMode.IDLE,
                isProcessing = false,
                simplifiedPoints = emptyList()
            )
        }
    }

    companion object {
        const val LOOP_CLOSE_THRESHOLD_M = 30.0
    }

    init {
        viewModelScope.launch {
            val result = checkApiLimitUseCase()
            val usage = when (result) {
                is CheckApiLimitUseCase.CheckResult.Allowed -> result.usage
                is CheckApiLimitUseCase.CheckResult.Blocked -> result.usage
            }
            intent {
                reduce { state.copy(tmapApiCallCount = usage.dailyCount, tmapMaxLimitCount = DeviceUsage.DAILY_BASE_LIMIT + usage.rechargedCount) }
            }
        }
        viewModelScope.launch {
            apiCallTracker.naverCount.collect { count ->
                intent {
                    reduce { state.copy(naverMapApiCallCount = count) }
                }
            }
        }
        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            intent {
                reduce {
                    state.copy(
                        markerIntervalMeters = settings.markerIntervalMeters,
                        epsilonDrawnDeg = settings.epsilonDrawnDeg,
                        epsilonRouteDeg = settings.epsilonRouteDeg,
                        includeTimestamps = settings.includeTimestamps,
                        runningPaceSecPerKm = settings.runningPaceSecPerKm
                    )
                }
            }
        }
    }

    fun onNaverMapLoaded() = intent {
        apiCallTracker.incrementNaver()
        Timber.d("Naver Map API (Mobile Dynamic Map) 로딩 완료")
    }

    fun onToggleMapType() = intent {
        val nextType = if (state.mapType == MapType.Basic) {
            MapType.Satellite
        } else {
            MapType.Basic
        }
        reduce { state.copy(mapType = nextType) }
    }

    fun onUpdateSettings(
        interval: Double,
        epsilonDrawn: Double,
        epsilonRoute: Double,
        includeTimestamps: Boolean,
        runningPaceSecPerKm: Int
    ) = intent {
        reduce {
            state.copy(
                markerIntervalMeters = interval,
                epsilonDrawnDeg = epsilonDrawn,
                epsilonRouteDeg = epsilonRoute,
                includeTimestamps = includeTimestamps,
                runningPaceSecPerKm = runningPaceSecPerKm
            )
        }
        withContext(Dispatchers.IO) {
            settingsRepository.saveSettings(
                AppSettings(
                    markerIntervalMeters = interval,
                    epsilonDrawnDeg = epsilonDrawn,
                    epsilonRouteDeg = epsilonRoute,
                    includeTimestamps = includeTimestamps,
                    runningPaceSecPerKm = runningPaceSecPerKm
                )
            )
        }
    }

    fun onLocationPermissionResult(granted: Boolean) {
        if (!granted) intent { reduce { state.copy(currentLocation = null) } }
    }

    fun onGpxUriSelected(uri: Uri, contentResolver: ContentResolver) = intent {
        if (state.snappedRoute.size < 2) {
            postSideEffect(MapSideEffect.ShowToast("내보낼 경로가 없습니다"))
            return@intent
        }

        reduce { state.copy(isProcessing = true) }
        try {
            val route = state.snappedRoute
            val options = GpxExportOptions(
                includeTimestamps = state.includeTimestamps,
                paceSecPerKm = state.runningPaceSecPerKm,
                startTimeMillis = System.currentTimeMillis()
            )
            val gpxString = withContext(Dispatchers.Default) { exportGpx(route, options) }
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(gpxString.toByteArray(Charsets.UTF_8))
                    outputStream.flush()
                } ?: throw Exception("Failed to open output stream for GPX export")
            }
            reduce { state.copy(isProcessing = false) }
            postSideEffect(MapSideEffect.ShowToast("GPX 파일이 성공적으로 저장되었습니다"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.e(e, "Failed to export GPX: ${e.message}")
            reduce { state.copy(isProcessing = false) }
            postSideEffect(MapSideEffect.ShowToast(e.message ?: "GPX 파일 저장에 실패했습니다"))
        }
    }

    fun onLocationReceived(latLng: LatLng) = intent {
        reduce { state.copy(currentLocation = latLng) }
    }

    /**
     * 새로 편집된 관리 포인트 인덱스를 dirtyRanges에 병합한다.
     * 인접(버퍼 이내)한 기존 구간이 있으면 확장하고, 없으면 새 구간을 추가한다.
     * 서로 멀리 떨어진 편집은 별도 구간으로 유지되어 Apply 시 그 사이 미편집 구간까지
     * 재라우팅에 포함되지 않는다.
     */
    private fun mergeDirtyRange(ranges: List<IntRange>, point: Int): List<IntRange> {
        val mergeDistance = 2
        val toMerge = ranges.filter { point in (it.first - mergeDistance)..(it.last + mergeDistance) }
        // IntRange 자체가 Iterable<Int>라서 list + IntRange가 원소 스프레드로 오인될 수 있어 listOf()로 명시
        if (toMerge.isEmpty()) return ranges + listOf(IntRange(point, point))
        val newStart = minOf(point, toMerge.minOf { it.first })
        val newEnd = maxOf(point, toMerge.maxOf { it.last })
        return ranges.filterNot { it in toMerge } + listOf(IntRange(newStart, newEnd))
    }

    /**
     * 마커 삭제로 [removedControlPoint] 이후 인덱스들이 1씩 당겨질 때 기존 dirtyRanges를 보정하고,
     * 삭제로 새로 이어진 지점([joinStart]..[joinEnd])을 dirty로 병합한다.
     */
    private fun shiftDirtyRangesAfterRemoval(
        ranges: List<IntRange>,
        removedControlPoint: Int,
        joinStart: Int,
        joinEnd: Int
    ): List<IntRange> {
        val shifted = ranges.map { r ->
            val newFirst = if (r.first > removedControlPoint) r.first - 1 else r.first
            val newLast = if (r.last >= removedControlPoint) r.last - 1 else r.last
            IntRange(minOf(newFirst, newLast), maxOf(newFirst, newLast))
        }
        return mergeDirtyRange(mergeDirtyRange(shifted, joinStart), joinEnd)
    }

    fun onDrawToggle() = intent {
        when (state.drawingMode) {
            DrawingMode.IDLE, DrawingMode.DONE -> {
                // 완전 초기화 후 새로 그리기
                reduce {
                    state.copy(
                        drawingMode = DrawingMode.DRAWING,
                        isContinuing = false,
                        drawnPoints = emptyList(),
                        pendingStrokes = emptyList(),
                        snappedRoute = emptyList(),
                        routeMarkers = emptyList(),
                        routeStart = null,
                        routeEnd = null,
                        editHistory = emptyList(),
                        hasPendingEdits = false,
                        dirtyRanges = emptyList()
                    )
                }
            }
            DrawingMode.DRAWING -> finishDrawing()
            DrawingMode.PROCESSING -> { /* 처리 중 무시 */ }
        }
    }

    /** 기존 경로 끝에 이어서 그리기 — snappedRoute 유지, isContinuing = true */
    fun onContinueDrawing() = intent {
        if (state.drawingMode != DrawingMode.DONE) return@intent
        reduce {
            state.copy(
                drawingMode = DrawingMode.DRAWING,
                isContinuing = true,
                drawnPoints = emptyList(),
                pendingStrokes = emptyList(),
                simplifiedPoints = emptyList()
                // snappedRoute / routeMarkers / routeStart / routeEnd 유지
            )
        }
    }

    fun onDrawStart(point: LatLng) = intent {
        if (state.drawingMode != DrawingMode.DRAWING) return@intent
        activeStroke.clear()
        activeStroke.add(point)
    }

    fun onDrawPoint(point: LatLng) = intent {
        if (state.drawingMode != DrawingMode.DRAWING) return@intent
        activeStroke.add(point)
    }

    /**
     * 한 획(스트로크)이 끝났을 때 호출됨: 즉시 스냅하지 않고 로컬에 적립만 한다.
     * 여러 획을 그린 뒤 "완료" 버튼(finishDrawing)에서 1회만 T-Map을 호출해 API 사용량을 절감한다.
     */
    fun onDrawEnd() = intent {
        if (state.drawingMode != DrawingMode.DRAWING) return@intent
        if (activeStroke.size < 2) {
            // 너무 짧은 스트로크(오터치 등)는 버림
            activeStroke.clear()
            return@intent
        }
        val stroke = activeStroke.toList()
        activeStroke.clear()
        reduce {
            state.copy(
                pendingStrokes = state.pendingStrokes + listOf(stroke)
            )
        }
    }

    /** "완료" 버튼: 적립된 모든 스트로크를 하나로 이어붙여 1회만 스냅한다 */
    private fun finishDrawing() = intent {
        val strokes = if (activeStroke.size >= 2) {
            state.pendingStrokes + listOf(activeStroke.toList())
        } else {
            state.pendingStrokes
        }
        activeStroke.clear()
        if (strokes.isEmpty()) {
            reduce {
                state.copy(
                    drawingMode = if (state.isContinuing && state.snappedRoute.isNotEmpty()) DrawingMode.DONE else DrawingMode.IDLE,
                    drawnPoints = emptyList(),
                    pendingStrokes = emptyList()
                )
            }
            return@intent
        }
        reduce {
            state.copy(
                drawnPoints = strokes.flatten(),
                pendingStrokes = emptyList()
            )
        }
        snapCurrentPath()
    }

    fun onClearDrawing() = intent {
        activeStroke.clear()
        reduce {
            state.copy(
                drawingMode = DrawingMode.IDLE,
                drawnPoints = emptyList(),
                pendingStrokes = emptyList(),
                simplifiedPoints = emptyList(),
                isLoop = false,
                isContinuing = false,
                snappedRoute = emptyList(),
                routeMarkers = emptyList(),
                selectedMarkerIndex = -1,
                selectedSegmentIndex = -1,
                showDeleteSegmentDialog = false,
                routeStart = null,
                routeEnd = null,
                error = null,
                editHistory = emptyList(),
                hasPendingEdits = false,
                dirtyRanges = emptyList()
            )
        }
    }

    /** 마커 탭: 미선택 → 선택 / 선택 중 재탭 → 선택 해제 (삭제는 휴지통 버블로 일원화) */
    fun onMarkerTapped(index: Int) = intent {
        val next = if (state.selectedMarkerIndex == index) -1 else index
        reduce { state.copy(selectedMarkerIndex = next) }
    }

    fun onDeleteMarkerTapped() = intent {
        reduce { state.copy(showDeleteMarkerDialog = true) }
    }

    fun onDeleteMarkerConfirmed() = intent {
        val idx = state.selectedMarkerIndex
        if (idx !in state.routeMarkers.indices) {
            reduce { state.copy(showDeleteMarkerDialog = false) }
            return@intent
        }
        val start = state.routeStart ?: run { reduce { state.copy(showDeleteMarkerDialog = false) }; return@intent }
        val end = state.routeEnd ?: run { reduce { state.copy(showDeleteMarkerDialog = false) }; return@intent }
        val prev = if (idx == 0) start else state.routeMarkers[idx - 1]
        val next = if (idx == state.routeMarkers.lastIndex) end else state.routeMarkers[idx + 1]
        val updatedMarkers = state.routeMarkers.toMutableList().also { it.removeAt(idx) }
        val newRoute = spliceRoute(state.snappedRoute, prev, next, listOf(prev, next))
        val newHistory = (state.editHistory + EditSnapshot(
            state.routeStart,
            state.routeEnd,
            state.routeMarkers,
            state.snappedRoute,
            state.dirtyRanges
        )).takeLast(20)
        val newDirtyRanges = shiftDirtyRangesAfterRemoval(
            state.dirtyRanges,
            removedControlPoint = idx + 1,
            joinStart = idx,
            joinEnd = idx + 1
        ).map { it.first.coerceIn(0, updatedMarkers.size + 1)..it.last.coerceIn(0, updatedMarkers.size + 1) }
        reduce {
            state.copy(
                routeMarkers = updatedMarkers,
                snappedRoute = newRoute,
                selectedMarkerIndex = -1,
                showDeleteMarkerDialog = false,
                hasPendingEdits = true,
                editHistory = newHistory,
                dirtyRanges = newDirtyRanges
            )
        }
    }

    fun onDeleteMarkerDismissed() = intent {
        reduce { state.copy(showDeleteMarkerDialog = false) }
    }

    fun onStartMarkerTapped() = intent {
        val next = if (state.selectedMarkerIndex == -2) -1 else -2
        reduce { state.copy(selectedMarkerIndex = next) }
    }

    fun onEndMarkerTapped() = intent {
        val next = if (state.selectedMarkerIndex == -3) -1 else -3
        reduce { state.copy(selectedMarkerIndex = next) }
    }

    /** 마커 드래그 시 마커 위치 갱신 및 로컬 경로 업데이트 */
    fun onMarkerDragged(index: Int, latLng: LatLng) = intent {
        if (state.routeMarkers.getOrNull(index) == latLng) return@intent
        val markers = state.routeMarkers
        if (index < 0 || index >= markers.size) return@intent
        val updatedMarkers = markers.toMutableList().also { it[index] = latLng }
        val start = state.routeStart ?: return@intent
        val end = state.routeEnd ?: return@intent
        val prev = if (index == 0) start else updatedMarkers[index - 1]
        val next = if (index == updatedMarkers.lastIndex) end else updatedMarkers[index + 1]
        val newRoute = spliceRoute(state.snappedRoute, prev, next, listOf(prev, latLng, next))
        val controlPointIndex = index + 1
        val newDirtyRanges = mergeDirtyRange(state.dirtyRanges, controlPointIndex)
        reduce {
            state.copy(
                routeMarkers = updatedMarkers,
                snappedRoute = newRoute,
                hasPendingEdits = true,
                dirtyRanges = newDirtyRanges
            )
        }
    }

    fun onStartMarkerDragged(latLng: LatLng) = intent {
        if (state.routeStart == latLng) return@intent
        val start = state.routeStart ?: return@intent
        val next = state.routeMarkers.firstOrNull() ?: state.routeEnd ?: return@intent
        val newRoute = spliceRoute(state.snappedRoute, start, next, listOf(latLng, next))
        val controlPointIndex = 0
        val newDirtyRanges = mergeDirtyRange(state.dirtyRanges, controlPointIndex)
        reduce {
            state.copy(
                routeStart = latLng,
                snappedRoute = newRoute,
                hasPendingEdits = true,
                dirtyRanges = newDirtyRanges
            )
        }
    }

    fun onEndMarkerDragged(latLng: LatLng) = intent {
        if (state.routeEnd == latLng) return@intent
        val end = state.routeEnd ?: return@intent
        val prev = state.routeMarkers.lastOrNull() ?: state.routeStart ?: return@intent
        val newRoute = spliceRoute(state.snappedRoute, prev, end, listOf(prev, latLng))
        val controlPointIndex = state.routeMarkers.size + 1
        val newDirtyRanges = mergeDirtyRange(state.dirtyRanges, controlPointIndex)
        reduce {
            state.copy(
                routeEnd = latLng,
                snappedRoute = newRoute,
                hasPendingEdits = true,
                dirtyRanges = newDirtyRanges
            )
        }
    }

    /** 구간 탭 → 삭제 확인 다이얼로그 표시 */
    fun onSegmentTapped(index: Int) = intent {
        reduce {
            state.copy(
                selectedSegmentIndex = index,
                showDeleteSegmentDialog = true,
                selectedMarkerIndex = -1   // 마커 선택 해제
            )
        }
    }

    /** 구간 삭제 확인: 경계 마커를 제거하고 앞뒤 경계 사이 구간만 재탐색 */
    fun onDeleteSegmentConfirmed() = intent {
        val segIdx = state.selectedSegmentIndex
        val markers = state.routeMarkers
        if (segIdx < 0 || markers.isEmpty()) {
            reduce { state.copy(selectedSegmentIndex = -1, showDeleteSegmentDialog = false) }
            return@intent
        }
        val markerIdx = segIdx.coerceAtMost(markers.lastIndex)
        val start = state.routeStart ?: run { reduce { state.copy(showDeleteSegmentDialog = false) }; return@intent }
        val end = state.routeEnd ?: run { reduce { state.copy(showDeleteSegmentDialog = false) }; return@intent }
        val prev = if (markerIdx == 0) start else markers[markerIdx - 1]
        val next = if (markerIdx == markers.lastIndex) end else markers[markerIdx + 1]
        val updatedMarkers = markers.toMutableList().also { it.removeAt(markerIdx) }
        val newRoute = spliceRoute(state.snappedRoute, prev, next, listOf(prev, next))
        val newHistory = (state.editHistory + EditSnapshot(
            state.routeStart,
            state.routeEnd,
            state.routeMarkers,
            state.snappedRoute,
            state.dirtyRanges
        )).takeLast(20)
        val newDirtyRanges = shiftDirtyRangesAfterRemoval(
            state.dirtyRanges,
            removedControlPoint = markerIdx + 1,
            joinStart = markerIdx,
            joinEnd = markerIdx + 1
        ).map { it.first.coerceIn(0, updatedMarkers.size + 1)..it.last.coerceIn(0, updatedMarkers.size + 1) }
        reduce {
            state.copy(
                routeMarkers = updatedMarkers,
                snappedRoute = newRoute,
                selectedSegmentIndex = -1,
                showDeleteSegmentDialog = false,
                hasPendingEdits = true,
                editHistory = newHistory,
                dirtyRanges = newDirtyRanges
            )
        }
    }

    /** 구간 삭제 취소 */
    fun onDeleteSegmentDismissed() = intent {
        reduce { state.copy(selectedSegmentIndex = -1, showDeleteSegmentDialog = false) }
    }



    /** 선택 해제 */
    fun onMarkerDeselect() = intent {
        reduce { state.copy(selectedMarkerIndex = -1) }
    }

    fun onDragStart() = intent {
        val currentSnapshot = EditSnapshot(
            routeStart = state.routeStart,
            routeEnd = state.routeEnd,
            routeMarkers = state.routeMarkers,
            snappedRoute = state.snappedRoute,
            dirtyRanges = state.dirtyRanges
        )
        reduce {
            state.copy(
                editHistory = (state.editHistory + currentSnapshot).takeLast(20)
            )
        }
    }

    fun onUndo() = intent {
        val history = state.editHistory
        if (history.isEmpty()) return@intent
        val lastSnapshot = history.last()
        val updatedHistory = history.subList(0, history.size - 1)
        reduce {
            state.copy(
                routeStart = lastSnapshot.routeStart,
                routeEnd = lastSnapshot.routeEnd,
                routeMarkers = lastSnapshot.routeMarkers,
                snappedRoute = lastSnapshot.snappedRoute,
                editHistory = updatedHistory,
                hasPendingEdits = updatedHistory.isNotEmpty(),
                selectedMarkerIndex = -1,
                selectedSegmentIndex = -1,
                dirtyRanges = lastSnapshot.dirtyRanges
            )
        }
    }

    /**
     * 국소 재라우팅 처리: 편집 클러스터(dirtyRanges)별로 각각 T-Map을 호출해 재탐색한다.
     * 서로 떨어진 클러스터 사이의 미편집 구간은 웨이포인트에 포함하지 않아 불필요한 재라우팅을 피한다.
     * 여러 구간을 처리할 때는 오른쪽(뒤) 구간부터 적용해 앞쪽 구간의 인덱스가 무효화되지 않도록 한다.
     */
    fun onApplyEdits() = intent {
        if (!state.hasPendingEdits) return@intent
        if (applyInFlight) return@intent  // 연타로 인한 중복 T-Map 호출 방지
        val start = state.routeStart ?: return@intent
        val end = state.routeEnd ?: return@intent
        val currentMarkers = state.routeMarkers
        val pCurrent = listOf(start) + currentMarkers + listOf(end)

        val baseRanges = state.dirtyRanges.ifEmpty { listOf(0..(pCurrent.size - 1)) }
        // 인접·중첩 구간은 하나로 합치고, 뒤(끝 인덱스가 큰 것)부터 처리할 수 있도록 정렬
        val mergedRanges = baseRanges.sortedBy { it.first }
            .fold(mutableListOf<IntRange>()) { acc, r ->
                val last = acc.lastOrNull()
                if (last != null && r.first <= last.last + 1) {
                    acc[acc.lastIndex] = minOf(last.first, r.first)..maxOf(last.last, r.last)
                } else {
                    acc.add(r)
                }
                acc
            }
        val boundedRanges = mergedRanges
            .map { r -> maxOf(0, r.first - 1)..minOf(pCurrent.size - 1, r.last + 1) }
            .filter { it.last - it.first >= 1 }
            .sortedByDescending { it.last }

        if (boundedRanges.isEmpty()) {
            reduce { state.copy(hasPendingEdits = false, dirtyRanges = emptyList()) }
            return@intent
        }

        val checkResult = checkApiLimitUseCase()
        if (checkResult is CheckApiLimitUseCase.CheckResult.Blocked) {
            reduce { state.copy(isAdPromptDialogVisible = true) }
            return@intent
        }

        val myGen = ++snapGeneration
        applyInFlight = true
        reduce { state.copy(isProcessing = true) }

        var workingRoute = state.snappedRoute
        var workingMarkers = currentMarkers
        var totalApiCallCount = 0
        var failureMessage: String? = null

        try {
            for (range in boundedRanges) {
                val startIdx = range.first
                val endIdx = range.last
                val waypoints = pCurrent.subList(startIdx, endIdx + 1)
                if (waypoints.size < 2) continue

                val result = withContext(Dispatchers.IO) { snapToRoad.fromWaypoints(waypoints, state.epsilonRouteDeg) }
                // 실제 API 호출은 이미 발생했으므로 취소·실패 여부와 무관하게 사용량에 반영
                result.onSuccess { totalApiCallCount += it.apiCallCount }

                if (myGen != snapGeneration) break  // 취소되었거나 새 요청이 들어옴

                val snapResult = result.getOrElse { e ->
                    failureMessage = e.message
                    null
                } ?: break

                val fromPt = pCurrent[startIdx]
                val toPt = pCurrent[endIdx]

                // 자기교차 경로 대비: toPt는 fromIdx 이후 구간에서만 탐색해 반대편 통과 지점과의 오매칭을 방지
                val fromIdx = if (startIdx == 0) 0 else indexOfClosest(workingRoute, fromPt)
                val toIdx = if (endIdx == pCurrent.size - 1) {
                    workingRoute.size - 1
                } else if (fromIdx >= 0) {
                    val tail = workingRoute.subList(fromIdx, workingRoute.size)
                    val idxInTail = indexOfClosest(tail, toPt)
                    if (idxInTail >= 0) fromIdx + idxInTail else -1
                } else {
                    -1
                }

                if (fromIdx >= 0 && toIdx >= 0 && fromIdx < toIdx) {
                    workingRoute = workingRoute.subList(0, fromIdx) + snapResult.route + workingRoute.subList(toIdx + 1, workingRoute.size)
                }

                val newSubMarkers = sampleMarkers(snapResult.route, intervalMeters = state.markerIntervalMeters)
                workingMarkers = workingMarkers.subList(0, startIdx) + newSubMarkers + workingMarkers.subList(endIdx - 1, workingMarkers.size)
            }
        } finally {
            applyInFlight = false
        }

        if (totalApiCallCount > 0) incrementApiCountUseCase(totalApiCallCount)

        if (myGen != snapGeneration) return@intent  // 취소되었거나 새 요청이 들어옴

        if (failureMessage != null) {
            reduce { state.copy(isProcessing = false, error = failureMessage) }
            postSideEffect(MapSideEffect.ShowToast(failureMessage ?: "경로 재탐색에 실패했습니다"))
            return@intent
        }

        val updatedCheckResult = checkApiLimitUseCase()
        val usage = when (updatedCheckResult) {
            is CheckApiLimitUseCase.CheckResult.Allowed -> updatedCheckResult.usage
            is CheckApiLimitUseCase.CheckResult.Blocked -> updatedCheckResult.usage
        }

        reduce {
            state.copy(
                snappedRoute = workingRoute,
                routeMarkers = workingMarkers,
                routeStart = workingRoute.firstOrNull() ?: state.routeStart,
                routeEnd = workingRoute.lastOrNull() ?: state.routeEnd,
                hasPendingEdits = false,
                dirtyRanges = emptyList(),
                editHistory = emptyList(),
                isProcessing = false,
                error = null,
                tmapApiCallCount = usage.dailyCount
            )
        }
        postSideEffect(MapSideEffect.ShowToast("경로 재탐색이 완료되었습니다"))
    }

    /**
     * snappedRoute 에서 fromPt~toPt 구간을 replacement 로 교체.
     * indexOf 로 경계점을 찾아 그 사이를 잘라내고 새 sub-route 를 삽입.
     * 경계점을 찾지 못하면 원본 경로 그대로 반환.
     */
    private fun indexOfClosest(list: List<LatLng>, point: LatLng): Int {
        var minDistance = Double.MAX_VALUE
        var minIdx = -1
        for (i in list.indices) {
            val dist = haversineMeters(list[i], point)
            if (dist < minDistance) {
                minDistance = dist
                minIdx = i
            }
        }
        return minIdx
    }

    private fun spliceRoute(
        full: List<LatLng>,
        fromPt: LatLng,
        toPt: LatLng,
        replacement: List<LatLng>
    ): List<LatLng> {
        val fromIdx = indexOfClosest(full, fromPt)
        if (fromIdx < 0) return full
        // 자기교차 경로 대비: toPt는 fromIdx 이후 구간에서만 탐색해 반대편 통과 지점과의 오매칭을 방지
        val idxInTail = indexOfClosest(full.subList(fromIdx, full.size), toPt)
        if (idxInTail < 0) return full
        val toIdx = fromIdx + idxInTail
        if (fromIdx >= toIdx) return full
        // full[0..fromIdx-1] + replacement + full[toIdx+1..end]
        return full.subList(0, fromIdx) + replacement + full.subList(toIdx + 1, full.size)
    }

    /** 경로에서 [intervalMeters] 간격으로 중간 마커 좌표 추출 (시작·끝 제외) */
    private fun sampleMarkers(route: List<LatLng>, intervalMeters: Double): List<LatLng> {
        if (route.size < 2) return emptyList()
        val result = mutableListOf<LatLng>()
        var accumulated = 0.0
        for (i in 1 until route.size - 1) {
            accumulated += haversineMeters(route[i - 1], route[i])
            if (accumulated >= intervalMeters) {
                result.add(route[i])
                accumulated = 0.0
            }
        }
        return result
    }



    private fun snapCurrentPath() = intent {
        if (snapInFlight) return@intent  // 연타로 인한 중복 T-Map 호출 방지
        val raw = state.drawnPoints
        Timber.d("snapCurrentPath: points.size=${raw.size}, continuing=${state.isContinuing}")
        if (raw.size < 2) {
            reduce { state.copy(drawingMode = if (state.isContinuing) DrawingMode.DONE else DrawingMode.IDLE) }
            return@intent
        }

        // 이어 그리기: 기존 경로 끝점을 첫 웨이포인트로 연결
        val basePoints = if (state.isContinuing && state.snappedRoute.isNotEmpty()) {
            listOf(state.snappedRoute.last()) + raw
        } else {
            raw
        }

        // 시작점·끝점이 LOOP_CLOSE_THRESHOLD_M 이내면 시작점을 끝에 추가해 경로를 닫음
        val isLoop = !state.isContinuing &&
                haversineMeters(basePoints.first(), basePoints.last()) <= LOOP_CLOSE_THRESHOLD_M
        val points = if (isLoop) basePoints + basePoints.first() else basePoints

        // 1단계: RDP 직선화 → 즉시 오버레이에 반영하여 사용자에게 피드백
        val simplified = withContext(Dispatchers.Default) { simplifyPath(points, state.epsilonDrawnDeg) }
        val myGen = ++snapGeneration
        reduce {
            state.copy(
                drawingMode = DrawingMode.PROCESSING,
                isProcessing = true,
                isLoop = isLoop,
                simplifiedPoints = simplified
            )
        }

        // 2단계: 직선화된 경로를 T-Map에 전달하여 실도로 스냅
        val checkResult = checkApiLimitUseCase()
        if (checkResult is CheckApiLimitUseCase.CheckResult.Blocked) {
            reduce {
                state.copy(
                    isAdPromptDialogVisible = true,
                    drawingMode = if (state.snappedRoute.isNotEmpty()) DrawingMode.DONE else DrawingMode.IDLE,
                    isProcessing = false
                )
            }
            return@intent
        }

        snapInFlight = true
        val result = try {
            withContext(Dispatchers.IO) { snapToRoad(points, state.epsilonDrawnDeg, state.epsilonRouteDeg) }
        } finally {
            snapInFlight = false
        }

        // 실제 API 호출은 이미 발생했으므로 취소 여부와 무관하게 사용량에 반영
        result.onSuccess { snapResult -> incrementApiCountUseCase(snapResult.apiCallCount) }

        if (myGen != snapGeneration) return@intent  // 취소되었거나 새 요청이 들어옴

        Timber.d("snapToRoad result: isSuccess=${result.isSuccess}, error=${result.exceptionOrNull()?.message}")

        result.fold(
            onSuccess = { snapResult ->
                val newRoute = snapResult.route
                Timber.d("route size=${newRoute.size}, continuing=${state.isContinuing}")

                val updatedCheckResult = checkApiLimitUseCase()
                val usage = when (updatedCheckResult) {
                    is CheckApiLimitUseCase.CheckResult.Allowed -> updatedCheckResult.usage
                    is CheckApiLimitUseCase.CheckResult.Blocked -> updatedCheckResult.usage
                }

                // 이어 그리기: 기존 경로 뒤에 새 구간 연결 (연결점 중복 제거)
                val combined = if (state.isContinuing && state.snappedRoute.isNotEmpty()) {
                    state.snappedRoute + newRoute.drop(1)
                } else {
                    newRoute
                }

                val markers = sampleMarkers(combined, intervalMeters = state.markerIntervalMeters)
                reduce {
                    state.copy(
                        drawingMode = DrawingMode.DONE,
                        isContinuing = false,
                        snappedRoute = combined,
                        simplifiedPoints = emptyList(),
                        routeMarkers = markers,
                        selectedMarkerIndex = -1,
                        routeStart = combined.first(),
                        routeEnd = combined.last(),
                        editHistory = emptyList(),
                        isProcessing = false,
                        error = null,
                        hasPendingEdits = false,
                        dirtyRanges = emptyList(),
                        tmapApiCallCount = usage.dailyCount
                    )
                }
            },
            onFailure = { e ->
                Timber.e(e, "snapToRoad FAILED: ${e.message}")
                reduce {
                    state.copy(
                        drawingMode = if (state.snappedRoute.isNotEmpty()) DrawingMode.DONE else DrawingMode.IDLE,
                        isContinuing = false,
                        isProcessing = false,
                        error = e.message
                    )
                }
                postSideEffect(
                    MapSideEffect.ShowToast(e.message ?: "경로 탐색에 실패했습니다")
                )
            }
        )
    }

    fun onDismissAdPrompt() = intent { reduce { state.copy(isAdPromptDialogVisible = false) } }
    fun onWatchAdRequested() = intent {
        reduce { state.copy(isAdPromptDialogVisible = false) }
        postSideEffect(MapSideEffect.ShowRewardedAd)
    }
    fun onWatchAdCompleted() = intent {
        rechargeApiLimitUseCase()
        val checkResult = checkApiLimitUseCase()
        val usage = when(checkResult) {
            is CheckApiLimitUseCase.CheckResult.Allowed -> checkResult.usage
            is CheckApiLimitUseCase.CheckResult.Blocked -> checkResult.usage
        }
        reduce { state.copy(tmapMaxLimitCount = DeviceUsage.DAILY_BASE_LIMIT + usage.rechargedCount, tmapApiCallCount = usage.dailyCount) }
        postSideEffect(MapSideEffect.ShowToast("API 한도가 10회 충전되었습니다"))
    }

    fun onOpenSaveRouteDialog() = intent {
        if (state.snappedRoute.size < 2) {
            postSideEffect(MapSideEffect.ShowToast("저장할 경로가 없습니다"))
            return@intent
        }
        reduce { state.copy(showSaveRouteDialog = true) }
    }

    fun onSaveRouteDismissed() = intent {
        reduce { state.copy(showSaveRouteDialog = false) }
    }

    fun onSaveRouteConfirmed(name: String) = intent {
        val start = state.routeStart ?: return@intent
        val end = state.routeEnd ?: return@intent
        val route = SavedRoute(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "경로" },
            createdAt = System.currentTimeMillis(),
            routeStart = start,
            routeEnd = end,
            routeMarkers = state.routeMarkers,
            snappedRoute = state.snappedRoute,
            distanceMeters = state.totalDistanceMeters
        )
        withContext(Dispatchers.IO) { savedRouteRepository.save(route) }
        reduce { state.copy(showSaveRouteDialog = false) }
        postSideEffect(MapSideEffect.ShowToast("경로가 저장되었습니다"))
    }

    /** 저장된 경로 목록 다이얼로그 표시: 매번 최신 목록을 다시 불러온다 */
    fun onOpenLoadRouteDialog() = intent {
        val routes = withContext(Dispatchers.IO) { savedRouteRepository.getAll() }
        reduce { state.copy(savedRoutes = routes, showLoadRouteDialog = true) }
    }

    fun onLoadRouteDialogDismissed() = intent {
        reduce { state.copy(showLoadRouteDialog = false) }
    }

    /** 저장된 경로를 현재 편집 상태로 불러온다 — 새로 그린 것처럼 DONE 모드로 진입 */
    fun onLoadRouteSelected(route: SavedRoute) = intent {
        reduce {
            state.copy(
                drawingMode = DrawingMode.DONE,
                isContinuing = false,
                drawnPoints = emptyList(),
                pendingStrokes = emptyList(),
                simplifiedPoints = emptyList(),
                snappedRoute = route.snappedRoute,
                routeMarkers = route.routeMarkers,
                routeStart = route.routeStart,
                routeEnd = route.routeEnd,
                selectedMarkerIndex = -1,
                selectedSegmentIndex = -1,
                editHistory = emptyList(),
                hasPendingEdits = false,
                dirtyRanges = emptyList(),
                showLoadRouteDialog = false,
                error = null
            )
        }
        postSideEffect(MapSideEffect.ShowToast("'${route.name}' 경로를 불러왔습니다"))
    }

    fun onDeleteSavedRoute(id: String) = intent {
        withContext(Dispatchers.IO) { savedRouteRepository.delete(id) }
        reduce { state.copy(savedRoutes = state.savedRoutes.filterNot { it.id == id }) }
    }
}
