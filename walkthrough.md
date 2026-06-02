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
- **드래그 앤 드롭 이동(Drag-to-Move)**: 
  - 선택된 마커 위에 Compose 오버레이를 통해 큰 터치 대상 영역(`64dp`)을 가진 드래그 핸들을 배치하여 가시성 및 터치 조작성을 대폭 강화합니다. (마커 지름은 80px로 2배 확대)
  - 사용자가 드래그를 진행할 때 Compose의 `detectDragGestures` 및 지도 `Projection`을 연동해 실시간 좌표를 추출하고 뷰모델 상태를 갱신합니다.
- **T-Map 호출 최적화 (Debounce)**: 
  - 드래그 동작이 멈춘 후 **300ms 디바운스** 딜레이 뒤에 최종 1회의 국소 재라우팅(`handlePartialReroute`) API만 호출되도록 이벤트를 결합(debounce)합니다.
- **포인트 증식 및 위치 변동 방지**:
  - 재라우팅 완료 시 기존 마커 목록을 전체 재생성하지 않고 **기존 리스트를 보존한 채 드래그하여 움직인 마커 1개만 신규 경로 상의 가장 가까운 지점으로 보정 스냅** 처리합니다. 이로 인해 마커 개수가 절대 증식하지 않고 다른 마커들이 원래 위치를 온전히 유지합니다.

### 출발 및 도착 마커 (최종 포인트 최적화)

- **출발(S) 및 도착(G) 마커 시각화**: 
  - 경로의 양 끝 최종 지점에 각각 초록색 바탕의 `S`(출발) 마커와 빨간색 바탕의 `G`(도착/종료) 마커가 표시됩니다.
  - 선택 시 중간 마커와 동일하게 오렌지색 하이라이트가 부여되고 드래그 조절 패널이 노출됩니다.
- **최종 포인트 드래그 시 경로 단축**:
  - **도착 마커(G)**를 원래 종료 지점보다 더 짧은 위치로 이동시키면 `routeEnd`가 갱신되며, 마지막 중간 마커에서 신규 도착지까지의 경로만 재탐색(Reroute)합니다. 기존에 목적지 통과 후 이전의 원래 종점으로 회귀하는 불필요한 경로(루프)가 깔끔하게 사라지며 **경로가 정상적으로 단축**됩니다.
  - **출발 마커(S)**를 드래그하는 경우에도 동일하게 `routeStart`가 갱신되어 출발부 경로가 정상 단축 및 변경됩니다.


### 구간 선택 삭제

- `segmentize(snappedRoute, routeMarkers)` → 마커 위치 기준으로 경로 분할
- 각 구간 별도 `PathOverlay` (탭 가능)
- 구간 탭 → 주황 하이라이트 + AlertDialog("구간 삭제")
- 삭제 확인 → 경계 마커(`markers[min(segIdx, markers.lastIndex)]`) 제거 → 재라우팅

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

## 파일 목록

| 파일 | 역할 |
|------|------|
| `SimplifyPathUseCase` | RDP 알고리즘, cosLat 보정, epsilon 파라미터 노출 |
| `SnapToRoadUseCase` | 입력 RDP → 샘플링 → API → 출력 RDP 파이프라인, `fromWaypoints()` |
| `RouteRepositoryImpl` | T-Map 청크 분할 호출, 경계 트리밍, 중복 좌표 제거 |
| `MapState` | DrawingMode, drawnPoints, simplifiedPoints, snappedRoute, routeMarkers, 선택 상태 등 |
| `MapViewModel` | 모든 인텐트, sampleMarkers, rerouteWithMarkers, isContinuing 분기 |
| `DrawingOverlay` | Canvas 기반 손그림 · 직선화 오버레이, 스냅존 원 |
| `MapScreen` | NaverMap Compose, segmentize, PathOverlay/Marker 렌더링, AlertDialog |
| `BottomControls` | 그리기 / 스냅 / 이어 그리기 / 지우기 FAB |

---

## Git 히스토리

| 커밋 | 내용 |
|------|------|
| `73bfb7e` | 초기 커밋 — T-Map 스냅, RDP, 루프 연결, 마커 편집 전체 구현 |
| `a75f758` | feat: 기존 경로에 이어 그리기 기능 추가 |
| `bf80454` | feat: 구간 탭으로 삭제 기능 추가 |
| `db4921f` | feat: 출발/도착 마커(S/G) 도입 및 최종 포인트 드래그 이동 시 경로 단축 최적화 |
