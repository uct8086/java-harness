package uct8086.ai.core.engine;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

/**
 * Extends Spring AI's {@link ToolCallingAdvisor} to capture turn counts and
 * tool-call records during the agent loop.
 * <p>
 * Collected data is exposed via {@link ChatClientResponse#context()} after the
 * loop completes:
 * <ul>
 *   <li>{@code "uct8086.toolCallRecords"} → {@code List<ToolCallRecord>}</li>
 *   <li>{@code "uct8086.turns"} → {@code int[]} (single-element array)</li>
 * </ul>
 *
 * @author uct8086
 */
public class HarnessToolCallingAdvisor extends ToolCallingAdvisor {

    /** Context key for {@code List<ToolCallRecord>} in {@link ChatClientResponse#context()}. */
    public static final String CONTEXT_TOOL_CALL_RECORDS = "uct8086.toolCallRecords";

    /** Context key for turn counter ({@code int[1]}) in {@link ChatClientResponse#context()}. */
    public static final String CONTEXT_TURNS = "uct8086.turns";

    public HarnessToolCallingAdvisor(ToolCallingManager toolCallingManager,
                                      ToolExecutionEligibilityChecker toolExecutionEligibilityChecker,
                                      int advisorOrder,
                                      boolean conversationHistoryEnabled) {
        super(toolCallingManager, toolExecutionEligibilityChecker, advisorOrder, conversationHistoryEnabled);
    }

    /**
     * Convenience constructor with default {@link ToolExecutionEligibilityChecker}.
     */
    public HarnessToolCallingAdvisor(ToolCallingManager toolCallingManager,
                                      int advisorOrder,
                                      boolean conversationHistoryEnabled) {
        super(toolCallingManager,
              DEFAULT_TOOL_EXECUTION_ELIGIBILITY_CHECKER,
              advisorOrder,
              conversationHistoryEnabled);
    }

    @Override
    public @NonNull String getName() {
        return "HarnessToolCallingAdvisor";
    }

    @Override
    protected @NonNull ChatClientRequest doInitializeLoop(ChatClientRequest chatClientRequest,
                                                          @NonNull CallAdvisorChain callAdvisorChain) {
        chatClientRequest.context().putIfAbsent(CONTEXT_TOOL_CALL_RECORDS,
                new ArrayList<AgentLoopResult.ToolCallRecord>());
        chatClientRequest.context().putIfAbsent(CONTEXT_TURNS, new int[] { 0 });
        return super.doInitializeLoop(chatClientRequest, callAdvisorChain);
    }

    @Override
    protected @NonNull ChatClientRequest doBeforeCall(ChatClientRequest chatClientRequest,
                                                      @NonNull CallAdvisorChain callAdvisorChain) {
        int[] counter = (int[]) chatClientRequest.context().get(CONTEXT_TURNS);
        if (counter != null) {
            counter[0]++;
        }
        return super.doBeforeCall(chatClientRequest, callAdvisorChain);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected @NonNull ChatClientResponse doAfterCall(@NonNull ChatClientResponse chatClientResponse,
                                                      @NonNull CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = super.doAfterCall(chatClientResponse, callAdvisorChain);

        ChatResponse cr = response.chatResponse();
        if (cr != null && cr.hasToolCalls()) {
            List<AgentLoopResult.ToolCallRecord> records =
                    (List<AgentLoopResult.ToolCallRecord>) response.context().get(CONTEXT_TOOL_CALL_RECORDS);
            if (records != null) {
                for (Generation gen : cr.getResults()) {
                    if (gen.getOutput() != null && gen.getOutput().hasToolCalls()) {
                        for (var tc : gen.getOutput().getToolCalls()) {
                            records.add(new AgentLoopResult.ToolCallRecord(
                                    tc.name(), tc.arguments(),
                                    null, // result filled later from final response
                                    false, 0));
                        }
                    }
                }
            }
        }

        // Propagate mutable collectors forward for the next loop iteration
        response.context().putIfAbsent(CONTEXT_TOOL_CALL_RECORDS,
                chatClientResponse.context().get(CONTEXT_TOOL_CALL_RECORDS));
        response.context().putIfAbsent(CONTEXT_TURNS,
                chatClientResponse.context().get(CONTEXT_TURNS));

        return response;
    }

    @Override
    protected @NonNull ChatClientResponse doFinalizeLoop(@NonNull ChatClientResponse chatClientResponse,
                                                         @NonNull CallAdvisorChain callAdvisorChain) {
        ChatClientResponse response = super.doFinalizeLoop(chatClientResponse, callAdvisorChain);
        response.context().putIfAbsent(CONTEXT_TOOL_CALL_RECORDS,
                chatClientResponse.context().get(CONTEXT_TOOL_CALL_RECORDS));
        response.context().putIfAbsent(CONTEXT_TURNS,
                chatClientResponse.context().get(CONTEXT_TURNS));
        return response;
    }
}
