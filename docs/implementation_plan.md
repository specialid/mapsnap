# GPX 호환성 보강 구현 계획

## 목표
지도에 그린 코스를 GPX로 내보내, **트랭글(1순위) · Strava · Garmin Connect** 등 국내외 러닝 가이드 앱에서
"동일한 형태"로 임포트·표시되도록 GPX 파일의 호환성·완결성을 끌어올린다.

## 호환성 기준 (적용 항목)
1. 좌표 정밀도 정리 (소수 6자리, Locale 무관) — 파일 크기·가독성, 가짜 정밀도 제거
2. `<metadata>` 블록 (name / time / bounds) — GPX 1.1 권장, 엄격 파서 대비
3. `<time>` 타임스탬프 (거리 기반 등속) — 트랙 시각을 요구하는 앱 대비, **토글 옵션(기본 OFF)**
4. 빈 경로 내보내기 가드 — 무효 GPX(빈 trkseg) 방지
5. haversine 공용화 (`GeoUtils`) — 신규 사용처 한정 도입

## 고도(`<ele>`) — 이번 범위 제외 (결정)
- T-Map 보행자 경로안내 API는 좌표가 2D(`[lng, lat]`)뿐 → 고도 미제공. 별도 DEM 소스 필수.
- 무료 후보(Open-Meteo)는 **비상업 한정** 약관이라 광고 수익 앱엔 리스크. 상용 안전 소스는 결제 등록 필요.
- 트랭글·Strava·Garmin 모두 `<ele>` 없어도 임포트 후 **자체 DEM으로 고도 프로파일 생성** → 호환성 비치명적.
- 결론: **1차 배포는 고도 생략(소비 앱 DEM 위임).** 추후 필요 시 Google Elevation 등으로 옵션 추가(향후 과제).

> **타입 결정:** `<trk>/<trkseg>/<trkpt>` 유지. 트랭글·Strava·Garmin 모두 트랙을 코스로 수용하므로
> `<rte>` 병행은 도입하지 않는다(혼선 방지). `<wpt>`(편집 마커 큐) 역시 호환성 필수가 아니므로 제외.

---

## 대상 파일

### 신규
- `domain/util/GeoUtils.kt` — 공용 haversine
- `domain/model/GpxExportOptions.kt` — 내보내기 옵션 값 객체

### 수정
- `domain/usecase/ExportGpxUseCase.kt` — 시그니처 개편 + metadata/time/정밀도
- `presentation/map/MapViewModel.kt` — `onGpxUriSelected` 빈 경로 가드 + 옵션 전달
- `presentation/map/MapState.kt` — 내보내기 옵션 상태 추가
- `presentation/component/MapScreen.kt` (설정 다이얼로그) — 타임스탬프 토글 + 페이스 입력

> 고도 관련 신규(Elevation DTO/Service/Repository/UseCase) 및 DI 변경은 **없음**.

---

## 1. GeoUtils (신규)
`domain/util/GeoUtils.kt`
```
object GeoUtils {
    fun haversineMeters(a: LatLng, b: LatLng): Double   // 기존 구현 그대로 이동 (R = 6_371_000.0)
}
```
- ExportGpxUseCase의 타임스탬프 거리 계산에 사용.
- 기존 4중 중복(RouteRepositoryImpl / SnapToRoadUseCase / MapViewModel / MapState) 전면 치환은
  디프 안정성 위해 **본 계획에서 강제하지 않음**(후속 리팩토링으로 분리, TODO 주석만 표기).

---

## 2. 내보내기 옵션 — `domain/model/GpxExportOptions.kt`
```
data class GpxExportOptions(
    val includeTimestamps: Boolean = false,   // 기본 OFF (코스 임포트엔 불필요)
    val paceSecPerKm: Int = 360,              // 6:00/km
    val startTimeMillis: Long = System.currentTimeMillis()
)
```

---

## 3. ExportGpxUseCase 개편

### 3-1. 시그니처
```
operator fun invoke(
    route: List<LatLng>,
    options: GpxExportOptions = GpxExportOptions(),
    routeName: String = "MapSnap Route"
): String
```
- 순수 함수 유지(시간 계산은 옵션의 startTimeMillis 기반, 외부 I/O 없음).

### 3-2. 출력 구조
```
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="MapSnap" xmlns=... xsi=... schemaLocation=...>
  <metadata>
    <name>{escaped}</name>
    <time>{startTime ISO-8601 Z}</time>
    <bounds minlat=".." minlon=".." maxlat=".." maxlon=".."/>
  </metadata>
  <trk>
    <name>{escaped}</name>
    <trkseg>
      <trkpt lat="37.123456" lon="127.123456">
        <time>2026-..Z</time>      <!-- includeTimestamps 일 때만 -->
      </trkpt>
      ...
    </trkseg>
  </trk>
</gpx>
```
- `<bounds>`: route 순회로 min/max lat·lon 계산.
- name escape 로직(`&`,`<`,`>`,`"`,`'`)은 기존 그대로 유지하되 별도 private 헬퍼로 추출해 metadata·trk 양쪽 재사용.

### 3-3. 정밀도/포맷 (Locale 안전)
- 좌표: `String.format(Locale.US, "%.6f", v)` — '.' 소수점 보장.
- 시각: `java.time.Instant` + `DateTimeFormatter.ISO_INSTANT`, 초 단위 절삭.
  - `Instant.ofEpochMilli(t).truncatedTo(ChronoUnit.SECONDS)` → `2026-06-13T09:00:00Z`

### 3-4. 타임스탬프 알고리즘 (거리 기반 등속)
점 간격 불균일 → **점당 고정 간격 금지**, 누적 거리 비례 배분:
```
val pace = options.paceSecPerKm.coerceAtLeast(1)   // 방어
val speed = 1000.0 / pace                           // m/s
var cum = 0.0
// i = 0 → startTimeMillis
for (i in 1 until route.size) {
    cum += GeoUtils.haversineMeters(route[i-1], route[i])
    val tMillis = options.startTimeMillis + (cum / speed * 1000).toLong()
}
```
- includeTimestamps=false면 `<time>` 자체를 출력하지 않음(자식 없는 `<trkpt .../>`).

---

## 4. MapViewModel.onGpxUriSelected

### 4-1. 빈 경로 가드 (선두 추가)
```
if (state.snappedRoute.size < 2) {
    postSideEffect(MapSideEffect.ShowToast("내보낼 경로가 없습니다"))
    return@intent
}
```

### 4-2. 옵션 전달 + 스레딩 분리
```
reduce { state.copy(isProcessing = true) }
val route = state.snappedRoute
val options = GpxExportOptions(
    includeTimestamps = state.includeTimestamps,
    paceSecPerKm = state.runningPaceSecPerKm,
    startTimeMillis = System.currentTimeMillis()
)
val gpxString = withContext(Dispatchers.Default) { exportGpx(route, options) }
withContext(Dispatchers.IO) { /* 기존 contentResolver.openOutputStream write 로직 유지 */ }
reduce { state.copy(isProcessing = false) }
postSideEffect(MapSideEffect.ShowToast("GPX 파일이 성공적으로 저장되었습니다"))
```
- 기존 try/catch 예외 처리 구조 유지.

---

## 5. MapState 추가 필드
```
val includeTimestamps: Boolean = false,
val runningPaceSecPerKm: Int = 360,   // 6:00/km
```
- `onUpdateSettings(...)` 확장: 기존 interval/epsilonDrawn/epsilonRoute에
  `includeTimestamps`, `runningPaceSecPerKm` 2개 인자 추가 → 호출부 동기 수정.

---

## 6. 설정 다이얼로그 (MapScreen.kt)
기존 설정 다이얼로그(간격/epsilon 조정)에 추가:
- **`타임스탬프 포함` 스위치(`Switch`)** — `includeTimestamps`
  - 보조 설명: "일부 앱이 시각 정보를 요구할 때만 켜세요(가짜 기록처럼 보일 수 있음)"
- **러닝 페이스 입력** — 타임스탬프 ON일 때만 노출
  - 분/킬로 단위(예: 6:00), 4:00~8:00 범위, 초 환산해 `runningPaceSecPerKm` 저장
- 적용 시 `onUpdateSettings(...)`로 전달.

---

## 7. 검증 (단위 테스트)
신규 `ExportGpxUseCaseTest`:
1. 기본(옵션 OFF): `<metadata>`·`<bounds>` 포함, 좌표 6자리, `<trkpt>`에 `<time>` 없음.
2. `<bounds>` min/max lat·lon 정확성.
3. 타임스탬프 ON: 마지막 trkpt 시각 ≈ `startTime + 총거리/speed` (±1s), 첫 점 == startTime.
4. Locale 무관: 기본 로케일을 콤마 소수점 로케일로 바꿔도 좌표/시각이 '.'·ISO 형식 유지.
5. 이름 escape: 특수문자(`&`,`<`,`>`) 정상 치환.

---

## 비대상 (이번 범위 제외)
- `<ele>` 고도 (위 "고도 제외 결정" 참조 — 향후 Google Elevation 등 옵션화)
- `<rte>/<rtept>` 병행 출력 (트랙으로 충분)
- `<wpt>` 편집 마커 큐 내보내기 (호환성 필수 아님)
- 기존 haversine 4중 중복 전면 치환 (디프 안정성 위해 후속 분리)
- OSRM 폴백 / 미사용 `OsrmResponse.kt` 처리 (라우팅 전략 별건)

---

## 작업 순서
1. GeoUtils → GpxExportOptions → ExportGpxUseCase 개편 + 단위테스트 (I/O 무관, 선검증)
2. MapState/onUpdateSettings/설정 다이얼로그 옵션 UI
3. MapViewModel.onGpxUriSelected 빈 경로 가드 + 옵션 전달
4. 빌드 후 트랭글/Strava 임포트 실측
