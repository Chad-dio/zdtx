package com.example.zdtx.utils;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.ContentType;
import cn.hutool.json.JSONUtil;
import org.springframework.http.HttpMethod;

import java.nio.charset.StandardCharsets;
import java.util.Map;

public class HttpUtils{

    /**
     * 简单 GET 带查询参数
     */
    public static String get(String url, Map<String, Object> params) {
        // 自动拼接 ?key=value
        return HttpUtil.get(url, params);
    }

    /**
     * GET 并自定义 Header、超时等
     */
    public static String getCustom(String url, Map<String, Object> params) {
        HttpResponse resp = HttpRequest.get(url)
                .addHeaders(Map.of("Authorization", "Bearer abc123"))
                .form(params)            // 也可以 .body() 发原始字符串
                .timeout(5_000)          // 5 秒超时
                .execute();
        return resp.body();
    }

    /**
     * POST 表单提交
     */
    public static String postForm(String url, Map<String, Object> formParams) {
        return HttpRequest.post(url)
                .contentType(ContentType.FORM_URLENCODED.toString())
                .form(formParams)       // 自动把 Map 转成 x-www-form-urlencoded
                .execute()
                .body();
    }

    /**
     * POST JSON 提交
     */
    public static String postJson(String url, Object jsonBody) {
        return HttpRequest.post(url)
                .contentType(ContentType.JSON.toString())
                .body(JSONUtil.toJsonStr(jsonBody))
                .execute()
                .body();
    }

    /**
     * 万能版：任意方法、自定义所有参数
     */
    public static String request(String url,
                          String method,
                          Map<String, String> headers,
                          Map<String, Object> formParams,
                          Object jsonBody) {
        // 1. 先准备 HttpRequest 对象
        HttpRequest req;
        if ("GET".equalsIgnoreCase(method)) {
            // 带参 GET
            req = HttpRequest.get(url).form(formParams);
        } else if ("POST".equalsIgnoreCase(method)) {
            if (jsonBody != null) {
                // JSON POST
                req = HttpRequest.post(url)
                        .contentType(ContentType.JSON.toString())
                        .body(JSONUtil.toJsonStr(jsonBody));
            } else {
                // 表单 POST
                req = HttpRequest.post(url)
                        .contentType(ContentType.FORM_URLENCODED.toString())
                        .form(formParams);
            }
        } else {
            // 其它 HTTP 方法，Hutool 在老版本里可能不支持 of(...)
            throw new UnsupportedOperationException("Unsupported method: " + method);
        }

        // 2. 公共设置：headers、超时
        if (headers != null) {
            req.addHeaders(headers);
        }
        req.timeout(10_000);  // 10 秒超时

        // 3. 发送并返回
        HttpResponse resp = req.execute();
        return resp.body();
    }
}