# Hearth Assist

하스스톤 **안드로이드 모바일**용 도우미 앱입니다. 하스스톤 위에 떠 있는 오버레이로 두 가지를 보여 줍니다.

1. **투기장(아레나) 카드 점수**: 드래프트 화면을 인식해서, 제시된 카드 3장 위에 각각 점수, 등급(S~D), 픽률을 띄웁니다.
2. **덱 트래커**: 게임 중에 내 덱에 남은 카드와 뽑을 확률, 상대가 낸 카드를 보여 줍니다.

> 이 앱은 Blizzard Entertainment와 관련이 없는 비공식 팬 프로젝트입니다.

## 설치

1. [Releases](../../releases)에서 최신 `HearthAssist-x.y.z.apk`를 휴대폰으로 받습니다.
   GitHub Actions 실행 결과의 `HearthAssist-apk` 아티팩트로 받아도 됩니다.
2. "출처를 알 수 없는 앱 설치"를 허용하고 설치합니다.
3. 앱을 열고 화면 안내에 따라 설정합니다. 자세한 순서는 아래에 있습니다.

Android 8.0(API 26) 이상, ARM 휴대폰에서 동작합니다.

## 처음 설정

### 1. 권한
- **다른 앱 위에 표시**: 오버레이를 띄우는 데 필요합니다. 필수입니다.
- **알림** (Android 13 이상): 실행 중 알림을 표시합니다.

### 2. 덱 트래커용 로그 연결 (Shizuku)
덱 트래커는 하스스톤이 남기는 로그 파일(`Power.log` 등)을 읽어서 동작합니다.
이 로그는 `Android/data/com.blizzard.wtcg.hearthstone/files/` 아래에 있는데,
Android 11부터는 다른 앱이 이 폴더를 읽을 수 없습니다. 그래서 **[Shizuku](https://shizuku.rikka.app/)** 가 필요합니다.

1. Play 스토어에서 Shizuku를 설치합니다.
2. Shizuku 앱에서 **무선 디버깅으로 시작**합니다. PC는 필요 없고, 개발자 옵션에서 무선 디버깅 페어링을 한 번 해야 합니다.
   휴대폰을 재부팅하면 다시 시작해야 합니다.
3. Hearth Assist의 "하스스톤 로그 연결"에서 **권한 요청**을 누르고 허용합니다.
4. **log.config 설정 (최초 1회)** 을 누릅니다. 하스스톤이 로그를 남기도록 설정 파일을 씁니다.
5. 하스스톤을 **완전히 종료했다가 다시 실행**합니다.

Android 10 이하에서는 Shizuku 없이 저장소 권한만으로 동작합니다.
루팅된 기기는 Shizuku를 root 모드로 실행하면 됩니다.

### 3. 데이터
- **카드 데이터**: 앱에 내장되어 있습니다. 새 확장팩이 나오면 "카드 데이터 업데이트"를 누르세요(HearthstoneJSON).
- **투기장 통계**: [Firestone](https://www.firestoneapp.com/)이 공개하는 투기장 통계를 자동으로 받습니다(12시간마다 갱신).
  "투기장/언더그라운드", 기간(최근 패치/7일/3일)을 고를 수 있습니다.

## 사용법

1. 앱에서 **오버레이 시작**을 누르고 하스스톤을 실행합니다.
2. 화면에 떠 있는 **HA** 버튼을 누르면 패널이 펼쳐집니다. 버튼은 끌어서 옮길 수 있습니다.

### 투기장 드래프트 점수
1. 패널의 **투기장** 탭에서 **화면 인식 시작**을 누르고 화면 녹화를 허용합니다.
   Android 14 이상에서는 "전체 화면"을 고르세요.
2. 드래프트 화면에서 카드 이름을 인식하면, 각 카드 위에 점수 배지가 나타납니다.
   점수가 가장 높은 카드에는 **추천** 표시가 붙습니다.
   - 로그가 연결되어 있으면 드래프트 화면에서만 자동으로 인식하고, 직업도 자동으로 고릅니다.
   - 로그가 없으면 계속 인식합니다. 이때는 직업을 패널에서 직접 고르세요.
3. 인식이 잘 안 되면 **지금 인식**을 누르거나, **카드 검색**으로 카드 이름을 입력해서 점수를 확인하세요.
4. 앱의 **티어 리스트**에서 직업별 전체 카드 순위를 볼 수 있습니다.

**점수란?** 그 카드를 뽑은(드로우한) 게임의 승률(%)입니다. 표본이 적은 카드는 평균 쪽으로 보정합니다.
등급은 전체 평균과의 차이로 매깁니다(S: +5%p 이상, A: +2.5%p, B: 평균 근처, C: -3.5%p까지, D: 그 아래).
배지 아래의 "픽률"은 그 카드가 제시됐을 때 다른 플레이어가 고른 비율이고, "6승+"는 6승 이상을 거둔 덱에서의 픽률입니다.

### 덱 트래커
- 게임이 시작되면 패널이 자동으로 **내 덱** 탭을 엽니다.
  - 덱 리스트를 아는 경우: 남은 카드, 장수, 다음에 뽑을 확률을 보여 줍니다. 다 뽑은 카드는 흐리게 표시합니다.
  - 덱 리스트는 일반 대전이면 로그(`Decks.log`)에서, 투기장이면 드래프트 기록(`Arena.log`)에서 자동으로 읽습니다.
    안 되면 앱의 "덱 코드" 칸에 하스스톤에서 복사한 덱 코드를 붙여 넣으세요.
- **상대** 탭: 상대가 덱에서 꺼내 공개한 카드와, 효과로 생성한 카드(✦), 상대의 남은 덱과 손패 장수를 보여 줍니다.

## 알아 둘 점
- 오버레이와 화면 인식은 배터리를 조금 더 씁니다. 인식 간격은 설정에서 조절할 수 있습니다.
- 화면 인식(OCR)은 한국어와 영어 클라이언트를 지원합니다. 앱 설정의 "게임 언어"를 하스스톤 언어와 맞추세요.
- 하스스톤 클라이언트가 바뀌어 로그 형식이나 위치가 달라지면 트래커가 동작하지 않을 수 있습니다.
- 서명 키(`app/signing/hearthassist.jks`)는 업데이트 설치를 이어 가기 위해 저장소에 포함한 공개 키입니다.
  직접 배포할 때는 `HA_KEYSTORE_PATH`, `HA_KEYSTORE_PASSWORD`, `HA_KEY_ALIAS`, `HA_KEY_PASSWORD` 환경 변수로 자신의 키를 쓰세요.

## 개발

```
core/   순수 Kotlin(JVM): 카드 DB, 로그 파서(Power/Arena/LoadingScreen/Decks), 덱 트래커,
        덱 코드, 투기장 점수 계산, OCR 카드 매칭. 단위 테스트 포함
app/    안드로이드 앱(Jetpack Compose): 설정 화면, 오버레이 서비스, Shizuku 파일 서비스,
        MediaProjection 화면 캡처 + ML Kit 한국어 OCR
tools/  build_card_db.py: 내장 카드 DB(app/src/main/assets/cards.json.gz) 생성
```

```bash
./gradlew :core:test                      # 안드로이드 SDK 없이 core만 테스트하려면 -PskipAndroid=true 추가
./gradlew :app:assembleRelease            # APK: app/build/outputs/apk/release/

pip install hearthstone hearthstone_data  # 내장 카드 DB 다시 만들기
python3 tools/build_card_db.py
```

푸시할 때마다 GitHub Actions(`.github/workflows/android.yml`)가 테스트와 APK 빌드를 하고, 결과 APK를 pre-release로 올립니다.

### 동작 방식 요약
- **로그 읽기**: Shizuku `UserService`(adb 셸 권한 프로세스)에서 파일을 읽고, 직접 작성한 Binder 프로토콜로 앱에 전달합니다.
  `LogWatcher`가 300ms마다 새로 추가된 부분만 읽어 파서에 넘깁니다.
- **덱 트래커**: `Power.log`의 `GameState.DebugPrintPower()` 줄로 엔티티, 존, 컨트롤러를 재구성합니다.
  게임 시작 시점에 덱에 있던 카드와 이후 생성된 카드를 구분해서 남은 카드를 계산합니다.
- **드래프트 인식**: 화면을 캡처해 ML Kit로 글자를 읽고, 카드 이름과 퍼지 매칭합니다.
  같은 줄(y)에 가로로 떨어져 있는 최대 3개의 매칭을 골라, 덱 목록이나 카드 설명에 나오는 이름은 걸러 냅니다.

## 크레딧
- 카드 데이터: [HearthstoneJSON](https://hearthstonejson.com/) / [python-hearthstone](https://github.com/HearthSim/python-hearthstone)
- 투기장 통계: [Firestone](https://www.firestoneapp.com/) (Zero to Heroes)
- 로그 접근: [Shizuku](https://github.com/RikkaApps/Shizuku)
