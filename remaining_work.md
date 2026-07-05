# MapSnap 남은 작업 목록 (2026-07-05 기준)

`code_review.md`(2026-07-05 최초 작성) 중 **심각도 높음 5건 · 심각도 중간 8건은 모두 수정 완료**되었다
(자세한 수정 내역은 `walkthrough.md`의 "코드 검토 후속 조치 1~3차" 참고).

이 문서는 **아직 손대지 않은 심각도 낮음/정리 대상 항목**만 모아 재검증하고,
현재 코드 기준 정확한 위치와 구체적인 조치 방법을 정리한 것이다. 코드는 수정하지 않았다.

우선순위는 "리스크 대비 수정 비용"이 낮은 순으로 배치했다 — 대부분 5~15분 내로 끝나는 국소 수정이다.

---

## 즉시 고쳐도 안전한 것들 (독립적, 부작용 없음)

### R-1. 삭제 확인 다이얼로그가 닫히지 않고 멈추는 경로
- 위치: [MapViewModel.kt:335](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:335) (`onDeleteMarkerConfirmed`), [MapViewModel.kt:458](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:458) (`onDeleteSegmentConfirmed`)
- 두 함수 모두 `idx`(또는 `segIdx`) 유효성 검사 실패 시엔 `showDeleteMarkerDialog`/`showDeleteSegmentDialog`를 `false`로 되돌리지만, 그 다음 줄의 `val start = state.routeStart ?: return@intent` 가드에서 조기 반환하면 다이얼로그 플래그를 정리하지 않는다.
- 재현 조건: 인덱스는 유효한데 `routeStart`/`routeEnd`가 `null`인 상태 — 정상 플로우에서는 거의 발생하지 않지만(경로가 있으면 항상 start/end도 존재), 만약 다른 버그나 레이스로 이 상태에 도달하면 삭제 확인 다이얼로그가 화면에 뜬 채로 아무 버튼도 반응하지 않는 것처럼 보일 수 있다(정확히는 확인 버튼을 눌러도 다이얼로그가 안 닫힘).
- 조치: `val start = state.routeStart ?: run { reduce { state.copy(showDeleteMarkerDialog = false) }; return@intent }` 형태로 가드마다 다이얼로그 정리 추가. 두 함수 공통.
- 비용: 5분. 리스크: 없음(순수 방어 코드 추가).

### R-2. `nearestLevelIndex` KDoc과 실제 반환 범위 불일치
- 위치: [MapScreen.kt:121](app/src/main/java/com/jason/mapsnap/presentation/component/MapScreen.kt:121) (주석), 함수 본문은 122행
- 주석: `/** 현재 degree 값에 가장 가까운 단계 인덱스(0..2) 반환 */` — 실제로는 `DRAWN_LEVELS`/`ROUTE_LEVELS`가 5단계(인덱스 0..4)라 반환 범위는 0..4.
- 조치: 주석 텍스트를 "0..4"로 수정.
- 비용: 1분. 리스크: 없음(주석만 변경).

### R-3. GPX 타임스탬프 테스트의 가드가 실패를 가려서 통과시킴
- 위치: [ExportGpxUseCaseTest.kt:110](app/src/test/java/com/jason/mapsnap/ExportGpxUseCaseTest.kt:110) (`testTimestampsCalculation`)
- `if (trkptBlock.contains("<time>")) { ... assert ... }` 구조라, `includeTimestamps=true`로 호출했음에도 실제 출력에 `<time>` 태그가 전혀 없는 회귀가 생겨도 테스트는 **그대로 통과**한다(assert 자체가 실행되지 않으므로).
- 조치: 가드를 없애고 `assertTrue(trkptBlock.contains("<time>"))`를 먼저 단정한 뒤 시각값을 비교하도록 변경. 즉 "타임스탬프가 있어야 한다"는 조건 자체를 검증 대상에 포함시켜야 한다.
- 비용: 5분. 리스크: 없음(테스트 파일만 변경, 프로덕션 코드 영향 없음). 다만 현재 프로덕션 로직이 정상이라면 이 수정 후에도 테스트는 그대로 통과해야 한다(사전에 로컬 실행으로 확인 필요).

### R-4. 미사용 데드코드 정리
- 위치:
  - [MapViewModel.kt:496-570](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:496) — `onMapTapped()` 전체(약 75줄). `MapScreen.kt`의 `onMapClick`은 `onMarkerDeselect()`만 호출하며 `onMapTapped`를 부르는 곳이 없다.
  - [MapViewModel.kt:21-23](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:21) — 미사용 import 3개: `BufferOverflow`, `MutableSharedFlow`, `flow.debounce`.
  - [MapViewModel.kt:71-72](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:71) — 미사용 상수 `REROUTE_DEBOUNCE_MS`, `DEFAULT_INTERVAL_METERS`.
  - [MapSideEffect.kt:5](app/src/main/java/com/jason/mapsnap/presentation/map/MapSideEffect.kt:5) — `RequestLocationPermission`을 `postSideEffect`로 발행하는 곳이 없다(위치 권한 흐름은 `MapScreen`이 직접 `rememberLauncherForActivityResult`로 처리).
- 주의: `onMapTapped`는 도움말 다이얼로그([MapScreen.kt:830](app/src/main/java/com/jason/mapsnap/presentation/component/MapScreen.kt:830) 부근 "지도 위에 선을 그리면…" 문구)와 직접 연관은 없으나, 삭제 전에 향후 "지도 탭으로 마커 이동" 기능을 되살릴 계획이 있는지 확인 필요. 없다면 삭제, 있다면 `MapScreen`에 실제 호출부를 연결하는 쪽으로 방향을 정해야 한다.
- 비용: 10분(단순 삭제 시). 리스크: 낮음(컴파일 확인 필수 — import 제거 시 다른 곳에서 안 쓰는지 재확인).

---

## 판단이 필요한 것들 (동작에 영향 있거나 취향 판단 필요)

### R-5. 릴리즈 빌드에서도 위치 로그가 남음
- 위치: `android.util.Log.d/e/w` 직접 호출 다수 — [MapViewModel.kt](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt), [RouteRepositoryImpl.kt](app/src/main/java/com/jason/mapsnap/data/repository/RouteRepositoryImpl.kt), [DeviceUsageRepositoryImpl.kt](app/src/main/java/com/jason/mapsnap/data/repository/DeviceUsageRepositoryImpl.kt) 등.
- `Timber`가 이미 의존성으로 추가되어 있고 디버그 빌드에서만 초기화되도록 설정되어 있으나(App.kt), 실제 로그 호출은 여전히 `android.util.Log`를 직접 쓴다. 즉 Timber를 도입한 의미가 없고, **릴리즈 빌드에서도 좌표·경로 등 위치 데이터가 logcat에 노출**된다.
- 조치안:
  - (A) 전면 치환: `Log.d/e/w` → `Timber.d/e/w`로 일괄 교체. Timber는 디버그 빌드에서만 `Timber.plant()`되므로 릴리즈에서는 자동으로 무음 처리됨.
  - (B) 절충안: 좌표가 포함된 로그(`RouteRepositoryImpl`의 시작/끝 좌표, `MapViewModel`의 `SnapDebug` 로그 등)만 우선 치환.
- 비용: (A) 30분 내외(치환 후 빌드 확인), (B) 10분.
- 권장: (A). 이미 Timber가 설치돼 있으므로 완성하는 편이 일관성 있다.

### R-6. haversine 4중 중복
- 위치: [GeoUtils.kt:10](app/src/main/java/com/jason/mapsnap/domain/util/GeoUtils.kt:10) (공용 구현), 그리고 각자 동일 로직을 사설로 보유한 곳 — [MapViewModel.kt](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt) 내부 private fun, [MapState.kt](app/src/main/java/com/jason/mapsnap/presentation/map/MapState.kt) 파일 스코프 private fun, [SnapToRoadUseCase.kt](app/src/main/java/com/jason/mapsnap/domain/usecase/SnapToRoadUseCase.kt) private fun, [RouteRepositoryImpl.kt](app/src/main/java/com/jason/mapsnap/data/repository/RouteRepositoryImpl.kt) private fun.
- `implementation_plan.md`(GPX 개편 계획서)에 "후속 리팩토링으로 분리, 디프 안정성 위해 이번엔 강제하지 않음"이라고 명시된 의도적 보류 사항이다.
- 조치: `GeoUtils.haversineMeters()`로 전부 치환. 로직이 100% 동일하므로 기능 변화 없음 — 순수 리팩토링.
- 비용: 15분(4개 파일 import 정리 + 호출부 치환 + 빌드 확인).
- 권장 시점: 이번 코드 검토 후속 작업이 어느 정도 안정화된 지금이 적기. 미룰수록 새 코드가 또 복제할 위험이 있음(예: `SnapToRoadUseCase.removeSpurs()`도 이번에 자체 haversineMeters를 그대로 재사용했음).

### R-7. `editHistory` 무제한 누적
- 위치: [MapState.kt:44](app/src/main/java/com/jason/mapsnap/presentation/map/MapState.kt:44) 부근 `editHistory: List<EditSnapshot>`
- 각 스냅샷은 `routeMarkers`+`snappedRoute`(좌표 리스트, 잠재적으로 수백~수천 점) 전체 사본을 보유한다. 사용자가 한 세션에서 편집을 많이 반복하면(특히 마커 드래그를 여러 번) 메모리가 계속 늘어난다.
- 조치: `onDragStart`/`onMarkerDragged` 계열에서 히스토리에 push할 때 `(state.editHistory + snapshot).takeLast(20)` 형태로 상한을 둔다. Undo는 어차피 마지막 항목부터 순차 소비하므로 오래된 항목을 버려도 사용성에 지장 없음.
- 비용: 10분(히스토리 추가하는 지점 4~5곳에 `.takeLast(N)` 적용).
- 주의: 이번 2-4(dirty range 리스트화) 작업으로 `EditSnapshot`이 `dirtyRanges: List<IntRange>`도 함께 보유하게 되어 스냅샷 크기가 약간 더 커졌다 — 상한 적용의 필요성이 이전보다 소폭 높아짐.

### R-8. `onDrawPoint`의 O(n²) 리스트 복사
- 위치: [MapViewModel.kt:245](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:245) 부근 `onDrawPoint(point: LatLng)`
- `state.drawnPoints + point`를 손가락이 움직일 때마다(터치 이벤트 수십~수백 회) 호출 — 매번 전체 리스트를 복사하므로 스트로크가 길어질수록 프레임당 비용이 커진다(O(n²) 누적).
- 조치안:
  - (A) `MapState.drawnPoints`를 `List<LatLng>` 대신 append 전용 구조(예: `persistentListOf` — kotlinx.collections.immutable 의존성 추가 필요)로 변경.
  - (B) ViewModel 내부에 그리기 전용 mutable 버퍼를 두고, DrawingOverlay가 이미 로컬로 `drawnLatLngs`(mutableStateListOf)를 들고 있으니 **ViewModel의 `drawnPoints`는 스트로크 종료 시(`onDrawEnd`)에만 한 번 채우고, 그리기 도중에는 State에 매 포인트를 반영하지 않는 방식**으로 구조를 바꾼다. 단, 이렇게 하면 `PROCESSING` 진입 전 RDP 미리보기 등 현재 UX가 손그림 원본이 아니라 스트로크 종료 시점에만 반영되므로 시각적 차이는 없음(원본 손그림은 이미 DrawingOverlay 로컬 상태로 렌더링되고, ViewModel의 drawnPoints는 스냅 계산에만 쓰이기 때문).
- 비용: (A) 20분 + 의존성 추가, (B) 15분(리스크 재검토 필요 — `onDrawStart`/`onDrawPoint`가 실시간으로 State를 갱신해야 하는 다른 소비처가 있는지 확인 필요, 현재는 없어 보임).
- 권장: (B). 실측상 한 스트로크가 보통 수십~백여 개 포인트 수준이라 실사용에서 체감 문제가 report되진 않았지만, 다획 배치 스냅(2-5, 완료) 도입으로 한 세션에 스트로크가 여러 번 이어질 수 있어 누적 비용이 이전보다 커졌다 — 우선순위를 R-9보다 살짝 높게 볼 여지 있음.

### R-9. OkHttp 타임아웃 미설정
- 위치: [NetworkModule.kt:20-29](app/src/main/java/com/jason/mapsnap/di/NetworkModule.kt:20)
- `OkHttpClient.Builder()`에 connect/read/write 타임아웃을 지정하지 않아 기본값(10초)을 그대로 사용한다. T-Map 응답이 지연되면 `PROCESSING` 상태가 그만큼 길게 유지되고, 사용자에게 "얼마나 더 기다려야 하는지" 피드백이 없다.
- 조치: `.connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)` 등 명시적 값 추가. 값 자체는 취향 판단(현재 앱 사용 패턴상 10초 기본값도 나쁘지 않을 수 있음 — 굳이 줄일 필요보다는 "명시"하는 데 의미가 있음, 문서화 목적).
- 비용: 5분. 리스크: 낮음(값 선정만 신중, 너무 짧으면 정상 요청도 실패 처리될 수 있음).

### R-10. 일일 기본 한도 `30`이 매직 넘버로 3곳에 중복
- 위치: [MapViewModel.kt:83](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:83), [MapViewModel.kt:920](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:920), [CheckApiLimitUseCase.kt:32](app/src/main/java/com/jason/mapsnap/domain/usecase/CheckApiLimitUseCase.kt:32)
- 셋 다 `30 + usage.rechargedCount` 형태로 동일 계산을 반복한다. 향후 기본 한도를 바꿀 때 세 곳을 다 고쳐야 해서 누락 위험이 있다.
- 조치: `DeviceUsage` 관련 상수를 한 곳(`CheckApiLimitUseCase.companion object` 또는 별도 `object ApiLimitPolicy`)에 `const val DAILY_BASE_LIMIT = 30`으로 선언하고 3곳 모두 참조.
- 비용: 10분.

### R-11. `sampleMarkers`의 누적 거리 리셋 특성 (버그 아님, 참고용 메모)
- 위치: [MapViewModel.kt:768](app/src/main/java/com/jason/mapsnap/presentation/map/MapViewModel.kt:768) `sampleMarkers()`
- 마커를 하나 찍을 때마다 누적 거리(`accumulated`)를 0으로 리셋하는 방식이라, 실제 마커 간 간격이 설정값(`markerIntervalMeters`)보다 항상 크거나 같아지고 평균적으로 설정값보다 약간 더 벌어진다. 예를 들어 설정 80m인데 실제로는 82~95m 간격으로 찍힐 수 있음.
- 기능 결함은 아니고 설계상 트레이드오프(초과분을 다음 구간에 이월하지 않음 — 이월하면 코너 밀집 구간에서 마커가 너무 촘촘해질 수 있어 현재 방식이 오히려 안전할 수 있음). **수정 여부 판단이 필요한 항목이라기보다, 향후 "마커 간격이 설정과 다르다"는 사용자 문의가 들어올 경우 참고할 원인 메모.**
- 조치 없음(현행 유지 권장). 문서화 목적으로만 남김.

---

## 코드 밖 확인 필요 (수정 대상 아님)

### R-12. Firebase RTDB 보안 규칙 확인
- 위치: `DeviceUsageRepositoryImpl`이 참조하는 `device_usage/{hashedDeviceId}` 경로 — Firebase 콘솔에서 확인해야 함(레포 안에 규칙 파일이 없음).
- 만약 해당 경로에 대한 쓰기 규칙이 열려 있다면(누구나 임의 값으로 `setValue` 가능), 클라이언트가 `dailyCount`/`rechargedCount`를 위조해 한도를 우회할 수 있다. 이번에 `incrementDailyCount()`를 서버 트랜잭션으로 원자화했지만(1-5 조치), 트랜잭션 자체도 클라이언트에서 임의 조작 가능한 규칙이면 의미가 퇴색된다.
- 조치: Firebase 콘솔 → Realtime Database → 규칙에서, `device_usage/$uid` 경로가 최소한 "형식이 맞는 값으로만 갱신 가능"(예: `dailyCount`는 이전 값 +1만 허용하는 `.validate` 규칙) 정도로 제한되어 있는지 확인. 코드 수정 사항이 아니라 별도 콘솔 작업.

### R-13. `OsrmResponse.kt` 미사용 확인
- 위치: [data/remote/dto/OsrmResponse.kt](app/src/main/java/com/jason/mapsnap/data/remote/dto/OsrmResponse.kt)
- `implementation_plan.md`에 "OSRM 폴백은 범위 외"로 의도적 보류가 명시되어 있음. 실제로 이 DTO를 사용하는 서비스/리포지토리 구현체가 없어 완전한 데드코드 상태.
- 조치: 향후 OSRM 폴백(예: T-Map 장애 시 대체 라우팅 엔진)을 실제로 도입할 계획이 없다면 삭제해도 무방. 계획이 있다면 현행 유지.

---

## 권장 처리 순서 (재정리)

착수 부담이 작고 리스크가 없는 것부터:

1. R-1 (다이얼로그 안 닫힘 방어) — 5분
2. R-2 (주석 오타) — 1분
3. R-3 (테스트 가드 허점) — 5분
4. R-4 (데드코드/미사용 정리) — 10분
5. R-10 (매직 넘버 상수화) — 10분
6. R-9 (OkHttp 타임아웃 명시) — 5분
7. R-6 (haversine 통합) — 15분
8. R-7 (editHistory 상한) — 10분
9. R-5 (Timber 전면 치환) — 30분
10. R-8 (onDrawPoint 리스트 복사 최적화) — 15~20분
11. R-12 (Firebase 규칙 확인) — 코드 밖, 콘솔 작업
12. R-13 (OsrmResponse 삭제 여부 결정) — 판단 필요

1~8번은 순수 정리·안전성 강화이며 서로 독립적이라 한 번에 묶어 처리해도 리스크가 낮다.
9~10번은 파일 여러 곳을 건드리는 치환 작업이라 별도 커밋으로 분리하는 것을 권장한다.
