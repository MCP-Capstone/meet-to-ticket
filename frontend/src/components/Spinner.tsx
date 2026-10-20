interface Props {
  // sm: 버튼 / 문장 안, lg: 화면 가운데
  size?: 'sm' | 'lg'
  // 스크린리더용 설명
  label?: string
}

export function Spinner({ size = 'lg', label = '처리 중' }: Props) {
  return <span className={`spinner ${size}`} role="status" aria-label={label} />
}
