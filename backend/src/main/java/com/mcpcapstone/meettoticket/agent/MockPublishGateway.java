package com.mcpcapstone.meettoticket.agent;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

// Agent와 GitHub 없이 발행 흐름을 확인하는 모의 구현. 실제 이슈를 만들지 않으므로 번호와 URL은 없다.
@Component
@ConditionalOnProperty(name = "agent.mode", havingValue = "mock")
public class MockPublishGateway implements PublishGateway {
    @Override
    public String mode() {
        return "MOCK";
    }

    @Override
    public Optional<Result> submit(Request request) {
        List<Item> items =
                request.tickets().stream()
                        .map(t -> new Item(
                                t.ticketId(), t.processingType(),
                                switch (t.processingType()) {
                                    case "LINK" -> "LINKED";
                                    case "UPDATE" -> "UPDATED";
                                    default -> "PUBLISHED";
                                },
                                null, null, null, List.of(), null, null, false, null, null))
                        .toList();
        return Optional.of(new Result(request.batchId(), "SUCCEEDED", Map.of("total", items.size()), items, null, null));
    }
}
