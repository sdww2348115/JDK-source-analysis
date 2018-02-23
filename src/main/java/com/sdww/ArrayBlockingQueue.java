package com.sdww;

/**
 * JDK concurrent collections共包含了8种类型的BlockingQueue，其继承关系如下：
 * BlockingQueue:
 *  implements:
 *      1.{@link java.util.concurrent.ArrayBlockingQueue}
 *      2.{@link java.util.concurrent.LinkedBlockingQueue}
 *      3.{@link java.util.concurrent.PriorityBlockingQueue}
 *      4.{@link java.util.concurrent.DelayQueue}
 *      5.{@link java.util.concurrent.SynchronousQueue}
 *  extends:
 *      1.{@link java.util.concurrent.TransferQueue}
 *        implement:
 *            {@link java.util.concurrent.LinkedTransferQueue}
 *      2.{@link java.util.concurrent.BlockingDeque}
 *        implement:
 *            {@link java.util.concurrent.LinkedBlockingDeque}
 * 本文通过阅读ArrayBlockingQueue源码，分析其工作实现方式与原理。
 * ArrayBlockingQueue通过一个{@link java.util.concurrent.locks.ReentrantLock} 实现线程间的同步
 * tips：
 *  1.可设置锁的公平与否
 *  2.Condition直接通过ReentrantLock.newCondition()获取
 */
public class ArrayBlockingQueue {

    /**
     * 阻塞模式的放入操作
     * 通过lock与condition的配合操作实现线程之间的同步
     * 通过判断count == items.length判断队列是否为满
     * @param e
     * @throws InterruptedException
     */
    public void put(E e) throws InterruptedException {
        checkNotNull(e);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            //请注意这里是使用的while循环来进行判断
            //原因是Condition.signal()方法只能保证将处于等待队列中的线程移到同步队列中，却不能保证该线程一定能够获取到锁！
            while (count == items.length)
                notFull.await();
            enqueue(e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将元素放入数组中的实际操作
     * 当putIndex超出下界时，将其回拨至0
     * @param x
     */
    private void enqueue(E x) {
        // assert lock.getHoldCount() == 1;
        // assert items[putIndex] == null;
        final Object[] items = this.items;
        items[putIndex] = x;
        if (++putIndex == items.length)
            putIndex = 0;
        count++;
        notEmpty.signal();
    }

    /**
     * 基本与put()完全镜像的操作，这里就不再赘述了
     * @return
     * @throws InterruptedException
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        try {
            while (count == 0)
                notEmpty.await();
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Extracts element at current take position, advances, and signals.
     * Call only when holding lock.
     */
    private E dequeue() {
        // assert lock.getHoldCount() == 1;
        // assert items[takeIndex] != null;
        final Object[] items = this.items;
        @SuppressWarnings("unchecked")
        E x = (E) items[takeIndex];
        items[takeIndex] = null;
        if (++takeIndex == items.length)
            takeIndex = 0;
        count--;
        //请注意这里
        if (itrs != null)
            itrs.elementDequeued();
        notFull.signal();
        return x;
    }

    /**
     * 个人觉得，整个ArrayBlockingQueue的核心算法相对比较简单
     * 整个类中比较巧妙的地方在于其迭代器的算法实现
     * 关键属性：
     *  1.prevCycles:上一次调用迭代器中方法时迭代器的圈数
     *  2.prevTakeIndex:上一次调用迭代器方法时Queue的Take方法处index，相当于队列头
     * Itr通过prevCycles与prevTakeIndex判断Queue是否有修改，以及修改后该迭代器是否仍然有效。
     * 大多数迭代器方法（next,remove等）都会在获取到锁之后立即调用incorporateDequeues()方法以保证操作能够正确作用于期望的值上。
     * 同时，迭代器方法中也会多次判断当前迭代器是否失效，比如落后太多该迭代器将被判定为失效。失效的迭代器将被标记为Detached，并将会被Iters清理掉。
     * Itr与Itrs有两个地方个人觉得设计比较巧妙：
     *  1.使用一个迭代器组hold住所有迭代器，并使用事件通知的方式将程序的修改传递到每一个迭代器处，最终使得每一个迭代器可以尽可能输出正确的值。
     *  每当queue中有值被删除时，都会调用Iters中的removedAt方法，将各个iter处理为能够指向正确的地址
     *  2.在使用链表将所有迭代器组织起来时，对于iter的refrence采用的是WeakRef，当迭代器使用完后，Iters链表中iter的Ref将被GC为null。
     *  也就是说，对于register类的处理时，我们可以把register后的ref以WeakRef的方式放在容器中，这样不会影响其本身使用完成后的GC
     */
    private class Itr implements Iterator<E> {

        /**
         * 指向next()后的下一个位置
         */
        private int cursor;

        /** Element to be returned by next call to next(); null if none */
        private E nextItem;

        /** Index of nextItem; NONE if none, REMOVED if removed elsewhere */
        private int nextIndex;

        /** Last element returned; null if none or not detached. */
        private E lastItem;

        /** Index of lastItem, NONE if none, REMOVED if removed elsewhere */
        private int lastRet;

        /** Previous value of takeIndex, or DETACHED when detached */
        private int prevTakeIndex;

        /** Previous value of iters.cycles */
        private int prevCycles;

        /** Special index value indicating "not available" or "undefined" */
        private static final int NONE = -1;

        /**
         * Special index value indicating "removed elsewhere", that is,
         * removed by some operation other than a call to this.remove().
         */
        private static final int REMOVED = -2;

        /** Special value for prevTakeIndex indicating "detached mode" */
        private static final int DETACHED = -3;
    }
}
