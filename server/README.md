# Follower Tracker connection service

일반 사용자는 YouTube 채널 링크와 Facebook 로그인·페이지 선택만 사용한다. Google 키·Meta 앱 시크릿·선택한 페이지 토큰은 서버가 관리한다.

[운영자 설정·발급·HTTPS 연결·보호 계약](../docs/OFFICIAL_API_SETUP.md)을 먼저 확인한다. Node.js 24 이상, 단일 프로세스와 영구 SQLite 디스크가 필요하며 별도 npm 의존성은 없다.

```sh
node --test server/test/*.test.mjs
# 비밀 환경변수를 주입한 뒤, server/에서
node src/main.mjs
```

기본 실행은 loopback에만 바인딩한다. 실제 공개 배포·운영 프로젝트·권한 심사는 아직 완료하지 않았다. 테스트는 합성 응답을 사용하며 Google·Facebook 계정이나 실제 토큰을 요구하지 않는다.
