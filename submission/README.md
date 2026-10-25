# 제출 문서 관리

AI 서비스 개발 Mini-project 제출 문서를 이 브랜치(`docs/submission-mini-project`)에서 관리한다. 팀 산출물은 2026-10-26(월) 14:00, 개인 회고노트는 같은 날 16:00~17:00까지다. 마감 이후에 고친 파일은 평가에 반영되지 않는다.

다른 PC에서 받는 방법

```bash
git fetch origin
git switch docs/submission-mini-project
```

## 산출물 6종

| 번호 | 산출물 | 형식과 파일명 | 상태 |
|---|---|---|---|
| 01 | 서비스 설계서 | PDF `{반_조_프로젝트명}-설계서.pdf` (STEP 01~05) | 초안 작성, 검토 필요 |
| 02 | 소스코드 | GitHub 공개 저장소 URL (이 저장소) | 진행 중 |
| 03 | 검증 결과서 | PDF `{반_조_프로젝트명}-검증.pdf` | 초안 작성, 검토 필요 |
| 04 | 발표자료 | PDF `{반_조_프로젝트명}-발표.pdf` (7장 내외) | 초안 작성, 검토 필요 |
| 05 | 결과 영상 | MP4 `{반_조_프로젝트명}-영상.mp4` (3분 이내) | 시나리오 확정 |
| 06 | 개인 회고노트 | PDF `{반_조_이름}-회고.pdf` (1인 1부, 구글 폼) | 작성 전 |

## 폴더

| 위치 | 내용 |
|---|---|
| `submission/01-design/design.md`, `architecture.svg` | 설계서 초안(STEP 01~05)과 시스템 구성도 |
| `submission/03-validation/validation.md`, `quality-check.csv` | 검증 결과서 초안과 AI 출력 품질 점검 30행 |
| `submission/04-slides/slides.md` | 발표 7장 문안, 15분 대본, 예상 질문 5개 |
| `submission/05-demo/scenario.md` | 시연 시나리오 (핵심 질문, 실패 유도 질문, 3분 구성, 촬영 전 준비, 알려진 문제) |
| `submission/evidence/2026-10-25-live-runs.csv` | 2026-10-25 밤 실제 키 실행 기록 |
| `submission/build/build_pdf.py` | 마크다운을 PDF로 바꾸는 스크립트 |

초안의 `(확정 후 기입)` 표시는 반, 조, 프로젝트명이 정해지면 채운다. 회고노트는 `06-retrospective` 폴더를 만들어 넣는다.

## PDF 만들기

Python과 Edge 또는 Chrome이 있으면 된다.

```bash
pip install markdown
python submission/build/build_pdf.py submission/01-design/design.md out/설계서.pdf
python submission/build/build_pdf.py submission/03-validation/validation.md out/검증.pdf
python submission/build/build_pdf.py submission/04-slides/slides.md out/발표.pdf --slides
```

`--slides`는 가로 페이지로 "## 슬라이드 N" 구간을 한 장씩 만든다. 파일명은 제출 규칙에 맞게 `{반_조_프로젝트명}-설계서.pdf` 형태로 바꿔서 낸다.

## 규칙

- 이 폴더의 문서는 이 브랜치에서만 고친다. 코드, README, `docs/` 변경은 일반 작업 브랜치에서 develop으로 PR을 보낸다.
- 저장소 규칙에 따라 가운뎃점과 em dash를 쓰지 않는다. 커밋 메시지는 `type(scope): 내용` 제목 한 줄만 쓴다.
- 비밀 값(`.env`, 토큰, 키)은 올리지 않는다. 이 저장소는 공개 저장소다.
- 문서 사이에 같은 값이 들어가는 곳(검증 ID, 평가 수치, 구성도, 저장소 주소, 역할)은 제출 전에 한 번 대조한다. 원본은 `docs/evaluation.md` 2절과 설계서 STEP 04다.
