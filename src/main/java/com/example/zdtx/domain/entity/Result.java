package com.example.zdtx.domain.entity;

import lombok.Data;

import java.io.Serializable;

@Data
public class Result<T> implements Serializable {

    private Integer responseCode; //编码：0成功，1和其它数字为失败
    private String responseMessage; //错误信息
    private T data; //数据

    public static <T> Result<T> success() {
        Result<T> result = new Result<T>();
        result.responseCode = 0;
        return result;
    }

    public static <T> Result<T> success(T object) {
        Result<T> result = new Result<T>();
        result.data = object;
        result.responseCode = 0;
        return result;
    }

    public static <T> Result<T> success(String responseMessage) {
        Result<T> result = new Result<T>();
        result.responseMessage = responseMessage;
        result.responseCode = 0;
        return result;
    }

    public static <T> Result<T> success(T object, String responseMessage) {
        Result<T> result = new Result<T>();
        result.data = object;
        result.responseMessage = responseMessage;
        result.responseCode = 0;
        return result;
    }

    public static <T> Result<T> error(String responseMessage) {
        Result result = new Result();
        result.responseMessage = responseMessage;
        result.responseCode = 1;
        return result;
    }
}