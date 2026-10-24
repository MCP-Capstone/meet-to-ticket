package com.mcpcapstone.meettoticket.agent;

// Agent가 일괄 발행 묶음을 모른다(GET /publish/{batchId} 404). Agent는 묶음 기록을 메모리에만 두므로 재시작하면 처리 중이던 묶음도 404다 (#302).
// Agent가 프로세스(uvicorn 워커) 하나로 돈다는 전제다. 워커를 늘리거나 여러 대로 띄우면 다른 워커가 404를 돌려줘
// 정상 묶음도 잃은 묶음으로 끝나므로, 그 전에 묶음 기록을 공유 저장소로 옮겨야 한다.
public class PublishBatchLostException extends RuntimeException {
	public PublishBatchLostException(String batchId) {
		super("Agent가 일괄 발행 묶음을 알지 못합니다: " + batchId);
	}
}
