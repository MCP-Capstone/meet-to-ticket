package com.mcpcapstone.meettoticket.common.config;

// 문서용 가상 예시. 토큰·실제 업무 결과를 포함하지 않는다.
public final class ApiExamples {
    private ApiExamples() {}

    public static final String PROJECTS = """
            {
              "projects": [
                {
                  "projectId": 1,
                  "name": "CSV 내보내기"
                }
              ],
              "executionMode": "MOCK"
            }
            """;

    public static final String ERROR_UNAUTHORIZED = """
            {
              "code": "UNAUTHORIZED",
              "message": "로그인이 필요합니다."
            }
            """;

    public static final String MEMBERS = """
            {
              "projectId": 1,
              "members": [
                {
                  "projectMemberId": 11,
                  "userId": null,
                  "name": "박종민",
                  "githubName": null
                }
              ]
            }
            """;

    public static final String ERROR_VALIDATION_FAILED = """
            {
              "code": "VALIDATION_FAILED",
              "message": "입력값을 확인해 주세요."
            }
            """;

    public static final String ERROR_PROJECT_ACCESS_DENIED = """
            {
              "code": "PROJECT_ACCESS_DENIED",
              "message": "접근할 수 없는 프로젝트입니다."
            }
            """;

    public static final String MEETING = """
            {
              "meetingId": 2,
              "projectId": 1,
              "title": "CSV 내보내기 중간 점검",
              "meetingDate": "2026-10-22",
              "participantIds": [
                11,
                12
              ],
              "originalFilename": "meeting.txt",
              "readyForAnalysis": true
            }
            """;

    public static final String ERROR_PARTICIPANT_MAPPING_FAILED = """
            {
              "code": "PARTICIPANT_MAPPING_FAILED",
              "message": "미등록 또는 동명이인 참여자가 있어 연결할 수 없습니다."
            }
            """;

    public static final String ERROR_MEETING_NOT_FOUND = """
            {
              "code": "MEETING_NOT_FOUND",
              "message": "회의를 찾을 수 없습니다."
            }
            """;

    public static final String ANALYSIS_ACCEPTED = """
            {
              "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
              "meetingId": 2,
              "status": "QUEUED",
              "executionMode": "MOCK"
            }
            """;

    public static final String ERROR_INVALID_MEETING_INPUT = """
            {
              "code": "INVALID_MEETING_INPUT",
              "message": "회의 날짜와 회의록(UTF-8 .md·.txt 파일 또는 붙여넣은 텍스트 중 하나)을 확인해 주세요."
            }
            """;

    public static final String ERROR_ANALYSIS_ALREADY_REQUESTED = """
            {
              "code": "ANALYSIS_ALREADY_REQUESTED",
              "message": "이미 분석을 요청한 회의입니다."
            }
            """;

    public static final String ERROR_AGENT_ACCEPTANCE_FAILED = """
            {
              "code": "AGENT_ACCEPTANCE_FAILED",
              "message": "Agent 실행 접수를 확인하지 못했습니다."
            }
            """;

    public static final String STATUS_RUNNING = """
            {
              "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
              "meetingId": 2,
              "status": "RUNNING",
              "stage": "REVIEWING",
              "executionMode": "MOCK",
              "createdAt": "2026-10-22T10:31:00+09:00",
              "finishedAt": null,
              "errorMessage": null
            }
            """;

    public static final String ERROR_TASK_NOT_FOUND = """
            {
              "code": "TASK_NOT_FOUND",
              "message": "분석 작업을 찾을 수 없습니다."
            }
            """;

    public static final String ITEMS_MOCK = """
            {
              "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
              "status": "COMPLETED",
              "executionMode": "MOCK",
              "items": [
                {
                  "itemId": "3c510e28-c776-43ce-85cc-6c90693d31f5",
                  "workKey": null,
                  "title": "[모의 실행] 후속 업무 1",
                  "contentJson": {
                    "background": "Agent 없이 저장 흐름을 확인하는 고정 모의 데이터",
                    "todo": [
                      "저장·조회 확인"
                    ],
                    "acceptanceCriteria": [
                      "결과 화면 표시"
                    ],
                    "assignees": [
                      {
                        "projectMemberId": 11,
                        "mentionedName": "박종민",
                        "githubName": null,
                        "mappingStatus": "MOCK"
                      }
                    ],
                    "due": {
                      "date": null,
                      "time": null,
                      "qualifier": null,
                      "originalText": null
                    },
                    "revision": 1,
                    "review": {
                      "correctionCount": 0,
                      "reviewedRevision": 1,
                      "reason": "[MOCK] 통과를 가정한 결과",
                      "approvedPayload": {
                        "owner": "MCP-Capstone",
                        "repo": "mcp-capstone",
                        "title": "[모의 실행] 후속 업무 1",
                        "body": "[MOCK] 실제 발행 없음",
                        "assignees": []
                      }
                    },
                    "publication": {
                      "mock": true,
                      "verified": false
                    }
                  },
                  "action": "CREATE",
                  "reason": "[MOCK] 고정 시나리오",
                  "reviewStatus": "APPROVED",
                  "publishStatus": "SKIPPED",
                  "githubIssueNumber": null,
                  "githubIssueUrl": null,
                  "errorMessage": null
                }
              ],
              "summary": {
                "total": 1,
                "approved": 1,
                "rejected": 0,
                "succeeded": 0,
                "failed": 0,
                "unknown": 0,
                "skipped": 1
              }
            }
            """;

    public static final String ERROR_TASK_NOT_FINISHED = """
            {
              "code": "TASK_NOT_FINISHED",
              "message": "처리 중입니다. 진행 상태를 계속 조회해 주세요."
            }
            """;

    public static final String ITEMS_EMPTY = """
            {
              "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
              "status": "COMPLETED",
              "executionMode": "MOCK",
              "items": [],
              "summary": {
                "total": 0,
                "approved": 0,
                "rejected": 0,
                "succeeded": 0,
                "failed": 0,
                "unknown": 0,
                "skipped": 0
              }
            }
            """;

    public static final String ITEMS_REJECTED = """
            {
              "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
              "status": "COMPLETED",
              "executionMode": "MOCK",
              "items": [
                {
                  "itemId": "3c510e28-c776-43ce-85cc-6c90693d31f5",
                  "workKey": null,
                  "title": "[모의 실행] 후속 업무 1",
                  "contentJson": {
                    "background": "Agent 없이 저장 흐름을 확인하는 고정 모의 데이터",
                    "todo": [
                      "저장·조회 확인"
                    ],
                    "acceptanceCriteria": [
                      "결과 화면 표시"
                    ],
                    "assignees": [
                      {
                        "projectMemberId": 11,
                        "mentionedName": "박종민",
                        "githubName": null,
                        "mappingStatus": "MOCK"
                      }
                    ],
                    "due": {
                      "date": null,
                      "time": null,
                      "qualifier": null,
                      "originalText": null
                    },
                    "revision": 3,
                    "review": {
                      "correctionCount": 2,
                      "reviewedRevision": 3,
                      "reason": "[MOCK] 보완 2회 후 반려",
                      "approvedPayload": null
                    },
                    "publication": {
                      "mock": true,
                      "verified": false
                    }
                  },
                  "action": "CREATE",
                  "reason": "[MOCK] 검수 반려",
                  "reviewStatus": "REJECTED",
                  "publishStatus": "BLOCKED",
                  "githubIssueNumber": null,
                  "githubIssueUrl": null,
                  "errorMessage": null
                }
              ],
              "summary": {
                "total": 1,
                "approved": 0,
                "rejected": 1,
                "succeeded": 0,
                "failed": 0,
                "unknown": 0,
                "skipped": 0
              }
            }
            """;

    public static final String ITEMS_FAILED = """
            {
              "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
              "status": "FAILED",
              "executionMode": "MOCK",
              "items": [
                {
                  "itemId": "3c510e28-c776-43ce-85cc-6c90693d31f5",
                  "workKey": null,
                  "title": "[모의 실행] 후속 업무 1",
                  "contentJson": {
                    "background": "Agent 없이 저장 흐름을 확인하는 고정 모의 데이터",
                    "todo": [
                      "저장·조회 확인"
                    ],
                    "acceptanceCriteria": [
                      "결과 화면 표시"
                    ],
                    "assignees": [
                      {
                        "projectMemberId": 11,
                        "mentionedName": "박종민",
                        "githubName": null,
                        "mappingStatus": "MOCK"
                      }
                    ],
                    "due": {
                      "date": null,
                      "time": null,
                      "qualifier": null,
                      "originalText": null
                    },
                    "revision": 1,
                    "review": {
                      "correctionCount": 0,
                      "reviewedRevision": 1,
                      "reason": "[MOCK] 통과를 가정한 결과",
                      "approvedPayload": {
                        "owner": "MCP-Capstone",
                        "repo": "mcp-capstone",
                        "title": "[모의 실행] 후속 업무 1",
                        "body": "[MOCK] 실제 발행 없음",
                        "assignees": []
                      }
                    },
                    "publication": {
                      "mock": true,
                      "verified": false
                    }
                  },
                  "action": "CREATE",
                  "reason": "[MOCK] 고정 시나리오",
                  "reviewStatus": "APPROVED",
                  "publishStatus": "FAILED",
                  "githubIssueNumber": null,
                  "githubIssueUrl": null,
                  "errorMessage": "[MOCK] 발행 실패 시나리오. 실제 GitHub 호출 없음"
                }
              ],
              "summary": {
                "total": 1,
                "approved": 1,
                "rejected": 0,
                "succeeded": 0,
                "failed": 1,
                "unknown": 0,
                "skipped": 0
              }
            }
            """;

    public static final String ITEMS_PARTIAL = """
            {
              "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
              "status": "PARTIALLY_FAILED",
              "executionMode": "MOCK",
              "items": [
                {
                  "itemId": "3c510e28-c776-43ce-85cc-6c90693d31f5",
                  "workKey": null,
                  "title": "[모의 실행] 후속 업무 1",
                  "contentJson": {
                    "background": "Agent 없이 저장 흐름을 확인하는 고정 모의 데이터",
                    "todo": [
                      "저장·조회 확인"
                    ],
                    "acceptanceCriteria": [
                      "결과 화면 표시"
                    ],
                    "assignees": [
                      {
                        "projectMemberId": 11,
                        "mentionedName": "박종민",
                        "githubName": null,
                        "mappingStatus": "MOCK"
                      }
                    ],
                    "due": {
                      "date": null,
                      "time": null,
                      "qualifier": null,
                      "originalText": null
                    },
                    "revision": 1,
                    "review": {
                      "correctionCount": 0,
                      "reviewedRevision": 1,
                      "reason": "[MOCK] 통과를 가정한 결과",
                      "approvedPayload": {
                        "owner": "MCP-Capstone",
                        "repo": "mcp-capstone",
                        "title": "[모의 실행] 후속 업무 1",
                        "body": "[MOCK] 실제 발행 없음",
                        "assignees": []
                      }
                    },
                    "publication": {
                      "mock": true,
                      "verified": false
                    }
                  },
                  "action": "CREATE",
                  "reason": "[MOCK] 고정 시나리오",
                  "reviewStatus": "APPROVED",
                  "publishStatus": "SKIPPED",
                  "githubIssueNumber": null,
                  "githubIssueUrl": null,
                  "errorMessage": null
                },
                {
                  "itemId": "3c510e28-c776-43ce-85cc-6c90693d31f6",
                  "workKey": null,
                  "title": "[모의 실행] 후속 업무 1",
                  "contentJson": {
                    "background": "Agent 없이 저장 흐름을 확인하는 고정 모의 데이터",
                    "todo": [
                      "저장·조회 확인"
                    ],
                    "acceptanceCriteria": [
                      "결과 화면 표시"
                    ],
                    "assignees": [
                      {
                        "projectMemberId": 11,
                        "mentionedName": "박종민",
                        "githubName": null,
                        "mappingStatus": "MOCK"
                      }
                    ],
                    "due": {
                      "date": null,
                      "time": null,
                      "qualifier": null,
                      "originalText": null
                    },
                    "revision": 1,
                    "review": {
                      "correctionCount": 0,
                      "reviewedRevision": 1,
                      "reason": "[MOCK] 통과를 가정한 결과",
                      "approvedPayload": {
                        "owner": "MCP-Capstone",
                        "repo": "mcp-capstone",
                        "title": "[모의 실행] 후속 업무 1",
                        "body": "[MOCK] 실제 발행 없음",
                        "assignees": []
                      }
                    },
                    "publication": {
                      "mock": true,
                      "verified": false
                    }
                  },
                  "action": "CREATE",
                  "reason": "[MOCK] 고정 시나리오",
                  "reviewStatus": "APPROVED",
                  "publishStatus": "FAILED",
                  "githubIssueNumber": null,
                  "githubIssueUrl": null,
                  "errorMessage": "[MOCK] 발행 실패 시나리오. 실제 GitHub 호출 없음"
                }
              ],
              "summary": {
                "total": 2,
                "approved": 2,
                "rejected": 0,
                "succeeded": 0,
                "failed": 1,
                "unknown": 0,
                "skipped": 1
              }
            }
            """;

    public static final String ITEMS_LIVE_FORMAT = """
            {
              "taskId": "9b12ec5a-1423-4b71-8e20-f8f28d306c81",
              "status": "COMPLETED",
              "executionMode": "LIVE",
              "items": [
                {
                  "itemId": "3c510e28-c776-43ce-85cc-6c90693d31f5",
                  "workKey": null,
                  "title": "CSV 빈 결과 오류 수정",
                  "contentJson": {
                    "description": "응답 형식 설명용 가상 예시. 실제 이슈 생성 결과가 아님",
                    "assignees": {
                      "names": [
                        "박종민"
                      ],
                      "github": [
                        "jongmin-example"
                      ],
                      "unresolved": []
                    },
                    "acceptance_criteria": [
                      "0건 CSV 정상 반환"
                    ],
                    "review": {
                      "revisions": 0,
                      "fallback": false,
                      "failed_items": []
                    }
                  },
                  "action": "CREATE",
                  "reason": "회의에서 실행하기로 한 업무",
                  "reviewStatus": "APPROVED",
                  "publishStatus": "SUCCEEDED",
                  "githubIssueNumber": 1,
                  "githubIssueUrl": "https://github.com/MCP-Capstone/mcp-capstone/issues/1",
                  "errorMessage": null
                }
              ],
              "summary": {
                "total": 1,
                "approved": 1,
                "rejected": 0,
                "succeeded": 1,
                "failed": 0,
                "unknown": 0,
                "skipped": 0
              }
            }
            """;

    public static final String AUTH_USER = """
            {"userId": 1, "loginId": "demo1", "name": "함주헌", "githubName": null}
            """;
}
