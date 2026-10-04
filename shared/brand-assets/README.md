# SNS 브랜드 에셋

2026-10-04에 각 플랫폼의 공식 브랜드 배포 자료에서 확보했다. `svg/`는 앱이 사용하는 벡터 원본이며 `sources.json`에 출처, 배포 파일, 변환 방식과 SHA-256을 기록한다. 타사 아이콘 라이브러리의 로고를 공식 파일로 대신 표기하지 않는다.

| SNS | 공식 출처 | 사용한 원본 |
| --- | --- | --- |
| Instagram | [Meta 브랜드 리소스](https://www.meta.com/brand/resources/instagram/instagram-brand/) | `IG_brand_asset_pack_2023.zip`의 `Instagram_Glyph_White.svg` |
| Facebook | [Meta Facebook 로고](https://www.meta.com/brand/resources/facebook/logo/) | `Facebook-Brand-Asset-Pack.zip`의 `Facebook_Logo_Primary.ai` |
| TikTok | [TikTok 개발자 디자인 자료](https://developers.tiktok.com/docs/en/getting-started-design-guidelines) | `logo-pack.zip`의 `TikTok_Icon_Black.ai`, 첫 아트보드 |
| X | [X 브랜드 툴킷](https://about.x.com/es/who-we-are/brand-toolkit) | `x-logo.zip`의 `logo.svg` |
| Reddit | [Reddit 브랜드](https://redditinc.com/brand) → [공식 Social 리소스](https://redditbrand.lingoapp.com/s/PXzmWE?v=46) | v5.1의 `Reddit_Icon_FullColor_Bleed.svg` |

Facebook·TikTok은 공식 배포 파일에 단독 SVG가 없어 PDF 호환 Illustrator 원본을 PyMuPDF 1.26.6으로 SVG로 내보냈다. 로고의 경로와 색상은 유지하고 아트보드의 바깥 여백만 `viewBox`로 조정했다. Instagram·X SVG는 배포 파일의 바이트를 그대로 보관했다. Reddit SVG는 줄 끝의 공백만 제거했으며 원본 파일 해시도 별도로 기록했다. 재다운로드 가능한 공식 원본의 이름과 해시는 `sources.json`에 있으며 대형 원본 ZIP·Illustrator 파일은 앱에 포함하지 않는다.

Android는 프로젝트 AGP에 포함된 `sdk-common 31.13.2`의 `Svg2Vector.parseSvgToXml`로 가져온 `res/drawable/provider_*.xml`을 사용한다. iOS는 같은 SVG를 `Provider*.imageset`에 원본 색상과 벡터 보존 설정으로 포함한다. 업데이트할 때 두 플랫폼의 리소스를 함께 갱신하고 실제 렌더링을 확인한다. Android의 SVG → VectorDrawable 변환은 SVG의 일부 방사형 그라데이션 표현을 근사하므로 Reddit의 미세한 음영은 원본 SVG와 차이가 있을 수 있다.

앱에서는 로고를 `Image`로 표시하고 색을 덧씌우지 않는다. Instagram·X·TikTok 로고 뒤의 배경, 카드 배경·띠·액션 색상은 플랫폼을 구분하는 앱 UI이다. 에셋은 패키지 안의 네이티브 리소스로 읽어 네트워크 다운로드, WebView, 추가 SVG 런타임 의존성 없이 표시한다.

상표와 로고의 권리는 각 플랫폼에 있으며 해당 공식 브랜드 이용 지침을 따른다. 이 파일은 로고에 별도의 오픈 소스 라이선스를 부여하거나 플랫폼의 승인·제휴를 표시하지 않는다.
