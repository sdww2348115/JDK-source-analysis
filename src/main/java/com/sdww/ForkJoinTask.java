package com.sdww;

/**
 *1.ForkJoinTask运行于ForkJoinPool中，类似于Thread，但是比Thread更轻量级。一般来说其运行于ForkJoinPool中，但是许多方法会隐式的使用ForkJoinPool.commonPool()
 * 比如：fork(), invoke(), 以及相关的其他方法。ForkJoinTask事实上是一种轻量级的Future,主要用于执行纯粹的计算任务(分治法、递归)或者操作独立的对象(Collection<T>.parallelStream)。
 * 在Fork/Join框架中，主要的同步方式为fork() & join()，task执行到fork()时，将会把subTasks添加到任务队列中;等到join执行完毕时再继续往下执行。
 * 在ForkJoinTask的方法中应该避免synchronize以及其他的blocking方法，不要在ForkJoinTask中使用Blocking IO，各个task及subTask所使用的数据也最好是相互独立无竞争的，这些方针被强制要求的原因是task中不允许抛出受检异常（例如IOException）。
 * UnChecked Exceptions将在join处被抛回给调用者。(这些Exceptions包含有RejectedExecutionException)
 *
 *在ForkJoinTask中使用Block是可能的，但是要遵循以下几点：
 *1.尽可能少的执行依赖于其他依赖于外部block的task的task。即尽量不要block!
 *2.最小化资源碰撞，task应该尽可能的小，尽可能少的使用blocking action
 *3.除非使用了ForkJoinPool.ManagedBlocker，或者可能发生blocking的task数量比起ForkJoinPool的getParallelism小，否则pool不保证这些task所执行的效率。
 *
 *task中等待完成/得到结果的主要方法为join()。除此之外还有一些变种：比如Future.get，invoke方法与fork,join相等，但是常常由调用的线程进行执行。invokeAll会在所有task执行完成后返回。
 *
 *在绝大多数应用中，一对fork-join操作工作起来类似于一组并行的递归操作。但是它比起这样的递归操作通常性能更加好。（并行递归）
 *
 *task状态：
 *1.isDone为true表示task执行完成（包括cancelled）
 *2.isCompletedNormally为true表示task完成执行，且没有被cancelled或抛出异常
 *3.isCompletedAbnormally与2相反
 *
 *通常不会直接使用ForkJoinTask类，而是使用其子类：
 *1.RecursiveAction: 无结果返回的task
 *2.RecursiveTask: 有结果返回的task
 *3.CountedCompleter：action完成时会触发其他actions
 *
 *使用join方法必须保证不会出现循环等待，如果存在则有可能存在死锁。需要使用一些额外的方式来帮助执行以避免死锁/循环等待
 *
 *ForkJoinTask基本计算单元数量在100到10000之间最好，如果task太大，并行流不能充分发挥优势提高吞吐量；如果太小，任务的维护与调度开销时间将大于任务处理时间，使得效率反而降低。
 */
public class ForkJoinTask {

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
     * 一般情况下，在一个task被完成并重新初始化前多次调用fork()方法会导致错误发生
     * 当前线程执行join()或返回true之前，其他线程对该task的修改并不一定对该线程可见！
     * @return
     */
    public final ForkJoinTask<V> fork() {
        Thread t;
        //当前线程在ForkJoinPool中
        if ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread)
            ((ForkJoinWorkerThread)t).workQueue.push(this);
        else
            //当前线程未在ForkJoinPool中
            ForkJoinPool.common.externalPush(this);
        return this;
    }

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
}
