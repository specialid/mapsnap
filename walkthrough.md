# MapSnap — Architecture Walkthrough

## 개요
지도 위에서 자유롭게 선을 그리면 Naver Directions API를 통해 실제 도로에 스냅하여
직선화된 경로를 표시하는 Android 앱.

---

## 아키텍처 결정 사항

### 1. Orbit MVI
- `MapViewModel`이 `ContainerHost<MapState, MapSideEffect>` 구현
- `reduce { }` 블록은 순수 상태 변환만 수행
- 모든 네트워크·CPU 작업은 `intent { }` + `withContext(Dispatchers.IO/Default)` 처리

### 2. 그리기 오버레이 좌표 처리
- `DrawingOverlay`는 NaverMap 위의 전체 화면 Canvas
- 터치 이벤트: `detectDragGestures`로 `Offset` (px) 수집
- LatLng 변환: `NaverMap.projection.fromScreenLocation(PointF)` 사용
- 시각적 렌더링: 로컬 `screenPoints` 리스트 (좌표 변환 불필요)
- 지도 제스처: 그리기 모드 진입 시 `MapUiSettings`로 비활성화

### 3. 경로 스냅 알고리즘
1. **RDP 단순화** (`SimplifyPathUseCase`, Dispatchers.Default)
   - epsilon ≈ 0.000135° (~15m)
   - 노이즈 제거 및 중복 포인트 정리
2. **균등 샘플링** (`SnapToRoadUseCase`)
   - start + goal + 중간 최대 3개 웨이포인트
   - Naver Directions 5 API 제한 (max 5 waypoints) 준수
3. **API 호출** (`RouteRepositoryImpl`, Dispatchers.IO) — **보행로(pedestrian) 스냅**
   - OSRM 공개 서버 사용: `GET https://router.project-osrm.org/route/v1/foot/{lng,lat;...}?overview=full&geometries=geojson`
   - 보행자 프로파일(`foot`)로 인도·횡단보도·보행로 포함 경로 반환
   - API 키 불필요, 무료
   - 그린 경로를 충실히 따르도록 최대 25개 웨이포인트 전달 (OSRM은 다수 경유지 지원)
   - 응답: `routes[0].geometry.coordinates` (GeoJSON, [lng,lat]) → `List<LatLng>`
   - **Naver Directions는 차도(driving) 전용 + 경유지 5개 제한이라 보행로 요구사항에 부적합 → OSRM으로 전환**

### 4. API 키 관리 (지도 SDK 전용 — 신규 NCP 인증)
- `local.properties`: `NAVER_CLIENT_ID` (지도 SDK용. OSRM은 키 불필요)
- `BuildConfig` + `manifestPlaceholders`로 주입
- Manifest meta-data: `com.naver.maps.map.NCP_KEY_ID` (신규 NCP 방식)
- `App.kt`에서 `NaverMapSdk.NcpKeyClient` 설정
- map-sdk **3.23.2** (구 3.19.1은 신규 키 미지원)

### 5. 현재 위치
- `FusedLocationProviderClient.lastLocation`
- `ActivityResultContracts.RequestPermission`으로 권한 요청
- 권한 거부 또는 위치 불가 시 서울 시청 (37.5666, 126.9784) 폴백

### 6. 좌표 변환 (DrawingOverlay)
- NaverMap SDK 인스턴스에 의존하지 않고, `CameraPositionState`의 카메라 정보로
  **Web Mercator(EPSG:3857) 수식을 직접 계산**해 화면 픽셀 ↔ LatLng 변환
- 그리기 모드일 때만 `pointerInput` 조건부 설치 → 비그리기 모드에서 지도 줌/스크롤 통과

---

## 파일 구조 요약

```
di/             → Hilt 모듈 (NetworkModule, AppModule)
data/           → Retrofit service, DTO, RepositoryImpl
domain/         → Repository interface, UseCase (SimplifyPath, SnapToRoad)
presentation/
  map/          → MapState, MapSideEffect, MapViewModel
  component/    → MapScreen, DrawingOverlay, BottomControls
ui/theme/       → MapSnapTheme
```

---

## Naver Cloud Platform 설정 가이드

1. https://console.ncloud.com 접속 및 로그인
2. **AI·NAVER API** → **Maps** 메뉴
3. **Mobile Dynamic Map** 이용 신청 (Android 지도 SDK)
4. **Directions** 이용 신청 (경로 API)
5. 애플리케이션 등록 → **Client ID**, **Client Secret** 발급
6. `local.properties`에 키 입력 후 Gradle Sync
