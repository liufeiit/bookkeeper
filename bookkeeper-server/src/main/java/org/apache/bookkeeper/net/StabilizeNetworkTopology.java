package org.apache.bookkeeper.net;

import org.apache.bookkeeper.util.MathUtils;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * This is going to provide a stabilize network topology regarding to flapping zookeeper registration.
 */
public class StabilizeNetworkTopology implements NetworkTopology {

    private static final Logger logger = LoggerFactory.getLogger(StabilizeNetworkTopology.class);

    static class NodeStatus {
        long lastPresentTime;
        boolean tentativeToRemove;

        NodeStatus() {
            this.lastPresentTime = MathUtils.now();
        }

        synchronized boolean isTentativeToRemove() {
            return tentativeToRemove;
        }

        synchronized NodeStatus updateStatus(boolean tentativeToRemove) {
            this.tentativeToRemove = tentativeToRemove;
            if (!this.tentativeToRemove) {
                this.lastPresentTime = MathUtils.now();
            }
            return this;
        }

        synchronized long getLastPresentTime() {
            return this.lastPresentTime;
        }
    }

    protected final NetworkTopologyImpl impl;
    // timer
    protected final HashedWheelTimer timer;
    // statuses
    protected final ConcurrentMap<Node, NodeStatus> nodeStatuses;
    // stabilize period seconds
    protected final long stabilizePeriodMillis;

    private class RemoveNodeTask implements TimerTask {

        private final Node node;

        RemoveNodeTask(Node node) {
            this.node = node;
        }

        @Override
        public void run(Timeout timeout) throws Exception {
            if (timeout.isCancelled()) {
                return;
            }
            NodeStatus status = nodeStatuses.get(node);
            if (null == status) {
                // no status of this node, remove this node from topology
                impl.remove(node);
            } else if (status.isTentativeToRemove()) {
                long millisSinceLastSeen = MathUtils.now() - status.getLastPresentTime();
                if (millisSinceLastSeen >= stabilizePeriodMillis) {
                    logger.info("Node {} (seen @ {}) becomes stale for {} ms, remove it from the topology.",
                            new Object[] { node, status.getLastPresentTime(), millisSinceLastSeen });
                    impl.remove(node);
                    nodeStatuses.remove(node, status);
                }
            }
        }
    }

    public StabilizeNetworkTopology(HashedWheelTimer timer,
                                    int stabilizePeriodSeconds) {
        this.impl = new NetworkTopologyImpl();
        this.timer = timer;
        this.nodeStatuses = new ConcurrentHashMap<Node, NodeStatus>();
        this.stabilizePeriodMillis = TimeUnit.SECONDS.toMillis(stabilizePeriodSeconds);
    }

    void updateNode(Node node, boolean tentativeToRemove) {
        NodeStatus ns = nodeStatuses.get(node);
        if (null == ns) {
            NodeStatus newStatus = new NodeStatus();
            NodeStatus oldStatus = nodeStatuses.putIfAbsent(node, newStatus);
            if (null == oldStatus) {
                ns = newStatus;
            } else {
                ns = oldStatus;
            }
        }
        ns.updateStatus(tentativeToRemove);
    }

    @Override
    public void add(Node node) {
        updateNode(node, false);
        this.impl.add(node);
    }

    @Override
    public void remove(Node node) {
        updateNode(node, true);
        timer.newTimeout(new RemoveNodeTask(node), stabilizePeriodMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean contains(Node node) {
        return impl.contains(node);
    }

    @Override
    public Node getNode(String loc) {
        return impl.getNode(loc);
    }

    @Override
    public int getNumOfRacks() {
        return impl.getNumOfRacks();
    }

    @Override
    public Set<Node> getLeaves(String loc) {
        return impl.getLeaves(loc);
    }
}
