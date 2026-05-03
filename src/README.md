# src

이 디렉터리는 현재 자동화 시스템의 두 서브시스템을 담는다.

```text
src/
  pension/  # Python ADB controller for IRP/DC pension workflows
  login/    # Android login helper for certificate login only
```

`pension`이 개인형 IRP/DC 퇴직연금 흐름과 검증을 소유한다. `login`은 공동인증서 TransKey 로그인처럼 ADB 캡처/입력이 막히는 보안 입력만 담당한다.

공동인증서 비밀번호는 Python에서 broadcast extra로 전달하지 않는다. Android helper 내부 Android Keystore 기반 암호화 저장소에 저장하고, Python은 `LOGIN` 같은 명령만 보낸다.
