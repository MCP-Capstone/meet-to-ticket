import type { DirectoryUser, ProjectDetail, ProjectMember, ProjectRole, ProjectSaveRequest, User } from '../api/types'
import { ApiRequestError } from '../api/client'
import { mockMembers, mockProject } from './data'

// 화면 개발용 데이터다. GitHub·Notion·Slack에 연결하거나 실제 권한을 검사하지 않는다.
const userIds = [2, 3, 4, 5, 1, 6]
export const mockDirectoryUsers: DirectoryUser[] = mockMembers.map((member, index) => ({
  userId: userIds[index], name: member.name, githubName: index === 5 ? null : member.githubName,
}))

type StoredProject = Omit<ProjectDetail, 'canEdit' | 'members'> & {
  adminUserIds: number[]
  members: (Omit<ProjectDetail['members'][number], 'admin'> & { projectMemberId: number })[]
}
const STORAGE_KEY = 'mock-project-editor-v1'
const seed: StoredProject = {
  ...mockProject, description: '회의에서 정한 CSV 내보내기 업무를 정리합니다.',
  githubOwner: 'MCP-Capstone', githubRepo: 'mcp-capstone', notionPageUrl: null, slackChannelId: null,
  adminUserIds: [1],
  connections: { github: null, notion: 'NOT_CONNECTED', slack: 'NOT_CONNECTED' },
  members: mockDirectoryUsers.map((user, index) => ({
    ...user, projectMemberId: index + 1,
    repositoryPermission: !user.githubName ? 'NO_ACCOUNT' : null,
  })),
}

function readProjects(): StoredProject[] {
  try {
    const data = sessionStorage.getItem(STORAGE_KEY)
    if (data) return JSON.parse(data) as StoredProject[]
  } catch { /* 저장소를 읽을 수 없으면 초기 데이터로 시작한다. */ }
  return [structuredClone(seed)]
}

function writeProjects(projects: StoredProject[]) {
  // 실패를 삼키지 않아 저장되지 않은 프로젝트를 저장 성공으로 표시하지 않는다.
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(projects))
}
const fail = (status: number, code: string, message: string) => new ApiRequestError(status, { code, message })
const hasAccess = (project: StoredProject, user: User) =>
  project.adminUserIds.includes(user.userId) || project.members.some((member) => member.userId === user.userId)

function findProject(projectId: number, user: User) {
  const project = readProjects().find((item) => item.projectId === projectId)
  if (!project) throw fail(404, 'PROJECT_NOT_FOUND', '프로젝트를 찾을 수 없습니다.')
  if (!hasAccess(project, user)) throw fail(403, 'PROJECT_ACCESS_DENIED', '프로젝트에 접근할 수 없습니다.')
  return project
}

function toDetail(project: StoredProject, user: User): ProjectDetail {
  const { adminUserIds, ...detail } = project
  return { ...detail, members: detail.members.map((member) => ({ ...member, admin: adminUserIds.includes(member.userId) })), canEdit: adminUserIds.includes(user.userId) }
}

export function listMockProjects(user: User) {
  return readProjects().filter((project) => hasAccess(project, user)).map(({ projectId, name }) => ({ projectId, name }))
}

export function getMockProject(projectId: number, user: User) {
  return toDetail(findProject(projectId, user), user)
}

export function listMockMembers(projectId: number, user: User): ProjectMember[] {
  return findProject(projectId, user).members.map(({ projectMemberId, userId, name, githubName }) => ({
    projectMemberId, userId, name, githubName,
  }))
}

export function changeMockProjectRole(projectId: number, targetUserId: number, role: ProjectRole, user: User) {
  const project = findProject(projectId, user)
  if (!project.adminUserIds.includes(user.userId)) {
    throw fail(403, 'PROJECT_ADMIN_REQUIRED', '프로젝트 관리자만 수정할 수 있습니다.')
  }
  if (role !== 'ADMIN' && role !== 'MEMBER') throw fail(400, 'VALIDATION_FAILED', '역할을 선택해 주세요.')
  if (!project.members.some((member) => member.userId === targetUserId)) {
    throw fail(404, 'PROJECT_MEMBER_NOT_FOUND', '프로젝트 멤버를 찾을 수 없습니다.')
  }
  const wasAdmin = project.adminUserIds.includes(targetUserId)
  if (role === 'MEMBER' && wasAdmin && project.adminUserIds.length <= 1) {
    throw fail(409, 'LAST_PROJECT_ADMIN', '프로젝트 관리자는 최소 1명 이상이어야 합니다.')
  }
  project.adminUserIds = role === 'ADMIN'
    ? [...new Set([...project.adminUserIds, targetUserId])]
    : project.adminUserIds.filter((id) => id !== targetUserId)
  writeProjects(readProjects().map((item) => item.projectId === projectId ? project : item))
  return toDetail(project, user)
}

export function saveMockProject(body: ProjectSaveRequest, user: User, directory: DirectoryUser[], projectId?: number) {
  const projects = readProjects()
  const previous = projectId === undefined ? undefined : findProject(projectId, user)
  if (previous && !previous.adminUserIds.includes(user.userId)) {
    throw fail(403, 'PROJECT_ADMIN_REQUIRED', '프로젝트 관리자만 수정할 수 있습니다.')
  }
  if (!body.name.trim() || !body.githubOwner || !body.githubRepo) {
    throw fail(400, 'VALIDATION_FAILED', '프로젝트 이름과 GitHub 저장소를 입력해 주세요.')
  }
  if (projects.some((project) => project.projectId !== projectId && project.githubOwner.toLowerCase() === body.githubOwner.toLowerCase() && project.githubRepo.toLowerCase() === body.githubRepo.toLowerCase())) {
    throw fail(409, 'DUPLICATE_PROJECT', '이미 등록된 GitHub 저장소입니다.')
  }
  // 신규 등록자는 자동 관리자·멤버가 된다. 기존 관리자는 멤버 선택과 별도로 유지한다.
  const memberIds = [...new Set([...body.memberUserIds, ...(previous?.adminUserIds ?? [user.userId])])]
  let nextMemberId = Math.max(0, ...(previous?.members.map((member) => member.projectMemberId) ?? [])) + 1
  const members = memberIds.map((userId): StoredProject['members'][number] => {
    const member = directory.find((item) => item.userId === userId)
    if (!member) throw fail(400, 'USER_NOT_FOUND', '가입한 사용자만 멤버로 추가할 수 있습니다.')
    return {
      ...member, projectMemberId: previous?.members.find((item) => item.userId === userId)?.projectMemberId ?? nextMemberId++,
      repositoryPermission: !member.githubName ? 'NO_ACCOUNT' : null,
    }
  })
  const record: StoredProject = {
    projectId: projectId ?? Math.max(0, ...projects.map((project) => project.projectId)) + 1,
    name: body.name, description: body.description, githubOwner: body.githubOwner, githubRepo: body.githubRepo,
    notionPageUrl: body.notionPageUrl, slackChannelId: body.slackChannelId,
    members, adminUserIds: previous?.adminUserIds ?? [user.userId],
    connections: {
      github: null, notion: body.notionPageUrl ? 'CONNECTED' : 'NOT_CONNECTED',
      slack: body.slackChannelId ? 'CONNECTED' : 'NOT_CONNECTED',
    },
  }
  writeProjects(previous ? projects.map((project) => project.projectId === projectId ? record : project) : [...projects, record])
  return toDetail(record, user)
}
