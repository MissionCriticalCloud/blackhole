package com.cloud.network.element;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.argThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloud.agent.AgentManager;
import com.cloud.deploy.DeployDestination;
import com.cloud.engine.orchestration.service.NetworkOrchestrationService;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.legacymodel.communication.answer.ConfigurePublicIpsOnLogicalRouterAnswer;
import com.cloud.legacymodel.communication.command.ConfigurePublicIpsOnLogicalRouterCommand;
import com.cloud.legacymodel.domain.Domain;
import com.cloud.legacymodel.exceptions.ConcurrentOperationException;
import com.cloud.legacymodel.exceptions.InsufficientCapacityException;
import com.cloud.legacymodel.exceptions.ResourceUnavailableException;
import com.cloud.legacymodel.network.Ip;
import com.cloud.legacymodel.network.Network;
import com.cloud.legacymodel.network.Network.Provider;
import com.cloud.legacymodel.network.Network.Service;
import com.cloud.legacymodel.user.Account;
import com.cloud.model.enumeration.BroadcastDomainType;
import com.cloud.model.enumeration.GuestType;
import com.cloud.model.enumeration.TrafficType;
import com.cloud.network.IpAddress;
import com.cloud.network.NetworkModel;
import com.cloud.network.NiciraNvpDeviceVO;
import com.cloud.network.NiciraNvpRouterMappingVO;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NiciraNvpDao;
import com.cloud.network.dao.NiciraNvpRouterMappingDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.ReservationContext;

import javax.naming.ConfigurationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;

public class NiciraNvpElementTest {

    private static final long NETWORK_ID = 42L;
    NiciraNvpElement element = new NiciraNvpElement();
    NetworkOrchestrationService networkManager = mock(NetworkOrchestrationService.class);
    NetworkModel networkModel = mock(NetworkModel.class);
    NetworkServiceMapDao ntwkSrvcDao = mock(NetworkServiceMapDao.class);
    AgentManager agentManager = mock(AgentManager.class);
    HostDao hostDao = mock(HostDao.class);
    NiciraNvpDao niciraNvpDao = mock(NiciraNvpDao.class);
    NiciraNvpRouterMappingDao niciraNvpRouterMappingDao = mock(NiciraNvpRouterMappingDao.class);

    @Before
    public void setUp() throws ConfigurationException {
        element.resourceMgr = mock(ResourceManager.class);
        element.networkManager = networkManager;
        element.ntwkSrvcDao = ntwkSrvcDao;
        element.networkModel = networkModel;
        element.agentMgr = agentManager;
        element.hostDao = hostDao;
        element.niciraNvpDao = niciraNvpDao;
        element.niciraNvpRouterMappingDao = niciraNvpRouterMappingDao;

        // Standard responses
        when(networkModel.isProviderForNetwork(Provider.NiciraNvp, NETWORK_ID)).thenReturn(true);

        element.configure("NiciraNvpTestElement", Collections.<String, Object>emptyMap());
    }

    @Test
    public void canHandleTest() {
        final Network net = mock(Network.class);
        when(net.getBroadcastDomainType()).thenReturn(BroadcastDomainType.Lswitch);
        when(net.getId()).thenReturn(NETWORK_ID);

        when(ntwkSrvcDao.canProviderSupportServiceInNetwork(NETWORK_ID, Service.Connectivity, Provider.NiciraNvp)).thenReturn(true);
        // Golden path
        assertTrue(element.canHandle(net, Service.Connectivity));

        when(net.getBroadcastDomainType()).thenReturn(BroadcastDomainType.Vlan);
        // Only broadcastdomaintype lswitch is supported
        assertFalse(element.canHandle(net, Service.Connectivity));

        when(net.getBroadcastDomainType()).thenReturn(BroadcastDomainType.Lswitch);
        when(ntwkSrvcDao.canProviderSupportServiceInNetwork(NETWORK_ID, Service.Connectivity, Provider.NiciraNvp)).thenReturn(false);
        // No nvp provider in the network
        assertFalse(element.canHandle(net, Service.Connectivity));

        when(networkModel.isProviderForNetwork(Provider.NiciraNvp, NETWORK_ID)).thenReturn(false);
        when(ntwkSrvcDao.canProviderSupportServiceInNetwork(NETWORK_ID, Service.Connectivity, Provider.NiciraNvp)).thenReturn(true);
        // NVP provider does not provide Connectivity for this network
        assertFalse(element.canHandle(net, Service.Connectivity));

        when(networkModel.isProviderForNetwork(Provider.NiciraNvp, NETWORK_ID)).thenReturn(true);
        // Only service Connectivity is supported
        assertFalse(element.canHandle(net, Service.Dhcp));
    }

    @Test
    public void implementTest() throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        final Network network = mock(Network.class);
        when(network.getBroadcastDomainType()).thenReturn(BroadcastDomainType.Lswitch);
        when(network.getId()).thenReturn(NETWORK_ID);

        final NetworkOffering offering = mock(NetworkOffering.class);
        when(offering.getId()).thenReturn(NETWORK_ID);
        when(offering.getTrafficType()).thenReturn(TrafficType.Guest);
        when(offering.getGuestType()).thenReturn(GuestType.Isolated);

        mock(DeployDestination.class);

        final Domain dom = mock(Domain.class);
        when(dom.getName()).thenReturn("domain");
        final Account acc = mock(Account.class);
        when(acc.getAccountName()).thenReturn("accountname");
        final ReservationContext context = mock(ReservationContext.class);
        when(context.getDomain()).thenReturn(dom);
        when(context.getAccount()).thenReturn(acc);
    }

    @Test
    public void applyIpTest() throws ResourceUnavailableException {
        final Network network = mock(Network.class);
        when(network.getBroadcastDomainType()).thenReturn(BroadcastDomainType.Lswitch);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getPhysicalNetworkId()).thenReturn(NETWORK_ID);

        final NetworkOffering offering = mock(NetworkOffering.class);
        when(offering.getId()).thenReturn(NETWORK_ID);
        when(offering.getTrafficType()).thenReturn(TrafficType.Guest);
        when(offering.getGuestType()).thenReturn(GuestType.Isolated);

        final List<PublicIpAddress> ipAddresses = new ArrayList<>();
        final PublicIpAddress pipReleased = mock(PublicIpAddress.class);
        final PublicIpAddress pipAllocated = mock(PublicIpAddress.class);
        final Ip ipReleased = new Ip(NetUtils.ip2Long("42.10.10.10"));
        final Ip ipAllocated = new Ip(NetUtils.ip2Long("10.10.10.10"));
        when(pipAllocated.getState()).thenReturn(IpAddress.State.Allocated);
        when(pipAllocated.getAddress()).thenReturn(ipAllocated);
        when(pipAllocated.getNetmask()).thenReturn("255.255.255.0");
        when(pipReleased.getState()).thenReturn(IpAddress.State.Releasing);
        when(pipReleased.getAddress()).thenReturn(ipReleased);
        when(pipReleased.getNetmask()).thenReturn("255.255.255.0");
        ipAddresses.add(pipAllocated);
        ipAddresses.add(pipReleased);

        final Set<Service> services = new HashSet<>();
        services.add(Service.SourceNat);
        services.add(Service.StaticNat);
        services.add(Service.PortForwarding);

        final List<NiciraNvpDeviceVO> deviceList = new ArrayList<>();
        final NiciraNvpDeviceVO nndVO = mock(NiciraNvpDeviceVO.class);
        final NiciraNvpRouterMappingVO nnrmVO = mock(NiciraNvpRouterMappingVO.class);
        when(niciraNvpRouterMappingDao.findByNetworkId(NETWORK_ID)).thenReturn(nnrmVO);
        when(nnrmVO.getLogicalRouterUuid()).thenReturn("abcde");
        when(nndVO.getHostId()).thenReturn(NETWORK_ID);
        final HostVO hvo = mock(HostVO.class);
        when(hvo.getId()).thenReturn(NETWORK_ID);
        when(hvo.getDetail("l3gatewayserviceuuid")).thenReturn("abcde");
        when(hostDao.findById(NETWORK_ID)).thenReturn(hvo);
        deviceList.add(nndVO);
        when(niciraNvpDao.listByPhysicalNetwork(NETWORK_ID)).thenReturn(deviceList);

        final ConfigurePublicIpsOnLogicalRouterAnswer answer = mock(ConfigurePublicIpsOnLogicalRouterAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(agentManager.easySend(eq(NETWORK_ID), any(ConfigurePublicIpsOnLogicalRouterCommand.class))).thenReturn(answer);

        assertTrue(element.applyIps(network, ipAddresses, services));

        verify(agentManager, atLeast(1)).easySend(eq(NETWORK_ID), argThat(new ArgumentMatcher<ConfigurePublicIpsOnLogicalRouterCommand>() {
            @Override
            public boolean matches(final Object argument) {
                final ConfigurePublicIpsOnLogicalRouterCommand command = (ConfigurePublicIpsOnLogicalRouterCommand) argument;
                if (command.getPublicCidrs().size() == 1) {
                    return true;
                }
                return false;
            }
        }));
    }
}
