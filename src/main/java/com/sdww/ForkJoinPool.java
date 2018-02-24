package com.sdww;

/**
 * ForkJoinPool属于并发大师Doug Lea于Java 1.7新增的并发框架Fork/Join的一部分
 * Fork/Join框架完全不同于原有的ExecutorService框架，其思想与MultiThread也不尽相同。
 * 它所关心的核心为task，task可以分裂/执行，适用于各种分治算法，递归算法。
 * 用户完全不用操心线程之间的同步问题，task的调度与同步均由Fork/Join框架自动完成。
 * Fork/Join框架最核心的算法是工作偷取(work-stealing)：线程不一定仅从某个task队列中获取task，它还会从其他的队列中偷取task来运行
 * 工作偷取机制保证了task之间尽量并发操作，保证了在最坏情况下task能够尽量被平均分配。从而提高了整个框架的执行效率。
 */
public class ForkJoinPool {
}
