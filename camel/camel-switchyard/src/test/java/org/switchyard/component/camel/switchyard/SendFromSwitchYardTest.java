/* 
 * JBoss, Home of Professional Open Source 
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors
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
package org.switchyard.component.camel.switchyard;

import javax.xml.namespace.QName;

import org.apache.camel.component.mock.MockEndpoint;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.switchyard.Exchange;
import org.switchyard.component.camel.common.composer.CamelBindingData;
import org.switchyard.component.camel.common.composer.CamelContextMapper;
import org.switchyard.component.camel.common.composer.CamelMessageComposer;
import org.switchyard.component.camel.common.handler.OutboundHandler;
import org.switchyard.component.camel.switchyard.util.Composer;
import org.switchyard.component.camel.switchyard.util.Mapper;
import org.switchyard.component.common.composer.MessageComposer;
import org.switchyard.component.test.mixins.cdi.CDIMixIn;
import org.switchyard.metadata.InOnlyService;
import org.switchyard.metadata.ServiceInterface;
import org.switchyard.test.SwitchYardRunner;
import org.switchyard.test.SwitchYardTestCaseConfig;

@RunWith(SwitchYardRunner.class)
@SwitchYardTestCaseConfig(mixins = CDIMixIn.class)
public class SendFromSwitchYardTest extends SwitchYardComponentTestBase {

    private static final String ENDPOINT_URI = "mock:output";
    private static final String PAYLOAD = "Test Payload";
    private static final String OPERATION_NAME = "foo";
    private MockEndpoint _mock;

    @Before
    public void startUp() {
        _mock = getMockEndpoint(ENDPOINT_URI);
    }

    @Test
    public void messageComposerComposeTest() throws InterruptedException {
        _mock.expectedBodiesReceived(Composer.DECOMPOSE_PREFIX + PAYLOAD);
        Exchange exchange = createExchange(new Composer().setContextMapper(new CamelContextMapper()));
        exchange.send(exchange.createMessage().setContent(PAYLOAD));
        _mock.assertIsSatisfied();
    }

    @Test
    public void contextMapperMapToTest() throws InterruptedException {
        _mock.expectedBodiesReceived(PAYLOAD);
        _mock.expectedHeaderReceived(Mapper.PROPERTY, Mapper.VALUE);

        Exchange exchange = createExchange(new CamelMessageComposer().setContextMapper(new Mapper()));
        exchange.send(exchange.createMessage().setContent(PAYLOAD));
        _mock.assertIsSatisfied();
    }

    public Exchange createExchange(MessageComposer<CamelBindingData> messageComposer) {
        QName serviceName = new QName("urn:test", "Service");
        ServiceInterface metadata = new InOnlyService(OPERATION_NAME);

        OutboundHandler handler = new OutboundHandler(ENDPOINT_URI, _camelContext, messageComposer);
        _serviceDomain.registerService(serviceName, metadata, handler);
        return _serviceDomain.registerServiceReference(serviceName, metadata).createExchange(OPERATION_NAME);
    }

}
