package com.yang.dataagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallJsonRepairTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validJsonReturnedAsIs() {
        String json = "{\"sql\": \"SELECT 1\"}";
        assertThat(ToolCallJsonRepair.repair(json, mapper)).isEqualTo(json);
    }

    @Test
    void repairsMissingClosingBrace() {
        // 评测中实际抓到的损坏形态：缺结尾 "}"
        String broken = "{\"chartType\": \"line\", \"categories\": [\"2026-01\"], "
                + "\"series\": [{\"name\": \"新增用户数\", \"data\": [13, 12]}]";
        assertThat(ToolCallJsonRepair.repair(broken, mapper)).isEqualTo(broken + "}");
    }

    @Test
    void repairsMultipleMissingClosers() {
        String broken = "{\"series\": [{\"data\": [1, 2";
        assertThat(ToolCallJsonRepair.repair(broken, mapper)).isEqualTo(broken + "]}]}");
    }

    @Test
    void repairsUnterminatedString() {
        String broken = "{\"sql\": \"SELECT 1";
        assertThat(ToolCallJsonRepair.repair(broken, mapper)).isEqualTo(broken + "\"}");
    }

    @Test
    void bracesInsideStringsAreIgnored() {
        String json = "{\"sql\": \"SELECT '}' FROM t WHERE a = '['\"}";
        assertThat(ToolCallJsonRepair.repair(json, mapper)).isEqualTo(json);
    }

    @Test
    void escapedQuoteInsideStringHandled() {
        String broken = "{\"title\": \"他说\\\"你好";
        assertThat(ToolCallJsonRepair.repair(broken, mapper)).isEqualTo(broken + "\"}");
    }

    @Test
    void unrepairableFallsBackToEmptyObject() {
        assertThat(ToolCallJsonRepair.repair("not json at all }{", mapper)).isEqualTo("{}");
        assertThat(ToolCallJsonRepair.repair(null, mapper)).isEqualTo("{}");
        assertThat(ToolCallJsonRepair.repair("  ", mapper)).isEqualTo("{}");
    }
}
