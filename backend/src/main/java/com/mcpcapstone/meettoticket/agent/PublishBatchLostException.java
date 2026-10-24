package com.mcpcapstone.meettoticket.agent;

// Agent가 일괄 발행 묶음을 모른다(GET /publish/{batchId} 404). Agent는 묶음 기록을 메모리에만 두므로 재시작하면 처리 중이던 묶음도 404다 (#302).
public class PublishBatchLostException extends RuntimeException {
	public PublishBatchLostException(String batchId) {
		super("Agent가 일괄 발행 묶음을 알지 못합니다: " + batchId);
	}
}
