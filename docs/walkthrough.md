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
  - 기존의 마커 드래그, 시작/끝 마커 드래그, 마커/세그먼트 삭제 시 실시간으로 API를 호출하던 디바이스 방식(`_rerouteSignal`)을 제거했습니다.
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

- **국소 마커 재탐색 (Local Marker Rerouting) 및 마커 보존**:
  - 마커 편집(드래그, 삭제, 탭 이동 등) 시 전체 경로를 재탐색하는 대신, 변경 사항이 발생한 마커의 앞뒤 범위(Dirty Range)를 실시간으로 추적합니다.
  - 적용(Apply) 시 해당 Dirty Range에 인접한 양단 버퍼(+1, -1 인덱스 범위) 구간만 추출하여 T-Map API(`snapToRoad.fromWaypoints`)를 호출합니다.
  - API 결과로 반환된 부분 경로를 원본 경로(`snappedRoute`)의 적절한 위치(`indexOfClosest`)에 국소 교체(Splice)하고, 해당 세그먼트에서만 마커를 재샘플링하여 기존의 수정되지 않은 다른 모든 마커들의 위치를 그대로 보존함으로써 API 트래픽을 대폭 절감하고 마커 유실을 방지합니다.
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
| `DeviceUsage` | Firebase RTDB 저장용 일일 API 호출 및 충전 횟수 데이터 모델 |
| `DeviceUsageRepository` | 디바이스 사용량 조회 및 업데이트 추상화 도메인 레포지토리 |
| `DeviceUsageRepositoryImpl` | FirebaseDatabase 지연 초기화 및 3초 타임아웃, 로컬 폴백을 적용한 Firebase RTDB 연동 구현체 |
| `DatabaseModule` | Repository DI 바인딩 모듈 (FirebaseDatabase 제공 로직 제거) |
| `MapState` | DrawingMode, drawnPoints, simplifiedPoints, snappedRoute, routeMarkers, API 카운트 등 |
| `MapViewModel` | 모든 인텐트, sampleMarkers, API 카운트 Flow 수집, GPX 저장 액션 처리 |
| `DrawingOverlay` | Canvas 기반 손그림 · 직선화 오버레이, 스냅존 원 |
| `MapScreen` | NaverMap Compose, Navigation Drawer 및 햄버거 메뉴 버튼, API 카운터 대시보드 오버레이, AlertDialog |
| `BottomControls` | 통합 하단 조작계 카드 컴포저블 (거리, 예상 시간, 마커 수 요약 및 상태별 버튼 제어) |

---

## Firebase Realtime Database (RTDB) 연동

- **기기 고유 식별자 추출 및 해싱**:
  - `Settings.Secure.ANDROID_ID`를 획득하여 `MessageDigest`를 활용해 SHA-256 해시값(Hex String)을 생성합니다. 이를 통해 개인정보를 직접 유출하지 않고도 각 고유 기기를 안전하게 구별합니다.
- **실시간 호출 횟수 클라우드 동기화 (지연 초기화 및 안전성 개선)**:
  - 아시아 동남아 리전 데이터베이스 주소(`https://map-snap-b0438-default-rtdb.asia-southeast1.firebasedatabase.app`)를 명시하여 연결 에러를 방지합니다.
  - 앱 시작 시 Firebase가 미초기화되었거나 네트워크 불통 시 발생하는 비정상 종료(Crash) 및 코루틴 무한 대기(Freeze) 문제를 예방하기 위해, `FirebaseDatabase` 인스턴스 획득에 try-catch 예외 처리를 적용하고 3초 타임아웃(`withTimeoutOrNull(3000L)`) 구조를 도입했습니다.
  - 데이터베이스 통신 실패 또는 타임아웃 시 앱이 중단되지 않고 로컬 캐시 데이터(`localFallbackUsage`)를 대신 사용하여 중단 없는 UX를 제공합니다.
  - Coroutines의 `await()`를 사용하여 Firebase의 비동기 리스너 호출을 일시중단(suspend) 함수 스타일로 래핑하고, MVI 스레딩 원칙에 따라 백그라운드 IO 디스패처(`Dispatchers.IO`) 상에서 동작하도록 보장하였습니다.
- **오프라인 지속성**:
  - 오프라인 상태에서도 캐시를 통해 앱이 비정상 종료 없이 동작할 수 있도록 로컬 레벨 영속성 고려.

---

## UI/UX 개선 (상단 드로어 및 하단 통합 조작 카드)

- **상단 공간의 극대화**:
  - 기존에 화면 상단을 가득 채우던 글래스모피즘 Card 상단 바를 전면 삭제했습니다.
  - 좌측 상단에 단독으로 위치한 플로팅 햄버거 메뉴 버튼(`Icons.Default.Menu`)을 제공하며, 클릭 시 안드로이드 표준 `ModalNavigationDrawer`가 열려 설정, 도움말, 정보를 띄울 수 있도록 개편했습니다.
- **하단 제어 기능 통합**:
  - 좌측 중간에 떠 있던 `경로 통계 카드` 및 여러 군데 분산되어 배치되었던 FAB(그리기, 스냅, 이어 그리기, GPX 저장, 다시 그리기, 지우기, 실행 취소 등)를 하나의 일관된 **하단 통합 조작 카드(BottomControlsCard)**로 완전히 정리했습니다.
  - **상태 기반 UI 흐름 제공**:
    - **그리기 전(IDLE)**: "원하는 경로를 그려보세요" 메시지 + `[그리기 시작]` 버튼.
    - **그리는 중(DRAWING)**: "지도 위에 선을 그려주세요" 메시지 + `[취소]` & `[완료]` 버튼.
    - **처리 중(PROCESSING)**: 원형 인디케이터 + "도로 스냅 경로 계산 중..." 텍스트.
    - **생성 완료(DONE)**: 수평 구조로 정렬된 경로 통계(거리, 예상 시간, 마커 수)와 주요 작업(수정 완료 시 `[적용]`, 평상시 `[이어 그리기]` + `[GPX 저장]`) 및 보조 텍스트 버튼(`[실행 취소]`, `[다시 그리기]`, `[지우기]`)을 단일 카드 내에 집약하여 뛰어난 터치 사용성과 개방적인 시각 경험을 안겨줍니다.

---

## 제스처 충돌 및 API 제한 데드락 버그 수정 (2026-06-05)

- **지도 드래그 제스처와 Drawer 스와이프 충돌 해결**:
  - `ModalNavigationDrawer` 컴포넌트에 `gesturesEnabled = drawerState.isOpen` 설정을 추가하여, 드로어가 닫혀 있을 때는 스와이프 열기 동작을 비활성화했습니다.
  - 이로써 화면 좌측 끝 영역을 드래그하여 지도를 이동(Pan)할 때 드로어가 예기치 않게 열리는 제스처 간섭 오류가 완전히 제거되었습니다. (드로어가 열려 있을 때 화면을 터치하거나 왼쪽으로 스와이프하여 닫는 제스처는 정상 유지됩니다.)
- **API 호출 한도 초과 시 UI 무한 로딩 데드락 방지**:
  - 기존에는 `snapCurrentPath()` 호출 도중 API 한도 초과(Blocked) 시, `drawingMode = DrawingMode.PROCESSING` 및 `isProcessing = true` 상태에 그대로 정체된 채 광고 다이얼로그를 띄워 화면이 굳는 문제가 있었습니다.
  - `Blocked` 분기 발생 시 기존 스냅 경로 존재 여부(`state.snappedRoute.isNotEmpty()`)에 따라 `drawingMode`를 `DrawingMode.DONE` 또는 `DrawingMode.IDLE`로 롤백하고 `isProcessing = false`로 동기화하도록 조치했습니다.
- **경로 탐색 실패 시의 상태 복원 개선**:
  - T-Map API 호출 실패(onFailure) 및 한도 초과 차단 시 무조건 `DrawingMode.DONE`으로 전이되던 상태 복원 흐름을 개선하여, 실제 기존의 스냅 경로 유무에 따라 `DONE` 또는 `IDLE`로 적합하게 복귀하도록 수정했습니다.

- **그리기 중 지도 조작 지원 및 '지도 이동' 모드 도입 (2026-06-05)**:
  - 그리기 모드(`DrawingMode.DRAWING`) 중 지도 드래그(Pan) 및 핀치 줌(Zoom) 조작을 지원하기 위해 `isMapMoveMode` 토글 상태를 구현했습니다.
  - 이동 모드 중에는 네이버 지도의 제스처를 동적 활성화하고 `DrawingOverlay`가 터치 이벤트를 소비하지 않고 지도로 그대로 투과되도록 조치했습니다.
  - 하단 카드에 `[지도 이동]` / `[그리기]` 모드 토글 버튼을 추가하고 좁은 화면에서도 3개 버튼이 안정적으로 어우러지도록 버튼 크기와 글자 크기를 축소 최적화했습니다.
- **마지막 API 호출 한도 도달 시 UI 무한 대기 프리징 해결 (2026-06-05)**:
  - T-Map API 호출 성공 시점에 횟수가 제한치(30회)를 정확히 채우게 되면 `checkApiLimitUseCase()`가 `Blocked`를 반환합니다.
  - 기존에는 `Allowed` 스마트 캐스팅 실패로 인해 `return@fold` 조기 반환이 유발되어, 스냅된 경로가 UI에 로드되지 않고 스피너가 무한 회전하는 결함이 있었습니다.
  - 스마트 캐스팅 대신 `when` 식 패턴 매칭을 사용하여 `Allowed` 및 `Blocked` 상태 모두에서 성공적으로 사용량 객체(`usage`)를 받아와 상태가 정상적으로 `DONE` 또는 롤백되도록 완전 조치하였습니다.
- **compileSdk 36 / targetSdk 36 상향 및 최신 라이브러리 의존성 일괄 업그레이드 (2026-06-05)**:
  - 기기에 구비된 Android 16 (API 36) SDK를 타겟팅하여 `compileSdk = 36` 및 `targetSdk = 36`으로 마이그레이션하였습니다.
  - 최신 SDK 버전에 맞추어 최신 코어 및 Compose 라이브러리 의존성들을 일괄 최신화했습니다:
    - Compose BOM `2026.05.01`, activityCompose `1.13.0`, coreKtx `1.15.0` (다운그레이드로 버전 정합성 유지), lifecycle `2.10.0`, naverMapCompose `1.9.0`
  - `naver-map-compose` 1.9.0 마이그레이션 규격에 맞추어 `MapScreen.kt`에서 기존 deprecated된 `rememberMarkerState` API들을 모두 신규 권장 API인 `rememberUpdatedMarkerState`로 깔끔하게 교체 마이그레이션 완료했습니다.
- **AGP 8.13.2 및 Gradle 8.13 빌드 도구 최신 권장 버전 업그레이드 (2026-06-05)**:
  - SDK 36 및 Compose BOM 2026 버전에 완벽히 대응하는 최신 권장 빌드 라이브러리 환경인 AGP `8.13.2` 및 Gradle wrapper `8.13`으로 일괄 상향 마이그레이션했습니다.
  - 최신 빌드 파이프라인의 호환성을 활용하여, 이전에 다운그레이드했던 `coreKtx` 버전을 SDK 36에서 지원하는 최고 안정 버전인 **`1.18.0`**으로 추가 상향했습니다.
  - 전체 컴파일 및 로컬 단위 테스트(`.\gradlew.bat compileDebugKotlin testDebugUnitTest`)가 경고 로그 및 우회 장치 없이 순정 상태로 정상 통과(`BUILD SUCCESSFUL`)함을 완벽하게 보증합니다.
- **compileSdk 37 / targetSdk 37 상향 및 AGP 9.2.1 / Gradle 9.4.1 최신 빌드 시스템 마이그레이션 (2026-06-05)**:
  - Android 16 SDK를 공식 지원하기 위해 `compileSdk = 37` 및 `targetSdk = 37`로 상향 조정했습니다.
  - 빌드 시스템을 **AGP 9.2.1** 및 **Gradle 9.4.1**로 마이그레이션했습니다.
  - AGP 9.x의 API 변경(BaseExtension 제거)에 대응하여 **Dagger Hilt** 버전을 **`2.59.2`**로 업그레이드했습니다.
  - AGP 9.0+의 내장 Kotlin 컴파일러 환경 통합에 맞추어 `app/build.gradle.kts`에서 명시적인 `kotlin.android` 플러그인 의존성을 삭제하고, 기존의 `kotlinOptions` 블록을 신규 `kotlin { compilerOptions { jvmTarget } }` 구조로 전면 교체하였습니다.
  - KSP가 코드를 생성하며 `kotlin.sourceSets`를 임의 구성할 때 발생하는 소스 세트 중복 에러를 해소하기 위해 `gradle.properties`에 `android.disallowKotlinSourceSets=false` 플래그를 추가했습니다.
  - 이전 단계에서 일시 다운그레이드했던 `coreKtx` 버전을 SDK 37 지원 최고 버전인 **`1.19.0`**으로 안전하게 복구했습니다.
  - 프로젝트 전체 컴파일 및 로컬 단위 테스트가 완벽히 성공하는 빌드 안정성을 확보했습니다.

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
| `cf6f3fb` | feat: 국소 마커 재탐색(Local Rerouting) 및 마커 보존 기능 구현 |
| `002508c` | fix: AndroidManifest.xml에 앱 런처 아이콘 지정 및 아이콘/라인 두께 UI 개선 |
| `f2e3a89` | feat: Firebase RTDB 기반 기기 고유 식별자 해싱 및 일일 API 호출 추적 레포지토리 구축 |
| `a4accdd` | feat: Firebase RTDB 기반 기기 식별자 API 일일 호출 제한 및 광고 충전 기능 구현 |
| `c832bdd` | feat: 상단 햄버거 드로어 도입 및 하단 통합 조작계(거리/시간 통계 통합) UI/UX 전면 개편 |
| `c828aea` | fix: 지도 드래그-드로어 제스처 간섭 해결 및 API 한도 초되 시 UI 데드락 수정 |
| `d148507` | feat: 그리기 모드 중 지도를 드래그/확대 조작할 수 있는 지도 이동 모드 토글 도입 |
| `2a33471` | fix: IDLE, DRAWING, PROCESSING 상태 간의 레이아웃 세로 높이를 36dp로 통일하여 Layout Shift 방지 |
| `c0004d1` | chore: compileSdk 35/AGP 8.7.3 호환 라이브러리 및 빌드 의존성 일괄 최신화 |
| `e9f3248` | fix: API 한도 도달 시 UI 무한 대기 프리징 버그 수정 (when 식 패턴 매칭 도입) |
| `139d6f2` | chore: compileSdk/targetSdk 36 상향 및 의존성 라이브러리(BOM 2026.05.01 등) 일괄 최신화 |
| `563c2cf` | chore: AGP 8.9.1 및 Gradle 8.12 마이그레이션 (임시 바이패스 제거 및 core-ktx 1.15.0 조정) |
| `736fd84` | chore: AGP 8.13.2 및 Gradle 8.13 마이그레이션 (coreKtx 1.18.0 상향 포함) |
| `3520eed` | chore: compileSdk/targetSdk 37 상향, AGP 9.2.1, Gradle 9.4.1, Hilt 2.59.2 및 coreKtx 1.19.0 업그레이드 |
| `timber` | chore: Timber 로깅 라이브러리 추가 및 디버그 빌드 한정 초기화 설정 |


---

## UI/UX 사용성 개선 (2026-06-06)

검토에서 도출한 사용성 항목을 일괄 반영. Orbit MVI 순수성·메인스레드 보호·diff 안정성 준수.

### 변경 요약
- **데이터 손실 방지**: DONE 모드 `지우기`/`다시 그리기`에 확인 다이얼로그 추가 (MapScreen).
- **파괴적 제스처 제거**: 마커 재탭을 삭제→선택 해제로 변경. 삭제는 휴지통 버블로 일원화 (MapViewModel.onMarkerTapped).
- **API 한도 가시성**: 우측 상단 카드를 프로덕션에도 노출(남은 횟수), 잔여 5회↓ 주황·0회 빨강 경고. Naver 진단 행은 DEBUG 전용 (MapScreen).
- **설정 추상화**: epsilon degree 슬라이더 → 약하게/보통/강하게 3단계 라벨화 (MapScreen + DRAWN/ROUTE_LEVELS 매핑).
- **위치 권한 복구**: 드로어 "현재 위치로 이동" 항목 추가, 거부 시 안내 토스트 (MapScreen).
- **테마 통일**: 편집 강조 주황(0xFFE65100)을 brand primary로 승격 (Theme.kt).
- **첫 실행 온보딩**: SharedPreferences 플래그로 최초 1회 도움말 자동 표시(IO 디스패처) (MapScreen).
- **미적용 편집 단서**: hasPendingEdits 시 경로를 앰버색으로 표시 (MapScreen PathOverlay).
- **PROCESSING 취소**: snapGeneration 토큰으로 in-flight 무효화, BottomControls에 취소 버튼 (MapViewModel/BottomControls).
- **접근성/로케일**: 거리/시간 String.format에 Locale 명시, BottomControls 아이콘 contentDescription 부여.

### 후속 과제(의도적 보류)
- 전체 문자열 strings.xml 리소스화: 906줄 파일 대규모 diff/회귀 위험으로 점진 이관 권장. Locale 처리로 i18n 실질 결함은 해소.
- 마커 색약 형태 구분: 현재 S/G 라벨로 완화. 추후 형태(아이콘) 차별화 검토.

### 검증
- `./gradlew.bat :app:compileDebugKotlin` BUILD SUCCESSFUL.

---

## GPX 호환성 보강 (2026-06-13)

`implementation_plan.md` 기반. 그린 코스를 트랭글·Strava·Garmin 등에서 "동일 형태"로
임포트하도록 GPX 출력 품질·완결성을 보강했다.

### 변경
- **신규** `domain/util/GeoUtils.kt` — 공용 haversine (ExportGpx 타임스탬프 계산용)
- **신규** `domain/model/GpxExportOptions.kt` — includeTimestamps / paceSecPerKm / startTimeMillis
- **개편** `domain/usecase/ExportGpxUseCase.kt`
  - `<metadata>`(name/time/bounds) 블록 추가
  - 좌표 `String.format(Locale.US, "%.6f", v)` — 6자리, 로케일 무관
  - `<time>` 타임스탬프: 거리 기반 등속(점 밀도 무관), `includeTimestamps` 토글 시에만 출력
  - 시각 포맷 `Instant.truncatedTo(SECONDS)` + `ISO_INSTANT`
- **수정** `MapState` — includeTimestamps(기본 false) / runningPaceSecPerKm(기본 360=6:00/km)
- **수정** `MapViewModel.onGpxUriSelected` — 빈 경로 가드(size<2), GPX 빌드 Dispatchers.Default 분리
- **수정** `MapScreen` 설정 다이얼로그 — 타임스탬프 Switch + 페이스(분:초, 4:00~8:00) 입력

### 의도적 제외 (결정)
- `<ele>` 고도: T-Map 미제공 + 무료 API 비상업 약관 리스크 → 소비 앱 DEM 위임, 향후 옵션화
- `<rte>`/`<wpt>`, 기존 haversine 4중 중복 전면치환, OSRM 폴백: 범위 외

### 검증
- `:app:compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL
- `ExportGpxUseCaseTest` 5/5 통과 (기본출력·bounds·타임스탬프·로케일·XML escape)
- 감사 중 발견한 계획 외 변경(`markerIcon`의 `isFakeBoldText` 삭제) 되돌림

---

## 코드 검토 후속 조치 (2026-07-05)

`code_review.md` 전면 검토(2026-07-05) 결과 중 심각도 높음 5건 및 API 절감 우선순위 상위 3건을 반영했다.

### 버그 수정 (심각도 높음)
- **`hasPendingEdits` 초기화 누락**: 지우기·다시 그리기·새 스냅 성공 시 `hasPendingEdits`/`dirtyStart`/`dirtyEnd`를 리셋하지 않아 방금 만든 깨끗한 경로가 "수정 대기"로 잘못 표시되던 문제 수정 (`MapViewModel`).
- **API 카운트 단위 불일치**: `RouteRepository.PedestrianRoute(points, apiCallCount)` / `SnapToRoadUseCase.SnapResult(route, apiCallCount)`로 실제 T-Map HTTP 청크 호출 수를 끝까지 전달, `IncrementApiCountUseCase(count)`로 정확히 반영. 취소된 stale 요청도 실제 호출은 이미 발생했으므로 gen 체크 이전에 카운트.
- **자기교차 경로 splice 오매칭 완화**: `spliceRoute`(ViewModel), `onApplyEdits`의 toIdx 탐색, `MapScreen.segmentize`를 "이전 지점 이후 구간에서만 탐색"하도록 수정해 그림 경로의 자기교차 지점에서 반대편 통과 지점과 오매칭되는 문제 완화(완전한 근본 해결은 인덱스 기반 상태 추적이 필요해 범위 외로 남김).
- **연타 시 중복 T-Map 호출 방지**: `snapInFlight`/`applyInFlight` volatile 플래그로 `snapCurrentPath`/`onApplyEdits` 재진입 차단.
- **DeviceUsage 원자성·영속성**: `DeviceUsageRepository.incrementDailyCount()`를 Firebase `runTransaction`으로 원자화, `localFallbackUsage`를 SharedPreferences(`mapsnap_usage_prefs`)에 영속화해 오프라인 시 앱 재시작으로 인한 한도 우회 가능성 축소.

### API 절감 / 반복 경로 방지
- **다획 배치 스냅**: 손가락을 뗄 때마다(스트로크마다) 즉시 스냅하던 방식을 변경 — 각 스트로크는 `MapState.pendingStrokes`에 로컬로만 적립되고, "완료" 버튼(`finishDrawing`)에서 모든 스트로크를 이어붙여 **1회만** T-Map을 호출한다. 다획 그림에서 API 호출 수가 획 수에 비례해 늘어나던 문제를 해소. `DrawingOverlay`는 완료 전 스트로크도 계속 화면에 표시해 사라진 것처럼 보이지 않도록 함.
- **왕복 스퍼(반복 경로) 후처리**: `SnapToRoadUseCase.removeSpurs()` 추가. T-Map이 경유지 통과를 위해 만드는 "막다른 길 왕복" 구간을 이동거리/직선거리 비율로 탐지해 제거하되, 사용자가 실제로 지정한 웨이포인트 근처는 보존해 의도된 왕복 획은 유지.
- **청크 경계 트리밍 조건부화**: `RouteRepositoryImpl`의 무조건 25m 트리밍을 `trimBoundaryArtifactIfLooping()`으로 교체 — 끝부분이 실제로 되돌아오는 루프(직선거리가 이동거리의 절반 이하)일 때만 트리밍하고, 단순 전진 구간은 그대로 두어 불필요한 직선 점프 생성을 방지.

### 검증
- `:app:compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL

---

## 코드 검토 후속 조치 2차 (2026-07-05)

`code_review.md` 중간 심각도 잔여 항목 중 4건을 중요도 순으로 추가 반영했다.

- **CancellationException 처리 (2-6)**: `RouteRepositoryImpl.getPedestrianRoute`의 `runCatching`이 취소 예외까지 삼키던 것을 `.also { }`에서 재던지도록 수정. `MapViewModel.onGpxUriSelected`의 광범위 `catch (e: Exception)`도 동일하게 재던짐 추가. 구조적 코루틴 취소가 예외 삼킴으로 깨지지 않도록 함.
- **근접 웨이포인트 병합 (2-3)**: `RouteRepositoryImpl`의 dedupe를 완전 일치(`!=`)에서 거리 기반(`MIN_WAYPOINT_SPACING_METERS = 10.0`)으로 변경. 실제 목적지(원본 마지막 웨이포인트)는 병합으로 소실되지 않도록 별도 보존 로직 추가. 청크 수 절감 및 미세 왕복 방지에 기여.
- **마커 아이콘 비트맵 캐싱 (2-7)**: `MapScreen.markerIcon()`을 (타입, 라벨, 선택 여부) 키의 파일 스코프 캐시로 감싸, 드래그 중 매 프레임 비트맵을 재생성하던 GC 압박 제거.
- **Compose 리컴포지션 최적화 (2-8)**: `segmentize()` 호출을 `remember(snappedRoute, routeMarkers)`로 감싸고, `state.totalDistanceMeters` getter 호출도 `remember(snappedRoute)`로 감싸 무관한 리컴포지션(마커 선택, 지도 타입 전환 등)에서의 재계산을 방지.

### 검증
- `:app:compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL

---

## 코드 검토 후속 조치 3차 (2026-07-05)

`code_review.md` 2-4(dirty range 리스트화)를 추가 반영했다. 서로 멀리 떨어진 편집 클러스터가 있을 때 그 사이 미편집 구간까지 재라우팅에 끌려들어가던 문제를 해소하는 구조 변경.

- **`MapState`**: `dirtyStart: Int?`/`dirtyEnd: Int?` 단일 스팬을 `dirtyRanges: List<IntRange>`(편집 클러스터별 구간 목록)로 교체. `EditSnapshot`도 동일하게 변경해 Undo가 클러스터 목록 전체를 복원하도록 함.
- **`MapViewModel.mergeDirtyRange`**: 새로 편집된 관리 포인트 인덱스를 인접(버퍼 2 이내) 기존 구간과 병합하거나, 멀리 떨어져 있으면 새 구간으로 추가. 마커 드래그/지도 탭 이동 계열 함수들(`onMarkerDragged`, `onStartMarkerDragged`, `onEndMarkerDragged`, `onMapTapped`)이 이 헬퍼로 통일됨.
- **`MapViewModel.shiftDirtyRangesAfterRemoval`**: 마커/구간 삭제로 뒤쪽 인덱스가 당겨질 때 기존 dirtyRanges 전체를 보정하고 삭제 지점의 새 이음매를 병합 (`onDeleteMarkerConfirmed`, `onDeleteSegmentConfirmed`).
- **`onApplyEdits` 재작성**: 단일 min~max 웨이포인트 추출 대신, 클러스터별로 별도 T-Map 호출(`snapToRoad.fromWaypoints`)을 수행. 여러 구간을 처리할 때는 **끝 인덱스가 큰 구간(뒤쪽)부터** 적용해, 앞쪽(왼쪽) 구간의 원본 인덱스가 뒤쪽 구간의 스플라이스로 무효화되지 않도록 함. 실패한 구간이 있으면 전체를 롤백(부분 반영 없음)해 상태 일관성을 유지. API 호출 수는 클러스터별 합산 후 한 번에 반영.
- 타입 버그 수정: `List<IntRange> + IntRange(...)`가 IntRange 자체의 `Iterable<Int>` 특성 때문에 원소 스프레드로 오인되어 `List<Any>`로 추론되는 Kotlin 함정 발견 → `listOf(...)`로 감싸 명시적 단일 원소 추가로 수정.

### 검증
- `:app:compileDebugKotlin testDebugUnitTest` BUILD SUCCESSFUL

### 남은 과제 (code_review.md 기준)
- 3장 정리 대상 전반(미사용 `onMapTapped`, 릴리즈 로그 노출, Firebase 보안 규칙 확인, `editHistory` 무제한 누적, `onDrawPoint` O(n²) 등).
