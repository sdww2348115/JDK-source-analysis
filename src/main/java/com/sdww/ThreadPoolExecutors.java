package com.sdww;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.ReentrantLock;

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
 * 线程池一共有4种状态：
 *   RUNNING:  Accept new tasks and process queued tasks
 *   SHUTDOWN: Don't accept new tasks, but process queued tasks
 *   STOP:     Don't accept new tasks, don't process queued tasks,
 *             and interrupt in-progress tasks
 *   TIDYING:  All tasks have terminated, workerCount is zero,
 *             the thread transitioning to state TIDYING
 *             will run the terminated() hook method
 *   TERMINATED: terminated() has completed
 */
public class ThreadPoolExecutor {

    /**
     * 每一种重要的并发容器内总能见到ctl的身影，对于ThreadPoolExecutor来说，ctl的最高3位用于表示threadPool运行状态，剩下的位数代表线程数
     * +
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    private static final int COUNT_BITS = Integer.SIZE - 3;
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    // runState is stored in the high-order bits
    private static final int RUNNING    = -1 << COUNT_BITS;
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    private static final int STOP       =  1 << COUNT_BITS;
    private static final int TIDYING    =  2 << COUNT_BITS;
    private static final int TERMINATED =  3 << COUNT_BITS;

    // Packing and unpacking ctl
    private static int runStateOf(int c)     { return c & ~CAPACITY; }
    private static int workerCountOf(int c)  { return c & CAPACITY; }
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /**
     * Lock held on access to workers set and related bookkeeping.
     * While we could use a concurrent set of some sort, it turns out
     * to be generally preferable to use a lock. Among the reasons is
     * that this serializes interruptIdleWorkers, which avoids
     * unnecessary interrupt storms, especially during shutdown.
     * Otherwise exiting threads would concurrently interrupt those
     * that have not yet interrupted. It also simplifies some of the
     * associated statistics bookkeeping of largestPoolSize etc. We
     * also hold mainLock on shutdown and shutdownNow, for the sake of
     * ensuring workers set is stable while separately checking
     * permission to interrupt and actually interrupting.
     * 访问workers set，记录相关信息时所使用的的lock。
     * 之前也尝试过使用一个有序并发集合来处理，但实际验证结果是lock更适合
     * 其中的原因是串行化的 interruptIdleWorkers，避免了不必要的interrupt风暴，特别是shutdown的时候
     * 除非尚未interruptted worker能够并发的interrupt。
     * 这样操作同样简化了巨大线程池的一些相关的统计操作
     * shutdown以及shutdownNow均需要hold住mainLock
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters.
     * 创建一个threadPoolExecutor
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        if (corePoolSize < 0 ||
                maximumPoolSize <= 0 ||
                maximumPoolSize < corePoolSize ||
                keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * Starts a core thread, causing it to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed. This method will return {@code false}
     * if all core threads have already been started.
     * 启动一个core线程
     *
     * @return {@code true} if a thread was started
     */
    public boolean prestartCoreThread() {
        /**
         * 个人觉得这里的workerCountOf(ctl.get()) < corePoolSize判断毫无必要，因为：
         * addWorker方法内部含有对线程池容量的判断，而且addWorker方法进去首先就是判断线程池运行状态，随后便是判断线程池容量，所以这里对容量的判断有些画蛇添足
         */
        return workerCountOf(ctl.get()) < corePoolSize &&
                addWorker(null, true);
    }

    /**
     * Starts all core threads, causing them to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed.
     *
     * @return the number of threads started
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * Checks if a new worker can be added with respect to current
     * pool state and the given bound (either core or maximum). If so,
     * the worker count is adjusted accordingly, and, if possible, a
     * new worker is created and started, running firstTask as its
     * first task. This method returns false if the pool is stopped or
     * eligible to shut down. It also returns false if the thread
     * factory fails to create a thread when asked.  If the thread
     * creation fails, either due to the thread factory returning
     * null, or due to an exception (typically OutOfMemoryError in
     * Thread.start()), we roll back cleanly.
     * 尝试向threadPool中添加一个新的worker，成功则返回true，失败返回false
     *
     * @param firstTask the task the new thread should run first (or
     * null if none). Workers are created with an initial first task
     * (in method execute()) to bypass queuing when there are fewer
     * than corePoolSize threads (in which case we always start one),
     * or when the queue is full (in which case we must bypass queue).
     * Initially idle threads are usually created via
     * prestartCoreThread or to replace other dying workers.
     *
     * @param core if true use corePoolSize as bound, else
     * maximumPoolSize. (A boolean indicator is used here rather than a
     * value to ensure reads of fresh values after checking other pool
     * state).
     * @return true if successful
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        /**
         * 整个retry循环的目的是：
         * 1.检查是否满足addWorker的条件
         * 2.如果满足，则试图向threadPool的workerCount+1
         */
        retry:
        for (;;) {
            int c = ctl.get();
            /**
             * step1.判断线程池运行状态,需要注意的是只有RUNNING < SHUTDOWN
             */
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            //当rs == SHUTDOWN && firstTask == null && workQueue.isNotEmpty情况下不会返回false，而是向下执行
            if (rs >= SHUTDOWN &&
                    ! (rs == SHUTDOWN &&
                            firstTask == null &&
                            ! workQueue.isEmpty()))
                return false;

            for (;;) {
                /**
                 * step2.判断线程池容量
                 */
                int wc = workerCountOf(c);
                if (wc >= CAPACITY ||
                        wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                c = ctl.get();  // Re-read ctl
                if (runStateOf(c) != rs)
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        /**
         * 下面逻辑为实际创建线程
         */
        boolean workerStarted = false;
        boolean workerAdded = false;
        java.util.concurrent.ThreadPoolExecutor.Worker w = null;
        try {
            w = new java.util.concurrent.ThreadPoolExecutor.Worker(firstTask);
            final Thread t = w.thread;
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    int rs = runStateOf(ctl.get());

                    if (rs < SHUTDOWN ||
                            (rs == SHUTDOWN && firstTask == null)) {
                        //这里检测线程的状态目的是以防线程创建失败，如果失败的话需要回滚
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();
                        //workers是一个hashTable
                        workers.add(w);
                        int s = workers.size();
                        //按照这里的代码逻辑，难道s还能大于largestPoolSize？？？
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                //添加成功后的
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            //worker创建失败或者start失败，都会走到这里的回滚逻辑中
            if (! workerStarted)
                addWorkerFailed(w);
        }
        return workerStarted;
    }

    /**
     * Rolls back the worker thread creation.
     * - removes worker from workers, if present
     * - decrements worker count
     * - rechecks for termination, in case the existence of this
     *   worker was holding up termination
     * 详细回滚逻辑
     * 1.将worker从workers中remove掉
     * 2.ctl中的workerCount--
     * 3.tryTerminate
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                workers.remove(w);
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Transitions to TERMINATED state if either (SHUTDOWN and pool
     * and queue empty) or (STOP and pool empty).  If otherwise
     * eligible to terminate but workerCount is nonzero, interrupts an
     * idle worker to ensure that shutdown signals propagate. This
     * method must be called following any action that might make
     * termination possible -- reducing worker count or removing tasks
     * from the queue during shutdown. The method is non-private to
     * allow access from ScheduledThreadPoolExecutor.
     */
    final void tryTerminate() {
        for (;;) {
            int c = ctl.get();
            //仍在运行状态
            if (isRunning(c) ||
                    //已经处于TERMINATED状态了
                    runStateAtLeast(c, TIDYING) ||
                    //SHUTDOWN但queue非空
                    (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;
            if (workerCountOf(c) != 0) { // Eligible to terminate
                //当走入这个逻辑时，说明threadPool在shutdown状态下新增了一个worker，这种情况下回滚操作只需随便interrupt一个空闲worker即可
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            /**
             * 逻辑走到这里意味着线程池满足一下两个条件：
             * 1.pool状态为SHUTDOWN，且pool为空，且queue为空
             * 2.pool状态为STOP，且pool为空
             * 这都意味着线程池达到了进入TIDYING状态的条件，后续步骤即为TIDYING->shutdown hook -> TERMINATED
             */
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        terminated();
                    } finally {
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    /**
     * Interrupts threads that might be waiting for tasks (as
     * indicated by not being locked) so they can check for
     * termination or configuration changes. Ignores
     * SecurityExceptions (in which case some threads may remain
     * uninterrupted).
     *
     * @param onlyOne If true, interrupt at most one worker. This is
     * called only from tryTerminate when termination is otherwise
     * enabled but there are still other workers.  In this case, at
     * most one waiting worker is interrupted to propagate shutdown
     * signals in case all threads are currently waiting.
     * Interrupting any arbitrary thread ensures that newly arriving
     * workers since shutdown began will also eventually exit.
     * To guarantee eventual termination, it suffices to always
     * interrupt only one idle worker, but shutdown() interrupts all
     * idle workers so that redundant workers exit promptly, not
     * waiting for a straggler task to finish.
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }
}
