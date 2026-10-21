package com.mcpcapstone.meettoticket.agent;

import java.util.UUID;

public interface AgentGateway {
    AgentContracts.Accepted submit(AgentContracts.StartRequest request);

    AgentContracts.Result status(UUID taskId);

    String mode();
}
