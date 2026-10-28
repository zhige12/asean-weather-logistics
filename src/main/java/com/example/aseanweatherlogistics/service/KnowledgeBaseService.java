package com.example.aseanweatherlogistics.service;

import com.example.aseanweatherlogistics.model.entity.KnowledgeEntry;
import com.example.aseanweatherlogistics.repository.KnowledgeBaseRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 行业知识库服务（计划书 3.4）。
 * <p>
 * 轻量 RAG：<b>检索增强</b>这条链路上，本类负责「召回」。打分口径是关键词加权
 * （标题 &gt; 关键词 &gt; 正文），不上向量库——知识条目只有几十条，向量化的收益抵不上
 * 引入 embedding 服务与离线演示断链的风险；但召回策略必须是可用的：
 * 旧版把<b>整句</b>当子串去匹配标题/正文，「友谊关通关要多久」这种自然语言问句永远召回 0 条
 * （句子不会是标题的子串），检索形同虚设。现改为<b>查询分词后逐词打分</b>：
 * 中文按二元切分（无需分词器依赖），ASCII 词整词匹配，虚词过滤，命中词累加。
 * <p>
 * 仍要如实交代的边界：这是词面匹配，不是语义检索——「堵车」召回不了「拥堵」（除非关键词里同时列了），
 * 所以条目 keywords 要写全同义词；召回质量靠数据而不是靠算法，这一点在答辩里应主动说明。
 */
@Service
public class KnowledgeBaseService {

    /** 停用词：这些字/词出现在几乎每个问句里，留着会把所有条目一起加权。 */
    private static final List<String> STOPWORDS = List.of(
            "现在", "请问", "一下", "怎么", "什么", "多少", "多久", "如何", "是否", "需要", "可以",
            "我们要", "帮我", "告诉", "情况", "状态", "这个", "那个", "有没有", "会不会", "哪些", "哪个",
            "吗", "呢", "啊", "吧", "的", "了", "在", "和", "与", "或", "是", "有");

    private final List<KnowledgeEntry> entries;
    /** 预先把字段小写化并拼成一个 blob：一次构建、每次查询复用，顺带给 df 统计用。 */
    private final List<Doc> docs = new ArrayList<>();
    private final List<String> categories = new ArrayList<>();

    public KnowledgeBaseService(KnowledgeBaseRepository repository) {
        this.entries = repository.loadAll();
        for (KnowledgeEntry e : entries) {
            docs.add(new Doc(e));
            if (!categories.contains(e.getCategory())) {
                categories.add(e.getCategory());
            }
        }
    }

    public List<KnowledgeEntry> all() {
        return entries;
    }

    public List<String> categories() {
        return categories;
    }

    /** 关键词检索，返回按相关度降序的前 N 条。 */
    public List<KnowledgeEntry> search(String query, int limit) {
        List<Match> ms = searchDetailed(query, limit);
        List<KnowledgeEntry> out = new ArrayList<>();
        for (Match m : ms) {
            out.add(m.entry());
        }
        return out;
    }

    /**
     * 检索并带相关度分数。分数要能交出去：只回条目、不回命中程度的话，上层没法判断
     * 「这条到底是被问句命中，还是靠一个虚词蹭进来的」，也就没法决定要不要引用。
     * <p>
     * 权重 = 字段权重 × 词稀有度。字段权重是标题 3 / 关键词 2 / 正文 1；稀有度按包含该词的条目数（df）
     * 折档：只有一条里有 → ×3，两三条里有 → ×2，到处都是 → ×1。没有这一档，「榴莲运输要保持多少度」
     * 会被「运输」这种通用词主导，把讲冷链通则的条目排在讲水果温区的条目前面——词面命中不等于相关。
     */
    public List<Match> searchDetailed(String query, int limit) {
        if (query == null || query.isBlank()) {
            List<Match> head = new ArrayList<>();
            for (int i = 0; i < Math.min(limit, entries.size()); i++) {
                head.add(new Match(entries.get(i), 0));
            }
            return head;
        }
        String flat = query.toLowerCase().replaceAll("\\s+", "");
        List<String> tokens = tokenize(query);
        List<Match> scored = new ArrayList<>();
        for (Doc d : docs) {
            int score = 0;
            int hitTokens = 0;
            // 整句就是标题的一部分：最强的信号，给足权重排在最前
            if (!flat.isEmpty() && d.title.contains(flat)) {
                score += 5;
            }
            for (String t : tokens) {
                int w = rarityWeight(t);
                boolean hit = false;
                if (d.title.contains(t)) {
                    score += 3 * w;
                    hit = true;
                }
                if (d.keywords.contains(t)) {
                    score += 2 * w;
                    hit = true;
                }
                if (d.content.contains(t)) {
                    score += w;
                    hit = true;
                }
                if (hit) {
                    hitTokens++;
                }
            }
            // 多个不同的词同时命中才说明整句主题对口；单靠一个稀有词吸上来的条目不得排前面
            score += 2 * Math.max(0, hitTokens - 1);
            if (score > 0) {
                scored.add(new Match(d.entry, score));
            }
        }
        scored.sort(Comparator.comparingInt(Match::score).reversed());
        return scored.stream().limit(limit).toList();
    }

    /** 词稀有度折档：df 是「有几个条目包含这个词」，越少越能区分相关性。 */
    private int rarityWeight(String token) {
        int df = 0;
        for (Doc d : docs) {
            if (d.blob.contains(token)) {
                df++;
            }
        }
        if (df <= 1) {
            return 3;
        }
        return df <= 3 ? 2 : 1;
    }

    /**
     * 把一句自然语言拆成检索词：连续 ASCII 当一个整词，中文串取二元组（滑动窗口）。
     * 二元组是「不引分词器」前提下的折中——单字命中面太宽（「关」字哪儿都有），
     * 三字又要求问句恰好照抄条目标题。二元组至少能稳定捞出「友谊关/通关/冷链/台风」这类实词。
     */
    static List<String> tokenize(String query) {
        List<String> out = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                flushAscii(ascii, out);
                cjk.append(Character.toLowerCase(c));
            } else if (Character.isLetterOrDigit(c)) {
                flushCjk(cjk, out);
                ascii.append(Character.toLowerCase(c));
            } else {
                flushAscii(ascii, out);
                flushCjk(cjk, out);
            }
        }
        flushAscii(ascii, out);
        flushCjk(cjk, out);
        return out;
    }

    private static void flushAscii(StringBuilder buf, List<String> out) {
        if (buf.length() > 0) {
            String w = buf.toString();
            buf.setLength(0);
            if (w.length() >= 2 && !STOPWORDS.contains(w)) {
                out.add(w);
            }
        }
    }

    private static void flushCjk(StringBuilder buf, List<String> out) {
        if (buf.length() == 0) {
            return;
        }
        String run = buf.toString();
        buf.setLength(0);
        if (run.length() == 1) {
            // 单字只有在不是虚词时才有区分度
            if (!STOPWORDS.contains(run)) {
                out.add(run);
            }
            return;
        }
        for (int i = 0; i + 2 <= run.length(); i++) {
            String bi = run.substring(i, i + 2);
            if (!STOPWORDS.contains(bi)) {
                out.add(bi);
            }
        }
    }

    public List<KnowledgeEntry> search(String query) {
        return search(query, 3);
    }

    /** 按分类检索（用于风险→知识映射）。 */
    public List<KnowledgeEntry> byCategory(String category) {
        return entries.stream().filter(e -> category.equalsIgnoreCase(e.getCategory())).toList();
    }

    /** 一条知识条目的预计算视图（小写字段 + 合并 blob）。 */
    private static final class Doc {
        private final KnowledgeEntry entry;
        private final String title;
        private final String keywords;
        private final String content;
        private final String blob;

        Doc(KnowledgeEntry e) {
            this.entry = e;
            this.title = lower(e.getTitle());
            this.keywords = lower(String.join(" ", listOrEmpty(e.getKeywords())));
            this.content = lower(e.getContent());
            this.blob = title + " " + keywords + " " + content;
        }

        private static String lower(String s) {
            return s == null ? "" : s.toLowerCase();
        }

        private static List<String> listOrEmpty(List<String> l) {
            return l == null ? List.of() : l;
        }
    }

    /** 一次命中：条目 + 相关度分数（分数口径见 {@link #searchDetailed}）。 */
    public record Match(KnowledgeEntry entry, int score) {
    }
}
