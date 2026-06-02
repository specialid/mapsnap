# MapSnap — Architecture Walkthrough

## 개요

지도 위에서 자유롭게 선을 그리면 T-Map 보행자 API를 통해 실제 도로에 스냅하여
직선화된 경로를 표시하는 Android 앱.  
스냅된 결과 경로의 중간 마커를 이동·삭제하거나, 구간을 탭해 삭제하고, 경로를 이어 그릴 수 있다.

---

## 아키텍처

### 레이어 구조

```
presentation/
  map/          MapState · MapSideEffect · MapViewModel (Orbit MVI)
  component/    MapScreen · DrawingOverlay · BottomControls
domain/
  usecase/      SimplifyPathUseCase · SnapToRoadUseCase
  repository/   RouteRepository (interface)
data/
  repository/   RouteRepositoryImpl
  remote/       TmapService (Retrofit) · dto/TmapRequest · TmapResponse
di/             NetworkModule · AppModule (Hilt)
```

### MVI 패턴 (Orbit)

- `MapViewModel`이 `ContainerHost<MapState, MapSideEffect>` 구현
- `reduce { }` 블록은 순수 상태 변환만 수행
- 모든 네트워크·CPU 작업은 `intent { }` + `withContext(Dispatchers.IO/Default)` 위임

---

## DrawingMode 상태 전이

```
IDLE ──[그리기 버튼]──► DRAWING ──[손가락 뗌/스냅 버튼]──► PROCESSING ──► DONE
 ▲                                                                          │
 └──────────────────────[지우기 버튼 / onClearDrawing]─────────────────────┘
 
DONE ──[이어 그리기(+) 버튼]──► DRAWING (isContinuing=true)
```

---

## 경로 스냅 파이프라인

### 입력 처리 (SnapToRoadUseCase)

```
손그림 raw points
    │
    ▼ [1] SimplifyPathUseCase.invoke(points, EPSILON_DRAWN_DEG=0.000135°, ~15m)
    │     Ramer-Douglas-Peucker · cosLat 보정으로 경도 비등방성 제거
    │
    ▼ [2] sampleByArcLength(simplified, maxWaypoints=19)
    │     Haversine 기반 아크-길이 균등 샘플링 (코너 밀집 구간 과탈락 방지)
    │     → T-Map API 청크 3회(chunkSize=7) 수준으로 제한
    │
    ▼ [3] RouteRepositoryImpl.getPedestrianRoute(waypoints)
    │     청크 분할(start+경유지5+end=7포인트) → T-Map 병렬 아닌 순차 호출
    │     청크 경계 끝 20m 트리밍 → 루프 아티팩트 방지
    │
    ▼ [4] SimplifyPathUseCase.invoke(route, EPSILON_ROUTE_DEG=0.000072°, ~8m)
          T-Map 결과 미세 곡선 제거, 교차로·꺾임 보존
```

### 파라미터 트레이드오프

| 파라미터 | 현재값 | 낮추면 | 높이면 |
|----------|--------|--------|--------|
| `maxWaypoints` | 19 | 경로 더 직선적, 청크↓ | 그린 선 밀착, 청크↑ |
| `EPSILON_DRAWN_DEG` | 0.000135° | 손 떨림 과보존 | 코너 손실 |
| `EPSILON_ROUTE_DEG` | 0.000072° | 도로 곡선 과보존 | 교차로 누락 위험 |
| `CHUNK_TRIM_METERS` | 20m | 루프 아티팩트 잔존 위험 | 청크 경계 경로 단절 |
| 마커 샘플 간격 | 30m | 마커 밀집 | 마커 희소 |

---

## DrawingOverlay 렌더링

| DrawingMode | 렌더링 내용 |
|-------------|------------|
| `DRAWING` | 손그림 원본 (파란 곡선) + 시작점 스냅존 원(초록) |
| `PROCESSING` | RDP 직선화 선분 (주황, 루프면 초록) + waypoint 점 |
| `DONE` / `IDLE` | 오버레이 비표시 (PathOverlay가 담당) |

---

## 루프(Loop) 자동 연결

- 시작점·끝점 거리 ≤ `LOOP_CLOSE_THRESHOLD_M` (30m) 시 자동 연결
- 손그림 마지막에 시작점 재추가 → T-Map이 닫힌 경로 생성
- 이어 그리기 중에는 루프 감지 비활성화

---

## 결과 경로 편집

### 중간 마커

- 스냅 완료 후 `sampleMarkers(route, 80m)`로 최초 자동 생성 (기존 30m에서 80m로 늘려 조절 포인트 과다 생성 방지)
- 마커 탭 → 선택(주황 표시)
- **마커 내부 순서 번호 표시**:
  - 각 중간 마커는 생성된 순서에 따라 **1부터 시작하는 순서 번호(Sequence Number)**가 마커 중앙에 표시됩니다.
  - 선택되지 않은 상태에서는 파란색 테두리 색상과 동일한 파란색(`0xFF1565C0`) 글씨로 표시되며, 선택되었을 때는 흰색(`0xFFFFFFFF`) 글씨로 반전되어 시각적 명확성을 보장합니다.
- **드래그 앤 드롭 이동(Drag-to-Move)**: 
  - 선택된 마커 위에 Compose 오버레이를 통해 큰 터치 대상 영역(`64dp`)을 가진 드래그 핸들을 배치하여 가시성 및 터치 조작성을 대폭 강화합니다. (마커 지름은 80px로 2배 확대)
  - 사용자가 드래그를 진행할 때 Compose의 `detectDragGestures` 및 지도 `Projection`을 연동해 실시간 좌표를 추출하고 뷰모델 상태를 갱신합니다.
- **T-Map 호출 최적화 (Debounce)**: 
  - 드래그 동작이 멈춘 후 **300ms 디바운스** 딜레이 뒤에 최종 1회의 국소 재라우팅(`handlePartialReroute`) API만 호출되도록 이벤트를 결합(debounce)합니다.
- **포인트 증식 및 위치 변동 방지**:
  - 재라우팅 완료 시 기존 마커 목록을 전체 재생성하지 않고 **기존 리스트를 보존한 채 드래그하여 움직인 마커 1개만 신규 경로 상의 가장 가까운 지점으로 보정 스냅** 처리합니다. 이로 인해 마커 개수가 절대 증식하지 않고 다른 마커들이 원래 위치를 온전히 유지합니다.
- **중간 마커 직접 선택 삭제**:
  - 선택된 마커가 중간 마커인 경우, 마커 위쪽(48dp 위)에 빨간색 삭제 버블 UI(Icons.Default.Delete 휴지통 아이콘 포함)를 표시합니다.
  - 삭제 버블을 클릭하거나 이미 선택된 상태의 마커를 지도 상에서 한 번 더 탭할 경우, "마커 삭제" 확인 다이얼로그를 트리거합니다.
  - 다이얼로그 승인 시 해당 중간 마커를 제거하고 T-Map API 국소 재라우팅(`PartialRerouteEvent.MarkerRemoved`)을 연동하여 삭제 지점 앞뒤 마커 기준 실도로 스냅 경로를 다시 산출합니다.
  - 삭제 시에도 15미터 미만 구간은 T-Map API 호출을 우회(Bypass)하는 단거리 삭제 최적화가 동일하게 적용됩니다.

### 출발 및 도착 마커 (최종 포인트 최적화)

- **출발(S) 및 도착(G) 마커 시각화**: 
  - 경로의 양 끝 최종 지점에 각각 초록색 바탕의 `S`(출발) 마커와 빨간색 바탕의 `G`(도착/종료) 마커가 표시됩니다.
  - 선택 여부와 관계없이 텍스트는 항상 흰색(`0xFFFFFFFF`)으로 시각화되어 가독성을 높입니다.
  - 선택 시 중간 마커와 동일하게 오렌지색 하이라이트가 부여되고 드래그 조절 패널이 노출됩니다.
- **최종 포인트 드래그 시 경로 단축**:
  - **도착 마커(G)**를 원래 종료 지점보다 더 짧은 위치로 이동시키면 `routeEnd`가 갱신되며, 마지막 중간 마커에서 신규 도착지까지의 경로만 재탐색(Reroute)합니다. 기존에 목적지 통과 후 이전의 원래 종점으로 회귀하는 불필요한 경로(루프)가 깔끔하게 사라지며 **경로가 정상적으로 단축**됩니다.
  - **출발 마커(S)**를 드래그하는 경우에도 동일하게 `routeStart`가 갱신되어 출발부 경로가 정상 단축 및 변경됩니다.


### 구간 선택 삭제

- `segmentize(snappedRoute, routeMarkers)` → 마커 위치 기준으로 경로 분할
- 各 구간 별도 `PathOverlay` (탭 가능)
- 구간 탭 → 주황 하이라이트 + AlertDialog("구간 삭제")
- 삭제 확인 → 경계 마커(`markers[min(segIdx, markers.lastIndex)]`) 제거 → 재라우팅

### 로컬 업데이트 및 일괄 적용 (Apply Edits)

- **로컬 즉시 반영 및 API 지연 호출**:
  - 기존의 마커 드래그, 시작/끝 마커 드래그, 마커/세그먼트 삭제 시 실시간으로 API를 호출하던 디바운스 방식(`_rerouteSignal`)을 제거했습니다.
  - 대신 편집이 일어날 때마다 `spliceRoute` 함수를 사용하여 로컬에서 `snappedRoute`와 마커 좌표만 즉시 업데이트하여 끊김 없는 드래그/편집 경험을 제공합니다.
  - 편집 발생 시 `hasPendingEdits` 상태를 `true`로 설정합니다.
- **배치 재라우팅 (Batch Rerouting)**:
  - `DrawingMode.DONE` 상태에서 `hasPendingEdits`가 `true`일 때 하단 컨트롤러에 **적용** 버튼(`ExtendedFloatingActionButton`, `Icons.Default.Refresh`)이 동적으로 표시됩니다.
  - 사용자가 **적용** 버튼을 누르면 `onApplyEdits()`가 호출되어 출발지, 중간 마커들, 목적지를 통합한 전체 웨이포인트를 구성하고, `snapToRoad.fromWaypoints()` API를 통해 전체 경로에 대해 일괄적으로 도로 스냅 재탐색을 실행합니다.
  - 재탐색 성공 시 새로운 스냅 경로로 상태를 업데이트하고 중간 마커들을 재샘플링한 후 `hasPendingEdits`를 `false`로 재설정합니다.

### 마커 편집 단계별 실행 취소 (Undo)

- **편집 상태 이력 스택 (`EditSnapshot` & `editHistory`)**:
  - 편집 중인 상태 데이터(`routeStart`, `routeEnd`, `routeMarkers`, `snappedRoute`)의 스냅샷을 나타내는 `@Immutable` `EditSnapshot` 데이터 모델을 정의했습니다.
  - 마커 조작 시마다 이전 상태의 스냅샷을 `editHistory: List<EditSnapshot>` 스택 형태로 누적 관리하여 다단계 되돌리기 기능을 제공합니다.
- **제스처 구분 및 스냅샷 저장 시점 최적화**:
  - 마커 드래그 시 매 프레임 스냅샷이 과다 저장되어 이력 스택이 폭발하는 현상을 차단하기 위해, 드래그 시작 시점(`onDragStart`)에만 딱 1회 스냅샷을 저장하도록 제스처 트리거를 설계했습니다.
  - 마커/구간 삭제 및 지도 탭 이동 등의 단발성 편집 시점에는 동작을 적용하기 직전에 스냅샷을 캡처해 이력에 추가합니다.
- **단계별 되돌리기 구현 (`onUndo`)**:
  - 사용자가 하단의 실행 취소(Undo) 버튼(Floating Action Button)을 누르면, 이력 스택에서 가장 최신 스냅샷을 꺼내 해당 상태로 복원합니다.
  - 편집 내용을 전부 되돌려 이력 스택이 비게 되면(`editHistory.isEmpty()`), 변경 대기 상태 플래그(`hasPendingEdits`)도 자동으로 `false`로 복구하여 미수정 원래 상태로 복귀시킵니다.
  - [적용] 버튼을 눌러 T-Map 실도로 스냅을 확정 갱신하거나 지우기/새 그리기 진행 시에는 이력 스택이 초기화(`emptyList()`)됩니다.

---

## 이어 그리기


- DONE 상태에서 `+` 버튼 → `isContinuing = true`, DRAWING 진입
- 기존 `snappedRoute` / `routeStart` / `routeEnd` / `routeMarkers` 유지
- 새 드로잉 스냅 시: `[snappedRoute.last()] + drawnPoints` 로 연결
- 스냅 성공: `existingRoute + newRoute.drop(1)` 연결 후 마커 재샘플링

---

## T-Map API

- Endpoint: `POST https://apis.openapi.sk.com/tmap/routes/pedestrian`
- 인증: `appKey` 헤더
- `searchOption = "0"` (추천 경로) — passList 제약과 함께 선 추종 안정성↑
- `reqCoordType / resCoordType = "WGS84GEO"`
- 청크당 경유지 최대 5개 → 7포인트씩 분할 처리
- API 키: `local.properties`의 `TMAP_API_KEY` → `BuildConfig.TMAP_API_KEY`

---

## 지도 SDK (Naver Maps)

- SDK 버전: `3.23.2` (신규 NCP 키 방식 필수)
- 인증: `NaverMapSdk.NcpKeyClient` (`App.kt`)
- Manifest meta-data: `com.naver.maps.map.NCP_KEY_ID`
- `local.properties`: `NAVER_CLIENT_ID`

---

## 위치 권한

- `FusedLocationProviderClient.lastLocation`
- `ActivityResultContracts.RequestPermission`으로 권한 요청
- 권한 거부 또는 위치 불가 시 서울 시청 (37.5666, 126.9784) 폴백

---

## GPX 내보내기 기능 (GPX Export)

- **GPX 1.1 XML 생성 (`ExportGpxUseCase`)**:
  - `snappedRoute` 리스트의 좌표(`LatLng`)들을 GPX 1.1 규격에 맞게 `<trkpt>` 태그로 변환합니다.
  - 성능 최적화를 위해 내부적으로 `StringBuilder`를 사용하여 빠르게 XML 문자열을 조립합니다.
  - 루트 이름에 특수 문자가 들어갈 수 있으므로 XML 이스케이프 문자 처리를 수행합니다.
- **Android CreateDocument SAF 연동 (`MapScreen`)**:
  - Compose의 `rememberLauncherForActivityResult`와 `ActivityResultContracts.CreateDocument`를 이용하여 사용자가 직접 저장 경로와 파일 이름을 선택할 수 있는 시스템 저장 대화상자를 제공합니다 (`mapsnap_route.gpx`).
- **비동기 IO 쓰기 및 MVI 예외 처리 (`MapViewModel`)**:
  - URI 선택 시 백전환 디스패처(`Dispatchers.IO`)로 이동하고, `isProcessing = true`로 상태를 두어 로딩 인디케이터를 활성화합니다.
  - `ContentResolver.openOutputStream`을 사용하여 원격/로컬 스트림에 XML 데이터를 쓰고 자원을 안전하게 해제(`use` 블록)합니다.
  - 성공/실패 여부에 따라 Toast 메시지 피드백을 사용자에게 보여주고 진행 중 상태를 해제합니다.
- **사용자 경험 (UX) 버튼 배치 (`BottomControls`)**:
  - 경로 작성이 완료된 `DrawingMode.DONE` 상태에서만 GPX 공유 버튼이 애니메이션 효과와 함께 노출됩니다.
  - 테마의 tertiaryContainer 배경색과 함께 `Icons.Default.Share` 아이콘을 사용해 프리미엄 디자인 룩앤필을 보장합니다.

---

## API 호출 모니터링 및 실시간 대시보드 오버레이

- **실시간 호출 횟수 관리 (`ApiCallTracker`)**:
  - Hilt 싱글톤으로 인스턴스화되며, `StateFlow` 구조를 통해 멀티스레드 환경에서 안전하고 신속하게 T-Map 및 Naver Map API 호출 수를 관리합니다 (`update { it + 1 }`).
- **상세 분석 로깅 (`RouteRepositoryImpl` & `MapViewModel`)**:
  - T-Map API 호출 전후로 청크 인덱스, 총 청크 수, 시작/끝 좌표 및 수신한 Feature 개수를 `Log.d("RouteRepositoryImpl", ...)`로 로깅하여 트래픽의 규모와 분할 전송 상태를 투명하게 모니터링할 수 있습니다.
  - 네이버 지도가 초기화되어 실제로 로딩을 마치는 시점(`MapEffect(Unit)`)에 `Log.d("MapViewModel", ...)`로 로딩 기록을 남기고 카운팅을 진행합니다.
- **글래스모피즘 오버레이 UI 대시보드 (`MapScreen`)**:
  - 지도 우측 상단에 세련된 반투명 카드 오버레이를 배치하여 `T-Map API` 및 `Naver Map API` 호출 카운트를 실시간으로 동기화하여 시각적으로 모니터링할 수 있도록 설계하였습니다.

---

## T-Map API 호출 최적화

- **경로 길이에 따른 동적 웨이포인트 샘플링**:
  - `SnapToRoadUseCase`에서 단순화된 경로(`simplified`)의 총 누적 거리(Haversine 공식)를 계산하고, 이에 비례하는 동적 웨이포인트 개수(`dynamicMaxWaypoints = (totalDist / 50.0).toInt().coerceIn(2, 19)`)를 계산하여 샘플링을 수행합니다. 이를 통해 짧은 경로에서 불필요하게 많은 웨이포인트를 생성 및 호출하지 않도록 최적화합니다.
- **드래그 위치 변경 중복 검사**:
  - `MapViewModel`의 `onMarkerDragged`, `onStartMarkerDragged`, `onEndMarkerDragged` 메서드에서 드래그 이벤트로 수신한 신규 좌표가 현재 마커/출발/도착점 좌표와 완전히 동일한 경우 작업을 즉시 생략하고 반환하여 불필요한 재라우팅 처리를 차단합니다.
- **단거리 마커 삭제 시 T-Map API 우회**:
  - `MapViewModel.handlePartialReroute`에서 `MarkerRemoved` 이벤트 시 이전 경계(`prevBoundary`)와 다음 경계(`nextBoundary`) 사이의 거리가 15.0미터 미만인 경우 T-Map API를 호출하지 않고, 기존 경로에서 두 경계를 직선으로 연결하는 방식으로 자체 우회(Bypass) 접합하여 네트워크 비용을 제거합니다.

---

## 파일 목록

| 파일 | 역할 |
|------|------|
| `ExportGpxUseCase` | snappedRoute 리스트를 표준 GPX 1.1 XML 형식으로 변환 |
| `SimplifyPathUseCase` | RDP 알고리즘, cosLat 보정, epsilon 파라미터 노출 |
| `SnapToRoadUseCase` | 입력 RDP → 샘플링 → API → 출력 RDP 파이프라인, `fromWaypoints()` |
| `RouteRepositoryImpl` | T-Map 청크 분할 호출, 경계 트리밍, 중복 좌표 제거 |
| `ApiCallTracker` | T-Map 및 네이버 지도 API 호출 횟수를 집계 및 관리하는 싱글톤 컴포넌트 |
| `MapState` | DrawingMode, drawnPoints, simplifiedPoints, snappedRoute, routeMarkers, API 카운트 등 |
| `MapViewModel` | 모든 인텐트, sampleMarkers, API 카운트 Flow 수집, GPX 저장 액션 처리 |
| `DrawingOverlay` | Canvas 기반 손그림 · 직선화 오버레이, 스냅존 원 |
| `MapScreen` | NaverMap Compose, segmentize, PathOverlay/Marker 렌더링, API 카운터 대시보드 오버레이, AlertDialog |
| `BottomControls` | 그리기 / 스냅 / 이어 그리기 / 지우기 / GPX 내보내기 FAB |

---

## Git 히스토리

| 커밋 | 내용 |
|------|------|
| `73bfb7e` | 초기 커밋 — T-Map 스냅, RDP, 루프 연결, 마커 편집 전체 구현 |
| `a75f758` | feat: 기존 경로에 이어 그리기 기능 추가 |
| `bf80454` | feat: 구간 탭으로 삭제 기능 추가 |
| `db4921f` | feat: 출발/도착 마커(S/G) 도입 및 최종 포인트 드래그 이동 시 경로 단축 최적화 |
| `e8a7fbc` | feat: GPX 1.1 표준 규격 내보내기(Export) 기능 추가 및 SAF 저장 다이얼로그 구현 |
| `1cf427d` | feat: T-Map & 네이버 지도 API 호출 추적 로깅 및 실시간 카운터 오버레이 대시보드 추가 |
| `db29f12` | fix: T-Map API 호출 시 연속 중복 좌표 제거를 통한 HTTP 400 오류 해결 |
| `f3a1d9c` | feat: T-Map API 호출 횟수 최적화 (동적 샘플링, 드래그 중복 제거, 마커 삭제 우회) |
| `bcca9af` | feat: T-Map API 호출 최적화 및 중간 마커 순서 식별성 UX 개선 |
| `8ff0213` | feat: 중간 마커 직접 삭제 및 T-Map 국소 재라우팅 연동 기능 추가 |
| `da8aa6a` | feat: 로컬 경로 업데이트 및 배치 재라우팅 (Apply edits) 일괄 적용 기능 구현 |
| `abe1464` | feat: 마커 편집 단계별 실행 취소 (Undo) 기능 구현 |

