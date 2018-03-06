package com.sdww;

/**
 * ForkJoinPool属于并发大师Doug Lea于Java 1.7新增的并发框架Fork/Join的一部分
 * Fork/Join框架完全不同于原有的ExecutorService框架，其思想与MultiThread也不尽相同。
 * 它所关心的核心为task，task可以分裂/执行，适用于各种分治算法，递归算法。
 * 用户完全不用操心线程之间的同步问题，task的调度与同步均由Fork/Join框架自动完成。
 * Fork/Join框架最核心的算法是工作偷取(work-stealing)：线程不一定仅从某个task队列中获取task，它还会从其他的队列中偷取task来运行
 * 工作偷取机制保证了task之间尽量并发操作，保证了在最坏情况下task能够尽量被平均分配。从而提高了整个框架的执行效率。
 * 关于Fork/Join理论的相关说明请见:{@link http://github.com/oldratlee/translations/blob/master/a-java-fork-join-framework/README.md}
 * 其核心设计思想为：
 *      Fork/Join程序可以在任何支持以下特性的框架之上运行：框架能够让构建的子任务并行执行，并且拥有一种等待子任务运行结束的机制。
 *      然而，java.lang.Thread类（同时也包括POSIX pthread，这些也是Java线程所基于的基础）对Fork/Join程序来说并不是最优的选择：
 *      Fork/Join任务对同步和管理有简单的和常规的需求。相对于常规的线程来说，Fork/Join任务所展示的计算布局将会带来更加灵活的调度策略。
 *      例如，Fork/Join任务除了等待子任务外，其他情况下是不需要阻塞的。因此传统的用于跟踪记录阻塞线程的代价在这种情况下实际上是一种浪费。
 *      对于一个合理的基础任务粒度来说，构建和管理一个线程的代价甚至可以比任务执行本身所花费的代价更大。
 *      尽管粒度是应该随着应用程序在不同特定平台上运行而做出相应调整的。但是超过线程开销的极端粗粒度会限制并行的发挥。
 *      对于一个合理的基础任务粒度来说，构建和管理一个线程的代价甚至可以比任务执行本身所花费的代价更大。
 *      尽管粒度是应该随着应用程序在不同特定平台上运行而做出相应调整的。但是超过线程开销的极端粗粒度会限制并行的发挥。
 *      简而言之，Java标准的线程框架对Fork/Join程序而言太笨重了。但是既然线程构成了很多其他的并发和并行编程的基础，完全消除这种代价或者为了这种方式而调整线程调度是不可能（或者说不切实际的）。
 *      尽管这种思想已经存在了很长时间了，但是第一个发布的能系统解决这些问题的框架是Cilk。Cilk和其他轻量级的框架是基于操作系统的基本的线程和进程机制来支持特殊用途的Fork/Join程序。
 *      这种策略同样适用于Java，尽管Java线程是基于低级别的操作系统的能力来实现的。创造这样一个轻量级的执行框架的主要优势是能够让Fork/Join程序以一种更直观的方式编写，进而能够在各种支持JVM的系统上运行。
 *      FJTask框架是基于Cilk设计的一种演变。其他的类似框架有Hood、Filaments、Stackthreads以及一些依赖于轻量级执行任务的相关系统。
 *      所有这些框架都采用和操作系统把线程映射到CPU上相同的方式来把任务映射到线程上。只是他们会使用Fork/Join程序的简单性、常规性以及一致性来执行这种映射。
 *      尽管这些框架都能适应不能形式的并行程序，他们优化了Fork/Join的设计：
 *          1.一组工作者线程池是准备好的。每个工作线程都是标准的（『重量级』）处理存放在队列中任务的线程（这地方指的是Thread类的子类FJTaskRunner的实例对象）。
 *          通常情况下，工作线程应该与系统的处理器数量一致。对于一些原生的框架例如说Cilk，他们首先将映射成内核线程或者是轻量级的进程，然后再在处理器上面运行。
 *          在Java中，虚拟机和操作系统需要相互结合来完成线程到处理器的映射。然后对于计算密集型的运算来说，这种映射对于操作系统来说是一种相对简单的任务。
 *          任何合理的映射策略都会导致线程映射到不同的处理器。
 *          2.所有的Fork/Join任务都是轻量级执行类的实例，而不是线程实例。在Java中，独立的可执行任务必须要实现Runnable接口并重写run方法。
 *          在FJTask框架中，这些任务将作为子类继承FJTask而不是Thread，它们都实现了Runnable接口。
 *          （对于上面两种情况来说，一个类也可以选择实现Runnable接口，类的实例对象既可以在任务中执行也可以在线程中执行。
 *          因为任务执行受到来自FJTask方法严厉规则的制约，子类化FJTask相对来说更加方便，也能够直接调用它们。）
 *          3.我们将采用一个特殊的队列和调度原则来管理任务并通过工作线程来执行任务。这些机制是由任务类中提供的相关方式实现的：主要是由fork、join、
 *          isDone（一个结束状态的标示符），和一些其他方便的方法，例如调用coInvoke来分解合并两个或两个以上的任务。
 *          4.一个简单的控制和管理类（这里指的是FJTaskRunnerGroup）来启动工作线程池，并初始化执行一个由正常的线程调用所触发的Fork/Join任务（就类似于Java程序中的main方法）。
 */
public class ForkJoinPool {

    /**
     * ForkJoinPool的RunStatus
     */
    // runState bits: SHUTDOWN must be negative, others arbitrary powers of two
    private static final int  RSLOCK     = 1;
    private static final int  RSIGNAL    = 1 << 1;
    private static final int  STARTED    = 1 << 2;
    private static final int  STOP       = 1 << 29;
    private static final int  TERMINATED = 1 << 30;
    private static final int  SHUTDOWN   = 1 << 31;

    /**
     * 非ForkJoinPool中的task进行提交。
     * @param task
     */
    final void externalPush(ForkJoinTask<?> task) {
        WorkQueue[] ws; WorkQueue q; int m;
        int r = ThreadLocalRandom.getProbe();
        int rs = runState;
        if ((ws = workQueues) != null && (m = (ws.length - 1)) >= 0 &&
                (q = ws[m & r & SQMASK]) != null && r != 0 && rs > 0 &&
                U.compareAndSwapInt(q, QLOCK, 0, 1)) {
            ForkJoinTask<?>[] a; int am, n, s;
            if ((a = q.array) != null &&
                    (am = a.length - 1) > (n = (s = q.top) - q.base)) {
                int j = ((am & s) << ASHIFT) + ABASE;
                U.putOrderedObject(a, j, task);
                U.putOrderedInt(q, QTOP, s + 1);
                U.putIntVolatile(q, QLOCK, 0);
                if (n <= 1)
                    signalWork(ws, q);
                return;
            }
            U.compareAndSwapInt(q, QLOCK, 1, 0);
        }
        externalSubmit(task);
    }

    /**
     *
     * @param task
     */
    private void externalSubmit(ForkJoinTask<?> task) {
        int r;                                    // initialize caller's probe
        if ((r = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();
            r = ThreadLocalRandom.getProbe();
        }
        //上面为获取一个随机数r，下面才是核心逻辑
        for (;;) {
            WorkQueue[] ws; WorkQueue q; int rs, m, k;
            boolean move = false;
            //判断ForkJoinPool是否Terminate
            if ((rs = runState) < 0) {
                tryTerminate(false, false);     // help terminate
                throw new RejectedExecutionException();
            }
            //ForkJoinPool刚初始化完成，还没有workQueues
            else if ((rs & STARTED) == 0 ||     // initialize
                    ((ws = workQueues) == null || (m = ws.length - 1) < 0)) {
                int ns = 0;
                //锁定 & 获取
                rs = lockRunState();
                try {
                    if ((rs & STARTED) == 0) {
                        U.compareAndSwapObject(this, STEALCOUNTER, null,
                                new AtomicLong());
                        // create workQueues array with size a power of two
                        int p = config & SMASK; // ensure at least 2 slots
                        int n = (p > 1) ? p - 1 : 1;
                        n |= n >>> 1; n |= n >>> 2;  n |= n >>> 4;
                        n |= n >>> 8; n |= n >>> 16; n = (n + 1) << 1;
                        //主要逻辑在此：创健WokrQueue数组
                        workQueues = new WorkQueue[n];
                        ns = STARTED;
                    }
                } finally {
                    unlockRunState(rs, (rs & ~RSLOCK) | ns);
                }
            }
            //ForkJoinPool已经初始化完毕，含有WorkQueue数据
            else if ((q = ws[k = r & m & SQMASK]) != null) {
                //锁定wq
                if (q.qlock == 0 && U.compareAndSwapInt(q, QLOCK, 0, 1)) {
                    ForkJoinTask<?>[] a = q.array;
                    int s = q.top;
                    boolean submitted = false; // initial submission or resizing
                    try {                      // locked version of push
                        if ((a != null && a.length > s + 1 - q.base) ||
                                //扩容
                                (a = q.growArray()) != null) {
                            //s的下一个位置
                            int j = (((a.length - 1) & s) << ASHIFT) + ABASE;
                            //使用CAS将task放入数组，并更新top的值
                            U.putOrderedObject(a, j, task);
                            U.putOrderedInt(q, QTOP, s + 1);
                            submitted = true;
                        }
                    } finally {
                        U.compareAndSwapInt(q, QLOCK, 1, 0);
                    }
                    if (submitted) {
                        signalWork(ws, q);
                        return;
                    }
                }
                move = true;                   // move on failure
            }
            //创建一个新的wq
            else if (((rs = runState) & RSLOCK) == 0) { // create new queue
                q = new WorkQueue(this, null);
                q.hint = r;
                q.config = k | SHARED_QUEUE;
                q.scanState = INACTIVE;
                rs = lockRunState();           // publish index
                if (rs > 0 &&  (ws = workQueues) != null &&
                        k < ws.length && ws[k] == null)
                    ws[k] = q;                 // else terminated
                unlockRunState(rs, rs & ~RSLOCK);
            }
            else
                move = true;                   // move if busy
            if (move)
                r = ThreadLocalRandom.advanceProbe(r);
        }
    }

    /**
     * 创建执行线程
     * @param ws
     * @param q
     */
    final void signalWork(WorkQueue[] ws, WorkQueue q) {
        long c; int sp, i; WorkQueue v; Thread p;
        while ((c = ctl) < 0L) {                       // too few active
            if ((sp = (int)c) == 0) {                  // no idle workers
                if ((c & ADD_WORKER) != 0L)            // too few workers
                    tryAddWorker(c);
                break;
            }
            if (ws == null)                            // unstarted/terminated
                break;
            if (ws.length <= (i = sp & SMASK))         // terminated
                break;
            if ((v = ws[i]) == null)                   // terminating
                break;
            int vs = (sp + SS_SEQ) & ~INACTIVE;        // next scanState
            int d = sp - v.scanState;                  // screen CAS
            long nc = (UC_MASK & (c + AC_UNIT)) | (SP_MASK & v.stackPred);
            if (d == 0 && U.compareAndSwapLong(this, CTL, c, nc)) {
                v.scanState = vs;                      // activate v
                if ((p = v.parker) != null)
                    U.unpark(p);
                break;
            }
            if (q != null && q.base == q.top)          // no more work
                break;
        }
    }

    /**
     * ForkJoinPool创建worker的核心逻辑
     * @return
     */
    private boolean createWorker() {
        ForkJoinWorkerThreadFactory fac = factory;
        Throwable ex = null;
        ForkJoinWorkerThread wt = null;
        try {
            if (fac != null && (wt = fac.newThread(this)) != null) {
                wt.start();
                return true;
            }
        } catch (Throwable rex) {
            ex = rex;
        }
        deregisterWorker(wt, ex);
        return false;
    }

    /**
     * 用于管理task的内部类
     */
    static final class WorkQueue {


        /**
         * Initializes or doubles the capacity of array. Call either
         * by owner or with lock held -- it is OK for base, but not
         * top, to move while resizings are in progress.
         */
        final ForkJoinTask<?>[] growArray() {
            ForkJoinTask<?>[] oldA = array;
            int size = oldA != null ? oldA.length << 1 : INITIAL_QUEUE_CAPACITY;
            if (size > MAXIMUM_QUEUE_CAPACITY)
                throw new RejectedExecutionException("Queue capacity exceeded");
            int oldMask, t, b;
            //创建新queue
            ForkJoinTask<?>[] a = array = new ForkJoinTask<?>[size];
            //将oldqueue中的值依次移入新的queue中
            if (oldA != null && (oldMask = oldA.length - 1) >= 0 &&
                    (t = top) - (b = base) > 0) {
                int mask = size - 1;
                do { // emulate poll from old array, push to new array
                    ForkJoinTask<?> x;
                    int oldj = ((b & oldMask) << ASHIFT) + ABASE;
                    int j    = ((b &    mask) << ASHIFT) + ABASE;
                    x = (ForkJoinTask<?>)U.getObjectVolatile(oldA, oldj);
                    if (x != null &&
                            U.compareAndSwapObject(oldA, oldj, x, null))
                        U.putObjectVolatile(a, j, x);
                } while (++b != t);
            }
            return a;
        }

        /**
         * Pushes a task. Call only by owner in unshared queues.  (The
         * shared-queue version is embedded in method externalPush.)
         * 将一个task直接push到queue中(top位置)
         * @param task the task. Caller must ensure non-null.
         * @throws RejectedExecutionException if array cannot be resized
         */
        final void push(ForkJoinTask<?> task) {
            ForkJoinTask<?>[] a; ForkJoinPool p;
            int b = base, s = top, n;
            if ((a = array) != null) {    // ignore if queue removed
                int m = a.length - 1;     // fenced write for task visibility
                U.putOrderedObject(a, ((m & s) << ASHIFT) + ABASE, task);
                U.putOrderedInt(this, QTOP, s + 1);
                //需要signal的情况
                if ((n = s - b) <= 1) {
                    if ((p = pool) != null)
                        p.signalWork(p.workQueues, this);
                }
                //插入新数据后，queue太大了，需要扩大其容量
                else if (n >= m)
                    growArray();
            }
        }

    }

    /**
     * Top-level runloop for workers, called by ForkJoinWorkerThread.run.
     * 启动一个worker，请注意这里的worker的queue应为null，所以worker的第一个任务需要从其他queue中steal
     */
    final void runWorker(WorkQueue w) {
        w.growArray();                   // allocate queue
        int seed = w.hint;               // initially holds randomization hint
        int r = (seed == 0) ? 1 : seed;  // avoid 0 for xorShift
        for (ForkJoinTask<?> t;;) {
            //steal work & execute
            if ((t = scan(w, r)) != null)
                w.runTask(t);
            else if (!awaitWork(w, r))
                break;
            r ^= r << 13; r ^= r >>> 17; r ^= r << 5; // xorshift
        }
    }

    /**
     * worker线程寻找task的具体方法,偷取其他queue base处的值
     * @param w
     * @param r 一个随机值
     * @return
     */
    private ForkJoinTask<?> scan(WorkQueue w, int r) {
        WorkQueue[] ws; int m;
        if ((ws = workQueues) != null && (m = ws.length - 1) > 0 && w != null) {
            int ss = w.scanState;                     // initially non-negative
            for (int origin = r & m, k = origin, oldSum = 0, checkSum = 0;;) {
                WorkQueue q; ForkJoinTask<?>[] a; ForkJoinTask<?> t;
                int b, n; long c;
                //首先从随机的workQueue找起
                if ((q = ws[k]) != null) {
                    if ((n = (b = q.base) - q.top) < 0 &&
                            (a = q.array) != null) {      // non-empty
                        long i = (((a.length - 1) & b) << ASHIFT) + ABASE;
                        if ((t = ((ForkJoinTask<?>)
                                U.getObjectVolatile(a, i))) != null &&
                                q.base == b) {
                            //找到base处的task并赋值给t
                            if (ss >= 0) {
                                if (U.compareAndSwapObject(a, i, t, null)) {
                                    q.base = b + 1;
                                    if (n < -1)       // signal others
                                        signalWork(ws, q);
                                    return t;
                                }
                            }
                            else if (oldSum == 0 &&   // try to activate
                                    w.scanState < 0)
                                tryRelease(c = ctl, ws[m & (int)c], AC_UNIT);
                        }
                        //重新进行scan操作
                        if (ss < 0)                   // refresh
                            ss = w.scanState;
                        r ^= r << 1; r ^= r >>> 3; r ^= r << 10;
                        origin = k = r & m;           // move and rescan
                        oldSum = checkSum = 0;
                        continue;
                    }
                    checkSum += b;
                }
                //请注意这里的隐藏逻辑：k = (k + 1) & m：典型线性探测法
                if ((k = (k + 1) & m) == origin) {    // continue until stable
                    //后面的操作均为将ss置为inactive
                    if ((ss >= 0 || (ss == (ss = w.scanState))) &&
                            oldSum == (oldSum = checkSum)) {
                        if (ss < 0 || w.qlock < 0)    // already inactive
                            break;
                        int ns = ss | INACTIVE;       // try to inactivate
                        long nc = ((SP_MASK & ns) |
                                (UC_MASK & ((c = ctl) - AC_UNIT)));
                        w.stackPred = (int)c;         // hold prev stack top
                        U.putInt(w, QSCANSTATE, ns);
                        if (U.compareAndSwapLong(this, CTL, c, nc))
                            ss = ns;
                        else
                            w.scanState = ss;         // back out
                    }
                    checkSum = 0;
                }
            }
        }
        return null;
    }

    /**
     * Pops the given task only if it is at the current top.
     * (A shared version is available only via FJP.tryExternalUnpush)
     * FJT从自己的workQueue中pop一个任务出来(获取top处的task)
     * 由于是自己独占的workQueue，所以不需要加锁
     */
    final boolean tryUnpush(ForkJoinTask<?> t) {
        ForkJoinTask<?>[] a; int s;
        if ((a = array) != null && (s = top) != base &&
                U.compareAndSwapObject
                        (a, (((a.length - 1) & --s) << ASHIFT) + ABASE, t, null)) {
            U.putOrderedInt(this, QTOP, s);
            return true;
        }
        return false;
    }

    /**
     * Helps and/or blocks until the given task is done or timeout.
     * 等待一个task执行完成
     *
     * @param w caller
     * @param task the task
     * @param deadline for timed waits, if nonzero
     * @return task status on exit
     */
    final int awaitJoin(WorkQueue w, ForkJoinTask<?> task, long deadline) {
        int s = 0;
        if (task != null && w != null) {
            ForkJoinTask<?> prevJoin = w.currentJoin;
            U.putOrderedObject(w, QCURRENTJOIN, task);
            CountedCompleter<?> cc = (task instanceof CountedCompleter) ?
                    (CountedCompleter<?>)task : null;
            for (;;) {
                if ((s = task.status) < 0)
                    break;
                if (cc != null)
                    helpComplete(w, cc, 0);
                //请注意这里为重点
                else if (w.base == w.top || w.tryRemoveAndExec(task)) //tryRemoveAndExec(task)方法将从queue中找到并获取task，执行之
                    //否则steal其他queue的task并执行
                    helpStealer(w, task);
                if ((s = task.status) < 0)
                    break;
                long ms, ns;
                if (deadline == 0L)
                    ms = 0L;
                else if ((ns = deadline - System.nanoTime()) <= 0L)
                    break;
                else if ((ms = TimeUnit.NANOSECONDS.toMillis(ns)) <= 0L)
                    ms = 1L;
                if (tryCompensate(w)) {
                    task.internalWait(ms);
                    U.getAndAddLong(this, CTL, AC_UNIT);
                }
            }
            U.putOrderedObject(w, QCURRENTJOIN, prevJoin);
        }
        return s;
    }
}
