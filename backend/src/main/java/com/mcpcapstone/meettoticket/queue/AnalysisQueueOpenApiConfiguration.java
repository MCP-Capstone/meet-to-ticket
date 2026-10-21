package com.mcpcapstone.meettoticket.queue;

import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 협업 중인 기존 analysis 컨트롤러를 수정하지 않고 큐 접수 계약을 문서에 반영한다. */
@Configuration
public class AnalysisQueueOpenApiConfiguration {
	@Bean
	public OperationCustomizer queuedAnalysisDocumentation() {
		return (operation, handler) -> {
			if ("startAnalysis".equals(operation.getOperationId())) {
				operation.setDescription("저장된 회의의 분석 작업과 큐 발행 기록을 저장한 뒤 taskId를 RabbitMQ에 발행한다. "
					+ "202는 접수 성공이며 Agent 중지 시에도 반환한다. 리스너에서 접수 실패를 2초·4초·8초 후 재시도하고, "
					+ "최종 실패 시 FAILED 및 DLQ로 처리한다. 접수 여부 불명 시 재POST 없이 기존 작업만 조회한다. "
					+ "동일 회의 재요청은 409이며 프론트는 반환된 taskId로 기존 상태·결과 API를 조회한다.");
				operation.getResponses().remove("502");
			}
			return operation;
		};
	}
}
