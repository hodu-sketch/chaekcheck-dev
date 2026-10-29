# 8. 책 표지 (S3 + CloudFront, 사이클 26)

책 표지 그림은 운영 서버가 아니라 Amazon S3(시드니) 비공개 버킷에 두고, CloudFront 기본 주소로 보입니다(plan ADR-0163). **S3에는 PC만 올립니다.** 운영 서버에는 S3 권한이 없습니다. 차례는 늘 **그림 먼저, 연결 나중**입니다: 그림을 올린 뒤에 `book-texts`의 `cover_file` 줄로 책과 잇습니다(올리지 못한 그림의 줄은 넣지 않음).

## 1. 이름과 주소

| 항목 | 값 |
|---|---|
| 버킷 | `chaekcheck-covers-135274608857` (ap-southeast-2, 공개 접근 모두 막음) |
| 그림 자리 | `s3://chaekcheck-covers-135274608857/covers/<sha1>.jpg` (평평하게, 하위 폴더 없음) |
| CloudFront | 배포 `E1RZWZ6VBX38DX`, 주소 `https://d1bdzw8sfjph5d.cloudfront.net/covers/<sha1>.jpg` (OAC로만 S3를 읽음) |
| 서버 설정 | `chaekcheck.covers.base-url` = `https://d1bdzw8sfjph5d.cloudfront.net` (`application.yml`, 운영은 `CHAEKCHECK_COVERS_BASEURL`로 바꿀 수 있음) |
| 웹 | `picksText.ts` `COVERS_BASE_URL`(빌드 때 `VITE_COVERS_BASE_URL`), CSP `img-src`에 같은 호스트 (`deploy/nginx/security-headers.conf`) |
| PC 업로드 프로필 | AWS CLI 프로필 `chaekcheck-covers` = IAM 사용자 `chaekcheck-covers-uploader`(그 버킷 `s3:PutObject`·`s3:ListBucket`만). 키는 PC의 `%USERPROFILE%\.aws\credentials`에만. 저장소·문서·로그에 적지 않음 |
| AWS CLI | `C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\tools\awscli\Amazon\AWSCLIV2\aws.exe` (v2.39.0, 관리자 권한 없이 MSI를 풀어 둠, git 밖) |
| 비밀 아닌 값 | dev `data/covers-aws.json` |

그림 이름:
- 판 표지: 수집 PC `fetch-covers`가 받은 파일의 sha1(`covers\covers.sqlite`의 `book_cover.file`). `catalog.book_cover.file_key`와 같음
- 목록 그림: `sha1(cover_source)` (UTF-8, 소문자 16진수, `picks.internal.lists.CoverFiles.key`). 사서추천 줄도 같음

## 2. 올리기 (공통)

- **PowerShell에서** 돌립니다. 2026-10-28 Git Bash에서 돌린 `aws s3 sync`는 네트워크 연결 없이 0장인 채로 멈췄고, 같은 명령이 PowerShell에서는 정상이었습니다.
- 옵션은 늘 같게: `--profile chaekcheck-covers --cache-control "public, max-age=604800" --content-type image/jpeg`
- 예(평평한 폴더 하나):
  ```
  & 'C:\Users\yujun\Documents\Claude\Projects\chaekcheck-dev\data\tools\awscli\Amazon\AWSCLIV2\aws.exe' s3 sync <폴더> s3://chaekcheck-covers-135274608857/covers/ --profile chaekcheck-covers --cache-control "public, max-age=604800" --content-type image/jpeg --no-progress *> upload.log
  ```
  `sync`는 이미 있는 같은 크기 파일을 건너뛰므로 다시 돌려도 됩니다. 끝나면 `upload.log`의 `upload:` 줄 수와 오류 줄(`error`, `failed`)을 셉니다.
- 수집 폴더가 `covers\<xx>\<sha1>.jpg`처럼 나뉘어 있으면 `sync`로 그대로 올리면 `covers/<xx>/…`가 되어 주소가 맞지 않습니다. 평평한 폴더로 모으거나(하드 링크·복사) 파일마다 `covers/<sha1>.jpg`로 올립니다.
- 확인:
  - 객체 수: `aws s3 ls s3://chaekcheck-covers-135274608857/covers/ --recursive --summarize --profile chaekcheck-covers` 마지막 두 줄
  - 연결할 이름이 모두 있는지: 넣을 `cover_file` 줄의 sha1 목록과 위 목록을 비교(빠진 것 0이어야 함)
  - 한 장을 CloudFront로 열어 200, `image/jpeg`, `cache-control: public, max-age=604800`
- 비용 어림(시드니, 2026-10-28 가격): PUT 1,000건에 $0.0055(8만 장 약 $0.45, 113만 장 약 $6.2), 저장 GB·월 $0.025. 전송은 CloudFront 무료 1TB/월 안

## 3. 연결 (`book-texts`의 `cover_file`)

- 그림을 다 올리고 확인한 뒤, 올라간 그림의 줄만 담아 `book-texts` 파일을 만듭니다: 첫 줄 `# export_id=<운영 마지막 가져오기의 export_id>`, 둘째 줄 헤더 `kind\tbook_id\tsource\tmade_on\ttext`, 줄마다 `cover_file\t<책 번호>\t\t\t<sha1>`
- 운영에 넣는 명령은 [6-aisearch.md](6-aisearch.md) 3절(파일에 `search_line`이 없으면 AI 검색 표는 건드리지 않음). `export_id`가 다르면 거절됩니다(가져오기로 책 번호가 바뀐 뒤면 PC에서 새 번호로 다시 만듦)
- 넣으면 서버를 다시 켜지 않아도 바로 보입니다(책첵 추천·AI 검색·책 상세는 요청마다 읽음, 추천목록 줄은 목록 캐시를 다시 읽을 때 10분 안)

## 4. 지금까지 한 일

| 날짜 | 무엇 | 결과 |
|---|---|---|
| 2026-10-28 | 버킷·CloudFront·IAM 만들기(사이클 26 1단계, `scripts/covers/setup-aws.ps1`, 관리자 단기 자격 `aws login --profile chaekcheck-admin`) | 위 1절 값. CloudFront 주소 200, S3 직접 403, 업로드 키로 읽기 403 |
| 2026-10-28 | 운영 `/srv/chaekcheck/covers` 81,451장(tar로 PC에 받음, 976MB) + `picks.cover` 108장(`scripts/covers/extract_picks_covers.py`, `sha1(source_url).jpg`) | S3 81,559개, 915MB, 오류 0, 44분. 운영 `foryou.book_cover` 이름 80,078개·목록 그림 2,580개 모두 있음 |
| 2026-10-29 | 사이클 26 배포 2(v1.20.6, 커밋 48ede47a): nginx `location /covers/`, compose 볼륨 `/srv/chaekcheck/covers:/srv/covers`, `GET /api/v1/picks/covers/{coverId}`, 서버의 목록 표지 받기(`CoverResizer`, `picks.cover`)를 지움 | 운영 `/srv/chaekcheck/covers`(81,451장, 모두 S3에 있음을 확인)는 쓰지 않아 지움(사용자 "너가 지워", 디스크 남은 공간 9.1 → 11GB). `catalog.book_cover` 1,145,754행(11단계, 2026-10-29) |

## 5. 처음부터 다시 만들 때 (버킷이 없어진 경우만)

- 관리자 로그인: `aws.exe login --profile chaekcheck-admin`(브라우저로 콘솔 로그인, 단기 자격)
- `powershell -File scripts/covers/setup-aws.ps1 -AdminProfile chaekcheck-admin -Aws <aws.exe 경로>`: 있으면 건너뛰고 없는 것만 만듦. 업로드 키는 화면에 찍지 않고 프로필 `chaekcheck-covers`에 바로 넣음
- 콘솔만 쓸 수 있으면 CloudShell용 `scripts/covers/setup-aws-cloudshell.sh`(업로드 키는 만들지 않음)
- 새 도메인이 나오면 서버 설정·웹 `COVERS_BASE_URL`·CSP를 함께 바꿉니다
