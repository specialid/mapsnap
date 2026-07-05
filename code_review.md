# MapSnap 코드 검토 보고서 (2026-07-05)

검토 목표는 두 가지다.

1. **T-Map API 사용량 최소화** 관점에서의 누수·낭비 지점 식별.
2. **불필요한 반복(왕복) 경로 생성 방지** 관점에서의 미해결 지점 식별.

그 외 일반 버그·성능·정리 항목을 심각도 순으로 함께 정리했다. **코드는 수정하지 않았다.**

이미 잘 구현된 최적화(참고): 동적 웨이포인트 샘플링(50m당 1개, 2~19 cap), dirty range 국소 재라우팅, 드래그 중 로컬 splice + 일괄 적용(Apply) 방식, snapGeneration 취소 토큰, 연속 중복 좌표 제거, 청크 경계 트리밍, 일일 한도 + 광고 충전.

---

## 1. 심각도 높음 (버그)

### 1-1. `hasPendingEdits`가 초기화되지 않고 새 경로로 누수됨
- 위치. `MapViewModel.kt:212` (onClearDrawing), `MapViewModel.kt:157` (onDrawToggle IDLE/DONE 분기), `MapViewModel.kt:765` (snapCurrentPath 성공 reduce)
- 세 곳 모두 상태를 리셋하면서 `hasPendingEdits`를 `false`로 되돌리지 않는다.
- 재현 시나리오. ① 경로 생성 → ② 마커 드래그(`hasPendingEdits=true`) → ③ "지우기" 또는 "다시 그리기" 확정 → ④ 새 경로 그리기 완료.
- 결과. 방금 스냅된 깨끗한 새 경로가 **앰버색(수정 대기)으로 표시**되고, 하단에 "이어 그리기 / GPX 저장" 대신 **"수정 완료 적용" 버튼이 노출**된다. 사용자가 GPX를 저장하려면 어쩔 수 없이 적용을 눌러야 하고, 이때 `dirtyStart/dirtyEnd`가 null이라 **전체 경로 재라우팅 T-Map 호출이 통째로 낭비**된다(웨이포인트 수에 따라 실제 HTTP 1~3회).
- 권장 수정. `snapCurrentPath` 성공 reduce와 `onClearDrawing`, `onDrawToggle` 리셋 분기에 `hasPendingEdits = false` 추가. (스냅 성공 지점 한 곳만 고쳐도 두 진입 경로가 모두 막힌다.)

### 1-2. 좌표 근접/동등성 기반 경로 식별 — 자기교차 경로에서 splice 오동작 (구조적 문제)
- 위치. `MapViewModel.kt:644` (indexOfClosest), `MapViewModel.kt:657` (spliceRoute), `MapViewModel.kt:597-604` (onApplyEdits의 fromIdx/toIdx), `MapScreen.kt:136` (segmentize의 exact-equality)
- 이 앱의 핵심 사용례는 **그림 그리기**라서 경로가 스스로 교차하거나 같은 도로를 두 번 지나는 경우가 흔하다. 그런데 경로 내 위치 식별을 전부 "가장 가까운 점 탐색(전역 최솟값)" 또는 "좌표 완전 일치"로 한다.
- 문제 A. `spliceRoute`/`onApplyEdits`에서 `indexOfClosest`가 **경로의 다른 통과 지점(교차점 반대편 패스)에 매칭**되면, 두 마커 사이가 아니라 경로의 큰 덩어리가 잘려 나가거나 엉뚱한 구간이 교체된다. 8자형·글자 모양 경로에서 마커를 교차점 근처로 드래그하면 재현 가능성이 높다.
- 문제 B. `onApplyEdits`에서 `fromIdx >= toIdx`이면 새 경로를 버리고 기존 `snappedRoute`를 유지하는데(`MapViewModel.kt:600-604`), 이때도 `hasPendingEdits=false`, `dirtyStart/dirtyEnd=null`로 클리어된다. 즉 **T-Map 호출·카운트는 발생했는데 결과는 폐기되고, 사용자의 편집(직선 splice 상태)은 도로 스냅 안 된 채로 "적용 완료"처럼 남는다.** 토스트는 "재탐색 완료"라고 뜬다.
- 문제 C. `segmentize`는 마커 좌표와 경로 점의 **완전 일치**(`it == m`)로 분할하는데, Apply 후 유지되는 경계 마커의 옛 좌표는 T-Map이 새로 스냅한 경로 위에 정확히 존재하지 않을 수 있다. 분할 인덱스가 어긋나면 구간 수와 마커 인덱스 매핑이 틀어져 **구간 탭 삭제 시 의도하지 않은 마커가 삭제**될 수 있다(`onDeleteSegmentConfirmed`의 `segIdx→markerIdx` 매핑 전제 붕괴).
- 루프 경로(routeStart == routeEnd 좌표 동일)에서는 시작/끝 근처 편집 시 fromIdx/toIdx가 같은 지점 부근으로 몰려 문제 B가 더 쉽게 발생한다.
- 권장 방향. 좌표가 아니라 **인덱스를 상태로 유지**하는 것이 근본 해법이다. 차선책으로는 indexOfClosest 탐색 범위를 "직전에 알던 인덱스 주변 윈도"로 제한하고, 마커에 (근사) 경로 인덱스를 함께 저장해 편집 때마다 갱신하는 방법이 있다.

### 1-3. API 카운트 단위 불일치 — 실제 T-Map 호출량 과소 계상
- 위치. `RouteRepositoryImpl.kt:87` (청크당 `apiCallTracker.incrementTmap()`), `MapViewModel.kt:750`·`MapViewModel.kt:587` (`incrementApiCountUseCase()` 스냅 작업당 1회)
- `RouteRepositoryImpl`은 웨이포인트를 7개 단위 청크로 나눠 **청크마다 실제 HTTP 호출**을 한다(19 웨이포인트 = 3회). 그런데 일일 한도(30회)에 반영되는 `incrementApiCountUseCase()`는 **스냅 작업당 1회만** 증가한다. 한도의 목적이 T-Map 앱키 쿼터 보호라면 실제 사용량이 **최대 3배까지 초과**될 수 있다.
- 부수 문제. 취소되어 stale이 된 요청(gen 토큰 불일치)은 HTTP 호출이 이미 완료됐는데도 성공 분기의 카운트 지점에 도달하지 못해 **아예 계상되지 않는다** (`MapViewModel.kt:742`, `MapViewModel.kt:584`의 조기 return이 increment보다 앞).
- 권장 수정. 카운트 지점을 Repository(실호출 지점)로 내리거나, 스냅 전에 예상 청크 수를 계산해 그 수만큼 한도 판정·증가.

### 1-4. "수정 완료 적용" 연타 시 중복 T-Map 호출
- 위치. `MapViewModel.kt:550` (onApplyEdits), `BottomControls.kt:256-272`
- `onApplyEdits`는 `hasPendingEdits`만 확인하고 `isProcessing` 중복 실행을 막지 않는다. `hasPendingEdits`는 성공 시점까지 true라서 버튼 연타(또는 UI 지연 중 재탭)로 **동일 재라우팅 API가 병렬 중복 호출**된다. gen 토큰으로 결과는 하나만 반영되지만 HTTP 비용은 그대로 나간다.
- 같은 계열의 좁은 레이스. `snapCurrentPath`는 RDP(Default 디스패처) 후에야 PROCESSING으로 전이하므로, 손 뗀 직후 잠깐 DRAWING이 유지되는 사이 "완료" 버튼을 누르면 스냅이 이중 시작될 수 있다(`MapViewModel.kt:696-726`).
- 권장 수정. intent 진입부에 `if (state.isProcessing) return@intent` 가드 추가(두 함수 공통).

### 1-5. DeviceUsage 읽기-수정-쓰기 비원자성 + 인메모리 폴백의 데이터 손상 가능성
- 위치. `IncrementApiCountUseCase.kt:10-17`, `DeviceUsageRepositoryImpl.kt:21` (localFallbackUsage), `CheckApiLimitUseCase.kt:22-30`
- 문제 A. 증가가 `getUsage() → copy(+1) → updateUsage()`의 비원자 시퀀스다. 동시 실행(연타, 이어 그리기 연속 스냅)에서 증분이 유실될 수 있다. Firebase RTDB는 `runTransaction` 또는 `ServerValue.increment`를 지원한다.
- 문제 B. `localFallbackUsage`가 인메모리 기본값(`lastActiveDate=""`)으로 시작한다. 네트워크 단절 시 ① 한도 카운트가 앱 재시작으로 초기화되어 **한도 우회가 가능**하고, ② Firebase가 일시 불통 → `getUsage()`가 기본값 반환 → `CheckApiLimitUseCase`가 날짜 불일치로 판단해 `updateUsage()`로 **클라우드의 실제 사용량을 0으로 덮어쓰는** 레이스가 있다(쓰기 시점에 네트워크가 복구된 경우).
- 권장 수정. DataStore 등 로컬 영속화 병행(재시작 우회 차단 + Firebase 읽기 감소), 증가는 트랜잭션/increment 사용, 폴백 상태에서의 날짜 리셋 쓰기는 보류.

---

## 2. 심각도 중간

### 2-1. 청크 경계 무조건 트리밍 — 25m 직선 점프 생성
- 위치. `RouteRepositoryImpl.kt:118-124`, 상수 `CHUNK_TRIM_METERS = 25.0` (`RouteRepositoryImpl.kt:22`)
- 마지막 청크가 아니면 **아티팩트 존재 여부와 무관하게** 끝 25m를 잘라낸다. 다음 청크의 시작점과 사이에 최대 25m의 직선 점프가 생기고, 이 직선은 건물·하천을 관통할 수 있다. GPX 결과물 품질에 직접 영향.
- 참고. `walkthrough.md`에는 20m로 기재되어 있어 문서와 코드가 불일치한다.
- 권장 방향. 청크 경계 부근에서 실제 되돌아옴(U턴·재방문)을 감지했을 때만 트리밍하거나, 트리밍 후 남은 끝점과 다음 청크 첫 점 사이 거리가 임계 초과면 트리밍을 롤백.

### 2-2. "불필요한 반복(왕복) 경로"의 주 발생원이 후처리되지 않음
- 위치. 파이프라인 전체 (`SnapToRoadUseCase.kt:45-52`, `RouteRepositoryImpl.kt:99-114`)
- T-Map은 passList의 각 경유지를 **반드시 통과**하려 하므로, 웨이포인트가 막다른 골목·단지 안쪽·보행로 살짝 옆에 찍히면 "들어갔다 되돌아 나오는" 왕복 스퍼(spur)가 생성된다. 현재 이를 제거하는 로직이 없다. RDP(`EPSILON_ROUTE_DEG`)는 수직 거리 기반이라 **왕복 구간은 수직 편차가 0에 가까워 제거하지 못한다.**
- 권장 방향. 스냅 결과 후처리로 "A→…→B→…→A 로 짧게 되돌아오는 스퍼"(예: 편도 30~50m 이내, 진행 방향 반전) 감지 시 스퍼 진입 전 지점으로 접합. 단, 그림 특성상 **의도적으로 같은 길을 왕복하는 획**도 있으므로 웨이포인트가 스퍼 끝에 있는 경우(=사용자가 그린 지점)는 보존하고, T-Map이 임의 생성한 짧은 스퍼만 제거해야 한다.
- 보조 방향. 웨이포인트를 스냅 전에 도로망에 가깝게 찍을수록 스퍼가 줄어든다. 근접 웨이포인트 병합(2-3)도 같은 효과가 있다.

### 2-3. 웨이포인트 dedupe가 완전 일치만 제거
- 위치. `RouteRepositoryImpl.kt:39-44`
- 연속 중복 제거가 `!=` 비교라서 수 미터 간격의 사실상 동일한 웨이포인트가 살아남는다. T-Map에 극단적으로 가까운 경유지 2개가 들어가면 미세 왕복이나 경로 꼬임이 생기고 청크 수(=HTTP 호출 수)도 늘어난다.
- 권장 수정. 최소 간격(예: 10m) 미만 웨이포인트 병합. API 절감과 반복 경로 방지에 동시에 기여한다.

### 2-4. dirty range가 단일 min~max 구간 — 떨어진 두 편집 사이가 통째로 재라우팅됨
- 위치. `MapViewModel.kt:314-315` 등 (dirtyStart=min, dirtyEnd=max 갱신), `MapViewModel.kt:557-563`
- 경로 양 끝의 마커를 하나씩 편집하면 dirty 범위가 사실상 전체가 되어, 손대지 않은 중간 구간까지 웨이포인트로 들어가 청크 수와 API 호출이 늘어난다.
- 권장 방향. dirty 구간을 리스트로 관리해 편집 클러스터별로 개별 재라우팅. (구현 복잡도 대비 효과는 사용 패턴에 따라 판단.)

### 2-5. 매 스트로크마다 자동 스냅 — 다획 그림에서 API 소모 가중
- 위치. `DrawingOverlay.kt:65` (onDragEnd → onDrawEnd), `MapViewModel.kt:203-210`
- 손가락을 뗄 때마다 즉시 스냅(=T-Map 1~3 HTTP)이 나간다. 글자·복잡한 도형처럼 여러 획으로 그리는 경우 획 수만큼 호출이 누적되고, 이어 그리기 흐름을 반복해야 한다.
- 권장 방향. DRAWING 중 여러 획을 로컬로 누적하고 "완료" 버튼에서만 스냅하는 배치 모드(또는 설정 옵션). 미리보기(RDP 직선화)는 로컬 연산이므로 즉시 보여줄 수 있다. 현재 구조에서 가장 큰 API 절감 여지가 있는 지점이다.

### 2-6. 취소/실패 처리에서 CancellationException 삼킴
- 위치. `RouteRepositoryImpl.kt:38` (runCatching), `MapViewModel.kt:146` (onGpxUriSelected의 catch (e: Exception))
- `runCatching`과 광범위 `catch (Exception)`은 코루틴 취소 신호(CancellationException)까지 삼켜 구조적 취소를 깨뜨린다. 현재는 gen 토큰 방식이라 실제 취소가 드물지만, ViewModel clear 시점 등에서 취소 후 reduce/토스트가 실행될 수 있다.
- 권장 수정. `if (e is CancellationException) throw e` 재던지기 또는 `runCatching` 대신 명시적 try-catch.

### 2-7. 마커 아이콘 비트맵을 매 리컴포지션마다 생성
- 위치. `MapScreen.kt:160-223` (markerIcon), 호출부 `MapScreen.kt:491`, `MapScreen.kt:511`, `MapScreen.kt:531`
- `markerIcon()`은 호출마다 100×100 Bitmap + Canvas를 새로 만든다. 마커 드래그 중에는 `state.routeMarkers`가 매 프레임 갱신되어 **모든 마커의 비트맵이 프레임마다 재생성**된다(마커 30개 × 60fps = 초당 1,800개 비트맵 → GC 압박, 잭 유발).
- 권장 수정. `remember(type, label, selected)` 캐시 또는 ViewModel 밖 LruCache. 라벨당 선택/비선택 2종만 있으면 된다.

### 2-8. segmentize·totalDistance가 리컴포지션마다 메인 스레드에서 O(n) 이상 재계산
- 위치. `MapScreen.kt:452` (segmentize 호출), `MapState.kt:56` (totalDistanceMeters getter)
- `segmentize`는 마커마다 `indexOfFirst` 전수 탐색이라 O(경로 점수 × 마커 수)다. 드래그 중 매 프레임 실행되며, 수천 점 경로 + 수십 마커면 프레임 드랍 가능성이 있다. `totalDistanceMeters`도 getter라 참조될 때마다 전체 haversine 합산을 다시 한다.
- 권장 수정. `remember(state.snappedRoute, state.routeMarkers) { segmentize(...) }` 및 거리 값의 상태 승격(스냅/적용 시점에 1회 계산) 또는 `derivedStateOf`.

---

## 3. 심각도 낮음 / 정리 대상

| # | 항목 | 위치 |
|---|------|------|
| 3-1 | `onMapTapped`(약 75줄)가 UI 어디서도 호출되지 않는 데드코드다. `onMapClick`은 `onMarkerDeselect`만 호출한다. 도움말·walkthrough의 "지도 탭 이동" 설명과도 어긋난다 | `MapViewModel.kt:428-502`, `MapScreen.kt:441-443` |
| 3-2 | 미사용 상수 `REROUTE_DEBOUNCE_MS`, `DEFAULT_INTERVAL_METERS`와 미사용 import(`MutableSharedFlow`, `debounce`, `BufferOverflow`). `onApplyEdits` KDoc의 "debounce를 통해 호출되므로"는 실제 코드와 다르다(디바운스 없음) | `MapViewModel.kt:63-64`, `MapViewModel.kt:20-22`, `MapViewModel.kt:546-549` |
| 3-3 | `MapSideEffect.RequestLocationPermission`은 발행하는 곳이 없다 | `MapSideEffect.kt:5` |
| 3-4 | haversine 구현이 4중 중복(GeoUtils 도입 후에도 MapViewModel·MapState·SnapToRoadUseCase·RouteRepositoryImpl 각자 보유). 계획서에 후속 과제로 명시된 사항이므로 일괄 치환 시점만 결정하면 된다 | `GeoUtils.kt:10` 외 4곳 |
| 3-5 | Timber를 도입(디버그 한정 초기화)했지만 코드 전반이 `android.util.Log` 직접 사용이라 릴리즈 빌드에서도 로그가 출력된다(좌표 등 위치 데이터 포함) | `MapViewModel.kt`, `RouteRepositoryImpl.kt`, `DeviceUsageRepositoryImpl.kt` |
| 3-6 | 일일 기본 한도 30이 매직 넘버로 3곳에 중복 | `MapViewModel.kt:75`, `MapViewModel.kt:813`, `CheckApiLimitUseCase.kt:32` |
| 3-7 | `editHistory` 무제한 누적 — 스냅샷마다 전체 경로 사본을 보유하므로 긴 편집 세션에서 메모리 증가. 상한(예: 20단계) 권장 | `MapState.kt:44` |
| 3-8 | `onDeleteMarkerConfirmed`/`onDeleteSegmentConfirmed`의 `routeStart ?: return@intent` 조기 반환 시 다이얼로그 플래그가 true로 남아 다이얼로그가 닫히지 않는다(상태 불일치 시에만 발생) | `MapViewModel.kt:251-252`, `MapViewModel.kt:385-386` |
| 3-9 | `onDrawPoint`가 포인트마다 `state.drawnPoints + point`로 리스트 전체 복사 — 긴 스트로크에서 O(n²) 누적 | `MapViewModel.kt:198-201` |
| 3-10 | OkHttp 타임아웃 미설정(기본 10초). T-Map 지연 시 PROCESSING이 길어지므로 명시적 connect/read 타임아웃과 사용자 피드백 권장 | `NetworkModule.kt:20-29` |
| 3-11 | `nearestLevelIndex` 주석 "0..2 반환"이 실제(0..4)와 불일치 | `MapScreen.kt:121` |
| 3-12 | 테스트 Case 3이 `if (trkptBlock.contains("<time>"))` 가드 안에서만 검증해 타임스탬프가 아예 출력되지 않아도 통과한다. 가드를 필수 assertion으로 전환 필요 | `ExportGpxUseCaseTest.kt:110-115` |
| 3-13 | `sampleMarkers`가 마커 추가 시 누적 거리를 0으로 리셋해 초과분이 버려진다 — 실제 마커 간격이 설정값보다 항상 크거나 같아지고 평균적으로 벌어진다(기능 문제는 아님) | `MapViewModel.kt:671-683` |
| 3-14 | Firebase RTDB `device_usage` 경로의 보안 규칙이 코드 밖 사안이지만, 규칙이 열려 있으면 클라이언트 위조 쓰기로 한도 우회가 가능하다. 콘솔에서 규칙 확인 필요 | `DeviceUsageRepositoryImpl.kt:25` |
| 3-15 | `OsrmResponse.kt`는 미사용(계획서에 의도적 보류로 명시 — 현행 유지 확인만) | `data/remote/dto/OsrmResponse.kt` |

---

## 4. API 사용량 절감 아이디어 (우선순위 제안)

위 버그 수정과 별개로, 목표(사용량 최소화) 대비 효과가 큰 순서다.

1. **다획 배치 스냅** (2-5). 획마다 스냅 → 완료 시 1회 스냅으로 바꾸면 다획 그림에서 호출 수가 획 수만큼 나누어 떨어진다. 절감 폭이 가장 크다.
2. **카운트 단위 정합** (1-3). 절감 자체는 아니지만, 현재는 사용량 계기판이 실제보다 적게 보여 "최대한 줄인다"는 목표의 측정 기반이 어긋나 있다. 최우선 정비 대상.
3. **T-Map 응답 캐시**. 웨이포인트 목록(좌표 반올림 키) → 응답 폴리라인 LRU 캐시. 같은 구간 재적용, 실행 취소 후 재적용, Blocked → 광고 시청 → 재시도 흐름에서 네트워크를 생략할 수 있다.
4. **근접 웨이포인트 병합** (2-3). 청크 수 자체를 줄인다.
5. **hasPendingEdits 누수 수정** (1-1) 및 **연타 가드** (1-4). 낭비성 호출 제거.
6. **DataStore 로컬 영속화** (1-5). Firebase 읽기(체크마다 1회) 감소 + 한도 우회 차단.
7. **dirty span 리스트화** (2-4). 편집 패턴에 따라 선택 적용.

## 5. 반복 경로 방지 아이디어

1. **왕복 스퍼 후처리 제거** (2-2). 사용자가 그리지 않은 T-Map 임의 스퍼만 제거하는 것이 핵심 조건.
2. **청크 트리밍의 조건부화** (2-1). 현재의 무조건 트리밍은 반복 경로 방지 장치가 오히려 경로 품질을 깎는 케이스.
3. **자기교차 경로에서의 splice 안정화** (1-2). 반복처럼 보이는 경로 손상의 상당수가 여기서 나올 수 있다.
