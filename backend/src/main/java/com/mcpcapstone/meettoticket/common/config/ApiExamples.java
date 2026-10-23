package com.mcpcapstone.meettoticket.common.config;

// 문서용 가상 예시. 토큰·실제 업무 결과를 포함하지 않는다.
public final class ApiExamples {
    private ApiExamples() {}

    public static final String PROJECTS = """
            {
              "projects": [
                {
                  "projectId": 1,
                  "name": "CSV 내보내기",
                  "githubOwner": "MCP-Capstone", "githubRepo": "mcp-capstone",
                  "connections": {"github": "CONNECTED", "notion": "NOT_CONNECTED", "slack": null},
                  "memberCount": 6, "lastMeetingDate": "2026-10-23", "canEdit": true
                }
              ],
              "executionMode": "MOCK"
            }
            """;

    public static final String PROJECT_DETAIL = """
            {
              "projectId": 1,
              "name": "CSV 내보내기",
              "description": "관리자 사용자 목록 내보내기",
              "githubOwner": "MCP-Capstone",
              "githubRepo": "mcp-capstone",
              "notionPageUrl": null,
              "slackChannelId": null,
              "members": [
                {
                  "userId": 1,
                  "name": "함주헌",
                  "githubName": "juhun-ham",
                  "admin": true,
                  "repositoryPermission": null
                },
                {
                  "userId": 2,
                  "name": "허민규",
                  "githubName": null,
                  "admin": false,
                  "repositoryPermission": "NO_ACCOUNT"
                }
              ],
              "connections": { "github": null, "notion": "NOT_CONNECTED", "slack": "NOT_CONNECTED" },
              "canEdit": true
            }
            """;

    public static final String USERS = """
            {
              "users": [
                { "userId": 1, "name": "함주헌", "githubName": "juhun-ham" }
              ]
            }
            """;

    public static final String ERROR_PROJECT_ADMIN_REQUIRED = """
            {
              "code": "PROJECT_ADMIN_REQUIRED",
              "message": "프로젝트 관리자만 편집할 수 있습니다."
            }
            """;

    public static final String DISPATCH_RESULT = """
            {"taskId":"9b12ec5a-1423-4b71-8e20-f8f28d306c81",
             "counts":{"total":2,"pendingReview":1,"linked":0,"assigneeRequired":1},
             "tickets":[{"ticket":{"ticketId":"3c510e28-c776-43ce-85cc-6c90693d31f5","title":"CSV 빈 결과 오류 수정",
                 "status":"PENDING_REVIEW","processingType":"CREATE","publishTarget":"GITHUB",
                 "dedupe":{"judged":false,"skipped":null,"relation":null,"issue":null,"overlap":false,"reason":null,"before":null,"after":null,"changed":false},
                 "projectId":1,"projectName":"CSV 내보내기","meetingId":2,"meetingTitle":"CSV 중간 점검",
                 "taskId":"9b12ec5a-1423-4b71-8e20-f8f28d306c81","createdAt":"2026-10-22T10:31:00+09:00",
                 "description":"필터 결과가 0건이면 헤더만 있는 CSV를 내려준다.",
                 "due":{"text":"내일 오후 5시","date":"2026-10-23","time":"17:00"},
                 "assignee":{"projectMemberId":11,"name":"박종민"}},
               "mine":false,
               "preview":{"body":"## 작업 내용\\n필터 결과가 0건이면 헤더만 있는 CSV를 내려준다.\\n","labels":["bug"],
                 "evidenceQuotes":["박종민이 내일 오후 5시까지 수정한다."]}},
              {"ticket":{"ticketId":"7d1f0c2a-55b1-4c0e-9a3e-2f6b8e0c9d11","title":"CSV 내보내기 통합 QA",
                 "status":"ASSIGNEE_REQUIRED","processingType":"CREATE","publishTarget":"GITHUB",
                 "dedupe":{"judged":false,"skipped":null,"relation":null,"issue":null,"overlap":false,"reason":null,"before":null,"after":null,"changed":false},
                 "projectId":1,"projectName":"CSV 내보내기","meetingId":2,"meetingTitle":"CSV 중간 점검",
                 "taskId":"9b12ec5a-1423-4b71-8e20-f8f28d306c81","createdAt":"2026-10-22T10:31:00+09:00",
                 "description":"내보내기 전체 흐름을 함께 점검한다.","due":{"text":null,"date":null,"time":null},"assignee":null},
               "mine":false,"preview":{"body":"## 작업 내용\\n내보내기 전체 흐름을 함께 점검한다.\\n","labels":[],"evidenceQuotes":[]}}],
             "canAssign":true,
             "members":[{"memberId":11,"name":"박종민"},{"memberId":12,"name":"함주헌"}]}""";

    public static final String ERROR_TICKET_UPLOADER_REQUIRED = """
            {
              "code": "TICKET_UPLOADER_REQUIRED",
              "message": "회의록을 올린 사람만 대표 담당을 지정할 수 있습니다."
            }
            """;

    public static final String ERROR_TICKET_NOT_FOUND = """
            {
              "code": "TICKET_NOT_FOUND",
              "message": "티켓을 찾을 수 없습니다."
            }""";

    public static final String ERROR_TICKET_ASSIGNEE_REQUIRED = """
            {
              "code": "TICKET_ASSIGNEE_REQUIRED",
              "message": "티켓 담당자만 수정하거나 상태를 바꿀 수 있습니다."
            }""";

    public static final String ERROR_TICKET_STATUS_CONFLICT = """
            {
              "code": "TICKET_STATUS_CONFLICT",
              "message": "현재 티켓 상태에서는 할 수 없는 작업입니다."
            }""";

    public static final String ERROR_LAST_PROJECT_ADMIN = """
            {
              "code": "LAST_PROJECT_ADMIN",
              "message": "관리자는 1명 이상 있어야 합니다. 다른 멤버를 먼저 관리자로 지정해 주세요."
            }
            """;

    public static final String ERROR_PROJECT_MEMBER_NOT_FOUND = """
            {
              "code": "PROJECT_MEMBER_NOT_FOUND",
              "message": "프로젝트 멤버가 아닌 사용자입니다."
            }
            """;

    public static final String ERROR_PROJECT_NAME_MISMATCH = """
            {
              "code": "PROJECT_NAME_MISMATCH",
              "message": "프로젝트 이름이 일치하지 않습니다."
            }
            """;

    public static final String ERROR_PROJECT_ANALYSIS_IN_PROGRESS = """
            {
              "code": "PROJECT_ANALYSIS_IN_PROGRESS",
              "message": "처리 중인 회의록이 있어 삭제할 수 없습니다. 처리가 끝난 뒤 다시 시도해 주세요."
            }
            """;

    public static final String ERROR_DUPLICATE_PROJECT_REPOSITORY = """
            {
              "code": "DUPLICATE_PROJECT_REPOSITORY",
              "message": "이미 다른 프로젝트에 등록된 GitHub 저장소입니다."
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

    public static final String ERROR_PROCESSING_TYPE_TARGET_MISMATCH = """
            {
              "code": "PROCESSING_TYPE_TARGET_MISMATCH",
              "message": "GitHub 이외 대상은 신규 발행만 할 수 있습니다."
            }
            """;

    public static final String ERROR_PUBLISH_TARGET_LOCKED = """
            {
              "code": "PUBLISH_TARGET_LOCKED",
              "message": "결과를 모르는 발행이 있어 발행 대상을 바꿀 수 없습니다. 먼저 결과를 확인하세요."
            }
            """;

    public static final String ERROR_EXISTING_ISSUE_REQUIRED = """
            {
              "code": "EXISTING_ISSUE_REQUIRED",
              "message": "연결이나 갱신할 열린 기존 이슈가 없습니다. 신규 발행으로 바꿔 주세요."
            }
            """;

    public static final String MY_TICKETS = """
            {"counts":{"pendingReview":1,"confirmed":0,"rejected":0,"failed":0},
             "tickets":[{"ticketId":"3c510e28-c776-43ce-85cc-6c90693d31f5","title":"CSV 빈 결과 오류 수정",
               "status":"PENDING_REVIEW","processingType":"CREATE","publishTarget":"GITHUB",
               "dedupe":{"judged":true,"skipped":null,"relation":"RELATED","issue":{"number":12,"title":"CSV 빈 결과 오류 수정","state":"open","url":"https://github.com/MCP-Capstone/mcp-capstone/issues/12"},"overlap":true,"reason":"같은 CSV 화면이지만 빈 결과 처리를 더 요구함","before":null,"after":null,"changedByRecheck":false},
               "projectId":1,"projectName":"CSV 내보내기",
               "meetingId":2,"meetingTitle":"CSV 중간 점검","taskId":"9b12ec5a-1423-4b71-8e20-f8f28d306c81",
               "createdAt":"2026-10-22T10:31:00+09:00",
               "description":"CSV 내보내기에서 결과가 비면 오류가 난다.","due":{"text":"내일 오후 6시","date":"2026-10-23","time":"18:00"},
               "assignee":{"projectMemberId":11,"name":"박종민"},"publishFailure":null}]}""";

    public static final String PUBLISH_STARTED = """
            {"batchId":"5f1d6c1e-0f8b-4f6a-8f43-0b0a6d2b9e11","count":3}""";

    public static final String PUBLISH_RESULT = """
            {"batchId":"5f1d6c1e-0f8b-4f6a-8f43-0b0a6d2b9e11","status":"PARTIAL_FAILED","executionMode":"LIVE",
             "createdAt":"2026-10-23T10:31:00+09:00","finishedAt":"2026-10-23T10:31:08+09:00",
             "summary":{"total":3,"published":1,"linked":1,"updated":0,"failed":1,"publishing":0},
             "tickets":[
               {"ticketId":"3c510e28-c776-43ce-85cc-6c90693d31f5","title":"CSV 빈 결과 오류 수정","status":"PUBLISHED","processingType":"CREATE",
                "projectId":1,"projectName":"CSV 내보내기","githubIssueNumber":42,"githubIssueUrl":"https://github.com/MCP-Capstone/mcp-capstone/issues/42",
                "errorCode":null,"errorMessage":null,"failureCategory":null,"retryable":false,"outcomeUnknown":false,"publishTarget":"GITHUB"},
               {"ticketId":"7a0b1c52-5d34-4a8e-9b7a-1f2e3d4c5b6a","title":"API 문서 갱신","status":"FAILED","processingType":"CREATE",
                "projectId":1,"projectName":"CSV 내보내기","githubIssueNumber":null,"githubIssueUrl":null,
                "errorCode":"GITHUB_FORBIDDEN","errorMessage":"저장소에 이슈를 만들 권한이 없습니다.","failureCategory":"REPOSITORY_PERMISSION","retryable":false,"outcomeUnknown":false,"publishTarget":"GITHUB"}]}""";

    public static final String RETRY_STARTED = """
            {"batchId":"8c2e4a10-7b3d-4f55-a1e2-6d9f0b3c7a44","count":1}""";

    public static final String ERROR_NO_CONFIRMED_TICKETS = """
            {
              "code": "NO_CONFIRMED_TICKETS",
              "message": "발행할 확인된 티켓이 없습니다."
            }""";

    public static final String ERROR_PUBLISH_BATCH_NOT_FOUND = """
            {
              "code": "PUBLISH_BATCH_NOT_FOUND",
              "message": "발행 요청을 찾을 수 없습니다."
            }""";

    public static final String TICKET = """
            {"ticketId":"3c510e28-c776-43ce-85cc-6c90693d31f5","title":"CSV 빈 결과 오류 수정",
             "status":"CONFIRMED","processingType":"CREATE","publishTarget":"GITHUB",
             "dedupe":{"judged":false,"skipped":"timeout","relation":null,"issue":null,"overlap":false,"reason":null,"before":null,"after":null,"changedByRecheck":false},
             "projectId":1,"projectName":"CSV 내보내기",
             "meetingId":2,"meetingTitle":"CSV 중간 점검","taskId":"9b12ec5a-1423-4b71-8e20-f8f28d306c81",
             "createdAt":"2026-10-22T10:31:00+09:00",
             "description":"CSV 내보내기에서 결과가 비면 오류가 난다.","due":{"text":"내일 오후 6시","date":"2026-10-23","time":"18:00"},
             "assignee":{"projectMemberId":11,"name":"박종민"},"publishFailure":null}""";

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
