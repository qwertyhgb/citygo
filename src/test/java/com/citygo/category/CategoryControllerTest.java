package com.citygo.category;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分类查询接口测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CategoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 场景：不带 token 可访问分类列表，包含"火锅"。
     */
    @Test
    void get_categories_public() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(6))
                .andExpect(jsonPath("$.data[?(@.categoryName=='火锅')]").exists());
    }

    /**
     * 场景：分类按 sort 升序排列（id=1 火锅 sort=1 应排在最前）。
     */
    @Test
    void get_categories_ordered() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].categoryName").value("火锅"))
                .andExpect(jsonPath("$.data[0].sort").value(1))
                .andExpect(jsonPath("$.data[5].sort").value(6));
    }

}