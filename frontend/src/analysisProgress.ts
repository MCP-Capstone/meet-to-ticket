import type { AnalysisProgress, AnalysisTask, ProgressStep } from './api/types'

export const progressSteps: { step: ProgressStep; label: string; doing: string }[] = [
  { step: 'CONTEXT', label: '맥락 파악', doing: '프로젝트 맥락을 불러오고 있어요' },
  { step: 'EXTRACTING', label: '티켓 추출', doing: '회의록에서 티켓 초안을 만들고 있어요' },
  { step: 'DEDUPING', label: '기존 이슈 확인', doing: '기존 이슈와 겹치는 업무를 확인하고 있어요' },
  { step: 'DISPATCHING', label: '담당자에게 전송', doing: '담당자에게 보낼 초안을 정리하고 있어요' },
]

export type ProgressState = 'waiting' | 'active' | 'done' | 'unknown' | 'stopped'

export const progressStateLabels: Record<ProgressState, string> = {
  waiting: '대기', active: '진행 중', done: '완료', unknown: '진행 정보 없음', stopped: '확인 필요',
}

export function progressIndex(task: AnalysisTask | null) {
  return progressSteps.findIndex(({ step }) => step === task?.progress?.step)
}

export function progressHeading(task: AnalysisTask | null) {
  if (!task) return '진행 상황을 불러오고 있어요'
  if (task.status === 'QUEUED') return '분석 순서를 기다리고 있어요'
  if (task.status === 'COMPLETED') return '처리가 끝나 결과를 불러오고 있어요'
  if (task.status === 'FAILED') return '처리하지 못했어요. 결과를 확인하고 있어요'
  if (task.status === 'PARTIALLY_FAILED') return '일부 항목에 문제가 있어 결과를 확인하고 있어요'
  return progressSteps[progressIndex(task)]?.doing ?? '회의록을 처리하고 있어요'
}

// 작업 종료만으로 모든 단계가 성공했거나 실행됐다고 판단하지 않는다.
// 특히 중복 검수는 선택 기능이므로 다음 단계에 도착해도 세부 정보가 없으면 미확인이다.
export function progressStates(task: AnalysisTask | null): ProgressState[] {
  const index = progressIndex(task)
  return progressSteps.map(({ step }, i) => {
    if (!task || task.status === 'QUEUED') return 'waiting'
    if (index < 0) return 'unknown'
    if (i < index) return step === 'DEDUPING' && !task.progress?.dedupe ? 'unknown' : 'done'
    if (i > index) return task.status === 'RUNNING' ? 'waiting' : 'unknown'
    if (task.status === 'COMPLETED') return 'done'
    if (task.status === 'FAILED' || task.status === 'PARTIALLY_FAILED') return 'stopped'
    return 'active'
  })
}

export function progressCount(value: unknown, unit = '건') {
  return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
    ? `${value.toLocaleString('ko-KR')}${unit}` : '확인 중'
}

export function progressDetails(progress: AnalysisProgress | null | undefined) {
  const groups: { title: string; values: { label: string; value: string }[] }[] = []
  if (progress?.context) groups.push({ title: '프로젝트 맥락', values: [
    { label: '멤버', value: progressCount(progress.context.members, '명') },
    { label: '기존 이슈', value: progressCount(progress.context.issues) },
    { label: 'Notion 문서', value: progressCount(progress.context.notionDocs) },
  ] })
  if (progress?.extraction) groups.push({ title: '추출 결과', values: [
    { label: '할 일', value: progressCount(progress.extraction.todos) },
    { label: '결정', value: progressCount(progress.extraction.decisions) },
    { label: '미결 질문', value: progressCount(progress.extraction.openQuestions) },
  ] })
  if (progress?.dedupe) groups.push({ title: '중복 검수 현황', values: [
    { label: '검수 처리 / 전체', value: `${progressCount(progress.dedupe.done)} / ${progressCount(progress.dedupe.total)}` },
    { label: '기존 이슈 연결', value: progressCount(progress.dedupe.linked) },
  ] })
  return groups
}
