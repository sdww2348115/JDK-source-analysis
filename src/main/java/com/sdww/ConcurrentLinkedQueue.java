package com.sdww;

/**
 * 分析该class的源代码时有两点需要注意：
 * 1.通过head node能够遍历到每一个存活的node
 * 2.tail node仅用于方便查找queue的最末node，并不保证一定指向最末node
 */
public class ConcurrentLinkedQueue {

    /**
     * ConcurrentLinkedQueue的插入操作，注意tail节点并不一定指向queue的最终元素！
     *
     * @param e
     * @return
     */
    public boolean offer(E e) {
        //绝大多数Concurrent Collection均不支持元素为null
        checkNotNull(e);
        final Node<E> newNode = new Node<E>(e);

        //个人觉得源代码的组织形式不太容易观看，稍微修改了一下：将for循环中的初始条件放在for代码块前面
        Node<E> t = tail, p = t;
        for (;;) {
            Node<E> q = p.next;
            //无论是怎么寻找的末尾，当q == null时，代表队列的末尾元素一定为p，target将被放在p的后面
            if (q == null) {
                // p is last node
                if (p.casNext(null, newNode)) {
                    // Successful CAS is the linearization point
                    // for e to become an element of this queue,
                    // and for newNode to become "live".
                    // 这里隐含的逻辑为：如果 p == t即tail目前指向p，tail所指向的node将不会更新！
                    // 从这里可以看出tail并不保证指向queue的最末node！
                    // 这样做的原因是减少cas操作，提高并发效率。
                    if (p != t) // hop two nodes at a time
                        casTail(t, newNode);  // Failure is OK.说明其他thread处理成功了，tail仍然能够正确指向queue的末尾
                    return true;
                }
                // Lost CAS race to another thread; re-read next
            }
            /**
             * 以下两个分支用于查找queue事实上的末尾节点
             */
            else if (p == q)
                // We have fallen off list.  If tail is unchanged, it
                // will also be off-list, in which case we need to
                // jump to head, from which all live nodes are always
                // reachable.  Else the new tail is a better bet.
                p = (t != (t = tail)) ? t : head;
            else
                // Check for tail updates after two hops.
                p = (p != t && t != (t = tail)) ? t : q;
        }
    }

    /**
     * 获取队列的最长live node
     * @return
     */
    public E poll() {
        restartFromHead:
        for (;;) {
            for (Node<E> h = head, p = h, q;;) {
                E item = p.item;

                /**
                 * 这里的处理与上面offer()处一样，只有当获取的node为head.next时才会更新head所指向的node
                 * 也是每两次offer()更新一次head
                 */
                if (item != null && p.casItem(item, null)) {
                    // Successful CAS is the linearization point
                    // for item to be removed from this queue.
                    if (p != h) // hop two nodes at a time
                        updateHead(h, ((q = p.next) != null) ? q : p);
                    return item;
                }
                //队列已经为空
                else if ((q = p.next) == null) {
                    updateHead(h, p);
                    return null;
                }
                //与上面offer()方法的p == q一样，表明node p已经被从queue中移除
                else if (p == q)
                    continue restartFromHead;
                else
                    p = q;
            }
        }
    }

    /**
     * Tries to CAS head to p. If successful, repoint old head to itself
     * as sentinel for succ(), below.
     * 这里需要注意的是：更新完毕head所指向的Node后，原head的tail将被指向自己
     * 也就是上面两个方法中所判断的p == q的情况！如果node.next == node，说明node已经被从queue中移除
     */
    final void updateHead(Node<E> h, Node<E> p) {
        if (h != p && casHead(h, p))
            h.lazySetNext(h);
    }

    /**
     * 与大多数并发容器一样，size()方法并不被鼓励使用
     * ConcurrentLinkedQueue的size方法并非O(1)，而是需要遍历queue的O(n)
     * @return
     */
    public int size() {
        int count = 0;
        for (Node<E> p = first(); p != null; p = succ(p))
            if (p.item != null)
                // Collection.size() spec says to max out
                if (++count == Integer.MAX_VALUE)
                    break;
        return count;
    }
}
