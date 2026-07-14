# OSM 라우팅 전환 계획 (T-Map → openrouteservice)

작성일: 2026-07-14. 근거: `commercialization_plan.md` 2-1-1(24시간 저장 조항 위반 추정), 2-1-2(대안 엔진 조사).

## 0. 배경과 성공 기준

**왜 전환하는가.**

- T-Map 무료 약관의 "저장 후 24시간 이상 사용 불가" 조항이 경로 저장·GPX 반출과 정면 충돌한다 (G2 게이트). OSM 계열은 ODbL이라 저장·반출·가공에 제약이 없어 이 리스크가 구조적으로 소멸한다.
- T-Map 유료 전환 시 건당 10원 단가에서는 현행 광고 충전 BM이 성립하지 않는다 (2-1-2). OSM 계열은 한계 원가가 0에 수렴한다.

**성공 기준.**

1. 기준 코스(강남 골목·한강공원·아파트 단지 등)에서 스냅 품질이 T-Map 대비 동등 이상이거나 수용 가능 수준.
2. 경로 저장·GPX 반출에 대한 약관 리스크 0 (ODbL 출처 표기만 이행).
3. 그리기 → 스냅 → 편집 → 저장 → GPX 반출 전 기능 회귀 없음 (기존 테스트 전부 통과).

**핵심 리스크.** 한국 OSM 보행자 데이터 품질(골목길·공원 내부 산책로·아파트 단지 관통로)이 미검증이다. 그래서 이 계획은 **품질 PoC를 판정 게이트**로 두고, 미달 시 전환을 중단하는 3갈래 출구를 정의한다.

## 1. 엔진 선택: openrouteservice (ORS)

| 후보 | 판단 | 이유 |
|---|---|---|
| **ORS (호스티드)** | **채택** | foot-walking 프로파일 무료 제공, 한국 커버. 경유지 **50개/요청** (공식 restrictions 확인, 2026-07-14) → 현재 최대 19 웨이포인트가 **1콜**에 들어가 청킹이 사라짐. 무료 쿼터 일 ~2,500건·분당 40건(키 단위 전역, 1차 조사값). 같은 API를 자체 호스팅 가능 → 확장 시 baseUrl만 교체 |
| OSRM | 확장 단계 후보 | 공개 데모 서버가 자동차 전용이라 무설치 PoC 불가. 자체 호스팅 시 유효 |
| Valhalla | PoC 보조 비교용 | FOSSGIS 데모 서버에 보행자 프로파일 있음. ORS 품질 미달 지역이 나오면 교차 검증 |
| Mapbox | 보류 | 유료 계정 필요, 상용 종속. ORS 계열 실패 시 재검토 |

ORS의 결정적 장점은 **호스티드 → 자체 호스팅이 같은 API 계약**이라는 점이다. PoC·베타는 무료 호스티드로, 규모 확장 시 한국 추출본을 자체 호스팅(백엔드 프록시 계획 — commercialization_plan 8장과 통합)으로 전환해도 앱 코드는 baseUrl 교체뿐이다.

## 2. 아키텍처 영향 분석

교체 지점은 이미 `RouteRepository` 인터페이스로 추상화되어 있다 — `getPedestrianRoute(waypoints) → PedestrianRoute(points, apiCallCount)`. 인터페이스 계약은 그대로 두고 구현만 바꾼다.

| 영역 | 현재 (T-Map) | 전환 후 (ORS) |
|---|---|---|
| `data/remote` | `TmapService` + Request/Response DTO | `OrsService` 신설: `POST /v2/directions/foot-walking/geojson`, body `{"coordinates": [[lng,lat],…]}`, `Authorization` 헤더 |
| `RouteRepositoryImpl` | 7포인트 청킹 + passList 조립 + 청크 경계 트리밍(`trimBoundaryArtifactIfLooping`) | `OrsRouteRepositoryImpl` 신설: 좌표 배열 1콜. **청킹·경계 트리밍 코드 전부 불필요** (`apiCallCount` 항상 1). 웨이포인트 근접 병합(10m)은 유지 |
| `SnapToRoadUseCase` | `maxWaypoints = 19` (T-Map 청크 수학 기준) | 상한 50까지 상향 여지 — 그린 선 밀착도가 올라감. 값은 PoC 품질로 결정. `removeSpurs`는 **유지** (경유지 유발 왕복은 엔진 불문 발생) — 파라미터 재튜닝은 PoC 항목 |
| `NetworkModule` | SK baseUrl 단일 Retrofit | ORS용 Retrofit 분리 제공 (`@Named` 구분). 전환 완료까지 양쪽 공존 |
| `BuildConfig` | `TMAP_API_KEY` | `ORS_API_KEY` 추가 (local.properties). T-Map 키는 Phase 4까지 유지 |
| 한도 시스템 (`CheckApiLimitUseCase` 등) | 기기당 일 30회 + 광고 충전 +10 | **일단 무변경** (surgical). 단 의미가 바뀜: ORS 무료 쿼터는 키 단위 전역이므로 기기 한도가 전역 쿼터 보호 장치 역할. 한도·충전 BM 재설계는 별도 기획 (4장) |
| `ApiCallTracker` | `incrementTmap()` | 전환 완료 시점에 엔진 중립 네이밍으로 정리 (Phase 4) |
| GPX 반출·저장·마커 편집·UI | — | **무변경** (RouteRepository 계약 동일) |
| 라이선스 고지 | — | 정보/설정 화면에 "경로 데이터 © OpenStreetMap contributors (ODbL)" 표기 — ODbL 필수 요건 |

부수 효과 두 가지.

- **`<ele>` 고도 태그 부활 가능**: ORS는 `elevation=true`로 경로에 고도를 포함해 반환한다. `implementation_plan.md`에서 Open-Meteo 비상업 약관 리스크로 제외했던 GPX `<ele>`를 별도 API 없이 넣을 수 있다 (Phase 3 선택 항목).
- 네이버 지도 위 오버레이는 자체 폴리라인 그리기이므로 ORS 결과 표시에 제약 없음.

## 3. 단계별 계획

### Phase 1 — ORS 어댑터 구현 (PoC 겸용)

스크립트 PoC 대신 **실제 어댑터를 디버그 토글 뒤에 구현**한다. 스냅 품질은 파이프라인 전체(RDP → 샘플링 → 라우팅 → 스퍼 제거)의 산물이라 앱 안에서 비교해야 유효하고, 이 코드가 그대로 전환 산출물이 된다.

- [ ] ORS 계정·키 발급, `local.properties` + `BuildConfig.ORS_API_KEY` → verify: 빌드 성공
- [ ] `OrsService` + DTO (GeoJSON LineString 파싱, 좌표 순서 lng,lat) → verify: 샘플 응답 JSON 파싱 단위 테스트
- [ ] `OrsRouteRepositoryImpl` (1콜, `apiCallCount = 1`, 웨이포인트 병합 로직 공유) → verify: `RouteRepository` 계약 기준 단위 테스트
- [ ] DI에 런타임 엔진 스위치 (디버그 설정 토글: T-Map ↔ ORS) → verify: 토글 후 각 엔진 호출 로그 확인
- [ ] 기존 테스트 전체 통과 → verify: `gradlew test`

### Phase 2 — 품질 PoC (판정 게이트)

- [ ] 기준 코스 5~10개 선정: 강남 골목, 한강공원 내부 산책로, 아파트 단지 관통, 대학 캠퍼스, 근교 등산로 초입
- [ ] 코스별로 T-Map/ORS 토글 스냅 → 스크린샷 + GPX 저장, `context-notes.md`에 기록
- [ ] 판정 기준(사전 고정): (a) 실보행로 위 스냅 여부 (b) 그린 선 대비 이탈 (c) 스퍼·왕복 아티팩트 빈도 (d) 응답 시간(독일 서버 레이턴시 실측)

**게이트 판정 — 3갈래 출구.**

1. ORS 동등 이상 → Phase 3 진행.
2. 특정 지역·코스만 미달 → Valhalla(FOSSGIS 데모)로 해당 코스 교차 검증 후 재판정.
3. 전반적 미달 → **전환 중단.** T-Map 잔류 + SK 서면 질의 트랙(commercialization_plan 2-1-1)으로 복귀. Phase 1 코드는 토글 뒤에 남겨둔다.

### Phase 3 — 기본 엔진 전환

- [ ] 기본 엔진을 ORS로 스위치 (T-Map은 폴백 플래그로 잔류)
- [ ] `maxWaypoints`·스퍼 파라미터를 PoC 결과로 튜닝
- [ ] ODbL 고지 UI 추가
- [ ] (선택) `elevation=true` + GPX `<ele>` 반영
- [ ] 문서 갱신: walkthrough.md 스냅 파이프라인 절, commercialization_plan G1·G2 게이트 상태
- [ ] 베타 모니터링: Firebase 사용량 데이터로 일 전역 호출량 → ORS 쿼터 소진율 추적

### Phase 4 — 정리 및 확장 준비 (전환 안정화 후)

- [ ] T-Map 코드 제거: `TmapService`·DTO·청킹/트리밍 로직·`TMAP_API_KEY`
- [ ] `ApiCallTracker` 엔진 중립 네이밍 정리
- [ ] 자체 호스팅 트리거 정의: 일 전역 호출이 무료 쿼터의 60~70% 도달 시 ORS 자체 호스팅(한국 OSM 추출본) + 백엔드 프록시(8장)로 이전 — APK 키 내장 문제(S1)도 함께 해소
- [ ] 한도·충전 BM 재설계 기획 (기기 30회/충전 +10의 존재 근거였던 T-Map 쿼터가 사라짐 — 별도 문서)

## 4. 리스크 매트릭스

| 리스크 | 영향 | 대응 |
|---|---|---|
| 한국 OSM 보행자 데이터 품질 미달 | 치명 | Phase 2 게이트 + 3갈래 출구 (Valhalla 교차 검증 → 최종 T-Map 잔류) |
| ORS 무료 쿼터(키 단위 전역) 소진 | 높음 | 기존 기기 한도 유지 + 소진율 모니터링, 60~70% 도달 시 자체 호스팅 |
| 호스티드 서버 레이턴시·장애 (독일 소재) | 중간 | PoC 판정 기준 (d)로 실측, 자체 호스팅 시 해소 |
| ORS 키 APK 내장 (S1 동일 구조) | 낮음 | 무료 키라 도용 손실 한도 낮음, 프록시 이전 시 해소 |
| 쿼터 수치(2,500/일 등) 변동 | 낮음 | 1차 조사값 — Phase 1 키 발급 시 대시보드에서 실측 확인 |

## 5. 진행 규약

- 구현 착수 시 `checklist.md`(위 체크박스 이관)와 `context-notes.md`(PoC 코스별 기록·파라미터 튜닝 근거)를 생성해 세션 간 인수인계에 쓴다.
- 각 Phase 완료는 의미 단위 커밋으로 남긴다. Phase 2 판정 결과는 커밋 메시지가 아니라 context-notes에 근거와 함께 기록한다.
