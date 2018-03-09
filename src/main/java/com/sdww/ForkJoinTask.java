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

    /** The run status of this task */
    /*
     高4位用来标记完成状态:
     DONE_MASK
     NORMAL
     CANCELLED
     EXCEPTIONAL
     最低16位用来做tags:SMASK
     第17位用来标记是否需要在完成后signal其他线程:SIGNAL
     */
    volatile int status; // accessed directly by pool and workers
    static final int DONE_MASK   = 0xf0000000;  // mask out non-completion bits
    static final int NORMAL      = 0xf0000000;  // must be negative
    static final int CANCELLED   = 0xc0000000;  // must be < NORMAL
    static final int EXCEPTIONAL = 0x80000000;  // must be < CANCELLED
    static final int SIGNAL      = 0x00010000;  // must be >= 1 << 16
    static final int SMASK       = 0x0000ffff;  // short bits for tags

    /**
     * Table of exceptions thrown by tasks, to enable reporting by
     * callers. Because exceptions are rare, we don't directly keep
     * them with task objects, but instead use a weak ref table.  Note
     * that cancellation exceptions don't appear in the table, but are
     * instead recorded as status values.
     *
     * Note: These statics are initialized below in static block.
     * FJT使用一个array来保存exception，cancelled Exception将不被保存在这里面，而是通过status表明
     */
    private static final ExceptionNode[] exceptionTable;

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
     * Returns the result of the computation when it {@link #isDone is
     * done}.  This method differs from {@link #get()} in that
     * abnormal completion results in {@code RuntimeException} or
     * {@code Error}, not {@code ExecutionException}, and that
     * interrupts of the calling thread do <em>not</em> cause the
     * method to abruptly return by throwing {@code
     * InterruptedException}.
     *
     * 当task运行到isDone == done时返回结果。
     * 其与future.get()不同主要有以下几个地方不同：
     * 1.运算结果为RuntimeException或者Error
     * 2.非ExecutionException
     * 3.通过某种方法使得运行程序中断时，不会抛出interrupted exception
     *
     * @return the computed result
     */
    public final V join() {
        int s;
        if ((s = doJoin() & DONE_MASK) != NORMAL)
            reportException(s);
        return getRawResult();
    }

    /**
     * Implementation for join, get, quietlyJoin. Directly handles
     * only cases of already-completed, external wait, and
     * unfork+exec.  Others are relayed to ForkJoinPool.awaitJoin.
     *
     * @return status upon completion
     */
    private int doJoin() {
        int s; Thread t; ForkJoinWorkerThread wt; ForkJoinPool.WorkQueue w;
        return (s = status) < 0 ? s ://status < 0 代表异常，返回异常status
                ((t = Thread.currentThread()) instanceof ForkJoinWorkerThread) ?
                        (w = (wt = (ForkJoinWorkerThread)t).workQueue).
                                //从workQueue中将当前task取出来并执行
                                tryUnpush(this) && (s = doExec()) < 0 ? s :
                                wt.pool.awaitJoin(w, this, 0L) :
                        externalAwaitDone();
    }

    /**
     * Primary execution method for stolen tasks. Unless done, calls
     * exec and records status if completed, but doesn't wait for
     * completion otherwise.
     *
     * @return status on exit from this method
     */
    final int doExec() {
        int s; boolean completed;
        if ((s = status) >= 0) {
            try {
                completed = exec();
            } catch (Throwable rex) {
                //关于Exception的处理
                return setExceptionalCompletion(rex);
            }
            if (completed)
                //如何标记为完成
                s = setCompletion(NORMAL);
        }
        return s;
    }

    /**
     * Marks completion and wakes up threads waiting to join this
     * task.
     * 对于该方法只用注意一点：使用notifyAll()方法通知其他线程。说明FJ框架的通知机制是基于wait()/notifyAll()的
     *
     * @param completion one of NORMAL, CANCELLED, EXCEPTIONAL
     * @return completion status on exit
     */
    private int setCompletion(int completion) {
        for (int s;;) {
            if ((s = status) < 0)
                return s;
            if (U.compareAndSwapInt(this, STATUS, s, s | completion)) {
                //请注意，status为NOMAL、EXCEPTIONAL、CANCELLED，无论SIGNAL是否为1都会出发notifyAll()
                if ((s >>> 16) != 0)
                    synchronized (this) { notifyAll(); }
                return completion;
            }
        }
    }

    /**
     * Records exception and possibly propagates.
     * 记录异常，触发回调/传播
     *
     * @return status on exit
     */
    private int setExceptionalCompletion(Throwable ex) {
        int s = recordExceptionalCompletion(ex);
        if ((s & DONE_MASK) == EXCEPTIONAL)
            internalPropagateException(ex);
        return s;
    }

    /**
     * Records exception and sets status.
     *
     * @return status on exit
     */
    final int recordExceptionalCompletion(Throwable ex) {
        int s;
        if ((s = status) >= 0) {
            int h = System.identityHashCode(this);
            final ReentrantLock lock = exceptionTableLock;
            lock.lock();
            try {
                expungeStaleExceptions();
                //根据hash值将exception放入table中，链表解决冲突问题
                ExceptionNode[] t = exceptionTable;
                int i = h & (t.length - 1);
                for (ExceptionNode e = t[i]; ; e = e.next) {
                    if (e == null) {
                        t[i] = new ExceptionNode(this, ex, t[i]);
                        break;
                    }
                    if (e.get() == this) // already present
                        break;
                }
            } finally {
                lock.unlock();
            }
            s = setCompletion(EXCEPTIONAL);
        }
        return s;
    }

    /**
     * 从exceptionTable中删除所有exceptionTableRefQueue中的exceptionNode
     * Poll stale refs and remove them. Call only while holding lock.
     */
    private static void expungeStaleExceptions() {
        for (Object x; (x = exceptionTableRefQueue.poll()) != null;) {
            if (x instanceof ExceptionNode) {
                int hashCode = ((ExceptionNode)x).hashCode;
                ExceptionNode[] t = exceptionTable;
                int i = hashCode & (t.length - 1);
                ExceptionNode e = t[i];
                ExceptionNode pred = null;
                while (e != null) {
                    ExceptionNode next = e.next;
                    if (e == x) {
                        if (pred == null)
                            t[i] = next;
                        else
                            pred.next = next;
                        break;
                    }
                    pred = e;
                    e = next;
                }
            }
        }
    }

    /**
     * Returns a rethrowable exception for the given task, if
     * available. To provide accurate stack traces, if the exception
     * was not thrown by the current thread, we try to create a new
     * exception of the same type as the one thrown, but with the
     * recorded exception as its cause. If there is no such
     * constructor, we instead try to use a no-arg constructor,
     * followed by initCause, to the same effect. If none of these
     * apply, or any fail due to other exceptions, we return the
     * recorded exception, which is still correct, although it may
     * contain a misleading stack trace.
     *
     * 所有worker的exception都保存在exceptionTable中，每个task根据自己的hash值将产生的exception放入table中对应位置处
     * 如果产生冲突，则以链表方式保存exception
     *
     * @return the exception, or null if none
     */
    private Throwable getThrowableException() {
        if ((status & DONE_MASK) != EXCEPTIONAL)
            return null;
        int h = System.identityHashCode(this);
        ExceptionNode e;
        final ReentrantLock lock = exceptionTableLock;
        //找到属于当前task的exception
        lock.lock();
        try {
            expungeStaleExceptions();
            ExceptionNode[] t = exceptionTable;
            e = t[h & (t.length - 1)];
            while (e != null && e.get() != this)
                e = e.next;
        } finally {
            lock.unlock();
        }
        //e为当前task所抛出的exception
        Throwable ex;
        if (e == null || (ex = e.ex) == null)
            return null;
        //exception并非由当前线程抛出
        //1.该task来自于steal其他workqueue
        //2.该task被其他worker steal并执行了
        if (e.thrower != Thread.currentThread().getId()) {
            Class<? extends Throwable> ec = ex.getClass();
            try {
                Constructor<?> noArgCtor = null;
                Constructor<?>[] cs = ec.getConstructors();// public ctors only
                for (int i = 0; i < cs.length; ++i) {
                    Constructor<?> c = cs[i];
                    Class<?>[] ps = c.getParameterTypes();
                    if (ps.length == 0)
                        noArgCtor = c;
                    else if (ps.length == 1 && ps[0] == Throwable.class) {
                        Throwable wx = (Throwable)c.newInstance(ex);
                        return (wx == null) ? ex : wx;
                    }
                }
                if (noArgCtor != null) {
                    Throwable wx = (Throwable)(noArgCtor.newInstance());
                    if (wx != null) {
                        wx.initCause(ex);
                        return wx;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return ex;
    }


}
