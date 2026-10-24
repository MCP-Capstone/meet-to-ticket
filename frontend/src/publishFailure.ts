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
  RESULT_LOST: '발행 중에 서비스가 다시 시작돼 결과를 잃었습니다. 대상에 반영됐는지 확인한 뒤 다시 시도하세요.',
  PUBLISH_DEADLINE: '발행 마감 시간 안에 끝내지 못했습니다. 처리 중이던 티켓은 대상에 반영됐는지 확인한 뒤, 나머지는 바로 다시 시도하세요.',
  COMMENT_FAILED: '이슈 본문은 갱신됐지만 알림 댓글을 확인하지 못했습니다. 알림 댓글만 다시 보낼 수 있습니다.',
  NOTION_VERIFY_REQUIRED: '지난 Notion 발행 결과를 몰라 중복 페이지를 막으려고 다시 만들지 않았습니다. Notion에서 확인한 결과를 알려 주세요.',
  SLACK_VERIFY_REQUIRED: '지난 Slack 공지 결과를 몰라 중복 게시를 막으려고 다시 보내지 않았습니다. Slack 채널에서 확인한 결과를 알려 주세요.',
}

export function failureGuide(failure: Pick<PublishFailure, 'errorCode' | 'errorMessage'>): string {
  const code = failure.errorCode
  return (code && guides[code]) || failure.errorMessage || '발행에 실패했습니다.'
}

// 이미 반영됐는지 찾는 도구가 없는 대상(Notion, Slack)의 결과 모름은 다시 시도해도 Agent가 막는다.
// 어떤 대상이 그런지는 백엔드가 정해 resolution으로 내려주고, 화면은 이 값만 본다 (#280).
export const needsOutcomeCheck = (failure: PublishFailure) => failure.resolution === 'MANUAL_CHECK'

// 권한 문제는 다시 시도해도 같은 결과이므로 먼저 권한을 풀어야 한다.
export const needsPermissionFix = (failure: PublishFailure) => failure.failureCategory === 'REPOSITORY_PERMISSION'

export const canRetryFailure = (failure: PublishFailure | null) => Boolean(failure?.retryable && !needsOutcomeCheck(failure))
export const canRetryComment = (ticket: { status: string; publishTarget: string; publishFailure: PublishFailure | null }) =>
  ticket.status === 'UPDATED' && ticket.publishTarget === 'GITHUB' && ticket.publishFailure?.errorCode === 'COMMENT_FAILED'
