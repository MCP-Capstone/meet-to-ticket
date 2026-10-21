"""demo 프로필에서만 실행하는 백엔드 HTTP 확인. 실제 GitHub를 호출하지 않는다."""
import http.cookiejar
import json
import os
from pathlib import Path
import time
import urllib.request
import uuid

BASE = os.environ.get('BACKEND_URL', 'http://localhost:8080')
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def call(path, data=None, content_type='application/json'):
    req = urllib.request.Request(BASE + path, data=data, headers={'Content-Type': content_type})
    with opener.open(req, timeout=10) as response:
        return json.load(response)

call('/api/auth/login', json.dumps({'loginId': os.environ.get('DEMO_LOGIN_ID', 'demo1'), 'password': os.environ.get('DEMO_PASSWORD', 'password1')}).encode())
projects = call('/api/projects')
if projects.get('executionMode') != 'MOCK':
    raise SystemExit('중단: 이 스크립트는 executionMode=MOCK에서만 실행합니다.')
project_id = projects['projects'][0]['projectId']
file = Path(__file__).with_name('meeting.txt')
boundary = uuid.uuid4().hex
body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="meeting.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode() + file.read_bytes() + f'\r\n--{boundary}--\r\n'.encode())
meeting = call(f'/api/projects/{project_id}/meetings', body, f'multipart/form-data; boundary={boundary}')
started = call(f'/api/meetings/{meeting["meetingId"]}/analysis-tasks', b'')
task_id = started['taskId']
for _ in range(20):
    state = call(f'/api/analysis-tasks/{task_id}')
    if state['status'] in ('COMPLETED', 'PARTIALLY_FAILED', 'FAILED'):
        result = call(f'/api/analysis-tasks/{task_id}/items')
        print(json.dumps({'meetingId': meeting['meetingId'], 'taskId': task_id, 'status': state['status'], 'executionMode': result['executionMode'], 'summary': result['summary']}, ensure_ascii=False, indent=2))
        break
    time.sleep(1)
else:
    raise SystemExit('결과 조회 시간 초과. 기존 taskId로 상태를 확인하세요: ' + task_id)
