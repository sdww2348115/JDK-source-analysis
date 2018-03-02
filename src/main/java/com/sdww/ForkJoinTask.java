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


}
