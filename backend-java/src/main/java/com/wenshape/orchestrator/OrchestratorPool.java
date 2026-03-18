package com.wenshape.orchestrator;

import com.wenshape.agent.ArchivistAgent;
import com.wenshape.agent.EditorAgent;
import com.wenshape.agent.WriterAgent;
import com.wenshape.context.ContextSelectEngine;
import com.wenshape.llm.LlmGateway;
import com.wenshape.storage.CanonStorage;
import com.wenshape.storage.CardStorage;
import com.wenshape.storage.DraftStorage;
import com.wenshape.storage.MemoryPackStorage;
import com.wenshape.websocket.SessionWebSocketHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排器池 - 管理每个项目的编排器实例
 */
@Slf4j
@Component
public class OrchestratorPool {
    
    private static final int MAX_POOL_SIZE = 20;
    private static final long TTL_MILLIS = 3600_000; // 1 hour
    
    private final LlmGateway llmGateway;
    private final CardStorage cardStorage;
    private final DraftStorage draftStorage;
    private final CanonStorage canonStorage;
    private final MemoryPackStorage memoryPackStorage;
    private final WriterAgent writerAgent;
    private final ArchivistAgent archivistAgent;
    private final EditorAgent editorAgent;
    private final ContextSelectEngine selectEngine;
    private final SessionWebSocketHandler webSocketHandler;
    
    // LRU 缓存
    private final LinkedHashMap<String, OrchestratorEntry> pool = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, OrchestratorEntry> eldest) {
            return size() > MAX_POOL_SIZE;
        }
    };
    
    public OrchestratorPool(LlmGateway llmGateway, CardStorage cardStorage, DraftStorage draftStorage,
                            CanonStorage canonStorage, MemoryPackStorage memoryPackStorage,
                            WriterAgent writerAgent, ArchivistAgent archivistAgent, EditorAgent editorAgent,
                            ContextSelectEngine selectEngine, SessionWebSocketHandler webSocketHandler) {
        this.llmGateway = llmGateway;
        this.cardStorage = cardStorage;
        this.draftStorage = draftStorage;
        this.canonStorage = canonStorage;
        this.memoryPackStorage = memoryPackStorage;
        this.writerAgent = writerAgent;
        this.archivistAgent = archivistAgent;
        this.editorAgent = editorAgent;
        this.selectEngine = selectEngine;
        this.webSocketHandler = webSocketHandler;
    }
    
    /**
     * 获取或创建项目的编排器
     */
    public synchronized Orchestrator getOrchestrator(String projectId) {
        evictStale();
        
        OrchestratorEntry entry = pool.get(projectId);
        if (entry != null) {
            entry.lastAccess = System.currentTimeMillis();
            return entry.orchestrator;
        }
        
        // 创建新的编排器
        Orchestrator orchestrator = new Orchestrator(
                llmGateway, cardStorage, draftStorage, canonStorage, memoryPackStorage,
                writerAgent, archivistAgent, editorAgent, selectEngine, webSocketHandler
        );
        
        pool.put(projectId, new OrchestratorEntry(orchestrator));
        log.info("Created new orchestrator for project: {}", projectId);
        
        return orchestrator;
    }
    
    /**
     * 清理过期的编排器
     */
    private void evictStale() {
        long now = System.currentTimeMillis();
        pool.entrySet().removeIf(entry -> {
            boolean stale = now - entry.getValue().lastAccess > TTL_MILLIS;
            if (stale) {
                log.info("Evicting stale orchestrator for project: {}", entry.getKey());
            }
            return stale;
        });
    }
    
    private static class OrchestratorEntry {
        final Orchestrator orchestrator;
        long lastAccess;
        
        OrchestratorEntry(Orchestrator orchestrator) {
            this.orchestrator = orchestrator;
            this.lastAccess = System.currentTimeMillis();
        }
    }
}
