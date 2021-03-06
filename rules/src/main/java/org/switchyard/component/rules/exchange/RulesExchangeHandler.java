/* 
 * JBoss, Home of Professional Open Source 
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved. 
 * See the copyright.txt in the distribution for a 
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use, 
 * modify, copy, or redistribute it subject to the terms and conditions 
 * of the GNU Lesser General Public License, v. 2.1. 
 * This program is distributed in the hope that it will be useful, but WITHOUT A 
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A 
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details. 
 * You should have received a copy of the GNU Lesser General Public License, 
 * v.2.1 along with this distribution; if not, write to the Free Software 
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, 
 * MA  02110-1301, USA.
 */
package org.switchyard.component.rules.exchange;

import static org.switchyard.component.common.knowledge.util.Actions.getInputList;
import static org.switchyard.component.common.knowledge.util.Actions.getListMap;
import static org.switchyard.component.common.knowledge.util.Actions.getOutput;
import static org.switchyard.component.common.knowledge.util.Actions.setGlobals;
import static org.switchyard.component.common.knowledge.util.Actions.toVariable;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicInteger;

import org.kie.runtime.rule.SessionEntryPoint;
import org.switchyard.Exchange;
import org.switchyard.ExchangePattern;
import org.switchyard.HandlerException;
import org.switchyard.Message;
import org.switchyard.ServiceDomain;
import org.switchyard.common.lang.Strings;
import org.switchyard.common.type.Classes;
import org.switchyard.component.common.knowledge.exchange.KnowledgeAction;
import org.switchyard.component.common.knowledge.exchange.KnowledgeExchangeHandler;
import org.switchyard.component.common.knowledge.session.KnowledgeDisposal;
import org.switchyard.component.common.knowledge.session.KnowledgeSession;
import org.switchyard.component.rules.RulesActionType;
import org.switchyard.component.rules.RulesConstants;
import org.switchyard.component.rules.config.model.RulesComponentImplementationModel;

/**
 * A "rules" implementation of a KnowledgeExchangeHandler.
 *
 * @author David Ward &lt;<a href="mailto:dward@jboss.org">dward@jboss.org</a>&gt; &copy; 2012 Red Hat Inc.
 */
public class RulesExchangeHandler extends KnowledgeExchangeHandler<RulesComponentImplementationModel> {

    private static final AtomicInteger FIRE_UNTIL_HALT_COUNT = new AtomicInteger();
    private static final KnowledgeAction DEFAULT_ACTION = new KnowledgeAction(RulesActionType.EXECUTE);

    private Thread _fireUntilHaltThread = null;

    /**
     * Constructs a new RulesExchangeHandler with the specified model and service domain.
     * @param model the specified model
     * @param domain the specified service domain
     */
    public RulesExchangeHandler(RulesComponentImplementationModel model, ServiceDomain domain) {
        super(model, domain);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public KnowledgeAction getDefaultAction() {
        return DEFAULT_ACTION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handleAction(Exchange exchange, KnowledgeAction action) throws HandlerException {
        Message inputMessage = exchange.getMessage();
        RulesActionType actionType = (RulesActionType)action.getType();
        switch (actionType) {
            case EXECUTE: {
                KnowledgeSession session = newStatelessSession();
                setGlobals(inputMessage, action, session);
                List<Object> facts = getInputList(inputMessage, action);
                session.getStateless().execute(facts);
                break;
            }
            case INSERT:
            case FIRE_ALL_RULES: {
                /*
                if (!isContinue(exchange)) {
                    disposeStatefulSession();
                }
                */
                KnowledgeSession session = getStatefulSession();
                setGlobals(inputMessage, action, session);
                List<Object> facts = getInputList(inputMessage, action);
                for (Object fact : facts) {
                    session.getStateful().insert(fact);
                }
                if (RulesActionType.FIRE_ALL_RULES.equals(actionType)) {
                    session.getStateful().fireAllRules();
                }
                if (isDispose(exchange)) {
                    disposeStatefulSession();
                }
                break;
            }
            case FIRE_UNTIL_HALT: {
                /*
                if (!isContinue(exchange)) {
                    disposeStatefulSession();
                }
                */
                KnowledgeSession session = getStatefulSession();
                setGlobals(inputMessage, action, session);
                if (_fireUntilHaltThread == null) {
                    FireUntilHalt fireUntilHalt = new FireUntilHalt(this, session, getLoader());
                    session.addDisposals(fireUntilHalt);
                    _fireUntilHaltThread = fireUntilHalt.startThread();
                }
                final String undefinedVariable = toVariable(exchange);
                Map<String, List<Object>> inputMap = getListMap(inputMessage, action.getInputExpressionMappings(), true, undefinedVariable);
                if (inputMap.size() > 0) {
                    for (Entry<String, List<Object>> inputEntry : inputMap.entrySet()) {
                        String key = inputEntry.getKey();
                        if (undefinedVariable.equals(key)) {
                            String eventId = Strings.trimToNull(action.getEventId());
                            if (eventId != null) {
                                key = eventId;
                            }
                        }
                        List<Object> facts = inputEntry.getValue();
                        if (undefinedVariable.equals(key)) {
                            for (Object fact : facts) {
                                session.getStateful().insert(fact);
                            }
                        } else {
                            SessionEntryPoint sep = session.getStateful().getEntryPoint(key);
                            if (sep != null) {
                                for (Object fact : facts) {
                                    sep.insert(fact);
                                }
                            } else {
                                throw new HandlerException("Unknown entry point: " + sep + "; please check your rules source.");
                            }
                        }
                    }
                } else {
                    List<Object> facts = getInputList(inputMessage, action);
                    for (Object fact : facts) {
                        session.getStateful().insert(fact);
                    }
                }
                if (isDispose(exchange)) {
                    disposeStatefulSession();
                }
                break;
            }
            default: {
                throw new HandlerException("Unsupported action type: " + actionType);
            }
        }
        Object output = getOutput(inputMessage, action);
        if (ExchangePattern.IN_OUT.equals(exchange.getContract().getProviderOperation().getExchangePattern())) {
            Message outputMessage = exchange.createMessage();
            outputMessage.setContent(output);
            exchange.send(outputMessage);
        }
    }

    /*
    private boolean isContinue(Exchange exchange) {
        return isBoolean(exchange, RulesConstants.CONTINUE_PROPERTY);
    }
    */

    private boolean isDispose(Exchange exchange) {
        return isBoolean(exchange, RulesConstants.DISPOSE_PROPERTY);
    }

    private final class FireUntilHalt implements Runnable, KnowledgeDisposal {

        private final RulesExchangeHandler _handler;
        private final KnowledgeSession _session;
        private final ClassLoader _loader;

        private FireUntilHalt(RulesExchangeHandler handler, KnowledgeSession session, ClassLoader loader) {
            _handler = handler;
            _session = session;
            _loader = loader;
        }

        @Override
        public void run() {
            ClassLoader originalLoader = Classes.setTCCL(_loader);
            try {
                _session.getStateful().fireUntilHalt();
            } finally {
                try {
                    _handler.disposeStatefulSession();
                } finally {
                    Classes.setTCCL(originalLoader);
                }
            }
        }

        @Override
        public void dispose() {
            _handler._fireUntilHaltThread = null;
        }

        private Thread startThread() {
            Thread thread = new Thread(this);
            String name = new StringBuilder()
                .append(_handler.getClass().getSimpleName())
                .append(':')
                .append(getClass().getSimpleName())
                .append(':')
                .append(FIRE_UNTIL_HALT_COUNT.incrementAndGet())
                .toString();
            thread.setName(name);
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

    }

}
