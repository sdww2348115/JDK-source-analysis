package com.sdww;

import java.util.concurrent.RejectedExecutionException;

/**
 * JDK中并发最重要的类：线程池
 * 通过复用昂贵的线程资源，可以达到以下两个目的：
 * 1.优化Runnable与Callable响应速度(不需要创建Thread)
 * 2.管理，复用线程资源，减轻系统负担
 * 默认ThreadFactory所生成的Thread应对modifyThread进行处理，否则可能造成threadPool参数无法修改或者终止threadPool始终不成功。
 * keep-alive Time本身只作用于超出CoreThreadPoolSize但未超出MaxThreadPoolSize的情况，但可以通过设置allowCoreThreadTimeOut(true)的方式将keep-alive同样应用于线程池中线程数量少于CoreThreadPoolSize的情况
 * Rejected tasks：当线程池处于shutdown状态或已满（BlockingQueue && 线程数达到maxThreadPoolSize）时，再提交task将触发reject逻辑
 * 所有的reject逻辑均由RejectedExecutionHandler实现，其中默认有4种逻辑：
 * 1.default：{@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy} ：直接抛出{@link RejectedExecutionException}异常
 * 2.{@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy}：由调用submit的线程继续执行
 * 3.{@link java.util.concurrent.ThreadPoolExecutor.DiscardPolicy}：直接丢弃当前任务
 * 4.{@link java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy}：丢弃Queue中第一个task（即被放入Queue中最久的那一个），并立即执行当前task，相当于将当前task替换并放入队列头部
 * 线程池提供了线程执行前后的钩子函数：{@link #beforeExecute(Thread, Runnable)} 与 {@link #afterExecute(Runnable, Throwable)}，清理ThreadLocal就方便多了！
 * 但是需要注意的是：如果钩子函数运行抛出异常，可能导致worker线程停止工作
 * 线程池的原理导致只要其中还有线程存在就一定不会被清理
 */
public class ThreadPoolExecutor {
}
