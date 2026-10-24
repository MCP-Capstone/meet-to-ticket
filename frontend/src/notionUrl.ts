// business-mcp normalize_page_id의 _SCHEMELESS_NOTION과 같은 규칙이다.
const SCHEMELESS_NOTION = /^(?:[a-z0-9-]+\.)*notion\.(?:so|site|com)(?:[/?#]|$)/i

// www.notion.so/... 처럼 scheme 없이 붙여 넣은 Notion 주소에 https://를 붙인다.
// 백엔드가 https:// 시작만 허용하므로 저장 전에 맞춘다. 그 밖의 값은 trim만 한다.
export function normalizeNotionPageUrl(value: string) {
  const trimmed = value.trim()
  return SCHEMELESS_NOTION.test(trimmed) ? `https://${trimmed}` : trimmed
}
