package com.example.zdtx.constants;

public class RedisConstants {
    //创建任务
    public static final String TASK_CREATE = "task:create:";

    public static final String TASK_WAITING_ZSET = "task:waiting";
    public static final String TASK_INFO = "task:info:";

    /** 已完成任务有序集合 */
    public static final String TASK_COMPLETED_SET      = "task:completed";
    /** 已取消任务集合 */
    public static final String TASK_CANCELLED_SET       = "task:cancelled";
}
