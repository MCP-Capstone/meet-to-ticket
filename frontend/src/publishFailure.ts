import type { PublishFailure } from './api'

// 실패 코드별 안내. 모르는 코드는 서버가 준 사유를 그대로 보여 준다.
const guides: Record<string, string> = {
  GITHUB_FORBIDDEN: 'GitHub 저장소 권한이 없습니다. 프로젝트 관리자에게 저장소 권한 확인을 요청한 뒤 다시 시도하세요.',
  GITHUB_AUTH: 'GitHub 인증에 실패했습니다. 프로젝트 관리자에게 저장소 권한 확인을 요청한 뒤 다시 시도하세요.',
  REPOSITORY_NOT_ALLOWED: '발행이 허용되지 않은 저장소입니다. 프로젝트 설정의 저장소를 확인하세요.',
  GITHUB_RATE_LIMIT: 'GitHub 호출 한도를 넘었습니다. 잠시 후 다시 시도하세요.',
  GITHUB_NOT_FOUND: '저장소나 대상 이슈를 찾지 못했습니다. 프로젝트 설정과 이슈 번호를 확인하세요.',
  GITHUB_VALIDATION: 'GitHub가 요청 내용을 받아들이지 않았습니다. 티켓 내용을 고친 뒤 다시 확인하세요.',
  MARKER_CHECK_INCOMPLETE: '댓글이 많아 이미 반영됐는지 확인하지 못했습니다. GitHub에서 직접 확인하세요.',
  RESULT_TIMEOUT: '기한 안에 발행 결과를 받지 못했습니다.',
  RESULT_UNKNOWN: '요청은 보냈지만 결과를 받지 못했습니다.',
  RESULT_MISSING: '발행 결과를 받지 못했습니다.',
}

export function failureGuide(failure: PublishFailure): string {
  const code = failure.errorCode
  return (code && guides[code]) || failure.errorMessage || '발행에 실패했습니다.'
}

// 권한 문제는 다시 시도해도 같은 결과이므로 먼저 권한을 풀어야 한다.
export const needsPermissionFix = (failure: PublishFailure) => failure.failureCategory === 'REPOSITORY_PERMISSION'
