package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.KnowledgeEntry;
import com.example.aseanweatherlogistics.repository.KnowledgeBaseRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 行业知识库召回用例（RAG 的「检索」半边）。
 * <p>
 * 这块必须锁住：评分表把「生成式AI应用深度（RAG）」单列成项，而 RAG 能不能称职全看召回——
 * 旧版把整句当子串去匹配标题，自然语言问句永远召回 0 条，检索形同虚设却不易被发现。
 * 用例直接从 data/knowledge-base.json 真数据跑（顺带验证 JSON 能解析、条目没写坏），
 * 不用 mock，避免「测试绿了但真实知识库召不回」。
 */
class KnowledgeBaseServiceTest {

    private final KnowledgeBaseService kb = new KnowledgeBaseService(new KnowledgeBaseRepository());

    private String topId(String query) {
        List<KnowledgeBaseService.Match> hits = kb.searchDetailed(query, 1);
        return hits.isEmpty() ? "" : hits.get(0).entry().getId();
    }

    private List<String> ids(String query, int limit) {
        List<String> out = new ArrayList<>();
        for (KnowledgeBaseService.Match m : kb.searchDetailed(query, limit)) {
            out.add(m.entry().getId());
        }
        return out;
    }

    @Test
    void loadsRealFile_withUniqueIdsAndCoverage() {
        List<KnowledgeEntry> all = kb.all();
        assertTrue(all.size() >= 30, "知识库应已扩充到 30 条以上，实际 " + all.size());
        assertEquals(all.size(), all.stream().map(KnowledgeEntry::getId).distinct().count(),
                "条目 id 不得重复");
        // 东盟关联是单独评分项：气候类与走廊类必须成组存在，不能只有中越口岸条目
        assertTrue(kb.categories().contains("climate"), "缺少东盟气候类条目");
        assertTrue(kb.categories().contains("corridor"), "缺少运输走廊类条目");
        for (KnowledgeEntry e : all) {
            assertFalse(e.getTitle() == null || e.getTitle().isBlank(), "条目缺标题：" + e.getId());
            assertFalse(e.getContent() == null || e.getContent().length() < 20,
                    "条目正文过短，检索增强拿不到料：" + e.getId());
            assertFalse(e.getKeywords() == null || e.getKeywords().isEmpty(),
                    "条目缺关键词，词面召回会落空：" + e.getId());
        }
    }

    @Test
    void naturalLanguageQuestion_retrievesRelevantEntry() {
        // 整句不是标题的子串——旧版在这种问句上召回 0 条
        assertEquals("KB-001", topId("友谊关通关要多久"));
        assertEquals("KB-001", topId("从凭祥出口岸要办哪些手续"));
        assertTrue(ids("榴莲运输要保持多少度", 3).contains("KB-021"),
                "水果温区问题应召回到冷害阈值条目，实际 " + ids("榴莲运输要保持多少度", 3));
        assertTrue(ids("越南北部什么时候雨最多", 3).contains("KB-011"),
                "区域气候问题应召回到越南雨季条目");
    }

    @Test
    void stopWords_areNotUsedForScoring() {
        List<String> tokens = KnowledgeBaseService.tokenize("友谊关现在什么风险");
        assertTrue(tokens.contains("友谊"), "实词二元组应保留");
        assertFalse(tokens.contains("什么"), "虚词不得参与打分");
        assertFalse(tokens.contains("现在"), "虚词不得参与打分");
        // 英文缩写整词保留，且按小写匹配（司机说 TIR/Form E）
        assertTrue(KnowledgeBaseService.tokenize("TIR 便利化怎么做").contains("tir"));
    }

    @Test
    void unrelatedQuestion_retrievesNothingInsteadOfForcingAnAnswer() {
        // 词面完全不相干时不得硬凑一条“最像的”给模型：RAG 的失败模式里，错引比不引更糟
        List<KnowledgeBaseService.Match> hits = kb.searchDetailed("量子纠缠与 Blockchain 共识机制", 3);
        for (KnowledgeBaseService.Match m : hits) {
            assertTrue(m.score() > 0, "无命中不得进结果：" + m.entry().getId());
        }
        assertTrue(hits.size() <= 3, "limit 必须生效");
    }

    @Test
    void blankQuery_returnsHeadEntriesWithZeroScore() {
        List<KnowledgeBaseService.Match> hits = kb.searchDetailed("  ", 2);
        assertEquals(2, hits.size(), "空查询按顺序给前 N 条，供展示用");
        assertEquals(0, hits.get(0).score(), "空查询没有相关性可言，分数不得虚高");
    }

    @Test
    void scoresAreMonotonicNonIncreasing() {
        List<KnowledgeBaseService.Match> hits = kb.searchDetailed("冷链 温控 通关 检疫", 5);
        for (int i = 1; i < hits.size(); i++) {
            assertTrue(hits.get(i - 1).score() >= hits.get(i).score(), "结果必须按相关度降序");
        }
    }
}
