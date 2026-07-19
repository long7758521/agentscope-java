/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.channel.wecomaibot;

import java.util.ArrayList;
import java.util.List;

/**
 * Pads GFM Markdown tables so column widths are consistent.
 *
 * <p>WeCom markdown rendering requires aligned column widths; without padding, tables often render
 * incorrectly. Fenced code blocks are left untouched.
 */
public final class WeComAibotMarkdownTables {

    private WeComAibotMarkdownTables() {}

    public static String format(String text) {
        if (text == null || !text.contains("|")) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        List<String> result = new ArrayList<>();
        int i = 0;
        boolean inCodeFence = false;
        while (i < lines.length) {
            String line = lines[i];
            String stripped = line.strip();
            if (stripped.startsWith("```")) {
                inCodeFence = !inCodeFence;
                result.add(line);
                i++;
                continue;
            }
            if (inCodeFence) {
                result.add(line);
                i++;
                continue;
            }
            if (line.contains("|")) {
                List<String> tableLines = new ArrayList<>();
                while (i < lines.length
                        && lines[i].contains("|")
                        && !lines[i].strip().startsWith("```")) {
                    tableLines.add(lines[i]);
                    i++;
                }
                if (!tableLines.isEmpty()) {
                    result.addAll(formatTable(tableLines));
                }
                continue;
            }
            result.add(line);
            i++;
        }
        return String.join("\n", result);
    }

    private static List<String> formatTable(List<String> lines) {
        if (lines.isEmpty()) {
            return lines;
        }
        boolean hasSeparator = lines.size() >= 2 && lines.get(1).strip().matches("[\\s\\-:|]+");
        List<List<String>> rows = new ArrayList<>();
        for (int idx = 0; idx < lines.size(); idx++) {
            if (hasSeparator && idx == 1) {
                continue;
            }
            String[] cells = lines.get(idx).split("\\|", -1);
            List<String> trimmed = new ArrayList<>();
            for (String cell : cells) {
                trimmed.add(cell.strip());
            }
            if (!trimmed.isEmpty() && trimmed.get(0).isEmpty()) {
                trimmed.remove(0);
            }
            if (!trimmed.isEmpty() && trimmed.get(trimmed.size() - 1).isEmpty()) {
                trimmed.remove(trimmed.size() - 1);
            }
            rows.add(trimmed);
        }
        if (rows.isEmpty()) {
            return lines;
        }
        int colCount = 0;
        for (List<String> row : rows) {
            colCount = Math.max(colCount, row.size());
        }
        int[] widths = new int[colCount];
        for (List<String> row : rows) {
            for (int c = 0; c < row.size(); c++) {
                widths[c] = Math.max(widths[c], displayWidth(row.get(c)));
            }
        }
        List<String> out = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            out.add(joinRow(rows.get(r), widths, colCount));
            if (r == 0) {
                out.add(joinSeparator(widths));
            }
        }
        return out;
    }

    private static String joinRow(List<String> row, int[] widths, int colCount) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < colCount; c++) {
            String cell = c < row.size() ? row.get(c) : "";
            sb.append(' ').append(padRight(cell, widths[c])).append(" |");
        }
        return sb.toString();
    }

    private static String joinSeparator(int[] widths) {
        StringBuilder sb = new StringBuilder("|");
        for (int width : widths) {
            sb.append(' ');
            sb.append("-".repeat(Math.max(3, width)));
            sb.append(" |");
        }
        return sb.toString();
    }

    private static String padRight(String s, int width) {
        int pad = width - displayWidth(s);
        if (pad <= 0) {
            return s;
        }
        return s + " ".repeat(pad);
    }

    /** Approximate display width: CJK double-width, others single. */
    private static int displayWidth(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x1100
                    && (cp <= 0x115F
                            || cp == 0x2329
                            || cp == 0x232A
                            || (cp >= 0x2E80 && cp <= 0xA4CF)
                            || (cp >= 0xAC00 && cp <= 0xD7A3)
                            || (cp >= 0xF900 && cp <= 0xFAFF)
                            || (cp >= 0xFE10 && cp <= 0xFE6F)
                            || (cp >= 0xFF00 && cp <= 0xFF60)
                            || (cp >= 0xFFE0 && cp <= 0xFFE6)
                            || (cp >= 0x20000 && cp <= 0x2FFFD)
                            || (cp >= 0x30000 && cp <= 0x3FFFD))) {
                w += 2;
            } else {
                w += 1;
            }
        }
        return w;
    }
}
