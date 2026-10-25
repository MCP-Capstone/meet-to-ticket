import { useEffect, useRef } from 'react'

export type WorkspacePage = 'my-tickets' | 'workflow' | 'project-list' | 'project-editor'
type IconName = 'tickets' | 'meeting' | 'projects' | 'collapse' | 'expand' | 'menu' | 'close' | 'logout'
const paths: Record<IconName, string> = {
  tickets: 'M4 5h16v5a2 2 0 0 0 0 4v5H4v-5a2 2 0 0 0 0-4V5Zm5 0v3m0 3v2m0 3v3',
  meeting: 'M7 3h7l4 4v14H4V3h3Zm7 0v5h4M8 12h6m-6 4h6',
  projects: 'M3 7h7l2 2h9v11H3V7Zm0 0V4h7l2 3',
  collapse: 'M4 4h16v16H4V4Zm5 0v16m7-12-4 4 4 4',
  expand: 'M4 4h16v16H4V4Zm5 0v16m4-12 4 4-4 4',
  menu: 'M4 6h16M4 12h16M4 18h16', close: 'm6 6 12 12M6 18 18 6',
  logout: 'M10 4H4v16h6m5-12 4 4-4 4m-7-4h11',
}
function Icon({ name }: { name: IconName }) {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]} /></svg>
}
interface Props {
  page: WorkspacePage
  name: string
  onNavigate: (page: WorkspacePage) => boolean
  disabled: Record<'tickets' | 'meeting' | 'projects', boolean>
  logoutDisabled: boolean
  loggingOut: boolean
  onLogout: () => void
}
export function WorkspaceNavigation({ page, name, onNavigate, disabled, logoutDisabled, loggingOut, onLogout }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const opener = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const media = window.matchMedia('(max-width: 820px)')
    const closeOnDesktop = () => { if (!media.matches) dialog.current?.close() }
    media.addEventListener('change', closeOnDesktop)
    return () => media.removeEventListener('change', closeOnDesktop)
  }, [])
  const items = [
    { page: 'my-tickets', icon: 'tickets', label: '내 티켓', disabled: disabled.tickets },
    { page: 'workflow', icon: 'meeting', label: '회의록 등록', disabled: disabled.meeting },
    { page: 'project-list', icon: 'projects', label: '프로젝트 관리', disabled: disabled.projects },
  ] as const
  const navigation = <nav className="dashboard-nav" aria-label="서비스 메뉴">{items.map(item => {
    const active = page === item.page || item.page === 'project-list' && page === 'project-editor'
    return <button key={item.page} type="button" aria-label={item.label} aria-current={active ? 'page' : undefined} disabled={item.disabled} onClick={() => { if (onNavigate(item.page)) dialog.current?.close() }}><Icon name={item.icon} /><span className="nav-label">{item.label}</span><span className="nav-tooltip" aria-hidden="true">{item.label}</span></button>
  })}</nav>
  const account = <div className="dashboard-account"><div className="dashboard-user"><span className="avatar" aria-hidden="true">{name.slice(0, 1)}</span><span className="nav-label"><strong>{name}</strong></span></div><button className="dashboard-logout" type="button" aria-label="로그아웃" title="로그아웃" disabled={logoutDisabled} onClick={onLogout}><Icon name="logout" /><span className="nav-label">{loggingOut ? '로그아웃 중...' : '로그아웃'}</span></button></div>
  return <>
    <aside className="desktop-sidebar" aria-label="작업 공간 탐색">
      <div className="dashboard-brand"><span className="brand-symbol" aria-hidden="true">M</span><strong className="nav-label">Meet-to-Ticket</strong></div>
      <div id="desktop-navigation">{navigation}</div>
    </aside>
    <div className="mobile-topbar"><button ref={opener} type="button" aria-label="메뉴 열기" aria-haspopup="dialog" aria-controls="mobile-navigation" onClick={() => dialog.current?.showModal()}><Icon name="menu" /></button><strong>Meet-to-Ticket</strong><span className="avatar" title={name} aria-label={`로그인 사용자 ${name}`}>{name.slice(0, 1)}</span></div>
    <dialog id="mobile-navigation" className="mobile-navigation" ref={dialog} aria-labelledby="mobile-menu-title" onClose={() => opener.current?.focus()} onClick={event => { if (event.target === event.currentTarget) { const r = event.currentTarget.getBoundingClientRect(); if (event.clientX > r.right || event.clientX < r.left) dialog.current?.close() } }}>
      <div className="mobile-drawer-content"><div className="mobile-drawer-heading"><strong id="mobile-menu-title">Meet-to-Ticket</strong><button type="button" aria-label="메뉴 닫기" onClick={() => dialog.current?.close()}><Icon name="close" /></button></div>{navigation}{account}</div>
    </dialog>
  </>
}
