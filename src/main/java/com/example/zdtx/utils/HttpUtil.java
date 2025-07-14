package com.example.zdtx.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Component
public class HttpUtil {

    private final RestTemplate restTemplate;

    @Autowired
    public HttpUtil(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 简单 GET 请求，不带参数
     */
    public String get(String url) {
        return restTemplate.getForObject(url, String.class);
    }

    /**
     * GET 请求，带查询参数
     */
    public String get(String url, Map<String, ?> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(url);
        if (params != null && !params.isEmpty()) {
            params.forEach((k, v) -> {
                if (v instanceof Iterable<?>) {
                    ((Iterable<?>) v).forEach(item ->
                            builder.queryParam(k, item)
                    );
                } else if (v != null && v.getClass().isArray()) {
                    for (Object item : (Object[]) v) {
                        builder.queryParam(k, item);
                    }
                } else {
                    builder.queryParam(k, v);
                }
            });
        }
        String uri = builder.build().encode().toUriString();
        return restTemplate.getForObject(uri, String.class);
    }

    /**
     * 简单 POST 表单请求，不带参数
     */
    public String postForm(String url) {
        return postForm(url, null);
    }

    /**
     * POST 表单请求（application/x-www-form-urlencoded）
     */
    public String postForm(String url, Map<String, ?> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = toMultiValueMap(params);
        HttpEntity<MultiValueMap<String, String>> entity =
                new HttpEntity<>(form, headers);
        ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
        return resp.getBody();
    }

    /**
     * 简单 POST JSON 请求，不带请求体
     */
    public String postJson(String url) {
        return postJson(url, null);
    }

    /**
     * POST JSON 请求
     */
    public String postJson(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Object> entity = new HttpEntity<>(body, headers);
        return restTemplate.postForObject(url, entity, String.class);
    }

    /**
     * 将任意 Map 转为 MultiValueMap<String,String>。
     * 支持单值、List、数组。
     */
    private MultiValueMap<String, String> toMultiValueMap(Map<String, ?> params) {
        LinkedMultiValueMap<String, String> mv = new LinkedMultiValueMap<>();
        if (params == null) {
            return mv;
        }
        params.forEach((key, value) -> {
            if (value == null) return;
            if (value instanceof Iterable<?>) {
                for (Object item : (Iterable<?>) value) {
                    mv.add(key, item == null ? null : item.toString());
                }
            } else if (value.getClass().isArray()) {
                for (Object item : (Object[]) value) {
                    mv.add(key, item == null ? null : item.toString());
                }
            } else {
                mv.add(key, value.toString());
            }
        });
        return mv;
    }
}
