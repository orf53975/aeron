/*
 * Copyright 2017 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.cluster.codecs.*;
import io.aeron.cluster.control.ClusterControl;
import io.aeron.logbuffer.ControlledFragmentHandler;
import org.agrona.CloseHelper;
import org.agrona.DirectBuffer;
import org.agrona.collections.ArrayListUtil;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.Iterator;

import static io.aeron.cluster.ClusterSession.State.*;
import static io.aeron.cluster.control.ClusterControl.Action.*;

class SequencerAgent implements Agent
{
    enum State
    {
        INIT, ACTIVE, SUSPENDED, SNAPSHOT, SHUTDOWN, ABORT, CLOSED
    }

    /**
     * Message detail to be sent when max concurrent session limit is reached.
     */
    public static final String SESSION_LIMIT_MSG = "Concurrent session limit";

    /**
     * Message detail to be sent when a session timeout occurs.
     */
    public static final String SESSION_TIMEOUT_MSG = "Session inactive";

    /**
     * Message detail to be sent when a session is rejected due to authentication.
     */
    public static final String SESSION_REJECTED_MSG = "Session failed authentication";

    private final long sessionTimeoutMs;
    private long nextSessionId = 1;
    private int servicesReadyCount = 0;
    private final AgentInvoker aeronClientInvoker;
    private final EpochClock epochClock;
    private final CachedEpochClock cachedEpochClock;
    private final TimerService timerService;
    private final ConsensusModuleAdapter consensusModuleAdapter;
    private final IngressAdapter ingressAdapter;
    private final EgressPublisher egressPublisher;
    private final LogAppender logAppender;
    private final Counter messageIndex;
    private final Counter controlToggle;
    private final ClusterSessionSupplier clusterSessionSupplier;
    private final Long2ObjectHashMap<ClusterSession> sessionByIdMap = new Long2ObjectHashMap<>();
    private final ArrayList<ClusterSession> pendingSessions = new ArrayList<>();
    private final ArrayList<ClusterSession> rejectedSessions = new ArrayList<>();
    private final ConsensusModule.Context ctx;
    private final Authenticator authenticator;
    private final SessionProxy sessionProxy;
    private State state = State.INIT;

    // TODO: last message correlation id per session counter

    SequencerAgent(
        final ConsensusModule.Context ctx,
        final EgressPublisher egressPublisher,
        final LogAppender logAppender,
        final IngressAdapterSupplier ingressAdapterSupplier,
        final TimerServiceSupplier timerServiceSupplier,
        final ClusterSessionSupplier clusterSessionSupplier,
        final ConsensusModuleAdapterSupplier consensusModuleAdapterSupplier)
    {
        this.ctx = ctx;
        this.epochClock = ctx.epochClock();
        this.cachedEpochClock = ctx.cachedEpochClock();
        this.sessionTimeoutMs = ctx.sessionTimeoutNs() / 1000;
        this.egressPublisher = egressPublisher;
        this.messageIndex = ctx.messageIndex();
        this.controlToggle = ctx.controlToggle();
        this.logAppender = logAppender;
        this.clusterSessionSupplier = clusterSessionSupplier;
        this.sessionProxy = new SessionProxy(egressPublisher);

        ingressAdapter = ingressAdapterSupplier.newIngressAdapter(this);
        timerService = timerServiceSupplier.newTimerService(this);
        consensusModuleAdapter = consensusModuleAdapterSupplier.newConsensusModuleAdapter(this);
        authenticator = ctx.authenticatorSupplier().newAuthenticator(this.ctx);
        aeronClientInvoker = ctx.ownsAeronClient() ? ctx.aeron().conductorAgentInvoker() : null;
    }

    public void onClose()
    {
        if (!ctx.ownsAeronClient())
        {
            for (final ClusterSession session : sessionByIdMap.values())
            {
                session.close();
            }

            CloseHelper.close(ingressAdapter);
            CloseHelper.close(consensusModuleAdapter);
        }
    }

    public int doWork()
    {
        int workCount = 0;

        final long nowMs = epochClock.time();
        cachedEpochClock.update(nowMs);

        if (null != aeronClientInvoker)
        {
            workCount += aeronClientInvoker.invoke();
        }

        workCount += checkControlToggle(nowMs);
        workCount += consensusModuleAdapter.poll();

        if (State.ACTIVE == state)
        {
            workCount += processPendingSessions(pendingSessions, nowMs);
            workCount += timerService.poll(nowMs);
            workCount += ingressAdapter.poll();
            workCount += checkSessions(sessionByIdMap, nowMs);
        }

        processRejectedSessions(rejectedSessions, nowMs);

        return workCount;
    }

    public String roleName()
    {
        return "sequencer";
    }

    public void onActionAck(final long serviceId, final ServiceAction action)
    {
        switch (action)
        {
            case READY:
                if (State.INIT != state)
                {
                    throw new IllegalStateException("Unexpected state: " + state);
                }

                if (servicesReadyCount >= ctx.serviceCount())
                {
                    throw new IllegalStateException("Service count exceeded: " + servicesReadyCount);
                }

                ++servicesReadyCount;
                state = State.ACTIVE;
                break;

            case SNAPSHOT:
                if (State.SNAPSHOT == state)
                {
                    state = State.ACTIVE;
                }
                break;

            case SHUTDOWN:
                if (State.SHUTDOWN == state)
                {
                    state = State.CLOSED;
                    ctx.shutdownSignalBarrier().signal();
                }
                break;

            case ABORT:
                if (State.ABORT == state)
                {
                    state = State.CLOSED;
                    ctx.shutdownSignalBarrier().signal();
                }
                break;
        }
    }

    public void onSessionConnect(
        final long correlationId,
        final int responseStreamId,
        final String responseChannel,
        final byte[] credentialData)
    {
        final long nowMs = cachedEpochClock.time();
        final long sessionId = nextSessionId++;
        final ClusterSession session = clusterSessionSupplier.newClusterSession(
            sessionId, responseStreamId, responseChannel);
        session.lastActivity(nowMs, correlationId);

        authenticator.onConnectRequest(sessionId, credentialData, nowMs);

        if (pendingSessions.size() + sessionByIdMap.size() < ctx.maxConcurrentSessions())
        {
            pendingSessions.add(session);
        }
        else
        {
            rejectedSessions.add(session);
        }
    }

    public void onSessionClose(final long clusterSessionId)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.close();
            if (appendClosedSession(session, CloseReason.USER_ACTION, cachedEpochClock.time()))
            {
                sessionByIdMap.remove(clusterSessionId);
            }
        }
    }

    public ControlledFragmentAssembler.Action onSessionMessage(
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final long clusterSessionId,
        final long correlationId)
    {
        final long nowMs = cachedEpochClock.time();
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null == session || (session.state() == TIMED_OUT || session.state() == CLOSED))
        {
            return ControlledFragmentHandler.Action.CONTINUE;
        }

        if (session.state() == OPEN && logAppender.appendMessage(buffer, offset, length, nowMs))
        {
            messageIndex.incrementOrdered();
            session.lastActivity(nowMs, correlationId);

            return ControlledFragmentHandler.Action.CONTINUE;
        }

        return ControlledFragmentHandler.Action.ABORT;
    }

    public void onKeepAlive(final long correlationId, final long clusterSessionId)
    {
        final ClusterSession session = sessionByIdMap.get(clusterSessionId);
        if (null != session)
        {
            session.lastActivity(cachedEpochClock.time(), correlationId);
        }
    }

    public void onChallengeResponse(final long correlationId, final long clusterSessionId, final byte[] credentialData)
    {
        for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = pendingSessions.get(i);

            if (session.id() == clusterSessionId && session.state() == CHALLENGED)
            {
                final long nowMs = cachedEpochClock.time();

                session.lastActivity(nowMs, correlationId);

                authenticator.onChallengeResponse(clusterSessionId, credentialData, nowMs);
                break;
            }
        }
    }

    public boolean onTimerEvent(final long correlationId, final long nowMs)
    {
        if (logAppender.appendTimerEvent(correlationId, nowMs))
        {
            messageIndex.incrementOrdered();

            return true;
        }

        return false;
    }

    public void onScheduleTimer(final long correlationId, final long deadlineMs)
    {
        timerService.scheduleTimer(correlationId, deadlineMs);
    }

    public void onCancelTimer(final long correlationId)
    {
        timerService.cancelTimer(correlationId);
    }

    State state()
    {
        return state;
    }

    private int checkControlToggle(final long nowMs)
    {
        final long toggleCode = controlToggle.get();

        if (NEUTRAL.code() == toggleCode)
        {
            return 0;
        }

        if (State.ABORT != state && ABORT.code() == toggleCode)
        {
            if (logAppender.appendActionRequest(ServiceAction.ABORT, nowMs))
            {
                state = State.ABORT;
                return 1;
            }
        }

        if (State.ACTIVE == state && SNAPSHOT.code() == toggleCode)
        {
            if (logAppender.appendActionRequest(ServiceAction.SNAPSHOT, nowMs))
            {
                state = State.SNAPSHOT;
                ClusterControl.Action.reset(controlToggle);
                return 1;
            }
        }

        if (State.ACTIVE == state && SHUTDOWN.code() == toggleCode)
        {
            if (logAppender.appendActionRequest(ServiceAction.SHUTDOWN, nowMs))
            {
                state = State.SHUTDOWN;
                ClusterControl.Action.reset(controlToggle);
                return 1;
            }
        }

        if (State.ACTIVE == state && SUSPEND.code() == toggleCode)
        {
            state = State.SUSPENDED;
            ClusterControl.Action.reset(controlToggle);
            return 1;
        }

        if (State.SUSPENDED == state && RESUME.code() == toggleCode)
        {
            state = State.ACTIVE;
            ClusterControl.Action.reset(controlToggle);
            return 1;
        }

        throw new IllegalStateException("Unknown toggle action code: " + toggleCode);
    }

    private int processPendingSessions(final ArrayList<ClusterSession> pendingSessions, final long nowMs)
    {
        int workCount = 0;

        for (int lastIndex = pendingSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = pendingSessions.get(i);

            if (session.state() == INIT || session.state() == CONNECTED)
            {
                if (session.responsePublication().isConnected())
                {
                    session.state(CONNECTED);
                    sessionProxy.clusterSession(session);
                    authenticator.onProcessConnectedSession(sessionProxy, nowMs);
                }
            }

            if (session.state() == CHALLENGED)
            {
                if (session.responsePublication().isConnected())
                {
                    sessionProxy.clusterSession(session);
                    authenticator.onProcessChallengedSession(sessionProxy, nowMs);
                }
            }

            if (session.state() == AUTHENTICATED)
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex);
                lastIndex--;

                session.timeOfLastActivityMs(nowMs);
                sessionByIdMap.put(session.id(), session);

                appendConnectedSession(session, nowMs);

                workCount += 1;
            }
            else if (session.state() == REJECTED)
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex);
                lastIndex--;

                rejectedSessions.add(session);
            }
            else if (nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                ArrayListUtil.fastUnorderedRemove(pendingSessions, i, lastIndex);
                lastIndex--;

                session.close();
            }
        }

        return workCount;
    }

    private void processRejectedSessions(final ArrayList<ClusterSession> rejectedSessions, final long nowMs)
    {
        for (int lastIndex = rejectedSessions.size() - 1, i = lastIndex; i >= 0; i--)
        {
            final ClusterSession session = rejectedSessions.get(i);
            String detail = SESSION_LIMIT_MSG;
            EventCode eventCode = EventCode.ERROR;

            if (session.state() == REJECTED)
            {
                detail = SESSION_REJECTED_MSG;
                eventCode = EventCode.AUTHENTICATION_REJECTED;
            }

            if (egressPublisher.sendEvent(session, eventCode, detail) ||
                nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                ArrayListUtil.fastUnorderedRemove(rejectedSessions, i, lastIndex);
                lastIndex--;

                session.close();
            }
        }
    }

    private int checkSessions(final Long2ObjectHashMap<ClusterSession> sessionByIdMap, final long nowMs)
    {
        int workCount = 0;

        final Iterator<ClusterSession> iter = sessionByIdMap.values().iterator();
        while (iter.hasNext())
        {
            final ClusterSession session = iter.next();

            final ClusterSession.State state = session.state();
            if (nowMs > (session.timeOfLastActivityMs() + sessionTimeoutMs))
            {
                switch (state)
                {
                    case OPEN:
                        egressPublisher.sendEvent(session, EventCode.ERROR, SESSION_TIMEOUT_MSG);
                        if (appendClosedSession(session, CloseReason.TIMEOUT, nowMs))
                        {
                            iter.remove();
                            workCount += 1;
                        }
                        else
                        {
                            session.state(TIMED_OUT);
                        }
                        break;

                    case TIMED_OUT:
                    case CLOSED:
                        final CloseReason reason = state == TIMED_OUT ? CloseReason.TIMEOUT : CloseReason.USER_ACTION;
                        if (appendClosedSession(session, reason, nowMs))
                        {
                            iter.remove();
                            workCount += 1;
                        }
                        break;

                    default:
                        session.close();
                        iter.remove();
                }
            }
            else if (state == CONNECTED)
            {
                if (appendConnectedSession(session, nowMs))
                {
                    workCount += 1;
                }
            }
        }

        return workCount;
    }

    private boolean appendConnectedSession(final ClusterSession session, final long nowMs)
    {
        if (logAppender.appendConnectedSession(session, nowMs))
        {
            session.state(ClusterSession.State.OPEN);
            messageIndex.incrementOrdered();

            return true;
        }

        return false;
    }

    private boolean appendClosedSession(final ClusterSession session, final CloseReason closeReason, final long nowMs)
    {
        if (logAppender.appendClosedSession(session, closeReason, nowMs))
        {
            messageIndex.incrementOrdered();
            session.close();

            return true;
        }

        return false;
    }
}
