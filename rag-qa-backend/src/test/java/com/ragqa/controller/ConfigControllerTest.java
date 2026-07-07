package com.ragqa.controller;

import com.ragqa.dto.ConfigDto;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfigController} 单元测试（F23）。
 *
 * <p>覆盖：从 {@code @Value} 注入读取 → DTO 正确传出。
 */
class ConfigControllerTest {

    @Test
    void getConfigShouldReturnDefaultsFromInjectedValues() {
        ConfigController ctrl = new ConfigController();
        ReflectionTestUtils.setField(ctrl, "defaultRagMode", "linear");
        ReflectionTestUtils.setField(ctrl, "defaultHistoryWindow", 5);

        ResponseEntity<ConfigDto> resp = ctrl.getConfig();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getRagMode()).isEqualTo("linear");
        assertThat(resp.getBody().getDefaultHistoryWindow()).isEqualTo(5);
    }

    @Test
    void getConfigShouldReturnAgenticDefaultWhenConfigured() {
        ConfigController ctrl = new ConfigController();
        ReflectionTestUtils.setField(ctrl, "defaultRagMode", "agentic");
        ReflectionTestUtils.setField(ctrl, "defaultHistoryWindow", 3);

        ResponseEntity<ConfigDto> resp = ctrl.getConfig();

        assertThat(resp.getBody().getRagMode()).isEqualTo("agentic");
        assertThat(resp.getBody().getDefaultHistoryWindow()).isEqualTo(3);
    }
}
