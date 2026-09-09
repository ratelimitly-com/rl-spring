package com.ratelimitly.spring.servlet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import com.ratelimitly.spring.policy.RateLimitlyPolicy;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/** Request-owned state: nesting is separate from continuation, never a global "already checked" flag. */
final class ServletDispatchLifecycle {
    private final Deque<Admission> active = new ArrayDeque<>();
    private final List<Admission> pending = new ArrayList<>();

    static ServletDispatchLifecycle get(HttpServletRequest request, String attribute) {
        Object existing = request.getAttribute(attribute);
        if (existing instanceof ServletDispatchLifecycle lifecycle) { return lifecycle; }
        ServletDispatchLifecycle lifecycle = new ServletDispatchLifecycle();
        request.setAttribute(attribute, lifecycle);
        return lifecycle;
    }

    synchronized Admission resume(HttpServletRequest request, Object handler) {
        Target target = Target.of(request);
        for (var iterator = pending.iterator(); iterator.hasNext();) {
            Admission admission = iterator.next();
            if (Objects.equals(admission.handler, handler) && admission.target.equals(target)
                    && !admission.completed.get()) {
                iterator.remove();
                return admission;
            }
        }
        return null;
    }

    synchronized void enter(Admission admission) { active.push(admission); }

    synchronized Admission exit(Object handler) {
        Admission current = active.peek();
        return current != null && Objects.equals(current.handler, handler) ? active.pop() : null;
    }

    private synchronized void suspend(Admission admission) {
        if (!pending.contains(admission)) { pending.add(admission); }
    }

    private synchronized void forget(Admission admission) { pending.remove(admission); }

    Admission admitted(HttpServletRequest request, Object handler,
            RateLimitlyPolicy policy, ServletRateLimitEnforcer enforcer) {
        return new Admission(handler, Target.of(request), policy, enforcer);
    }

    private record Target(String method, String uri, String query) {
        static Target of(HttpServletRequest request) {
            String query = request.getQueryString();
            if (request.getDispatcherType() == jakarta.servlet.DispatcherType.INCLUDE) {
                query = (String) request.getAttribute(RequestDispatcher.INCLUDE_QUERY_STRING);
            }
            return new Target(request.getMethod(), ServletRequestIdentitySupport.dispatchUri(request), query);
        }
    }

    final class Admission {
        private final Object handler;
        private final Target target;
        private final RateLimitlyPolicy policy;
        private final ServletRateLimitEnforcer enforcer;
        private final long started = System.nanoTime();
        private final AtomicBoolean completed = new AtomicBoolean();
        private volatile boolean measurementAvailable = true;
        private boolean listening;

        private Admission(Object handler, Target target, RateLimitlyPolicy policy, ServletRateLimitEnforcer enforcer) {
            this.handler = handler;
            this.target = target;
            this.policy = policy;
            this.enforcer = enforcer;
        }

        void complete() {
            if (completed.compareAndSet(false, true)) {
                forget(this);
                if (measurementAvailable) {
                    enforcer.afterCompletion(policy, System.nanoTime() - started);
                }
            }
        }

        void awaitCompletion(HttpServletRequest request) {
            if (completed.get()) { return; }
            suspend(this);
            if (listening) { return; }
            try {
                request.getAsyncContext().addListener(new AsyncListener() {
                    @Override public void onComplete(AsyncEvent event) { complete(); }
                    @Override public void onTimeout(AsyncEvent event) { /* Not necessarily terminal. */ }
                    @Override public void onError(AsyncEvent event) { /* Error handling can still dispatch. */ }
                    @Override public void onStartAsync(AsyncEvent event) {
                        if (!completed.get()) {
                            try { event.getAsyncContext().addListener(this); }
                            catch (IllegalStateException unavailableCycle) { measurementAvailable = false; }
                        }
                    }
                });
                listening = true;
            } catch (IllegalStateException unavailableCycle) {
                // Do not invent an early sample or alter admission if a custom/container
                // lifecycle no longer permits listener registration.
                // Preserve the outcome for continuation even if measurement is unavailable.
                measurementAvailable = false;
            }
        }
    }
}
