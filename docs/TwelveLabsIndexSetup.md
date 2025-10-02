# TwelveLabs Index SDK Setup

이 문서는 TwelveLabs Python SDK를 PyPI 대신 GitHub 저장소에서 직접 내려받아 인덱스를 생성하는 방법을 정리한 것입니다.

## 1. GitHub에서 SDK 내려받기

1. 저장소를 클론하거나 아카이브(zip) 파일을 다운로드합니다.
   ```bash
   git clone https://github.com/twelvelabs-io/twelvelabs-python.git
   ```
2. 클론한 저장소 안의 `src/twelvelabs` 디렉터리를 통째로 복사하여 이 프로젝트의 `third_party/twelvelabs-sdk` 디렉터리 아래에 붙여 넣습니다. (해당 폴더는 버전 관리를 하지 않도록 `.gitignore`에 등록되어 있습니다.)
3. 필요하다면 예제 스크립트(`examples/index.py` 등)도 함께 참고용으로 복사할 수 있습니다.

> ⚠️ SDK는 압축을 해제한 폴더 형태로 배치해야 하며, wheel(`*.whl`)이나 zip 상태 그대로 두면 Python이 모듈을 찾지 못합니다.

## 2. 의존성 연결

SDK 폴더를 복사한 뒤에는 Python이 `third_party/twelvelabs-sdk/twelvelabs` 모듈을 찾을 수 있도록 경로를 지정해 주어야 합니다. PyPI에서 설치하는 대신 GitHub에서 내려받은 소스 폴더를 직접 참조하는 방식입니다.

가장 간단한 방법은 스크립트를 실행할 때 일시적으로 `PYTHONPATH`를 지정하는 것입니다.

```bash
PYTHONPATH="third_party/twelvelabs-sdk" python scripts/create_twelvelabs_index.py
```

또는 셸 초기화 스크립트(`.bashrc`, `.zshrc` 등)에 다음과 같이 등록하여 항상 TwelveLabs SDK 폴더를 경로에 포함시킬 수도 있습니다.

```bash
export PYTHONPATH="$(pwd)/third_party/twelvelabs-sdk:${PYTHONPATH}"
```

## 3. API 키 준비

TwelveLabs API를 호출하려면 환경 변수 `API_KEY`에 발급받은 키를 넣어야 합니다.

```bash
export API_KEY="tlk_XXXXXXXXXXXXXXXX"
```

## 4. 인덱스 생성 예제 실행

`scripts/create_twelvelabs_index.py` 스크립트는 TwelveLabs의 예제를 기반으로 인덱스를 생성하고 조회하는 플로우를 보여줍니다.

```bash
PYTHONPATH="third_party/twelvelabs-sdk" python scripts/create_twelvelabs_index.py
```

스크립트는 다음 작업을 수행합니다.

- 새로운 인덱스를 생성하고 ID를 출력합니다.
- 방금 생성한 인덱스를 조회합니다.
- 인덱스 이름을 갱신합니다.
- 현재 계정에 존재하는 모든 인덱스를 나열합니다.

이 과정을 통해 GitHub에서 내려받은 SDK가 올바르게 연결되었는지 확인할 수 있습니다.
