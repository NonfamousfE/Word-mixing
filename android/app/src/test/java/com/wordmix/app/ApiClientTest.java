package com.wordmix.app;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.junit.Before;
import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * 验证 Android 端 ApiClient 与新 C-S 后端标准 RESTful API 的通信。
 */
public class ApiClientTest {

    private ApiClient client;
    private static final String TEST_URL = "http://127.0.0.1:8000/api";

    private boolean isServerRunning() {
        try {
            URL u = new URL("http://127.0.0.1:8000/health");
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Before
    public void setUp() {
        client = new ApiClient(TEST_URL, "");
    }

    @Test
    public void testLoginAndFetchGroups() {
        assumeTrue("后端未在 8000 端口运行，跳过在线测试", isServerRunning());

        // 1. 登录
        boolean loggedIn = client.login("admin", "admin123");
        assertTrue("登录应当成功", loggedIn);
        assertFalse("Token 应当已填充", client.getToken().isEmpty());

        // 2. 获取词库列表
        List<Map<String, Object>> groups = (List) client.getGroups(null);
        assertNotNull("分组列表不应为空", groups);
        assertTrue("应当有至少 50 个分组", groups.size() >= 50);

        // 3. 搜索测试
        List<Map<String, Object>> searchRes = (List) client.getGroups("plague");
        assertNotNull(searchRes);
        assertTrue("搜索 plague 应当有结果", searchRes.size() >= 1);

        // 4. 音标测试
        String ipa = client.getPhonetic("plague");
        assertTrue("音标应当解析成功", ipa.contains("pleɪɡ"));
    }
}
