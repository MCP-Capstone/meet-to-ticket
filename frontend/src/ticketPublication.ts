import type { MyTicket, PublishTarget, ProcessingType, TicketPublicationRequest } from './api/types'

// 이슈 번호를 추정하지 않고 목록 API가 제공한 열린 기존 이슈만 사용한다.
export function canUseExistingIssue(ticket: Pick<MyTicket, 'dedupe'>): boolean {
  const issue = ticket.dedupe?.issue
  return Boolean(issue && Number.isInteger(issue.number) && issue.number > 0 && issue.state === 'open')
}
export function publicationRequest(publishTarget: PublishTarget, processingType: ProcessingType): TicketPublicationRequest {
  return { publishTarget, processingType: publishTarget === 'GITHUB' ? processingType : 'CREATE' }
}
