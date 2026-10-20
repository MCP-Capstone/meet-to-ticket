const steps = ['회의록 입력', '할 일 추출', '결과 확인'] as const

interface Props {
  current: 0 | 1 | 2
}

export function Stepper({ current }: Props) {
  return (
    <ol className="stepper">
      {steps.map((label, i) => (
        <li key={label} className={i === current ? 'active' : i < current ? 'done' : ''}>
          <span className="step-num">{i < current ? '✓' : i + 1}</span>
          {label}
        </li>
      ))}
    </ol>
  )
}
