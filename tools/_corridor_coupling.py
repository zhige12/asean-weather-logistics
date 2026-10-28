# -*- coding: utf-8 -*-
"""走廊耦合面清点：还有多少处把具体节点 id 写死在 Java 里。

第二走廊（换国家）的真实工作量不在算路引擎，而在这些"只有这条走廊才成立"的字面量：
它们决定了换一份路网文件后，哪些功能直接失配（预热、候选默认 O/D、运河联运、场景注入）。
输出按文件计数，供 DEV_LOG 与答辩口径引用，不是估的。
"""
import collections
import glob
import os
import re

_HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(_HERE, "..", "src", "main", "java")

# 走廊节点 id（大写两三位字母），只在字符串字面量里出现才算一处耦合
NODE_IDS = ("NN", "HN", "LS", "BG", "BN", "HP", "HD", "MC", "HL", "DD", "YGG", "DX", "PX", "CZ",
            "LC", "HK", "SPA", "VH", "HT", "QT", "HUE", "DN", "TH", "NB", "ND", "TY", "TQ",
            "YB", "HB", "SL", "DB", "DQH")
# 口岸/走廊虚拟边 id 同理
EDGE_IDS = ("E1", "E2", "E3", "E4", "E8", "E9", "E36")

STR_LIT = re.compile(r'"([^"\\\n]{1,200})"')
COMMENT = re.compile(r"^\s*(//|\*|/\*)")


def main():
    counts = collections.Counter()
    samples = {}
    for path in glob.glob(os.path.join(ROOT, "**", "*.java"), recursive=True):
        rel = path.split("aseanweatherlogistics")[-1].lstrip("\\/")
        hits = 0
        for line in open(path, encoding="utf-8"):
            if COMMENT.match(line):
                continue
            for m in STR_LIT.finditer(line):
                if m.group(1) in NODE_IDS or m.group(1) in EDGE_IDS:
                    hits += 1
                    samples.setdefault(rel, []).append((m.group(1), line.strip()[:90]))
        if hits:
            counts[rel] = hits
    print("耦合点总数（字符串字面量里的走廊节点/边 id）: %d，分布在 %d 个类" % (sum(counts.values()), len(counts)))
    for f, n in counts.most_common():
        print("  %3d  %s" % (n, f))
        for ident, s in samples.get(f, [])[:2]:
            print("        %s | %s" % (ident, s))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
