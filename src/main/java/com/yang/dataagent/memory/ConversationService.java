package com.yang.dataagent.memory;

import com.yang.dataagent.config.AgentProperties;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 对话记忆：MySQL 持久化 + 窗口截断。
 * 每轮只把 user 问题和 assistant 最终回答入库；注入模型时取最近 windowSize 条，
 * 更早的历史直接丢弃（窗口截断）。摘要压缩留作后续优化点。
 */
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;
    private final AgentProperties props;

    public ConversationService(ConversationRepository conversationRepository,
                               ChatMessageRepository messageRepository,
                               AgentProperties props) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.props = props;
    }

    /** id 为空或不存在时新建对话（title 取首问前 32 字），返回对话 id */
    @Transactional
    public String getOrCreate(String conversationId, String firstQuestion) {
        if (conversationId != null && !conversationId.isBlank()
                && conversationRepository.existsById(conversationId)) {
            return conversationId;
        }
        String title = firstQuestion.length() > 32 ? firstQuestion.substring(0, 32) : firstQuestion;
        ConversationEntity conversation = new ConversationEntity(UUID.randomUUID().toString(), title);
        conversationRepository.save(conversation);
        return conversation.getId();
    }

    /** 取最近 windowSize 条历史，转成 Spring AI 消息（时间正序），供注入 ReAct 循环 */
    @Transactional(readOnly = true)
    public List<Message> loadHistory(String conversationId) {
        List<ChatMessageEntity> recent = messageRepository.findByConversationIdOrderByIdDesc(
                conversationId, PageRequest.of(0, props.memory().windowSize()));
        Collections.reverse(recent);
        List<Message> messages = new ArrayList<>(recent.size());
        for (ChatMessageEntity m : recent) {
            messages.add("user".equals(m.getRole())
                    ? new org.springframework.ai.chat.messages.UserMessage(m.getContent())
                    : new AssistantMessage(m.getContent()));
        }
        return messages;
    }

    /** 一轮问答结束后成对落库，并刷新对话的更新时间 */
    @Transactional
    public void appendTurn(String conversationId, String question, String answer) {
        messageRepository.save(new ChatMessageEntity(conversationId, "user", question));
        messageRepository.save(new ChatMessageEntity(conversationId, "assistant", answer));
        conversationRepository.findById(conversationId).ifPresent(c -> {
            c.touch();
            conversationRepository.save(c);
        });
    }
}
