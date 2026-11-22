# KT 칠금점 개통장부 (Android APK)

충주 KT대리점 칠금점 개통장부 웹앱(`https://kbs2106-oss.github.io/kt-store-manager/`)을
안드로이드 앱으로 감싸고, **화면 캡처(스크린샷)·화면 녹화·최근 앱 미리보기를 막은** 앱입니다.

| 항목 | 값 |
| --- | --- |
| 앱 이름 | `KT 칠금점 개통장부` |
| 패키지 | `com.ktchilgeum.ledger` |
| 버전 | 1.0.0 (versionCode 1) |
| 최소 안드로이드 | 7.0 (API 24) |
| 로드 주소 | `https://kbs2106-oss.github.io/kt-store-manager/` |
| User-Agent 식별자 | `... KTChilgeumLedgerApp/1.0` |

## 캡처 차단

`MainActivity.onCreate()`에서 `FLAG_SECURE`를 켭니다.

```java
getWindow().setFlags(
        WindowManager.LayoutParams.FLAG_SECURE,
        WindowManager.LayoutParams.FLAG_SECURE);
```

이 한 줄로 스크린샷, 화면 녹화, 최근 앱(작업 전환) 미리보기가 모두 검게 가려집니다.

## 웹 ↔ 앱 연결

- `window.print()` → 안드로이드 인쇄(PrintManager). PDF 저장·프린터 출력 가능.
- Blob / `data:` 링크 다운로드 → `다운로드/KT칠금점개통장부/` 폴더에 저장(JS 가로채기 + `KTChilgeumApp.saveFile`).
- `http(s)` 다운로드 링크 → 안드로이드 다운로드 관리자.
- 파일 선택(`<input type="file">`) → 안드로이드 파일 선택기(백업 JSON·엑셀 가져오기).
- `tel:` → 전화 다이얼, `mailto:` → 메일 앱, 그 밖의 외부 링크(`docs.google.com` 등) → 외부 브라우저.
- 뒤로가기 → WebView 히스토리 우선, 히스토리가 없으면 "한 번 더 누르면 종료".

## 빌드

APK는 GitHub Actions(`.github/workflows/build-apk.yml`)가 만듭니다.
`android/**` 또는 워크플로 파일이 바뀌면 자동 실행되고, Actions 탭에서 수동 실행(`workflow_dispatch`)도 됩니다.
결과물: `KT-Chilgeum-Ledger-apk` 아티팩트 → `app-release.apk`(debug 키로 서명되어 바로 설치 가능).

로컬에서 빌드하려면 JDK 17 + Android SDK가 필요합니다.

```bash
cd android
gradle assembleRelease      # 또는 ./gradlew assembleRelease
# 결과: android/app/build/outputs/apk/release/app-release.apk
```

아이콘 PNG를 다시 만들려면:

```bash
python3 android/tools/make_icons.py android/app/src/main/res   # Pillow 필요
```

## 참고

- 웹 쪽 파일(`index.html`, `sw.js`, `CLAUDE.md`)은 이 앱 작업에서 건드리지 않습니다.
- 인쇄 → PDF 저장은 막지 않습니다(장부 출력 용도로 필요). 캡처 차단은 화면 캡처·녹화·미리보기에 한정됩니다.
