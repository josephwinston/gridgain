/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.service;

import org.gridgain.grid.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.resources.*;
import org.gridgain.grid.service.*;
import org.gridgain.grid.util.tostring.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jdk8.backport.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.kernal.GridClosureCallMode.*;

/**
 * Wrapper for making {@link GridService} class proxies.
 */
class GridServiceProxy<T> implements Serializable {
    /** Grid logger. */
    @GridToStringExclude
    private final GridLogger log;

    /** Proxy object. */
    private final T proxy;

    /** Grid projection. */
    private final GridProjection prj;

    /** Kernal context. */
    @GridToStringExclude
    private final GridKernalContext ctx;

    /** Remote node to use for proxy invocation. */
    private final AtomicReference<GridNode> rmtNode = new AtomicReference<>();

    /** {@code True} if projection includes local node. */
    private boolean hasLocNode;

    /**
     * @param prj Grid projection.
     * @param name Service name.
     * @param svc Service type class.
     * @param sticky Whether multi-node request should be done.
     */
    @SuppressWarnings("unchecked") GridServiceProxy(GridProjection prj, String name, Class<T> svc, boolean sticky,
        GridKernalContext ctx) {
        this.prj = prj;
        this.ctx = ctx;
        hasLocNode = hasLocalNode(prj);

        log = ctx.log(getClass());

        proxy = (T)Proxy.newProxyInstance(
            svc.getClassLoader(),
            new Class[] {svc},
            new ProxyInvocationHandler(name, sticky)
        );
    }

    /**
     * @param prj Grid nodes projection.
     * @return Whether given projection contains any local node.
     */
    private boolean hasLocalNode(GridProjection prj) {
        for (GridNode n : prj.nodes()) {
            if (n.isLocal())
                return true;
        }

        return false;
    }

    /**
     * @return Proxy object for a given instance.
     */
    T proxy() {
        return proxy;
    }

    /**
     * Invocation handler for service proxy.
     */
    private class ProxyInvocationHandler implements InvocationHandler {
        /** Service name. */
        private final String name;

        /** Whether multi-node request should be done. */
        private final boolean sticky;

        /**
         * @param name Name.
         * @param sticky Sticky.
         */
        private ProxyInvocationHandler(String name, boolean sticky) {
            this.name = name;
            this.sticky = sticky;
        }

        /** {@inheritDoc} */
        @SuppressWarnings("BusyWait")
        @Override public Object invoke(Object proxy, final Method mtd, final Object[] args) {
            while (true) {
                GridNode node = null;

                try {
                    node = nodeForService(name, sticky);

                    if (node == null)
                        throw new GridRuntimeException("Failed to find deployed service: " + name);

                    // If service is deployed locally, then execute locally.
                    if (node.isLocal()) {
                        GridServiceContextImpl svcCtx = ctx.service().serviceContext(name);

                        if (svcCtx != null)
                            return mtd.invoke(svcCtx.service(), args);
                    }
                    else {
                        // Execute service remotely.
                        return ctx.closure().callAsyncNoFailover(
                            BALANCE,
                            new ServiceProxyCallable(mtd.getName(), name, args),
                            Collections.singleton(node),
                            false
                        ).get();
                    }
                }
                catch (GridServiceNotFoundException | GridTopologyException e) {
                    if (log.isDebugEnabled())
                        log.debug("Service was not found or topology changed (will retry): " + e.getMessage());
                }
                catch (RuntimeException | Error e) {
                    throw e;
                }
                catch (Exception e) {
                    throw new GridRuntimeException(e);
                }

                // If we are here, that means that service was not found
                // or topology was changed. In this case, we erase the
                // previous sticky node and try again.
                rmtNode.compareAndSet(node, null);

                // Add sleep between retries to avoid busy-wait loops.
                try {
                    Thread.sleep(10);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    throw new GridRuntimeException(e);
                }
            }
        }

        /**
         * @param sticky Whether multi-node request should be done.
         * @param name Service name.
         * @return Node with deployed service or {@code null} if there is no such node.
         */
        private GridNode nodeForService(String name, boolean sticky) {
            do { // Repeat if reference to remote node was changed.
                if (sticky) {
                    GridNode curNode = rmtNode.get();

                    if (curNode != null)
                        return curNode;

                    curNode = randomNodeForService(name);

                    if (curNode == null)
                        return null;

                    if (rmtNode.compareAndSet(null, curNode))
                        return curNode;
                }
                else
                    return randomNodeForService(name);
            }
            while (true);
        }

        /**
         * @param name Service name.
         * @return Local node if it has a given service deployed or randomly chosen remote node,
         * otherwise ({@code null} if given service is not deployed on any node.
         */
        private GridNode randomNodeForService(String name) {
            if (hasLocNode && ctx.service().service(name) != null)
                return ctx.discovery().localNode();

            Map<UUID, Integer> snapshot = serviceTopology(name);

            if (snapshot == null || snapshot.isEmpty())
                return null;

            // Optimization for cluster singletons.
            if (snapshot.size() == 1) {
                UUID nodeId = snapshot.keySet().iterator().next();

                return prj.node(nodeId);
            }

            Collection<GridNode> nodes = prj.nodes();

            // Optimization for 1 node in projection.
            if (nodes.size() == 1) {
                GridNode n = nodes.iterator().next();

                return snapshot.containsKey(n.id()) ? n : null;
            }

            // Optimization if projection is the whole grid.
            if (prj.predicate() == F.<GridNode>alwaysTrue()) {
                int idx = ThreadLocalRandom8.current().nextInt(snapshot.size());

                int i = 0;

                // Get random node.
                for (Map.Entry<UUID, Integer> e : snapshot.entrySet()) {
                    if (i++ >= idx) {
                        if (e.getValue() > 0)
                            return ctx.discovery().node(e.getKey());
                    }
                }

                i = 0;

                // Circle back.
                for (Map.Entry<UUID, Integer> e : snapshot.entrySet()) {
                    if (e.getValue() > 0)
                        return ctx.discovery().node(e.getKey());

                    if (i++ == idx)
                        return null;
                }
            }
            else {
                List<GridNode> nodeList = new ArrayList<>(nodes.size());

                for (GridNode n : nodeList) {
                    Integer cnt = snapshot.get(n.id());

                    if (cnt != null && cnt > 0)
                        nodeList.add(n);
                }

                int idx = ThreadLocalRandom8.current().nextInt(nodeList.size());

                return nodeList.get(idx);
            }

            return null;
        }

        /**
         * @param name Service name.
         * @return Map of number of service instances per node ID.
         */
        private Map<UUID, Integer> serviceTopology(String name) {
            for (GridServiceDescriptor desc : ctx.service().deployedServices()) {
                if (desc.name().equals(name))
                    return desc.topologySnapshot();
            }

            return null;
        }
    }

    /**
     * Callable proxy class.
     */
    private static class ServiceProxyCallable implements GridCallable<Object>, Externalizable {
        /** Serial version UID. */
        private static final long serialVersionUID = 0L;

        /** Method name. */
        private String mtdName;

        /** Service name. */
        private String svcName;

        /** Args. */
        private Object[] args;

        /** Grid instance. */
        @GridInstanceResource
        private transient Grid grid;

        /**
         * Empty constructor required for {@link Externalizable}.
         */
        public ServiceProxyCallable() {
            // No-op.
        }

        /**
         * @param mtdName Service method to invoke.
         * @param svcName Service name.
         * @param args Arguments for invocation.
         */
        private ServiceProxyCallable(String mtdName, String svcName, Object[] args) {
            this.mtdName = mtdName;
            this.svcName = svcName;
            this.args = args;
        }

        /** {@inheritDoc} */
        @Override public Object call() throws Exception {
            GridServiceContextImpl svcCtx = ((GridKernal)grid).context().service().serviceContext(svcName);

            if (svcCtx == null)
                throw new GridServiceNotFoundException(svcName);

            GridServiceMethodReflectKey key = new GridServiceMethodReflectKey(mtdName, args);

            Method mtd = svcCtx.method(key);

            if (mtd == null)
                throw new GridServiceMethodNotFoundException(svcName, mtdName, key.argTypes());

            return mtd.invoke(svcCtx.service(), args);
        }

        /** {@inheritDoc} */
        @Override public void writeExternal(ObjectOutput out) throws IOException {
            U.writeString(out, svcName);
            U.writeString(out, mtdName);
            U.writeArray(out, args);
        }

        /** {@inheritDoc} */
        @Override public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
            svcName = U.readString(in);
            mtdName = U.readString(in);
            args = U.readArray(in);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(ServiceProxyCallable.class, this);
        }
    }
}