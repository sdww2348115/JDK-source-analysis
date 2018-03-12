package com.sdww;

/**
 * CyclicBarrier也是concurrent包中一个重要的类，可认为其为{@link java.util.concurrent.CountDownLatch}的增强版
 * CountDownLatch的典型方法：
 * 1.await()阻塞并等待CountDownLatch对象归零，阻塞
 * 2.countDown()将CountDownLatch对象中的计数值-1，CAS
 * CyclicBarrier的典型用法与CountDownLatch略微不同:它仅有一个await方法，调用后线程进入阻塞状态；当到达指定数量的Thread调用await方法后，所有的线程一起继续往下执行
 * CyclicBarrier可以调用reset()方法复用
 * 可以通过new CyclicBarrier(Runnable)的方式指定调用await()方法的线程数到达指定值之后需要执行的方法来实现类似CountDownLatch的功能。
 * 相比通过AQS简单实现的CountDownLatch，它的实现难度有以下几点:
 * 1.CountDownLatch对于计数器的减少是通过CAS原子操作来实现的，只会返回成功或失败；CyclicBarrier则是会阻塞的await方法，await阻塞后就有可能被interrupt，此时计数器应如何处理？
 * 2.同上，在其他线程await的过程中，对于CyclicBarrier调用reset()应该如何处理？
 * 3.到底是由哪个进程执行Runnable方法？
 */
public class CyclicBarrier {

    /**
     * Main barrier code, covering the various policies.
     */
    private int dowait(boolean timed, long nanos)
            throws InterruptedException, BrokenBarrierException,
            TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            //通过final的方式记录当前Generation，如果g != generation，则说明已经是新Generation了
            final Generation g = generation;

            //判断Generation是否仍然可用
            if (g.broken)
                throw new BrokenBarrierException();

            //如果这里不做检测，当最后一个await线程执行到一半却被interruptted时，CyclicBarrier会把command给执行了，与逻辑不符
            if (Thread.interrupted()) {
                breakBarrier();
                throw new InterruptedException();
            }

            //如果当前线程是最后一个await的线程，则执行command
            //说明默认情况下是由最后一个调用await的线程来执行command的！
            int index = --count;
            if (index == 0) {  // tripped
                boolean ranAction = false;
                try {
                    final Runnable command = barrierCommand;
                    if (command != null)
                        command.run();
                    ranAction = true;
                    //请注意这里，每次所有线程调用完成await后，Generation就已经被更新了，不必调用reset()方法的！
                    //调用await方法的线程通过判断Generation决定自己的返回值
                    nextGeneration();
                    return 0;
                } finally {
                    if (!ranAction)
                        breakBarrier();
                }
            }

            // loop until tripped, broken, interrupted, or timed out
            for (;;) {
                try {
                    if (!timed)
                        trip.await();
                    else if (nanos > 0L)
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    if (g == generation && ! g.broken) {
                        breakBarrier();
                        throw ie;
                    } else {
                        // We're about to finish waiting even if we had not
                        // been interrupted, so this interrupt is deemed to
                        // "belong" to subsequent execution.
                        //
                        Thread.currentThread().interrupt();
                    }
                }

                if (g.broken)
                    throw new BrokenBarrierException();

                if (g != generation)
                    return index;

                if (timed && nanos <= 0L) {
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Updates state on barrier trip and wakes up everyone.
     * Called only while holding lock.
     */
    private void nextGeneration() {
        // signal completion of last generation
        trip.signalAll();
        // set up next generation
        count = parties;
        generation = new Generation();
    }

    /**
     * Sets current barrier generation as broken and wakes up everyone.
     * Called only while holding lock.
     * 请注意这里的breakBarrier中将count赋值为parties，相当于做了一次重置操作，也就意味着catch错误后CyclicBarrier将被刷新，可直接重新使用
     */
    private void breakBarrier() {
        generation.broken = true;
        count = parties;
        trip.signalAll();
    }
}
